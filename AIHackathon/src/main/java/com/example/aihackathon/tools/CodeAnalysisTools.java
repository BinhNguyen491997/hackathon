package com.example.aihackathon.tools;

import java.util.List;
import java.util.Locale;

import com.example.aihackathon.codeanalysis.ApiFlowAnalyzer;
import com.example.aihackathon.codeanalysis.EndpointNotFoundException;
import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Tool để model trả lời câu hỏi "luồng API này chạy thế nào" bằng dữ liệu thật từ source code.
 *
 * <p>Ranh giới trách nhiệm rất rõ: call graph do phân tích tĩnh dựng (tất định, không đoán),
 * model chỉ diễn giải lại kết quả bằng ngôn ngữ nghiệp vụ. Không có tool nào cho model tự
 * "suy luận" ra lời gọi, vì đó chính là chỗ model bịa ra method không tồn tại.
 */
@Component
public class CodeAnalysisTools {

    private static final Logger log = LoggerFactory.getLogger(CodeAnalysisTools.class);

    /** Trên ngưỡng này thì chỉ trả đường dẫn file, tránh đốt context bằng nội dung sơ đồ. */
    private static final int INLINE_DIAGRAM_LIMIT = 6000;

    private static final int ENDPOINT_LIST_LIMIT = 120;

    private final ApiFlowAnalyzer analyzer;

