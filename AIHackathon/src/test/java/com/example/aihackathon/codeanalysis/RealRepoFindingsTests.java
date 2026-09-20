package com.example.aihackathon.codeanalysis;

import java.util.List;

import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.CodeSpec;
import com.example.aihackathon.codeanalysis.model.FlowComparison;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse.Argument;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse.Argument.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Năm vấn đề tìm được khi chạy agent trên repo card-enrollment-service thật. Mỗi test dưới đây ứng
 * với một vấn đề, và mô phỏng đúng đoạn code đã làm lộ ra nó.
 */
class RealRepoFindingsTests {

    private static ApiFlow flow;

    private static CodeSpec spec;

    @BeforeAll
    static void analyzeOnce() {
        JavaSourceIndex index = ProcedureFixtureRepo.index();
        ApiEndpoint endpoint = EndpointScanner.scan(index).stream()
                .filter(candidate -> candidate.path().equals("/api/events/{id}/post"))
                .findFirst()
                .orElseThrow();
        flow = new CallFlowBuilder(index, ProcedureFixtureRepo.properties())
                .build(endpoint, ProcedureFixtureRepo.REPO_URL, ProcedureFixtureRepo.BRANCH,
                        ProcedureFixtureRepo.COMMIT_SHA);
        spec = EvidenceCollector.collect(index, flow);
    }

    // ------------------------------------------------------------------
    // 1. Chữ ký IN/OUT thật từ API JPA
    // ------------------------------------------------------------------

    @Test
    void docDuocChieuInOutRefCursorTuApiJpa() {
        StoredProcedureUse use = procedure("PRC_PROCESS_EVENT_ONE");

        assertThat(use.arguments())
                .extracting(Argument::name, Argument::direction, Argument::type, Argument::hint)
                .containsExactly(
                        tuple("?1", Direction.INOUT, "String", null),
                        // setParameter(2, request.contractNumber) cho tên gợi nhớ cho vị trí
                        tuple("?2", Direction.IN, "String", "contractNumber"),
                        tuple("?3", Direction.IN, "String", "eventCode"),
                        // void.class + REF_CURSOR -> in REF_CURSOR, không in "void"
                        tuple("?4", Direction.OUT, "REF_CURSOR", null),
                        tuple("?5", Direction.OUT, "String", null));
    }

    @Test
    void khongConCheMatThamSoRaNhuKhiChiDocChuKyJava() {
        StoredProcedureUse use = procedure("PRC_PROCESS_EVENT_ONE");

        // Chữ ký Java là postEvent(ProcessEventRequest) -> ProcessEventRequest: 1 vào, 1 ra.
        // Chữ ký thật của procedure: 3 vào, 3 ra (1 INOUT + REF_CURSOR + 1 OUT).
        assertThat(use.inputs()).hasSize(3);
        assertThat(use.outputs()).hasSize(3);
        assertThat(use.signature()).contains("?4: REF_CURSOR");
    }

    // ------------------------------------------------------------------
    // 2. Câu log không được đọc thành procedure
    // ------------------------------------------------------------------

    @Test
    void cauLogBatDauBangChuCallKhongBiDocThanhProcedure() {
        // log.info("Call method postEvent contractNo: {} eventCode: {} ", ...)
        assertThat(spec.procedures()).extracting(StoredProcedureUse::routineName)
                .containsExactly("PRC_PROCESS_EVENT_ONE")
                .doesNotContain("METHOD", "POSTEVENT");
    }

    @Test
    void chuoiCoPlaceholderCuaSlf4jLuonBiCoiLaLogChuKhongPhaiSql() {
        JavaSourceIndex index = ProcedureFixtureRepo.index();
        ApiEndpoint endpoint = EndpointScanner.scan(index).stream()
                .filter(candidate -> candidate.path().equals("/api/events/{id}/post"))
                .findFirst().orElseThrow();
        ApiFlow reBuilt = new CallFlowBuilder(index, ProcedureFixtureRepo.properties())
                .build(endpoint, ProcedureFixtureRepo.REPO_URL, ProcedureFixtureRepo.BRANCH,
                        ProcedureFixtureRepo.COMMIT_SHA);

        // "Post event {} return {}" trong EventService cũng không được sinh procedure nào.
        assertThat(StoredProcedureScanner.scan(index, reBuilt))
                .extracting(StoredProcedureUse::routineName)
                .containsExactly("PRC_PROCESS_EVENT_ONE");
    }

    // ------------------------------------------------------------------
    // 3. Alias interface -> impl khi tên impl không đúng khuôn <Interface>Impl
    // ------------------------------------------------------------------

    @Test
    void khopDuocKhiAiGhiTenInterfaceMaImplDatTenKhacKhuon() {
        // Đúng tình huống thật: EnrollC2PMasterCardServiceImpl hiện thực EnrollC2PService.
        ApiFlow withAlias = flowWithImplementationNote("EnrollC2PService",
                "EnrollC2PMasterCardServiceImpl");

        FlowComparison comparison = FlowComparator.compare(withAlias,
                "STEP EnrollInfoController.manageCardC2PFromChannel -> "
                        + "EnrollC2PService.manageCardC2PFromChannel | chuyển sang tầng nghiệp vụ; "
                        + "qua interface, lớp hiện thực khả nghi là EnrollC2PMasterCardServiceImpl");

        assertThat(comparison.agreed()).hasSize(1);
        assertThat(comparison.onlyByAi()).isEmpty();
        assertThat(comparison.onlyByParser()).isEmpty();
        assertThat(comparison.agreementPercent()).isEqualTo(100);
    }

