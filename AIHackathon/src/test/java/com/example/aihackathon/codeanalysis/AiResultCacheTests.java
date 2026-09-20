package com.example.aihackathon.codeanalysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.aihackathon.codeanalysis.model.FlowComparison;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cache kết quả LLM. Thứ được cache ở đây là <b>tiền và quota</b>, không phải thời gian: hỏi lại
 * cùng một endpoint trên cùng commit mà không cache là trả tiền token lần nữa, và bước đối chiếu còn
 * gửi lại nguyên văn source ra ngoài hạ tầng lần nữa.
 *
 * <p>Vì vậy nhóm test này đo đúng một thứ: <b>số lần model được gọi</b>.
 */
class AiResultCacheTests {

    @TempDir
    Path outputDir;

    private AnalysisProperties properties;

    private ApiFlowAnalyzer analyzer;

    private final List<String> narratorCalls = new ArrayList<>();

    private final AtomicInteger proposerCalls = new AtomicInteger();

    /** Bản mô tả hợp lệ: chỉ dẫn E1 - mã luôn tồn tại vì dẫn chứng đầu tiên là ENDPOINT. */
    private static final String VALID_NARRATIVE = """
            ### Hệ thống đang làm gì
            Hệ thống tiếp nhận yêu cầu hạch toán bù trừ [E1].

            ### Suy luận - cần xác nhận với dev
            Không có""";

    private final NarrativeWriter writer = (promptText, feedback) -> {
        this.narratorCalls.add(promptText);
        return VALID_NARRATIVE;
    };

    private final FlowProposer proposer = (bundle, endpointLabel) -> {
        this.proposerCalls.incrementAndGet();
        return "STEP SettlementController.post -> SettlementService.post | vào tầng nghiệp vụ";
    };

    @BeforeEach
    void setUp() {
        this.properties = ProcedureFixtureRepo.properties();
        this.properties.setOutputDir(this.outputDir);
        this.properties.getAi().setCache(true);
        this.properties.getAi().setModel("z-ai/glm-5.2-hackathon");
        this.analyzer = new ApiFlowAnalyzer(
                new ProcedureFixtureRepo.StubFetcher(this.properties), this.properties);
    }

    private ApiFlowAnalyzer.AnalysisResult analyze() {
        return this.analyzer.analyzeFlowWith(ProcedureFixtureRepo.REPO_URL, "master", "POST",
                "/api/settlements/{id}/post", this.writer, this.proposer);
    }

    // ------------------------------------------------------------------
    // Không gọi lại model
    // ------------------------------------------------------------------

    @Test
    void lanThuHaiKhongGoiModelNua() {
        ApiFlowAnalyzer.AnalysisResult first = analyze();
        ApiFlowAnalyzer.AnalysisResult second = analyze();

        assertThat(this.narratorCalls).hasSize(1);
        assertThat(this.proposerCalls).hasValue(1);

        // Nội dung tài liệu phải giống nhau, nếu không thì cache đang đổi kết quả đầu ra.
        assertThat(second.markdown().content()).isEqualTo(first.markdown().content());
        assertThat(second.aiUsed()).isTrue();
        assertThat(second.comparison().aiResponded()).isTrue();
        assertThat(second.comparison().agreed()).isEqualTo(first.comparison().agreed());
    }

    @Test
    void cacheNamTrenDiaNenSongQuaViecTaoLaiAnalyzer() {
        analyze();

        // Analyzer mới = heap mới, đúng như sau khi restart process.
        ApiFlowAnalyzer restarted = new ApiFlowAnalyzer(
                new ProcedureFixtureRepo.StubFetcher(this.properties), this.properties);
        restarted.analyzeFlowWith(ProcedureFixtureRepo.REPO_URL, "master", "POST",
                "/api/settlements/{id}/post", this.writer, this.proposer);

        assertThat(this.narratorCalls).hasSize(1);
        assertThat(this.proposerCalls).hasValue(1);
    }