    public CodeAnalysisTools(ApiFlowAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Tool(description = "Liệt kê tất cả endpoint HTTP (path + HTTP method + mô tả @Operation) "
            + "trong một repo GitLab Java/Spring. Gọi tool này trước khi phân tích nếu người dùng "
            + "chưa cho path chính xác, hoặc khi cần kiểm tra path có tồn tại không.")
    public String listApiEndpoints(
            @ToolParam(description = "URL HTTPS của repo GitLab, ví dụ https://gitlab.com/team/shop.git")
            String repoUrl,
            @ToolParam(description = "Tên branch, để trống thì dùng master", required = false)
            String branch,
            @ToolParam(description = "Chuỗi lọc theo path hoặc mô tả, để trống thì lấy hết",
                    required = false) String filter) {

        log.info("TOOL listApiEndpoints(repo={}, branch={}, filter={})", repoUrl, branch, filter);
        List<ApiEndpoint> endpoints = this.analyzer.endpoints(repoUrl, branch);

        List<ApiEndpoint> filtered = (filter == null || filter.isBlank())
                ? endpoints
                : endpoints.stream().filter(endpoint -> matchesFilter(endpoint, filter)).toList();

        if (filtered.isEmpty()) {
            return endpoints.isEmpty()
                    ? "Repo này không có endpoint nào (không tìm thấy @RestController/@Controller)."
                    : "Không có endpoint nào khớp '" + filter + "'. Repo có " + endpoints.size()
                            + " endpoint, thử gọi lại tool này với filter để trống.";
        }
        StringBuilder result = new StringBuilder("Tìm thấy ").append(filtered.size())
                .append(" endpoint:");
        filtered.stream().limit(ENDPOINT_LIST_LIMIT)
                .forEach(endpoint -> result.append("\n- ").append(endpoint.describe()));
        if (filtered.size() > ENDPOINT_LIST_LIMIT) {
            result.append("\n... còn ").append(filtered.size() - ENDPOINT_LIST_LIMIT).append(" endpoint nữa.");
        }
        return result.toString();
    }

    @Tool(description = "Phân tích luồng thực thi thật của một API trong repo GitLab Java/Spring: "
            + "đi từ controller xuống service, repository, lời gọi ra ngoài, kèm điều kiện rẽ nhánh "
            + "và nhánh lỗi. Trả về bản mô tả có cấu trúc. Dùng khi người dùng hỏi 'API này làm gì', "
            + "'luồng xử lý ra sao', 'code hiện tại kiểm tra những gì'.")
    public String describeApiFlow(
            @ToolParam(description = "URL HTTPS của repo GitLab") String repoUrl,
            @ToolParam(description = "Tên branch, để trống thì dùng master", required = false)
            String branch,
            @ToolParam(description = "HTTP method: GET, POST, PUT, DELETE, PATCH. Để trống nếu chỉ "
                    + "tìm theo mô tả.", required = false) String httpMethod,
            @ToolParam(description = "Path của API, ví dụ /api/orders/{id}. Nếu không biết path thì "
                    + "truyền từ khoá mô tả chức năng để tìm theo @Operation(summary).")
            String pathOrSummary) {

        log.info("TOOL describeApiFlow(repo={}, branch={}, {} {})", repoUrl, branch, httpMethod,
                pathOrSummary);
        try {
            ApiFlowAnalyzer.AnalysisResult result =
                    this.analyzer.analyze(repoUrl, branch, httpMethod, pathOrSummary);
            return result.summary();
        }
        catch (EndpointNotFoundException ex) {
            return notFoundMessage(ex);
        }
    }

    @Tool(description = "Sinh báo cáo sơ đồ luồng cho một API trong repo GitLab Java/Spring, ở dạng "
            + "BA xem được ngay không cần cài công cụ: file HTML mở bằng browser và bản Markdown "
            + "dán vào GitLab wiki. Dùng khi người dùng yêu cầu sequence diagram, sơ đồ luồng, "
            + "hoặc tài liệu cho BA.")
    public String generateSequenceDiagram(
            @ToolParam(description = "URL HTTPS của repo GitLab") String repoUrl,
            @ToolParam(description = "Tên branch, để trống thì dùng master", required = false)
            String branch,
            @ToolParam(description = "HTTP method: GET, POST, PUT, DELETE, PATCH", required = false)
            String httpMethod,
            @ToolParam(description = "Path của API, hoặc từ khoá khớp @Operation(summary)")
            String pathOrSummary) {

        log.info("TOOL generateSequenceDiagram(repo={}, branch={}, {} {})", repoUrl, branch, httpMethod,
                pathOrSummary);
        ApiFlowAnalyzer.AnalysisResult result;
        try {
            result = this.analyzer.analyze(repoUrl, branch, httpMethod, pathOrSummary);
        }
        catch (EndpointNotFoundException ex) {
            return notFoundMessage(ex);
        }

        StringBuilder answer = new StringBuilder("Đã sinh báo cáo cho ")
                .append(result.flow().endpoint().label())
                .append(" (").append(result.flow().branch()).append(" @ ")
                .append(shortSha(result.flow().commitSha())).append(")\n");

        // Trả ĐƯỜNG DẪN file, không trả nội dung html/markdown: nội dung dài hàng chục nghìn
        // ký tự, nhồi vào context là hết token mà model cũng không cần đọc nó.
        appendFile(answer, "HTML (mở bằng browser, không cần cài gì)", result.html().filePath());
        appendFile(answer, "Markdown (dán vào GitLab wiki/MR để hiện sơ đồ)",
                result.markdown().filePath());
        appendFile(answer, "PlantUML (cho developer)", result.puml().filePath());

        String mermaid = result.mermaid().content();
        if (mermaid.length() <= INLINE_DIAGRAM_LIMIT) {
            answer.append("\nMã sơ đồ Mermaid (dán vào GitLab wiki là hiện hình):\n")
                    .append("```mermaid\n").append(mermaid).append("```\n");
        }
        else {
            answer.append("\nSơ đồ dài ").append(mermaid.length())
                    .append(" ký tự nên không trả trực tiếp ở đây, xem file bên trên.\n");
        }
        answer.append("\nTóm tắt luồng:\n").append(result.summary());
        return answer.toString();
    }

    @Tool(description = "BƯỚC 1 khi cần viết tài liệu đặc tả/mô tả chức năng cho một API: thu thập "
            + "dữ kiện đọc được từ code (điểm vào, phân quyền, trường dữ liệu đầu vào, quy tắc kiểm "
            + "tra, quy tắc nghiệp vụ, truy cập dữ liệu, gọi ra ngoài, mã lỗi) kèm mã dẫn chứng "
            + "[E1], [E2]..., và danh sách điểm cần xác nhận với developer. PHẢI gọi tool này trước "
            + "khi viết bất kỳ mô tả nào về chức năng của API.")
    public String collectCodeEvidence(
            @ToolParam(description = "URL HTTPS của repo GitLab") String repoUrl,
            @ToolParam(description = "Tên branch, để trống thì dùng master", required = false)
            String branch,
            @ToolParam(description = "HTTP method: GET, POST, PUT, DELETE, PATCH", required = false)
            String httpMethod,
            @ToolParam(description = "Path của API, hoặc từ khoá khớp @Operation(summary)")
            String pathOrSummary) {

        log.info("TOOL collectCodeEvidence(repo={}, branch={}, {} {})", repoUrl, branch, httpMethod,
                pathOrSummary);
        try {
            ApiFlowAnalyzer.SpecResult result =
                    this.analyzer.collectSpec(repoUrl, branch, httpMethod, pathOrSummary);
            return result.promptText()
                    + "\nHƯỚNG DẪN VIẾT: mỗi phát biểu về hành vi hệ thống phải kèm mã dẫn chứng "
                    + "dạng [E3] hoặc [E3, E7]. Tuyệt đối KHÔNG tự viết tên file kèm số dòng. "
                    + "Điều gì không có dẫn chứng thì phải đặt trong mục \"Suy luận - cần xác nhận\". "
                    + "Viết xong thì gọi tool writeFunctionalSpec để lưu.";
        }
        catch (EndpointNotFoundException ex) {
            return notFoundMessage(ex);
        }
    }

    @Tool(description = "BƯỚC 2 sau khi đã gọi collectCodeEvidence và đã viết xong phần mô tả: lưu "
            + "tài liệu đặc tả chức năng hiện trạng ra file HTML và Markdown. Hệ thống sẽ KIỂM TRA "
            + "mọi mã dẫn chứng trong phần mô tả; nếu dẫn tới mã không tồn tại hoặc tự viết "
            + "file:line thì tool báo lỗi để sửa lại.")
    public String writeFunctionalSpec(
            @ToolParam(description = "URL HTTPS của repo GitLab") String repoUrl,
            @ToolParam(description = "Tên branch, để trống thì dùng master", required = false)
            String branch,
            @ToolParam(description = "HTTP method: GET, POST, PUT, DELETE, PATCH", required = false)
            String httpMethod,
            @ToolParam(description = "Path của API, hoặc từ khoá khớp @Operation(summary)")
            String pathOrSummary,
            @ToolParam(description = "Phần mô tả chức năng bằng tiếng Việt, mỗi phát biểu kèm mã "
                    + "dẫn chứng [En]. Dùng gạch đầu dòng cho danh sách. Không tự viết file:line.")
            String narrative) {

        log.info("TOOL writeFunctionalSpec(repo={}, {} {}, mô tả {} ký tự)", repoUrl, httpMethod,
                pathOrSummary, narrative == null ? 0 : narrative.length());
        ApiFlowAnalyzer.SpecResult result;
        try {
            result = this.analyzer.writeSpec(repoUrl, branch, httpMethod, pathOrSummary, narrative);
        }
        catch (EndpointNotFoundException ex) {
            return notFoundMessage(ex);
        }

        if (!result.citations().trustworthy()) {
            // Trả lỗi để model tự sửa, KHÔNG âm thầm chấp nhận: dẫn chứng bịa là dạng sai
            // nguy hiểm nhất vì nó làm câu văn trông có căn cứ.
            return "CHƯA LƯU - phần mô tả không đạt yêu cầu truy vết:\n"
                    + result.citations().describe()
                    + "\nHãy sửa lại phần mô tả rồi gọi lại tool này.";
        }

        StringBuilder answer = new StringBuilder("Đã lưu tài liệu đặc tả cho ")
                .append(result.flow().endpoint().label()).append('\n');
        appendFile(answer, "HTML (BA mở bằng browser)", result.html().filePath());
        appendFile(answer, "Markdown (dán vào GitLab wiki)", result.markdown().filePath());
        answer.append("- Dẫn chứng: ").append(result.spec().evidence().size())
                .append(", trích dẫn hợp lệ: ").append(result.citations().citedIds().size())
                .append(", điểm cần xác nhận với dev: ").append(result.spec().questions().size())
                .append('\n');

        if (!result.citations().uncitedKinds().isEmpty()) {
            answer.append("- Lưu ý: chưa có câu nào dẫn tới nhóm dẫn chứng ")
                    .append(result.citations().uncitedKinds()).append(" - có thể còn thiếu một mặt "
                            + "của đặc tả.\n");
        }
        if (result.spec().hasBlockingQuestions()) {
            answer.append("- CẢNH BÁO: có điểm ở mức \"phải xác nhận trước khi dùng tài liệu\". "
                    + "Phải nói rõ điều này với người dùng.\n");
        }
        return answer.toString();
    }

    private static void appendFile(StringBuilder out, String label, String path) {
        if (path != null) {
            out.append("- ").append(label).append(": ").append(path).append('\n');
        }
    }

    private static boolean matchesFilter(ApiEndpoint endpoint, String filter) {
        String needle = filter.trim().toLowerCase(Locale.ROOT);
        if (endpoint.path().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        return endpoint.summary() != null
                && endpoint.summary().toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Trả lời rõ ràng khi không có gì để phân tích, kèm gợi ý để người dùng gõ lại. */
    private static String notFoundMessage(EndpointNotFoundException ex) {
        StringBuilder message = new StringBuilder(ex.getMessage());
        if (!ex.suggestions().isEmpty()) {
            message.append("\nCác endpoint đang có trong repo (tối đa 30):");
            ex.suggestions().forEach(suggestion -> message.append("\n- ").append(suggestion));
        }
        return message.toString();
    }

    private static String shortSha(String sha) {
        if (sha == null) {
            return "n/a";
        }
        return sha.length() > 8 ? sha.substring(0, 8) : sha;
    }
}
