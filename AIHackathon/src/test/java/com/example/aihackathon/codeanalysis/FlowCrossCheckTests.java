package com.example.aihackathon.codeanalysis;

import java.util.List;

import com.example.aihackathon.codeanalysis.model.FlowComparison;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mức 3: LLM tự đọc source và dựng call graph riêng, rồi đối chiếu với parser.
 *
 * <p>Test dùng {@link FlowProposer} giả nên không cần API key. Phần được kiểm tra là bộ so sánh và
 * bộ đọc output - hai chỗ quyết định bảng đối chiếu có dùng được hay không. LLM thật sẽ trả về văn
 * bản lộn xộn hơn nhiều so với ví dụ trong tài liệu, nên khả năng chịu rác là yêu cầu chính.
 */
class FlowCrossCheckTests {

    private ApiFlowAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        this.analyzer = FixtureRepo.analyzer();
    }

    private ApiFlowAnalyzer.SpecResult run(FlowProposer proposer) {
        return this.analyzer.specWith(FixtureRepo.REPO_URL, "master", "POST", "/api/orders",
                NarrativeWriter.NONE, proposer);
    }

    // ------------------------------------------------------------------
    // Không bật đối chiếu
    // ------------------------------------------------------------------

    @Test
    void khongBatThiKhongGuiSourceVaKhongCoPhanDoiChieu() {
        ApiFlowAnalyzer.SpecResult result = run(FlowProposer.NONE);

        assertThat(result.comparison().aiResponded()).isFalse();
        assertThat(result.markdown().content()).doesNotContain("Đối chiếu chéo");
        assertThat(result.html().content()).doesNotContain("Đối chiếu chéo");
    }

    // ------------------------------------------------------------------
    // Gói source gửi cho LLM
    // ------------------------------------------------------------------

    @Test
    void goiSourceChuaThanMethodThatChuKhongPhaiBanRutGon() {
        List<String> bundles = new java.util.ArrayList<>();
        run((sourceBundle, endpointLabel) -> {
            bundles.add(sourceBundle);
            return null;
        });

        assertThat(bundles).hasSize(1);
        String bundle = bundles.get(0);
        // phai co code that de LLM co co hoi tim ra thu parser bo sot
        assertThat(bundle)
                .contains("ENDPOINT: POST /api/orders")
                .contains("OrderController.create")
                .contains("orderService.create(request)")
                .contains("OrderServiceImpl.create")
                .contains("this.orderValidator.validate(request)")
                .contains("// field của OrderServiceImpl:");
    }

    @Test
    void chiGuiMethodTrongLuongChuKhongGuiCaRepo() {
        List<String> bundles = new java.util.ArrayList<>();
        run((sourceBundle, endpointLabel) -> {
            bundles.add(sourceBundle);
            return null;
        });

        // OrderExceptionHandler khong nam trong luong -> khong duoc gui di
        assertThat(bundles.get(0)).doesNotContain("handleInvalidInput");
    }

    // ------------------------------------------------------------------
    // So sánh hai call graph
    // ------------------------------------------------------------------

    @Test
    void nhanRaBuocHaiBenCungTimRa() {
        ApiFlowAnalyzer.SpecResult result = run((sourceBundle, endpointLabel) -> """
                STEP OrderController.create -> OrderServiceImpl.create | sang tầng nghiệp vụ
                STEP OrderServiceImpl.create -> OrderRepository.save | lưu đơn
                """);

        FlowComparison comparison = result.comparison();
        assertThat(comparison.aiResponded()).isTrue();
        assertThat(comparison.agreed()).extracting(FlowComparison.Step::label)
                .contains("OrderController.create -> OrderServiceImpl.create",
                        "OrderServiceImpl.create -> OrderRepository.save");
    }

    /**
     * LLM hay viết tên interface ở chỗ parser đã resolve sang lớp Impl. Coi đó là lệch nhau thì
     * bảng đối chiếu sẽ đầy khác biệt giả và chỗ lệch thật bị chôn mất.
     */
    @Test
    void coiTenInterfaceVaTenImplLaCungMotBuoc() {
        ApiFlowAnalyzer.SpecResult result = run((sourceBundle, endpointLabel) ->
                "STEP OrderController.create -> OrderService.create | LLM viết tên interface");

        assertThat(result.comparison().agreed()).extracting(FlowComparison.Step::label)
                .contains("OrderController.create -> OrderServiceImpl.create");
        assertThat(result.comparison().onlyByAi()).isEmpty();
    }

    @Test
    void neuBatChiAiTimRaThiDuaVaoMucCanKiemTraThuCong() {
        ApiFlowAnalyzer.SpecResult result = run((sourceBundle, endpointLabel) ->
                "STEP OrderServiceImpl.create -> FraudCheckClient.verify | gọi kiểm tra gian lận");

        assertThat(result.comparison().onlyByAi()).extracting(FlowComparison.Step::label)
                .containsExactly("OrderServiceImpl.create -> FraudCheckClient.verify");
        assertThat(result.markdown().content())
                .contains("Chỉ AI tìm ra - cần kiểm tra thủ công")
                .contains("FraudCheckClient.verify")
                .contains("Chưa xác nhận thì đừng đưa vào tài liệu chính thức");
    }

    @Test
    void lietKeBuocChiParserTimRa() {
        ApiFlowAnalyzer.SpecResult result = run((sourceBundle, endpointLabel) ->
                "STEP OrderController.create -> OrderServiceImpl.create | chỉ thấy bước này");

        assertThat(result.comparison().onlyByParser()).isNotEmpty();
        assertThat(result.markdown().content()).contains("Chỉ phân tích tĩnh tìm ra");
    }

    @Test
    void tinhMucDongThuan() {
        // 1 buoc khop, 0 buoc chi AI, cac buoc con lai chi parser
        ApiFlowAnalyzer.SpecResult result = run((sourceBundle, endpointLabel) ->
                "STEP OrderController.create -> OrderServiceImpl.create");

        FlowComparison comparison = result.comparison();
        assertThat(comparison.agreed()).hasSize(1);
        assertThat(comparison.agreementPercent()).isBetween(1, 99);
        assertThat(comparison.totalAiSteps()).isEqualTo(1);
        assertThat(comparison.totalParserSteps()).isEqualTo(comparison.agreed().size()
                + comparison.onlyByParser().size());
    }

    // ------------------------------------------------------------------
    // Chịu rác từ LLM
    // ------------------------------------------------------------------

    @Test
    void chiuDuocVanBanLonXonQuanhCacDongStep() {
        ApiFlowAnalyzer.SpecResult result = run((sourceBundle, endpointLabel) -> """
                Được, tôi đã đọc source. Dưới đây là các bước tôi xác định:

                ```
                STEP OrderController.create -> OrderServiceImpl.create | vào tầng nghiệp vụ
                ```
                STEP OrderServiceImpl.create   ->   OrderRepository.save   |   lưu đơn hàng

                Tôi không chắc về nhánh thanh toán vì không thấy cấu hình retry.
                """);

        assertThat(result.comparison().agreed()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result.comparison().aiNoteText()).contains("không chắc về nhánh thanh toán");
    }

    @Test
    void chapNhanCacBienTheMuiTenVaChuHoaChuThuong() {
        ApiFlowAnalyzer.SpecResult result = run((sourceBundle, endpointLabel) -> """
                step OrderController.create => OrderServiceImpl.create
                STEP OrderServiceImpl.create → OrderRepository.save
                """);

        assertThat(result.comparison().agreed()).hasSize(2);
    }

    @Test
    void boQuaDongStepSaiCuPhapChuKhongMatCaKetQua() {
        ApiFlowAnalyzer.SpecResult result = run((sourceBundle, endpointLabel) -> """
                STEP thiếu mũi tên nên dòng này hỏng
                STEP OrderController.create -> OrderServiceImpl.create | dòng này đúng
                STEP A -> B
                """);

        assertThat(result.comparison().agreed()).hasSize(1);
    }

    @Test
    void llmKhongTraVeGiThiCoiNhuKhongChayDoiChieu() {
        assertThat(run((sourceBundle, endpointLabel) -> null).comparison().aiResponded()).isFalse();
        assertThat(run((sourceBundle, endpointLabel) -> "   ").comparison().aiResponded()).isFalse();
    }

    @Test
    void loiGoiLlmKhongLamSapCaRequest() {
        ApiFlowAnalyzer.SpecResult result = run((sourceBundle, endpointLabel) -> {
            throw new IllegalStateException("401 Unauthorized");
        });

        assertThat(result.comparison().aiResponded()).isFalse();
        // phan tat dinh van nguyen ven
        assertThat(result.spec().evidence()).isNotEmpty();
        assertThat(result.markdown().content()).contains("| Mã | Dữ kiện | Nguồn |");
    }

    @Test
    void doiChieuKhongLamDoiPhanTatDinh() {
        String withoutCheck = run(FlowProposer.NONE).markdown().content();
        String withCheck = run((sourceBundle, endpointLabel) ->
                "STEP OrderController.create -> OrderServiceImpl.create").markdown().content();

        // phan tu "Dan chung tu code" den truoc muc doi chieu phai giong nhau y nguyen
        assertThat(section(withCheck, "## Dẫn chứng từ code", "## Đối chiếu chéo"))
                .isEqualTo(section(withoutCheck, "## Dẫn chứng từ code", "\n---\n"));
    }

    private static String section(String text, String from, String to) {
        int start = text.indexOf(from);
        int end = text.indexOf(to, start);
        assertThat(start).as("không tìm thấy mốc đầu: " + from).isNotNegative();
        assertThat(end).as("không tìm thấy mốc cuối: " + to).isPositive();
        return text.substring(start, end).strip();
    }

    @Test
    void parseTrucTiepTraVeGhiChuCuaTungBuoc() {
        List<FlowComparison.Step> steps = FlowComparator.parse(
                "STEP A.m -> B.n | ghi chú của AI\nSTEP C.x -> D.y");

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).note()).isEqualTo("ghi chú của AI");
        assertThat(steps.get(1).note()).isNull();
    }
}
