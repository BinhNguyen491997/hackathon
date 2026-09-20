package com.example.aihackathon.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.aihackathon.codeanalysis.ApiFlowAnalyzer;
import com.example.aihackathon.codeanalysis.EndpointNotFoundException;
import com.example.aihackathon.codeanalysis.FlowProposer;
import com.example.aihackathon.codeanalysis.NarrativeWriter;
import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API tất định cho BA/developer: đưa link repo + endpoint, nhận về sequence diagram.
 *
 * <p>Không đi qua LLM. Cùng một commit luôn cho ra cùng một kết quả, nên dùng được cho cả
 * việc so sánh diagram giữa hai lần release.
 */
@RestController
public class ApiFlowController {

    private static final Logger log = LoggerFactory.getLogger(ApiFlowController.class);

    private final ApiFlowAnalyzer analyzer;

    private final NarrativeWriter narrativeWriter;

    private final FlowProposer flowProposer;

    public ApiFlowController(ApiFlowAnalyzer analyzer, NarrativeWriter narrativeWriter,
            FlowProposer flowProposer) {
        this.analyzer = analyzer;
        this.narrativeWriter = narrativeWriter;
        this.flowProposer = flowProposer;
    }

    /** Phân tích một endpoint và sinh sequence diagram. */
    @PostMapping(path = "/api/analyze/api-flow", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiFlowResponse analyze(@Valid @RequestBody ApiFlowRequest request) {
        return ApiFlowResponse.from(runFlow(request));
    }

    /**
     * Sơ đồ tuần tự dạng PlantUML, trả về như một file .puml để tải xuống.
     *
     * <p>Cùng body với {@code /api/analyze/spec.html}. Khác {@code /api/analyze/api-flow} ở chỗ
     * response không phải JSON mà là nội dung {@code .puml} thô kèm {@code Content-Disposition:
     * attachment}, nên {@code curl -OJ} hay browser lưu thẳng ra file mở được bằng plugin
     * PlantUML - không phải bóc field {@code formats.puml.content} ra khỏi JSON rồi tự unescape.
     *
     * <p>Tên file lấy theo file mà analyzer đã ghi (có gắn commit SHA) để hai lần release không
     * ghi đè nhau; nếu việc ghi file thất bại thì dựng tên từ method + path.
     */
    @PostMapping(path = "/api/analyze/api-flow.puml", produces = "text/plain; charset=UTF-8")
    public ResponseEntity<String> apiFlowAsPuml(@Valid @RequestBody ApiFlowRequest request) {
        ApiFlowAnalyzer.AnalysisResult result = runFlow(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(pumlFileName(result), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(result.puml().content());
    }

    private ApiFlowAnalyzer.AnalysisResult runFlow(ApiFlowRequest request) {
        if (!request.hasPath() && !request.hasSummary()) {
            throw new IllegalArgumentException("Cần ít nhất một trong hai: path hoặc summary.");
        }
        long startNanos = System.nanoTime();
        log.info("--> POST /api/analyze/api-flow repo={} branch={} {} {} useAi={} crossCheckWithAi={} "
                        + "gitToken={}",
                request.repoUrl(), request.branch(), request.httpMethod(), request.target(),
                request.aiEnabled(), request.crossCheckEnabled(),
                request.hasGitToken() ? "của người gọi" : "cấu hình server");

        ApiFlowAnalyzer.AnalysisResult result = this.analyzer.analyzeFlowWith(request.repoUrl(),
                request.branch(), request.httpMethod(), request.target(),
                request.aiEnabled() ? this.narrativeWriter : NarrativeWriter.NONE,
                request.crossCheckEnabled() ? this.flowProposer : FlowProposer.NONE,
                request.gitToken());

        log.info("<-- POST /api/analyze/api-flow {} ms endpoint={} participants={} AI={} đối chiếu={}",
                (System.nanoTime() - startNanos) / 1_000_000, result.flow().endpoint().label(),
                result.flow().participants().size(), result.aiUsed(),
                result.comparison().aiResponded());
        return result;
    }

    /** Tên file .puml gợi ý cho client, không bao giờ chứa ký tự cấm của filesystem. */
    private static String pumlFileName(ApiFlowAnalyzer.AnalysisResult result) {
        Path written = result.puml().file();
        if (written != null && written.getFileName() != null) {
            return written.getFileName().toString();
        }
        ApiEndpoint endpoint = result.flow().endpoint();
        String name = (endpoint.httpMethod() + endpoint.path())
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("(^-|-$)", "")
                .toLowerCase(Locale.ROOT);
        return (name.isBlank() ? "api-flow" : name) + ".puml";
    }

    /**
     * Trả thẳng báo cáo HTML để mở bằng browser - BA không cần cài gì.
     *
     * <p>Dùng GET để dán được vào thanh địa chỉ. Lưu ý: nếu {@code agent.api-key} có cấu hình thì
     * {@link ApiKeyFilter} vẫn đòi header {@code X-API-Key}, mà browser không tự gửi header được.
     * Khi đó cách chia sẻ cho BA là gửi file .html đã ghi sẵn trong analysis.output-dir, chứ
     * không phải gửi link. Tôi cố tình KHÔNG cho truyền api-key qua query param vì URL bị ghi
     * vào access log.
     *
     * <p>Cùng lý do đó, các endpoint GET ở dưới KHÔNG nhận {@code gitToken}: chỉ POST mới nhận,
     * vì token phải nằm trong body. Muốn dùng token riêng thì gọi bản POST.
     */
    @GetMapping(path = "/api/analyze/api-flow.html", produces = "text/html; charset=UTF-8")
    public String analyzeAsHtml(
            @RequestParam String repoUrl,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String httpMethod,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String summary) {

        return resolve(repoUrl, branch, httpMethod, path, summary).html().content();
    }

    /** Trả báo cáo Markdown để copy vào GitLab wiki / mô tả MR (GitLab tự render Mermaid). */
    @GetMapping(path = "/api/analyze/api-flow.md", produces = "text/markdown; charset=UTF-8")
    public String analyzeAsMarkdown(
            @RequestParam String repoUrl,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String httpMethod,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String summary) {

        return resolve(repoUrl, branch, httpMethod, path, summary).markdown().content();
    }

    private ApiFlowAnalyzer.AnalysisResult resolve(String repoUrl, String branch, String httpMethod,
            String path, String summary) {

        boolean hasPath = path != null && !path.isBlank();
        boolean hasSummary = summary != null && !summary.isBlank();
        if (!hasPath && !hasSummary) {
            throw new IllegalArgumentException("Cần ít nhất một trong hai: path hoặc summary.");
        }
        log.info("--> GET /api/analyze/api-flow repo={} branch={} {} {}", repoUrl, branch, httpMethod,
                hasPath ? path : "summary=" + summary);
        return hasPath
                ? this.analyzer.analyzeByPath(repoUrl, branch, httpMethod, path)
                : this.analyzer.analyzeBySummary(repoUrl, branch, summary);
    }

    /**
     * Tài liệu đặc tả chức năng hiện trạng.
     *
     * <p>{@code useAi=false} (mặc định): chỉ dữ kiện tất định, không gọi LLM, không tốn token.
     * {@code useAi=true}: thêm phần diễn giải + suy luận do LLM viết, gắn nhãn rõ trong tài liệu.
     * Phần bảng dẫn chứng luôn do phân tích tĩnh sinh, không đổi giữa hai chế độ.
     */
    @PostMapping(path = "/api/analyze/spec", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecResponse spec(@Valid @RequestBody ApiFlowRequest request) {
        return SpecResponse.from(runSpec(request));
    }

    /** Cùng nội dung nhưng trả HTML để mở thẳng bằng browser. */
    @PostMapping(path = "/api/analyze/spec.html", produces = "text/html; charset=UTF-8")
    public String specHtmlPost(@Valid @RequestBody ApiFlowRequest request) {
        return runSpec(request).html().content();
    }

    private ApiFlowAnalyzer.SpecResult runSpec(ApiFlowRequest request) {
        if (!request.hasPath() && !request.hasSummary()) {
            throw new IllegalArgumentException("Cần ít nhất một trong hai: path hoặc summary.");
        }
        long startNanos = System.nanoTime();
        log.info("--> spec repo={} branch={} {} {} useAi={} crossCheckWithAi={} gitToken={}",
                request.repoUrl(), request.branch(), request.httpMethod(), request.target(),
                request.aiEnabled(), request.crossCheckEnabled(),
                request.hasGitToken() ? "của người gọi" : "cấu hình server");

        ApiFlowAnalyzer.SpecResult result = this.analyzer.specWith(request.repoUrl(),
                request.branch(), request.httpMethod(), request.target(),
                request.aiEnabled() ? this.narrativeWriter : NarrativeWriter.NONE,
                request.crossCheckEnabled() ? this.flowProposer : FlowProposer.NONE,
                request.gitToken());

        log.info("<-- spec {} ms | endpoint={} | AI={} ({} lượt) | đối chiếu={} (đồng thuận {}%) "
                        + "| {} dẫn chứng | {} câu hỏi",
                (System.nanoTime() - startNanos) / 1_000_000, result.flow().endpoint().label(),
                result.aiUsed(), result.aiAttempts(), result.comparison().aiResponded(),
                result.comparison().agreementPercent(), result.spec().evidence().size(),
                result.spec().questions().size());
        return result;
    }

    /**
     * Tài liệu đặc tả chức năng hiện trạng, dạng HTML mở bằng browser.
     *
     * <p>Không truyền {@code narrative} thì tài liệu chỉ gồm phần tất định: bảng dẫn chứng kèm
     * file:line và danh sách điểm cần xác nhận với dev. Phần văn xuôi do agent viết đi qua
     * {@code /api/chat} vì nó cần model.
     */
    @GetMapping(path = "/api/analyze/spec.html", produces = "text/html; charset=UTF-8")
    public String specAsHtml(
            @RequestParam String repoUrl,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String httpMethod,
            @RequestParam String path,
            @RequestParam(required = false, defaultValue = "false") boolean useAi,
            @RequestParam(required = false, defaultValue = "false") boolean crossCheckWithAi) {

        log.info("--> GET /api/analyze/spec.html repo={} {} {} useAi={} crossCheckWithAi={}",
                repoUrl, httpMethod, path, useAi, crossCheckWithAi);
        return this.analyzer.specWith(repoUrl, branch, httpMethod, path,
                useAi ? this.narrativeWriter : NarrativeWriter.NONE,
                crossCheckWithAi ? this.flowProposer : FlowProposer.NONE).html().content();
    }

    /** Tài liệu đặc tả dạng Markdown, để dán vào GitLab wiki. */
    @GetMapping(path = "/api/analyze/spec.md", produces = "text/markdown; charset=UTF-8")
    public String specAsMarkdown(
            @RequestParam String repoUrl,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String httpMethod,
            @RequestParam String path,
            @RequestParam(required = false, defaultValue = "false") boolean useAi,
            @RequestParam(required = false, defaultValue = "false") boolean crossCheckWithAi) {

        return this.analyzer.specWith(repoUrl, branch, httpMethod, path,
                useAi ? this.narrativeWriter : NarrativeWriter.NONE,
                crossCheckWithAi ? this.flowProposer : FlowProposer.NONE).markdown().content();
    }

    /** Dẫn chứng và câu hỏi dạng JSON, để hệ thống khác dùng lại. */
    @PostMapping(path = "/api/analyze/evidence", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpecResponse evidence(@Valid @RequestBody ApiFlowRequest request) {
        if (!request.hasPath() && !request.hasSummary()) {
            throw new IllegalArgumentException("Cần ít nhất một trong hai: path hoặc summary.");
        }
        return SpecResponse.from(this.analyzer.collectSpec(request.repoUrl(), request.branch(),
                request.httpMethod(), request.target(), request.gitToken()));
    }

    /** Liệt kê toàn bộ endpoint trong repo - dùng khi BA chưa biết path chính xác. */
    @PostMapping(path = "/api/analyze/endpoints", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<EndpointView> endpoints(@Valid @RequestBody EndpointListRequest request) {
        List<ApiEndpoint> endpoints = this.analyzer.endpoints(request.repoUrl(), request.branch(),
                request.gitToken());
        log.info("liệt kê {} endpoint từ {}", endpoints.size(), request.repoUrl());
        return endpoints.stream()
                .map(endpoint -> new EndpointView(endpoint.httpMethod(), endpoint.path(),
                        endpoint.summary(), endpoint.controllerSimpleName() + "." + endpoint.methodName(),
                        endpoint.sourceFile() + ":" + endpoint.line()))
                .toList();
    }

    /**
     * @param gitToken token GitLab của người gọi; để trống thì dùng cấu hình server. Xem
     *                 {@link ApiFlowRequest#gitToken()} để biết vì sao trường này không có
     *                 ràng buộc validation.
     */
    public record EndpointListRequest(
            @jakarta.validation.constraints.NotBlank String repoUrl,
            String branch,
            String gitToken) {

        @Override
        public String toString() {
            return "EndpointListRequest[repoUrl=%s, branch=%s, gitToken=%s]".formatted(this.repoUrl,
                    this.branch, this.gitToken == null || this.gitToken.isBlank() ? "(trống)" : "***");
        }
    }

    public record EndpointView(String httpMethod, String path, String summary, String handler,
            String source) {
    }

    /**
     * Không tìm thấy endpoint là kết quả bình thường của nghiệp vụ, không phải lỗi hệ thống -
     * trả 404 kèm gợi ý thay vì 500.
     */
    @ExceptionHandler(EndpointNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(EndpointNotFoundException ex) {
        log.info("không tìm thấy endpoint: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", String.valueOf(ex.getMessage()),
                "suggestions", ex.suggestions()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException ex) {
        log.warn("bad request: {}", ex.getMessage());
        return Map.of("error", String.valueOf(ex.getMessage()));
    }
}
