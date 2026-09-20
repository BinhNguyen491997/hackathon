package com.example.aihackathon.codeanalysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import com.example.aihackathon.codeanalysis.model.FlowComparison;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache kết quả gọi LLM, ghi xuống đĩa và khoá theo commit.
 *
 * <p>Vì sao là đĩa chứ không phải {@code Map} trong heap như cache index: hai thứ được cache có bản
 * chất chi phí khác nhau. Mất cache index chỉ tốn <b>thời gian</b> - parse lại vài giây bằng CPU của
 * chính mình. Mất cache LLM tốn <b>tiền và quota</b>, và riêng bước đối chiếu còn gửi lại 40 000 ký
 * tự source ra ngoài hạ tầng lần nữa. Thứ đắt như vậy không nên biến mất chỉ vì restart process.
 *
 * <p>Đặt trong {@code analysis.output-dir} là có chủ ý: đó là thư mục đã được khuyến nghị mount
 * volume khi deploy, nên cache bền luôn qua redeploy container mà không cần cấu hình thêm chỗ nào.
 *
 * <h2>Khoá cache</h2>
 * Gồm commit SHA + endpoint + <b>model id</b> (+ hạn mức source với phần đối chiếu). Model id phải
 * có trong khoá: đổi từ model này sang model khác mà vẫn trả bản cũ là nói sai nguồn gốc của đoạn
 * văn, đúng loại sai mà cả hệ thống này được dựng để tránh.
 *
 * <h2>Chỉ cache bản đã được nhận</h2>
 * Bản nháp bị {@link CitationValidator} loại, và các lần gọi model thất bại, đều KHÔNG được cache.
 * Cache một bản nháp trượt truy vết rồi lần sau đọc lên như thể hợp lệ là phá đúng cơ chế kiểm tra.
 * Lỗi mạng cũng không cache: lần sau thử lại có thể thành công.
 */
final class AiResultCache {

    private static final Logger log = LoggerFactory.getLogger(AiResultCache.class);

    /** Tăng khi đổi cấu trúc file cache, để bản cũ bị coi là miss thay vì đọc sai. */
    private static final String FORMAT_VERSION = "v1";

    private static final String DIRECTORY = "ai-cache";

    private final AnalysisProperties properties;

    private final ObjectMapper mapper = new ObjectMapper();

    AiResultCache(AnalysisProperties properties) {
        this.properties = properties;
    }

    /**
     * Phần mô tả do model viết, đã qua kiểm tra truy vết.
     *
     * <p>KHÔNG lưu {@link CitationValidator.Result}: khi đọc cache ra, hệ thống kiểm tra lại truy
     * vết trên chính bảng dẫn chứng hiện tại. Vừa rẻ (chỉ là regex), vừa chặt hơn - bản cache không
     * khớp được với dẫn chứng thì bị coi là miss chứ không được dùng.
     */
    record CachedNarration(String narrative, String aiNote, int attempts) {
    }

    // ------------------------------------------------------------------
    // Phần mô tả
    // ------------------------------------------------------------------

    Optional<CachedNarration> narration(String commitSha, ApiEndpoint endpoint) {
        return read(narrationFile(commitSha, endpoint), CachedNarration.class)
                .filter(cached -> cached.narrative() != null && !cached.narrative().isBlank());
    }

    void putNarration(String commitSha, ApiEndpoint endpoint, String narrative, String aiNote,
            int attempts) {

        if (narrative == null || narrative.isBlank()) {
            return;
        }
        write(narrationFile(commitSha, endpoint), new CachedNarration(narrative, aiNote, attempts));
    }

    // ------------------------------------------------------------------
    // Phần đối chiếu chéo
    // ------------------------------------------------------------------

    Optional<FlowComparison> comparison(String commitSha, ApiEndpoint endpoint) {
        return read(comparisonFile(commitSha, endpoint), FlowComparison.class)
                .filter(FlowComparison::aiResponded);
    }