    @Test
    void khongCoGhiChuHienThucThiVanGiuHanhViCu() {
        // Không có alias -> heuristic bỏ hậu tố Impl là tất cả những gì còn lại, và nó trượt.
        ApiFlow withoutNote = flowWithImplementationNote(null, "EnrollC2PMasterCardServiceImpl");

        FlowComparison comparison = FlowComparator.compare(withoutNote,
                "STEP EnrollInfoController.manageCardC2PFromChannel -> "
                        + "EnrollC2PService.manageCardC2PFromChannel | x");

        assertThat(comparison.agreed()).isEmpty();
        assertThat(comparison.onlyByAi()).hasSize(1);
        assertThat(comparison.onlyByParser()).hasSize(1);
    }

    @Test
    void heuristicBoHauToImplVanConTacDungChoKhuonChuan() {
        ApiFlow withoutNote = flowWithImplementationNote(null, "OrderServiceImpl");

        FlowComparison comparison = FlowComparator.compare(withoutNote,
                "STEP EnrollInfoController.manageCardC2PFromChannel -> "
                        + "OrderService.manageCardC2PFromChannel | x");

        assertThat(comparison.agreed()).hasSize(1);
        assertThat(comparison.agreementPercent()).isEqualTo(100);
    }

    /** Một luồng tối giản: controller gọi vào một lớp, kèm (hoặc không kèm) ghi chú hiện thực. */
    private static ApiFlow flowWithImplementationNote(String declaredInterface, String implSimpleName) {
        return FlowNodeFixtures.call("EnrollInfoController", "manageCardC2PFromChannel",
                        implSimpleName, "manageCardC2PFromChannel",
                        declaredInterface == null ? null : "hiện thực của " + declaredInterface)
                .toFlow(flow.endpoint());
    }

    // ------------------------------------------------------------------
    // 4. Nhiễu Lombok không vào danh sách unresolved
    // ------------------------------------------------------------------

    @Test
    void khongBaoLogVaBuilderCuaLombokLaDiemChuaXacDinh() {
        // EventService dùng log.info(...) và ProcessEventRequest.builder()...build(), cả hai đều là
        // thành viên Lombok sinh lúc compile nên symbol solver chắc chắn gãy.
        assertThat(flow.unresolved()).isEmpty();
    }

    @Test
    void banBaoCaoNoiRoPhanTichPhuHetLuong() {
        String markdown = MarkdownReportRenderer.render(flow, MermaidRenderer.render(flow),
                FlowSummarizer.extract(flow), spec, null, null, null);

        assertThat(markdown).contains("Phân tích tĩnh phủ hết luồng này, không có điểm mờ");
        assertThat(markdown).doesNotContain("không suy được lớp đích");
    }

    // ------------------------------------------------------------------
    // 5. Footer nói đúng nguồn gốc
    // ------------------------------------------------------------------
    @Test
    void footerNoiKhongDungModelKhiThucSuKhongDung() {
        String markdown = MarkdownReportRenderer.render(flow, MermaidRenderer.render(flow),
                FlowSummarizer.extract(flow), spec, null, null, FlowComparison.notRun());

        assertThat(markdown).contains("Không dùng model ngôn ngữ, nên cùng một commit luôn cho ra "
                + "cùng kết quả");
    }

    @Test
    void footerKhongDuocNoiKhongDungModelKhiTaiLieuCoPhanDoAiViet() {
        String markdown = MarkdownReportRenderer.render(flow, MermaidRenderer.render(flow),
                FlowSummarizer.extract(flow), spec, "Hệ thống làm X [E1].", "do AI viết",
                FlowComparison.notRun());

        assertThat(markdown).doesNotContain("Không dùng model ngôn ngữ");
        assertThat(markdown).contains("Riêng phần \"Mô tả chức năng\" có model ngôn ngữ tham gia");
        assertThat(markdown).contains("KHÔNG tái lập được nguyên văn giữa hai lần chạy");
    }

    @Test
    void footerNeuCaHaiPhanKhiBatCaHaiCo() {
        FlowComparison comparison = new FlowComparison(List.of(), List.of(), List.of(), null, true);

        String markdown = MarkdownReportRenderer.render(flow, MermaidRenderer.render(flow),
                FlowSummarizer.extract(flow), spec, "Hệ thống làm X [E1].", "do AI viết", comparison);
        String html = HtmlReportRenderer.render(flow, FlowSummarizer.extract(flow), spec,
                "Hệ thống làm X [E1].", "do AI viết", comparison);

        assertThat(markdown).contains("Riêng phần \"Mô tả chức năng\" và phần \"Đối chiếu chéo\"");
        assertThat(html).doesNotContain("Không dùng model ngôn ngữ");
        assertThat(html).contains("có model ngôn ngữ tham gia");
    }

    private static StoredProcedureUse procedure(String routineName) {
        return spec.procedures().stream()
                .filter(use -> use.routineName().equals(routineName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("không tìm thấy procedure " + routineName));
    }
}
