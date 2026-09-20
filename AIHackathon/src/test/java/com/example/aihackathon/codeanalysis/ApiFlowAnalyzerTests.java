package com.example.aihackathon.codeanalysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kiểm tra toàn bộ chuỗi xử lý (index -> tìm endpoint -> call graph -> .puml + mô tả) bằng cách
 * thay bước clone bằng repo mẫu trên đĩa. Không cần mạng, không cần GitLab.
 */
class ApiFlowAnalyzerTests {

    private AnalysisProperties properties;

    private FixtureRepo.StubFetcher fetcher;

    private ApiFlowAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        this.properties = FixtureRepo.properties();
        this.properties.setOutputDir(Path.of("target", "test-diagrams"));
        this.fetcher = new FixtureRepo.StubFetcher(this.properties);
        this.analyzer = new ApiFlowAnalyzer(this.fetcher, this.properties);
    }

    @Test
    void phanTichTheoPathVaGhiRaDuBonDinhDang() throws Exception {
        ApiFlowAnalyzer.AnalysisResult result =
                this.analyzer.analyzeByPath(FixtureRepo.REPO_URL, "master", "POST", "/api/orders");

        assertThat(result.flow().endpoint().label()).isEqualTo("POST /api/orders");
        assertThat(result.html().content()).contains("<!DOCTYPE html>").contains("OrderServiceImpl");
        assertThat(result.markdown().content()).contains("```mermaid").contains("# POST /api/orders");
        assertThat(result.mermaid().content()).startsWith("sequenceDiagram");
        assertThat(result.puml().content()).contains("@startuml");

        // moi dinh dang phai duoc ghi ra dia va khop voi noi dung tra ve
        for (ApiFlowAnalyzer.AnalysisResult.Rendered rendered :
                List.of(result.html(), result.markdown(), result.mermaid(), result.puml())) {
            assertThat(rendered.file()).isNotNull();
            assertThat(Files.exists(rendered.file())).isTrue();
            assertThat(Files.readString(rendered.file())).isEqualTo(rendered.content());
        }
    }

    @Test
    void tenFileMangDuoiDungVaGanCommitSha() {
        ApiFlowAnalyzer.AnalysisResult result =
                this.analyzer.analyzeByPath(FixtureRepo.REPO_URL, "master", "POST", "/api/orders");

        assertThat(result.html().file().getFileName().toString())
                .isEqualTo("post-api-orders__01234567.html");
        assertThat(result.markdown().file().getFileName().toString()).endsWith(".md");
        assertThat(result.mermaid().file().getFileName().toString()).endsWith(".mmd");
        assertThat(result.puml().file().getFileName().toString()).endsWith(".puml");
    }

    @Test
    void phanTichTheoOperationSummaryKhiKhongBietPath() {
        ApiFlowAnalyzer.AnalysisResult result =
                this.analyzer.analyzeBySummary(FixtureRepo.REPO_URL, "master", "đơn hàng mới");

        assertThat(result.flow().endpoint().path()).isEqualTo("/api/orders");
        assertThat(result.flow().endpoint().httpMethod()).isEqualTo("POST");
    }

    @Test
    void tuDongThuPathTruocRoiMoiThuSummary() {
        ApiFlowAnalyzer.AnalysisResult byPath =
                this.analyzer.analyze(FixtureRepo.REPO_URL, "master", "GET", "/api/orders/{id}");
        assertThat(byPath.flow().endpoint().methodName()).isEqualTo("findOne");

        ApiFlowAnalyzer.AnalysisResult bySummary =
                this.analyzer.analyze(FixtureRepo.REPO_URL, "master", null, "chi tiết đơn hàng");
        assertThat(bySummary.flow().endpoint().methodName()).isEqualTo("findOne");
    }

    @Test
    void baoKhongCoThongTinCanTimKemGoiY() {
        assertThatThrownBy(() -> this.analyzer.analyzeByPath(FixtureRepo.REPO_URL, "master",
                "POST", "/api/khong-ton-tai"))
                .isInstanceOf(EndpointNotFoundException.class)
                .hasMessageContaining("Không có thông tin cần tìm")
                .satisfies(thrown -> assertThat(((EndpointNotFoundException) thrown).suggestions())
                        .anyMatch(suggestion -> suggestion.contains("/api/orders")));
    }

    @Test
    void baoKhongCoThongTinKhiSummaryKhongKhop() {
        assertThatThrownBy(() -> this.analyzer.analyzeBySummary(FixtureRepo.REPO_URL, "master",
                "thanh toán bằng tiền mặt"))
                .isInstanceOf(EndpointNotFoundException.class)
                .hasMessageContaining("@Operation(summary)");
    }

    @Test
    void ketQuaTatDinh() {
        ApiFlowAnalyzer.AnalysisResult first = this.analyzer
                .analyzeByPath(FixtureRepo.REPO_URL, "master", "POST", "/api/orders");
        ApiFlowAnalyzer.AnalysisResult second = this.analyzer
                .analyzeByPath(FixtureRepo.REPO_URL, "master", "POST", "/api/orders");

        assertThat(second.puml().content()).isEqualTo(first.puml().content());
        assertThat(second.mermaid().content()).isEqualTo(first.mermaid().content());
        assertThat(second.markdown().content()).isEqualTo(first.markdown().content());
        assertThat(second.html().content()).isEqualTo(first.html().content());
    }

    /**
     * Bug thật đã xảy ra: người dùng gọi POST /api/analyze/api-flow (báo cáo sơ đồ luồng), bật
     * useAi, thấy mã [E2] trong phần mô tả nhưng KHÔNG có mục "Dẫn chứng từ code" nào để tra, và
     * bấm vào mã không nhảy đi đâu. Nguyên nhân: MarkdownReportRenderer/HtmlReportRenderer (dùng
     * cho báo cáo sơ đồ luồng) không hề render bảng dẫn chứng - chỉ SpecReportRenderer (dùng cho
     * /api/analyze/spec) có mục đó.
     */
    @Test
    void apiFlowPhaiCoMucDanChungKhiBatAiVaLinkPhaiNhayDungCho() {
        List<String> promptSeen = new ArrayList<>();
        ApiFlowAnalyzer.AnalysisResult result = this.analyzer.analyzeFlowWith(FixtureRepo.REPO_URL,
                "master", "POST", "/api/orders",
                (promptText, feedback) -> {
                    promptSeen.add(promptText);
                    return "Tiếp nhận yêu cầu tạo đơn hàng [E1].";
                },
                FlowProposer.NONE);

        assertThat(promptSeen).hasSize(1);
        assertThat(result.markdown().content())
                .as("thiếu mục Dẫn chứng từ code trong báo cáo sơ đồ luồng")
                .contains("## Dẫn chứng từ code")
                .contains("[[E1]](#E1)")
                .contains("<a id=\"E1\"></a>`E1`");
        assertThat(result.html().content())
                .contains("<h2>Dẫn chứng từ code</h2>")
                .contains("href=\"#E1\"")
                .contains("<tr id=\"E1\">");
    }

    @Test
    void apiFlowVanCoBangDanChungKhiKhongBatAiVaKhongCoCauGiaiThichThua() {
        // Bang dan chung tat dinh co gia tri rieng du khong co van xuoi; nhung cau giai thich
        // "cach doc ma trich dan" chi can khi thuc su co ma [En] trong phan mo ta.
        ApiFlowAnalyzer.AnalysisResult result = this.analyzer.analyzeFlowWith(FixtureRepo.REPO_URL,
                "master", "POST", "/api/orders", NarrativeWriter.NONE, FlowProposer.NONE);

        assertThat(result.markdown().content())
                .contains("## Dẫn chứng từ code")
                .doesNotContain("Cách đọc mã trích dẫn");
        assertThat(result.html().content())
                .contains("<h2>Dẫn chứng từ code</h2>")
                .doesNotContain("Cách đọc mã trích dẫn");
    }

    @Test
    void kiemTraLaiCommitMoiLanGoiVaLamMoiCacheKhiCommitDoi() {
        ApiFlowAnalyzer.AnalysisResult first =
                this.analyzer.analyzeByPath(FixtureRepo.REPO_URL, "master", "POST", "/api/orders");
        assertThat(first.flow().commitSha()).isEqualTo(FixtureRepo.COMMIT_SHA);

        this.fetcher.commitSha = "ffffffffffffffffffffffffffffffffffffffff";
        ApiFlowAnalyzer.AnalysisResult second =
                this.analyzer.analyzeByPath(FixtureRepo.REPO_URL, "master", "POST", "/api/orders");

        assertThat(this.fetcher.calls).isEqualTo(2);
        assertThat(second.flow().commitSha()).isEqualTo("ffffffffffffffffffffffffffffffffffffffff");
    }

    @Test
    void banMoTaChoBaCoDuCacPhanCanThiet() {
        String summary = this.analyzer
                .analyzeByPath(FixtureRepo.REPO_URL, "master", "POST", "/api/orders").summary();

        assertThat(summary)
                .contains("ENDPOINT: POST /api/orders")
                .contains("Mô tả (@Operation): Tạo đơn hàng mới cho khách")
                .contains("== CÁC LỚP THAM GIA ==")
                .contains("== TRÌNH TỰ ==")
                .contains("== DỮ LIỆU BỊ TÁC ĐỘNG - gọi vào database Way4 ==")
                .contains("== GỌI RA NGOÀI HỆ THỐNG ==")
                .contains("== ĐIỀU KIỆN RẼ NHÁNH ==")
                .contains("== NHÁNH LỖI ==");

        assertThat(summary).contains("OrderRepository.save").contains("bảng: orders");
        assertThat(summary).contains("PaymentClient.charge");
        assertThat(summary).contains("NÉM LỖI:");
    }

    @Test
    void apiFlowCungPhaiTonTrongHaiCoAi() {        // Bug da tung xay ra: hai co nay nam trong DTO dung chung nhung chi endpoint spec xu ly,
        // endpoint api-flow nhan co roi bo qua im lang.
        List<String> narrativeCalls = new ArrayList<>();
        List<String> proposerCalls = new ArrayList<>();

        ApiFlowAnalyzer.AnalysisResult result = this.analyzer.analyzeFlowWith(FixtureRepo.REPO_URL,
                "master", "POST", "/api/orders",
                (promptText, feedback) -> {
                    narrativeCalls.add("called");
                    return "Tiếp nhận yêu cầu tạo đơn hàng [E1].";
                },
                (sourceBundle, endpointLabel) -> {
                    proposerCalls.add("called");
                    return "STEP OrderController.create -> OrderServiceImpl.create";
                });

        assertThat(narrativeCalls).hasSize(1);
        assertThat(proposerCalls).hasSize(1);
        assertThat(result.aiUsed()).isTrue();
        assertThat(result.comparison().aiResponded()).isTrue();

        assertThat(result.markdown().content())
                .contains("## Mô tả chức năng (do AI viết)")
                .contains("## Đối chiếu chéo");
        assertThat(result.html().content())
                .contains("do AI viết")
                .contains("Đối chiếu chéo");
    }

    @Test
    void apiFlowKhongBatAiThiKhongCoPhanAi() {
        ApiFlowAnalyzer.AnalysisResult result = this.analyzer.analyzeFlowWith(FixtureRepo.REPO_URL,
                "master", "POST", "/api/orders", NarrativeWriter.NONE, FlowProposer.NONE);

        assertThat(result.aiUsed()).isFalse();
        assertThat(result.comparison().aiResponded()).isFalse();
        assertThat(result.markdown().content())
                .doesNotContain("do AI viết")
                .doesNotContain("Đối chiếu chéo");
    }

    @Test
    void lietKeToanBoEndpoint() {
        assertThat(this.analyzer.endpoints(FixtureRepo.REPO_URL, "master"))
                .hasSize(2)
                .anyMatch(endpoint -> endpoint.label().equals("POST /api/orders"));
    }
}