    void putComparison(String commitSha, ApiEndpoint endpoint, FlowComparison comparison) {
        // notRun() nghĩa là không bật cờ HOẶC gọi model thất bại - không phải kết quả để dùng lại.
        if (comparison == null || !comparison.aiResponded()) {
            return;
        }
        write(comparisonFile(commitSha, endpoint), comparison);
    }

    // ------------------------------------------------------------------
    // Đọc / ghi
    // ------------------------------------------------------------------

    private <T> Optional<T> read(Path file, Class<T> type) {
        if (file == null || !Files.isReadable(file)) {
            return Optional.empty();
        }
        try {
            T value = this.mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), type);
            log.info("dùng lại {} từ cache, KHÔNG gọi model: {}", type.getSimpleName(),
                    file.getFileName());
            return Optional.ofNullable(value);
        }
        catch (IOException | RuntimeException ex) {
            // File cache hỏng hoặc sai định dạng không được làm sập request: coi như chưa có cache.
            log.warn("bỏ qua cache hỏng {}: {}", file.getFileName(), ex.getMessage());
            return Optional.empty();
        }
    }

    private void write(Path file, Object value) {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, this.mapper.writeValueAsString(value), StandardCharsets.UTF_8);
        }
        catch (IOException | RuntimeException ex) {
            log.warn("không ghi được cache {}: {}", file.getFileName(), ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Khoá
    // ------------------------------------------------------------------

    private Path narrationFile(String commitSha, ApiEndpoint endpoint) {
        // Phần mô tả chỉ phụ thuộc bảng dẫn chứng và model - KHÔNG phụ thuộc max-source-chars, vì
        // model viết mô tả không hề nhìn thấy source. Tách khoá như vậy để request chỉ bật useAi
        // vẫn dùng lại được bản đã cache bởi request bật cả hai cờ.
        return file(commitSha, endpoint, "narration", fingerprint(model()));
    }

    private Path comparisonFile(String commitSha, ApiEndpoint endpoint) {
        // Đối chiếu chéo phụ thuộc cả hạn mức source: đổi hạn mức là đổi lượng code LLM được thấy,
        // nên kết luận của nó cũng khác.
        return file(commitSha, endpoint, "comparison",
                fingerprint(model() + "|" + this.properties.getAi().getMaxSourceChars()));
    }

    private Path file(String commitSha, ApiEndpoint endpoint, String part, String fingerprint) {
        if (!this.properties.getAi().isCache() || endpoint == null) {
            return null;
        }
        String name = slug(endpoint) + "__" + shortSha(commitSha) + "__" + part + "__" + fingerprint
                + ".json";
        return this.properties.getOutputDir().resolve(DIRECTORY).resolve(name);
    }

    private String model() {
        String configured = this.properties.getAi().getModel();
        // Để trống nghĩa là chưa cấu hình analysis.ai.model. Không được coi như "cùng một model":
        // dùng một nhãn riêng để bản cache không lẫn với bản sinh bởi model đã biết tên.
        return configured == null || configured.isBlank() ? "unknown-model" : configured.trim();
    }

    /** Tên file đọc được: giữ method + path, cùng quy ước với các file .puml/.md đã sinh. */
    private static String slug(ApiEndpoint endpoint) {
        return (endpoint.httpMethod() + endpoint.path())
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("(^-|-$)", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String shortSha(String commitSha) {
        return commitSha == null || commitSha.isBlank()
                ? "nosha"
                : commitSha.substring(0, Math.min(8, commitSha.length()));
    }

    /**
     * Băm phần khoá không đưa được vào tên file.
     *
     * <p>Model id chứa dấu {@code /} ({@code z-ai/glm-5.2-hackathon}) nên không dùng thẳng làm tên
     * file được. Băm rồi cắt 8 ký tự: đủ để hai model khác nhau ra hai file khác nhau, và vẫn nhìn
     * được bằng mắt khi debug.
     */
    private static String fingerprint(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((FORMAT_VERSION + "|" + value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 8);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM không có SHA-256", ex);
        }
    }
}
