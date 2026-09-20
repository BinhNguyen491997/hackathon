package com.example.aihackathon.config;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kiểm tra cấu hình LLM lúc khởi động để lỗi lộ ra ngay, thay vì nhận 401/404 khi user hỏi câu đầu.
 *
 * <p>Spring AI 2.x gọi model qua OpenAI Java SDK chính thức: SDK nối thẳng
 * {@code chat/completions} vào {@code base-url}, nên base-url của GreenNode MaaS phải là
 * {@code https://maas-llm-aiplatform-hcm.api.vngcloud.vn/v1}. Thiếu {@code /v1} là 404, và để trống
 * api-key sẽ bật "no-auth mode" của SDK (bỏ header Authorization) nên endpoint trả 401.
 */
@Component
public class LlmStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(LlmStartupCheck.class);

    /**
     * Các host của GreenNode MaaS (endpoint được serve trên domain VNG Cloud). Với những host này
     * cấu hình sai là chắc chắn không chạy được, nên fail fast thay vì chỉ cảnh báo.
     */
    static final List<String> MAAS_HOSTS = List.of("api.vngcloud.vn", "greennode.ai");

    private final String baseUrl;

    private final String apiKey;

    private final String model;

    public LlmStartupCheck(
            @Value("${spring.ai.openai.base-url:}") String baseUrl,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.chat.model:}") String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @PostConstruct
    void check() {
        validate(this.baseUrl, this.apiKey).forEach(log::warn);
        warnings(this.model).forEach(log::warn);
        log.info("LLM endpoint: {}/chat/completions model={}", trimTrailingSlash(this.baseUrl),
                this.model.isBlank() ? "<provider default>" : this.model);
    }

    /**
     * Chặn cấu hình chắc chắn gọi sai với GreenNode MaaS; trả về cảnh báo cho các provider khác.
     * @throws IllegalStateException khi cấu hình GreenNode sai không thể chạy được
     */
    static List<String> validate(String baseUrl, String apiKey) {
        String base = trimTrailingSlash(baseUrl);
        boolean maas = MAAS_HOSTS.stream().anyMatch(base::contains);
        List<String> warnings = new ArrayList<>();

        if (!base.endsWith("/v1")) {
            String message = """
                    base-url "%s" không kết thúc bằng "/v1". Spring AI 2.x dùng OpenAI Java SDK,
                    SDK nối thẳng "chat/completions" vào base-url nên URL gọi thật sẽ là
                    "%s/chat/completions".
                    Cách sửa: LLM_BASE_URL="%s/v1"
                    """.formatted(baseUrl, base, base);
            if (maas) {
                throw new IllegalStateException(message);
            }
            warnings.add(message);
        }

        if (apiKey == null || apiKey.isBlank()) {
            String message = """
                    LLM_API_KEY đang trống. Api-key rỗng bật "no-auth mode" của OpenAI SDK
                    (bỏ hẳn header Authorization) nên endpoint sẽ trả 401.
                    Lấy key ở GreenNode portal -> Model as a Service -> API key, rồi truyền qua
                    env LLM_API_KEY (đừng ghi vào application.yml).
                    """;
            if (maas) {
                throw new IllegalStateException(message);
            }
            warnings.add(message);
        }

        return warnings;
    }

    /** Cảnh báo cấu hình vẫn chạy được nhưng gần như chắc chắn không như ý. */
    static List<String> warnings(String model) {
        if (model == null || model.isBlank()) {
            return List.of("LLM_MODEL đang trống: SDK sẽ không gửi field \"model\", GreenNode MaaS "
                    + "sẽ từ chối request. Lấy id bằng GET /v1/models rồi đặt LLM_MODEL "
                    + "(ví dụ: z-ai/glm-5.2-hackathon).");
        }
        return List.of();
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = (value == null) ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