    @Test
    void ghiCacheVaoThuMucAiCacheDuoiOutputDir() throws Exception {
        analyze();

        List<String> files;
        try (var entries = Files.list(this.outputDir.resolve("ai-cache"))) {
            files = entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertThat(files).hasSize(2);
        assertThat(files).anyMatch(name -> name.contains("__narration__") && name.endsWith(".json"));
        assertThat(files).anyMatch(name -> name.contains("__comparison__") && name.endsWith(".json"));
        // Tên file mang method + path + sha8 của commit, cùng quy ước với các file .puml/.md.
        assertThat(files).allMatch(name -> name.startsWith("post-api-settlements-id-post__fedcba98"));
    }

    // ------------------------------------------------------------------
    // Khoá cache
    // ------------------------------------------------------------------

    @Test
    void doiModelThiPhaiGoiLaiKhongDuocTraBanCu() {
        analyze();
        this.properties.getAi().setModel("another-vendor/some-other-model");
        analyze();

        assertThat(this.narratorCalls).hasSize(2);
        assertThat(this.proposerCalls).hasValue(2);
    }

    @Test
    void doiHanMucSourceThiChiPhanDoiChieuPhaiChayLai() {
        analyze();
        this.properties.getAi().setMaxSourceChars(8000);
        analyze();

        // Phần mô tả không phụ thuộc max-source-chars vì model viết mô tả không thấy source.
        assertThat(this.narratorCalls).hasSize(1);
        // Phần đối chiếu thì có: đổi hạn mức là đổi lượng code LLM được thấy.
        assertThat(this.proposerCalls).hasValue(2);
    }

    @Test
    void doiCommitThiPhaiChayLaiCaHai() {
        analyze();

        ProcedureFixtureRepo.StubFetcher fetcher =
                new ProcedureFixtureRepo.StubFetcher(this.properties);
        fetcher.commitSha = "1111111122222222111111112222222211111111";
        new ApiFlowAnalyzer(fetcher, this.properties).analyzeFlowWith(ProcedureFixtureRepo.REPO_URL,
                "master", "POST", "/api/settlements/{id}/post", this.writer, this.proposer);

        assertThat(this.narratorCalls).hasSize(2);
        assertThat(this.proposerCalls).hasValue(2);
    }

    @Test
    void tatCacheThiLanNaoCungGoiModel() {
        this.properties.getAi().setCache(false);

        analyze();
        analyze();

        assertThat(this.narratorCalls).hasSize(2);
        assertThat(this.proposerCalls).hasValue(2);
    }

    // ------------------------------------------------------------------
    // Không cache thứ không được nhận
    // ------------------------------------------------------------------

    @Test
    void khongCacheBanNhapBiLoaiViTruotTruyVet() {
        AtomicInteger calls = new AtomicInteger();
        NarrativeWriter fabricating = (promptText, feedback) -> {
            calls.incrementAndGet();
            return "Hệ thống làm việc X [E9999] tại OrderService.java:42";
        };

        this.analyzer.analyzeFlowWith(ProcedureFixtureRepo.REPO_URL, "master", "POST",
                "/api/settlements/{id}/post", fabricating, FlowProposer.NONE);
        this.analyzer.analyzeFlowWith(ProcedureFixtureRepo.REPO_URL, "master", "POST",
                "/api/settlements/{id}/post", fabricating, FlowProposer.NONE);

        // 2 lượt mỗi lần chạy (viết + viết lại), và lần chạy thứ hai KHÔNG được dùng bản đã bị loại.
        assertThat(calls).hasValue(4);
    }

    @Test
    void khongCacheKhiGoiModelThatBai() {
        AtomicInteger calls = new AtomicInteger();
        NarrativeWriter failing = (promptText, feedback) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("mất mạng tới LLM");
        };
        FlowProposer failingProposer = (bundle, label) -> {
            throw new IllegalStateException("mất mạng tới LLM");
        };

        ApiFlowAnalyzer.AnalysisResult first = this.analyzer.analyzeFlowWith(
                ProcedureFixtureRepo.REPO_URL, "master", "POST", "/api/settlements/{id}/post",
                failing, failingProposer);
        this.analyzer.analyzeFlowWith(ProcedureFixtureRepo.REPO_URL, "master", "POST",
                "/api/settlements/{id}/post", failing, failingProposer);

        assertThat(first.comparison().aiResponded()).isFalse();
        // Lỗi mạng không phải kết quả để dùng lại: lần sau phải thử lại.
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);
        assertThat(Files.exists(this.outputDir.resolve("ai-cache"))).isFalse();
    }

    @Test
    void banCacheKhongConDatTruyVetThiBiCoiNhuChuaCoCache() throws Exception {
        analyze();

        Path narrationFile;
        try (var entries = Files.list(this.outputDir.resolve("ai-cache"))) {
            narrationFile = entries.filter(path -> path.getFileName().toString().contains("narration"))
                    .findFirst().orElseThrow();
        }
        // Sửa tay file cache thành bản có mã dẫn chứng bịa - mô phỏng cache bị can thiệp.
        Files.writeString(narrationFile,
                "{\"narrative\":\"Hệ thống làm gì đó [E9999].\",\"aiNote\":\"x\",\"attempts\":1}");

        analyze();

        // Phải gọi lại model thay vì đưa bản có trích dẫn bịa vào tài liệu.
        assertThat(this.narratorCalls).hasSize(2);
    }

    @Test
    void cacheHongKhongLamSapRequest() throws Exception {
        analyze();

        Path cacheDir = this.outputDir.resolve("ai-cache");
        try (var entries = Files.list(cacheDir)) {
            for (Path file : entries.toList()) {
                Files.writeString(file, "{ đây không phải json");
            }
        }

        ApiFlowAnalyzer.AnalysisResult result = analyze();

        assertThat(result.markdown().content()).contains("Procedure và package database");
        assertThat(this.narratorCalls).hasSize(2);
        assertThat(this.proposerCalls).hasValue(2);
    }

    // ------------------------------------------------------------------
    // Hai cờ độc lập
    // ------------------------------------------------------------------

    @Test
    void chiBatUseAiVanDungLaiDuocBanMoTaDaCacheBoiLanBatCaHaiCo() {
        analyze();

        ApiFlowAnalyzer.AnalysisResult onlyNarrative = this.analyzer.analyzeFlowWith(
                ProcedureFixtureRepo.REPO_URL, "master", "POST", "/api/settlements/{id}/post",
                this.writer, FlowProposer.NONE);

        assertThat(this.narratorCalls).hasSize(1);
        assertThat(onlyNarrative.aiUsed()).isTrue();
        // Không bật cross-check thì không có đối chiếu, dù cache có sẵn bản đối chiếu.
        assertThat(onlyNarrative.comparison()).isEqualTo(FlowComparison.notRun());
    }
}
