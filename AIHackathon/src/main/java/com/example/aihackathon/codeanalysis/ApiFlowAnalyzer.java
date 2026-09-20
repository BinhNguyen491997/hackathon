package com.example.aihackathon.codeanalysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.CodeSpec;
import com.example.aihackathon.codeanalysis.model.FlowComparison;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

/**
 * Ghép các bước lại thành một luồng: clone -> index -> tìm endpoint -> dựng call graph ->
 * sinh .puml + bản mô tả.
 *
 * <p>Index được cache theo commit SHA. Parse cả repo là phần tốn thời gian nhất (vài giây tới
 * vài chục giây với monolith), nên hỏi endpoint thứ hai trên cùng commit sẽ trả về gần như
 * tức thì.
 */
@Service
public class ApiFlowAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ApiFlowAnalyzer.class);

    private final GitRepoFetcher fetcher;

    private final AnalysisProperties properties;

    private final Map<String, RepoSnapshot> snapshots = new ConcurrentHashMap<>();

    /** Cache kết quả gọi LLM trên đĩa. Thứ được cache là tiền, không phải thời gian. */
    private final AiResultCache aiCache;

    public ApiFlowAnalyzer(GitRepoFetcher fetcher, AnalysisProperties properties) {
        this.fetcher = fetcher;
        this.properties = properties;
        this.aiCache = new AiResultCache(properties);
    }

    /** Ảnh chụp một commit: index + danh sách endpoint đã quét sẵn. */
    private record RepoSnapshot(String commitSha, JavaSourceIndex index, List<ApiEndpoint> endpoints) {
    }

    /**
     * Kết quả trả về cho cả REST endpoint và tool của LLM.
     *
     * <p>Bốn định dạng cho bốn người đọc khác nhau, nhưng tất cả sinh từ CÙNG một cây FlowNode
     * nên không thể lệch nội dung: html cho BA mở bằng browser, markdown để dán vào GitLab
     * wiki/MR, puml cho developer, summary dạng text cho LLM diễn giải lại.
     *
     * @param comparison kết quả đối chiếu chéo với AI; {@link FlowComparison#notRun()} khi không bật
     * @param aiNote     ghi chú về phần AI, null khi không bật
     */
    public record AnalysisResult(
            ApiFlow flow,
            String summary,
            Rendered html,
            Rendered markdown,
            Rendered mermaid,
            Rendered puml,
            FlowComparison comparison,
            boolean aiUsed,
            String aiNote) {

        /** Một định dạng đã render, kèm đường dẫn file đã ghi (null nếu ghi thất bại). */
        public record Rendered(String content, Path file) {

            /**
             * Đường dẫn để trả ra API, luôn dùng dấu {@code /} kể cả trên Windows.
             *
             * <p>Hai lý do. Thứ nhất là nhất quán: {@code sourceFile} trong cùng response đã được
             * {@code JavaSourceIndex} chuẩn hoá về dấu {@code /}, nên để đường dẫn file sinh ra dùng
             * {@code \} là hai kiểu trong một JSON. Thứ hai là đọc được: trong JSON mỗi {@code \}
             * bị escape thành {@code \\}, nên
             * {@code C:\Users\x\AppData\Local\Temp\agent-code-analysis\diagrams} trả về thành
             * {@code C:\\Users\\x\\AppData\\Local\\Temp\\...} - dán vào Explorer không chạy. Dạng
             * {@code C:/Users/x/AppData/Local/Temp/...} thì Windows, Java và browser đều nhận.
             *
             * <p>Lưu ý: đây là đường dẫn trên MÁY CHẠY SERVER. Khi agent chạy trên GreenNode/Docker
             * thì nó vô nghĩa với client - dùng {@code content} trong cùng response thay vì đi tìm
             * file.
             */
            public String filePath() {
                return this.file == null ? null : this.file.toString().replace('\\', '/');
            }
        }
    }

    /**
     * Phân tích luồng, có thể kèm phần do AI đóng góp.
     *
     * <p>Hai cờ này trước đây chỉ có tác dụng ở endpoint đặc tả; endpoint sơ đồ luồng nhận cờ rồi
     * bỏ qua im lặng. Giờ cả hai đường đều dùng cùng một cơ chế.
     */
    public AnalysisResult analyzeFlowWith(String repoUrl, String branch, String httpMethod,
            String pathOrKeyword, NarrativeWriter writer, FlowProposer proposer) {

        RepoSnapshot snapshot = snapshot(repoUrl, branch);
        ApiEndpoint endpoint = locate(snapshot, httpMethod, pathOrKeyword);
        return analyze(endpoint, snapshot, repoUrl, branch, writer, proposer);
    }

    /** Phân tích theo HTTP method + path, ví dụ ("POST", "/api/orders"). */
    public AnalysisResult analyzeByPath(String repoUrl, String branch, String httpMethod, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Thiếu path của API cần phân tích.");
        }
        RepoSnapshot snapshot = snapshot(repoUrl, branch);
        List<ApiEndpoint> matches = snapshot.endpoints().stream()
                .filter(endpoint -> endpoint.matches(httpMethod, path))
                .toList();

        if (matches.isEmpty()) {
            throw notFound("path " + describeRequest(httpMethod, path), snapshot);
        }
        if (matches.size() > 1) {
            log.warn("path {} khớp {} endpoint, dùng cái đầu tiên: {}", path, matches.size(),
                    matches.get(0).describe());
        }
        return analyze(matches.get(0), snapshot, repoUrl, branch, NarrativeWriter.NONE,
                FlowProposer.NONE);
    }

    /**
     * Phân tích theo mô tả nghiệp vụ, khớp với {@code @Operation(summary = "...")}. Dùng khi BA
     * chỉ biết tên chức năng chứ không biết path.
     */
    public AnalysisResult analyzeBySummary(String repoUrl, String branch, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("Thiếu từ khoá mô tả chức năng cần tìm.");
        }
        RepoSnapshot snapshot = snapshot(repoUrl, branch);
        String needle = keyword.trim().toLowerCase(Locale.ROOT);

        Optional<ApiEndpoint> match = snapshot.endpoints().stream()
                .filter(endpoint -> endpoint.summary() != null
                        && endpoint.summary().toLowerCase(Locale.ROOT).contains(needle))
                .findFirst();

        if (match.isEmpty()) {
            throw notFound("mô tả '" + keyword + "' trong @Operation(summary)", snapshot);
        }
        return analyze(match.get(), snapshot, repoUrl, branch, NarrativeWriter.NONE,
                FlowProposer.NONE);
    }

    /**
     * Tìm theo path trước, không có thì thử theo {@code @Operation(summary)}. Đúng thứ tự mà
     * người dùng mong đợi: path là thông tin chắc chắn, summary chỉ là phương án dự phòng.
     */
    public AnalysisResult analyze(String repoUrl, String branch, String httpMethod, String pathOrKeyword) {
        boolean looksLikePath = pathOrKeyword != null && pathOrKeyword.trim().startsWith("/");
        if (looksLikePath) {
            return analyzeByPath(repoUrl, branch, httpMethod, pathOrKeyword);
        }
        try {
            return analyzeBySummary(repoUrl, branch, pathOrKeyword);
        }
        catch (EndpointNotFoundException ex) {
            // người dùng có thể gõ path thiếu dấu "/" ở đầu
            return analyzeByPath(repoUrl, branch, httpMethod, "/" + String.valueOf(pathOrKeyword).trim());
        }
    }

    public List<ApiEndpoint> endpoints(String repoUrl, String branch) {
        return snapshot(repoUrl, branch).endpoints();
    }

    /**
     * Kết quả sinh tài liệu đặc tả.
     *
     * @param spec           dữ kiện + câu hỏi, đều tất định
     * @param promptText     bản gọn để model đọc trước khi viết mô tả
     * @param citations      kết quả kiểm tra trích dẫn trong phần mô tả
     * @param aiUsed         có gọi LLM để viết phần mô tả hay không
     * @param aiAttempts     số lần đã nhờ LLM viết (lần 2 là do lần 1 không đạt truy vết)
     * @param rejectedDraft  bản nháp bị loại vì không đạt truy vết, giữ lại để developer soi;
     *                       KHÔNG được đưa vào tài liệu
     * @param aiNote         ghi chú về phần AI để hiển thị cho người dùng
     */
    public record SpecResult(
            ApiFlow flow,
            CodeSpec spec,
            String promptText,
            AnalysisResult.Rendered markdown,
            AnalysisResult.Rendered html,
            CitationValidator.Result citations,
            boolean aiUsed,
            int aiAttempts,
            String rejectedDraft,
            String aiNote,
            FlowComparison comparison) {
    }

    /**
     * Thu dữ kiện cho một endpoint mà chưa cần văn bản mô tả.
     *
     * <p>Agent gọi bước này trước để biết nó đang có bằng chứng gì, rồi mới viết mô tả.
     */
    public SpecResult collectSpec(String repoUrl, String branch, String httpMethod,
            String pathOrSummary) {

        return buildSpec(repoUrl, branch, httpMethod, pathOrSummary, NarrativeWriter.NONE,
                FlowProposer.NONE);
    }

    /**
     * Sinh tài liệu đặc tả với phần mô tả do model viết.
     *
     * <p>Phần mô tả BỊ KIỂM TRA trước khi ghi ra file: mọi mã {@code [En]} phải trỏ tới dẫn chứng
     * thật, và model không được tự gõ {@code file:line}. Đây là chỗ biến yêu cầu "phải dẫn nguồn"
     * từ lời nhắc trong prompt thành một ràng buộc thực thi được.
     */
    public SpecResult writeSpec(String repoUrl, String branch, String httpMethod,
            String pathOrSummary, String narrative) {

        return buildSpec(repoUrl, branch, httpMethod, pathOrSummary,
                (promptText, feedback) -> narrative, FlowProposer.NONE);
    }

    /**
     * Sinh tài liệu đặc tả, để {@code writer} lo phần văn xuôi và {@code proposer} lo phần đối
     * chiếu chéo.
     *
     * <p>Truyền {@link NarrativeWriter#NONE} và {@link FlowProposer#NONE} là chế độ không dùng AI.
     * Hai cờ độc lập nhau vì chúng có giá khác nhau: viết văn xuôi chỉ gửi danh sách dữ kiện, còn
     * đối chiếu chéo GỬI NGUYÊN VĂN SOURCE CODE ra ngoài.
     */
    public SpecResult specWith(String repoUrl, String branch, String httpMethod,
            String pathOrSummary, NarrativeWriter writer, FlowProposer proposer) {

        return buildSpec(repoUrl, branch, httpMethod, pathOrSummary, writer, proposer);
    }

    private SpecResult buildSpec(String repoUrl, String branch, String httpMethod,
            String pathOrSummary, NarrativeWriter writer, FlowProposer proposer) {

        RepoSnapshot snapshot = snapshot(repoUrl, branch);
        ApiEndpoint endpoint = locate(snapshot, httpMethod, pathOrSummary);

        ApiFlow flow = new CallFlowBuilder(snapshot.index(), this.properties)
                .build(endpoint, repoUrl, branch, snapshot.commitSha());
        CodeSpec spec = EvidenceCollector.collect(snapshot.index(), flow);
        String promptText = SpecReportRenderer.promptText(spec);

        Narration narration = narrate(writer, promptText, spec, endpoint, snapshot.commitSha());
        FlowComparison comparison = crossCheck(proposer, snapshot.index(), flow);

        String mermaid = MermaidRenderer.render(flow);
        String markdown = SpecReportRenderer.markdown(flow, spec, mermaid, narration.narrative(),
                narration.aiNote(), comparison);
        String html = SpecReportRenderer.html(flow, spec, narration.narrative(),
                narration.aiNote(), comparison);

        String baseName = baseName(endpoint, snapshot.commitSha()) + "__spec";
        log.info("sinh đặc tả {} | {} dẫn chứng | {} câu hỏi | AI={} ({} lượt) | đối chiếu={} "
                        + "(đồng thuận {}%) | {} trích dẫn hợp lệ",
                endpoint.label(), spec.evidence().size(), spec.questions().size(),
                narration.attempts() > 0, narration.attempts(), comparison.aiResponded(),
                comparison.agreementPercent(), narration.citations().citedIds().size());

        return new SpecResult(flow, spec, promptText,
                new AnalysisResult.Rendered(markdown, write(baseName + ".md", markdown)),
                new AnalysisResult.Rendered(html, write(baseName + ".html", html)),
                narration.citations(), narration.attempts() > 0, narration.attempts(),
                narration.rejectedDraft(), narration.aiNote(), comparison);
    }

    /**
     * Cho LLM tự dựng call graph rồi so với parser.
     *
     * <p>LLM cố tình KHÔNG được xem kết quả của parser: thấy trước thì nó sẽ xác nhận lại parser và
     * bước đối chiếu mất hết giá trị. Hai bên phải độc lập rồi mới so.
     */
    private FlowComparison crossCheck(FlowProposer proposer, JavaSourceIndex index, ApiFlow flow) {
        if (proposer == FlowProposer.NONE) {
            return FlowComparison.notRun();
        }
        Optional<FlowComparison> cached =
                this.aiCache.comparison(flow.commitSha(), flow.endpoint());
        if (cached.isPresent()) {
            // Không gọi model, và quan trọng hơn: KHÔNG gửi lại source code ra ngoài hạ tầng.
            return cached.get();
        }
        FlowComparison comparison = crossCheckFresh(proposer, index, flow);
        this.aiCache.putComparison(flow.commitSha(), flow.endpoint(), comparison);
        return comparison;
    }

    private FlowComparison crossCheckFresh(FlowProposer proposer, JavaSourceIndex index, ApiFlow flow) {
        String bundle = SourceBundleBuilder.build(index, flow,
                this.properties.getAi().getMaxSourceChars());
        String proposal;
        try {
            proposal = proposer.propose(bundle, flow.endpoint().label());
        }
        catch (RuntimeException ex) {
            log.warn("gọi LLM để đối chiếu call graph thất bại: {}", describeLlmFailure(ex));
            return FlowComparison.notRun();
        }
        return FlowComparator.compare(flow, proposal);
    }

    /** Kết quả của bước nhờ LLM viết mô tả, sau khi đã kiểm tra truy vết. */
    private record Narration(
            String narrative,
            CitationValidator.Result citations,
            int attempts,
            String rejectedDraft,
            String aiNote) {
    }

    /**
     * Nhờ {@code writer} viết mô tả, kiểm tra truy vết, và cho thử lại MỘT lần với phản hồi cụ thể.
     *
     * <p>Nếu lần hai vẫn không đạt thì <b>loại bỏ</b> phần mô tả thay vì đưa vào tài liệu kèm cảnh
     * báo. Lý do: người đọc tài liệu là BA, họ không đối chiếu được dẫn chứng; một đoạn văn trôi
     * chảy có trích dẫn bịa sẽ được tin ngay. Phần dữ kiện tất định vẫn dùng được bình thường.
     */
    private Narration narrate(NarrativeWriter writer, String promptText, CodeSpec spec,
            ApiEndpoint endpoint, String commitSha) {

        if (writer == NarrativeWriter.NONE) {
            return new Narration(null, CitationValidator.validate(null, spec.evidence()), 0, null,
                    null);
        }
        Optional<Narration> cached = cachedNarration(commitSha, endpoint, spec);
        if (cached.isPresent()) {
            return cached.get();
        }
        Narration fresh = narrateFresh(writer, promptText, spec, endpoint);
        this.aiCache.putNarration(commitSha, endpoint, fresh.narrative(), fresh.aiNote(),
                fresh.attempts());
        return fresh;
    }

    /**
     * Bản mô tả đã cache, sau khi <b>kiểm tra lại</b> truy vết trên bảng dẫn chứng hiện tại.
     *
     * <p>Kiểm tra lại thay vì cache luôn kết quả kiểm tra: phép kiểm chỉ là regex nên rẻ, và nó biến
     * cache thành thứ không thể hạ thấp chuẩn - bản cache nào không khớp được với dẫn chứng thì bị
     * coi như chưa có cache, model sẽ được gọi lại.
     */
    private Optional<Narration> cachedNarration(String commitSha, ApiEndpoint endpoint,
            CodeSpec spec) {

        return this.aiCache.narration(commitSha, endpoint)
                .map(cached -> new Narration(cached.narrative(),
                        CitationValidator.validate(cached.narrative(), spec.evidence()),
                        cached.attempts(), null, cached.aiNote()))
                .filter(narration -> {
                    if (narration.citations().trustworthy()) {
                        return true;
                    }
                    log.warn("bỏ qua bản mô tả trong cache cho {} vì không còn đạt truy vết: {}",
                            endpoint.label(), narration.citations().describe());
                    return false;
                });
    }

    private Narration narrateFresh(NarrativeWriter writer, String promptText, CodeSpec spec,
            ApiEndpoint endpoint) {

        String draft = safeWrite(writer, promptText, null, endpoint);
        if (draft == null || draft.isBlank()) {
            return new Narration(null, CitationValidator.validate(null, spec.evidence()), 1, null,
                    "Đã bật chế độ AI nhưng không tạo được phần mô tả (thiếu cấu hình LLM hoặc lỗi "
                            + "gọi model). Tài liệu bên dưới chỉ gồm phần dữ kiện tất định.");
        }

        CitationValidator.Result citations = CitationValidator.validate(draft, spec.evidence());
        if (citations.trustworthy()) {
            return new Narration(draft, citations, 1, null, aiNote(1));
        }

        log.warn("bản nháp AI cho {} không đạt truy vết, thử lại: {}", endpoint.label(),
                citations.describe());
        String retry = safeWrite(writer, promptText, citations.describe(), endpoint);
        if (retry != null && !retry.isBlank()) {
            CitationValidator.Result retryCitations =
                    CitationValidator.validate(retry, spec.evidence());
            if (retryCitations.trustworthy()) {
                return new Narration(retry, retryCitations, 2, null, aiNote(2));
            }
            return new Narration(null, retryCitations, 2, retry, rejectionNote(retryCitations));
        }
        return new Narration(null, citations, 2, draft, rejectionNote(citations));
    }

    /** Lỗi từ LLM không được làm sập cả request: phần tất định vẫn phải trả về được. */
    private String safeWrite(NarrativeWriter writer, String promptText, String feedback,
            ApiEndpoint endpoint) {
        try {
            return writer.write(promptText, feedback);
        }
        catch (RuntimeException ex) {
            log.warn("gọi LLM để viết mô tả cho {} thất bại: {}", endpoint.label(),
                    describeLlmFailure(ex));
            return null;
        }
    }

    /**
     * Diễn giải lỗi gọi LLM thành gợi ý cụ thể.
     *
     * <p>{@code OpenAIIoException} nghĩa là OkHttp KHÔNG nhận được response nào để đọc mã lỗi -
     * khác hẳn 401/404 (server có trả lời, chỉ là từ chối). Nguyên nhân gần như luôn là DNS,
     * proxy/firewall chặn, hoặc request quá lâu vượt timeout. Không có exception con nào của SDK
     * cho các trường hợp này nên phải dựa vào tên lớp và {@code getCause()}.
     */
    private static String describeLlmFailure(RuntimeException ex) {
        String type = ex.getClass().getSimpleName();
        String cause = ex.getCause() == null ? "" : " (nguyên nhân gốc: "
                + ex.getCause().getClass().getSimpleName() + ": " + ex.getCause().getMessage() + ")";

        if ("OpenAIIoException".equals(type)) {
            return ex.getMessage() + cause + " -- OkHttp KHÔNG nhận được response (không phải lỗi "
                    + "401/404 từ server), nên hầu như luôn là: (1) DNS/mạng/proxy/firewall không "
                    + "tới được base-url LLM, hoặc (2) request vượt agent.llm.timeout-seconds. "
                    + "Kiểm tra: Test-NetConnection <host> -Port 443 từ đúng máy chạy server này.";
        }
        return ex.toString();
    }

    private static String aiNote(int attempts) {
        return "Phần mô tả bên dưới do model ngôn ngữ viết dựa trên bảng dẫn chứng, và đã qua kiểm "
                + "tra: mọi mã [En] đều trỏ tới dẫn chứng thật"
                + (attempts > 1 ? " (model phải viết lại lần thứ hai mới đạt)" : "")
                + ". Phần bảng dẫn chứng và danh sách câu hỏi KHÔNG do model sinh ra.";
    }

    private static String rejectionNote(CitationValidator.Result citations) {
        return "Đã bật chế độ AI, nhưng phần mô tả do model viết bị LOẠI BỎ vì không đạt kiểm tra "
                + "truy vết sau 2 lượt. " + citations.describe()
                + " Tài liệu bên dưới chỉ gồm phần dữ kiện tất định - vẫn dùng được, chỉ thiếu phần "
                + "diễn giải bằng văn xuôi.";
    }

    /** Tìm endpoint theo path, không có thì thử theo mô tả - dùng chung cho cả hai luồng. */
    private ApiEndpoint locate(RepoSnapshot snapshot, String httpMethod, String pathOrSummary) {
        if (pathOrSummary == null || pathOrSummary.isBlank()) {
            throw new IllegalArgumentException("Thiếu path hoặc từ khoá mô tả của API cần phân tích.");
        }
        String needle = pathOrSummary.trim();
        Optional<ApiEndpoint> byPath = snapshot.endpoints().stream()
                .filter(endpoint -> endpoint.matches(httpMethod, needle))
                .findFirst();
        if (byPath.isPresent()) {
            return byPath.get();
        }
        String lower = needle.toLowerCase(Locale.ROOT);
        return snapshot.endpoints().stream()
                .filter(endpoint -> endpoint.summary() != null
                        && endpoint.summary().toLowerCase(Locale.ROOT).contains(lower))
                .findFirst()
                .orElseThrow(() -> notFound("'" + needle + "'", snapshot));
    }

    private AnalysisResult analyze(ApiEndpoint endpoint, RepoSnapshot snapshot, String repoUrl,
            String branch, NarrativeWriter writer, FlowProposer proposer) {

        long startNanos = System.nanoTime();
        ApiFlow flow = new CallFlowBuilder(snapshot.index(), this.properties)
                .build(endpoint, repoUrl, branch, snapshot.commitSha());

        MarkdownReportRenderer.FlowFacts facts = FlowSummarizer.extract(flow);
        String summary = FlowSummarizer.summarize(flow);

        // Phần AI dùng chung đúng cơ chế với tài liệu đặc tả: dẫn chứng tất định, mô tả có kiểm
        // tra truy vết, đối chiếu chéo tách riêng.
        CodeSpec spec = EvidenceCollector.collect(snapshot.index(), flow);
        Narration narration = narrate(writer, SpecReportRenderer.promptText(spec), spec, endpoint,
                snapshot.commitSha());
        FlowComparison comparison = crossCheck(proposer, snapshot.index(), flow);

        String mermaid = MermaidRenderer.render(flow);
        String markdown = MarkdownReportRenderer.render(flow, mermaid, facts, spec,
                narration.narrative(), narration.aiNote(), comparison);
        String html = HtmlReportRenderer.render(flow, facts, spec,
                narration.narrative(), narration.aiNote(), comparison);
        String puml = PlantUmlRenderer.render(flow, spec, comparison,
                this.properties.getDatabase().isShowInDiagram());

        String baseName = baseName(endpoint, snapshot.commitSha());
        AnalysisResult result = new AnalysisResult(flow, summary,
                new AnalysisResult.Rendered(html, write(baseName + ".html", html)),
                new AnalysisResult.Rendered(markdown, write(baseName + ".md", markdown)),
                new AnalysisResult.Rendered(mermaid, write(baseName + ".mmd", mermaid)),
                new AnalysisResult.Rendered(puml, write(baseName + ".puml", puml)),
                comparison, narration.attempts() > 0, narration.aiNote());

        log.info("phân tích {} xong sau {} ms | {} participant | AI={} | đối chiếu={} "
                        + "(đồng thuận {}%) | html {} ký tự", endpoint.label(),
                (System.nanoTime() - startNanos) / 1_000_000, flow.participants().size(),
                narration.attempts() > 0, comparison.aiResponded(), comparison.agreementPercent(),
                html.length());
        return result;
    }

    private RepoSnapshot snapshot(String repoUrl, String branch) {
        GitRepoFetcher.FetchedRepo repo = this.fetcher.fetch(repoUrl, branch);
        String key = repo.repoUrl() + "#" + repo.branch();

        RepoSnapshot cached = this.snapshots.get(key);
        if (cached != null && cached.commitSha().equals(repo.commitSha())) {
            return cached;
        }
        JavaSourceIndex index = JavaSourceIndex.build(repo.root(), this.properties);
        RepoSnapshot fresh = new RepoSnapshot(repo.commitSha(), index, EndpointScanner.scan(index));
        this.snapshots.put(key, fresh);
        return fresh;
    }

    private EndpointNotFoundException notFound(String what, RepoSnapshot snapshot) {
        List<String> suggestions = snapshot.endpoints().stream()
                .limit(30)
                .map(ApiEndpoint::describe)
                .toList();
        return new EndpointNotFoundException(
                "Không có thông tin cần tìm: repo không có endpoint nào khớp " + what + ".",
                suggestions);
    }

    private static String describeRequest(String httpMethod, String path) {
        return (httpMethod == null || httpMethod.isBlank() ? "" : httpMethod.toUpperCase(Locale.ROOT) + " ")
                + path;
    }

    /** Tên file dùng chung cho mọi định dạng, gắn commit SHA để không lẫn giữa các lần release. */
    private static String baseName(ApiEndpoint endpoint, String commitSha) {
        String name = (endpoint.httpMethod() + endpoint.path())
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("(^-|-$)", "")
                .toLowerCase(Locale.ROOT);
        String sha = (commitSha == null || commitSha.isBlank())
                ? "nosha"
                : commitSha.substring(0, Math.min(8, commitSha.length()));
        return name + "__" + sha;
    }

    /** Ghi một định dạng ra đĩa. Ghi thất bại không làm hỏng cả lần phân tích, chỉ mất file. */
    private Path write(String fileName, String content) {
        try {
            Path dir = this.properties.getOutputDir();
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        }
        catch (IOException | UncheckedIOException ex) {
            log.warn("không ghi được {}: {}", fileName, ex.getMessage());
            return null;
        }
    }
}
