package com.example.aihackathon.codeanalysis;

import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.CodeEvidence;
import com.example.aihackathon.codeanalysis.model.CodeSpec;
import com.example.aihackathon.codeanalysis.model.ErrorCode;
import com.example.aihackathon.codeanalysis.model.ErrorCode.Origin;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Hai yêu cầu sau khi đọc báo cáo thật: chữ ký procedure phải dễ đọc trên sơ đồ, và tài liệu phải
 * liệt kê được cả bảng mã lỗi của hệ thống.
 */
class ProcedureTableAndErrorCodeTests {

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
    // Chữ ký procedure trên .puml
    // ------------------------------------------------------------------

    @Test
    void nhanMuiTenChiMangTenNganCuaProcedure() {
        String puml = PlantUmlRenderer.render(flow, spec, true);

        assertThat(puml).contains(": gọi procedure PRC_PROCESS_EVENT_ONE");
        // Nhãn cũ nhồi cả package và danh sách tham số vào một dòng - không được quay lại kiểu đó.
        assertThat(puml).doesNotContain("gọi procedure CARDAPP.PKG_MSB_CARD_API.PRC_PROCESS_EVENT_ONE(");
    }

    @Test
    void chiTietThamSoNamTrongBangCreole() {
        String puml = PlantUmlRenderer.render(flow, spec, true);

        assertThat(puml).contains("<b>CARDAPP.PKG_MSB_CARD_API.PRC_PROCESS_EVENT_ONE</b>");
        assertThat(puml).contains("|= # |= chiều |= tên |= kiểu |");
        assertThat(puml).contains("| 2 | vào | contractNumber | String |");
        assertThat(puml).contains("| 4 | ra | -- | REF_CURSOR |");
        assertThat(puml).contains("| 1 | vào-ra | -- | String |");
    }

    @Test
    void muiTenTraVeChiNeuSoLuongKhiCoNhieuThamSoRa() {
        String puml = PlantUmlRenderer.render(flow, spec, true);

        assertThat(puml).contains("trả về 3 tham số ra (xem bảng)");
    }

    @Test
    void legendNeuTomLuocSoThamSoThayViCaChuKy() {
        String puml = PlantUmlRenderer.render(flow, spec, true);

        assertThat(puml).contains("CARDAPP.PKG_MSB_CARD_API.PRC_PROCESS_EVENT_ONE  -  3 vào / 3 ra");
        assertThat(puml).contains("Chi tiết tham số của từng procedure nằm ở bảng cạnh mũi tên");
    }

    @Test
    void oTrongTrongBangHienDauGachNganChuKhongDeTrong() {
        StoredProcedureUse use = spec.procedures().get(0);

        // ?1 là INOUT không có tên biến nào gán vào -> displayName null -> bảng hiện "--"
        assertThat(use.arguments().get(0).displayName()).isNull();
        assertThat(use.arguments().get(0).position()).isEqualTo("1");
        assertThat(use.arguments().get(1).displayName()).isEqualTo("contractNumber");
    }

    // ------------------------------------------------------------------
    // Mã lỗi API này có thể trả về
    // ------------------------------------------------------------------

    @Test
    void chiLietKeMaLoiDiToiDuocTuEndpointKhongPhaiCaBangCuaSource() {
        // CommonErrorCode có 7 hằng; endpoint này chỉ đi tới được 3.
        assertThat(spec.errorCodes())
                .extracting(ErrorCode::name, ErrorCode::code, ErrorCode::httpStatus,
                        ErrorCode::origin)
                .containsExactly(
                        tuple("CALL_EVENT_PROCESS_ONE", "1025", "INTERNAL_SERVER_ERROR",
                                Origin.THROWN),
                        tuple("INVALID_FIELD", "400", "BAD_REQUEST", Origin.VALIDATION),
                        tuple("SYSTEMS_ERROR", "-1", "BAD_REQUEST", Origin.CATCH_ALL));

        assertThat(spec.errorCodes()).extracting(ErrorCode::name)
                .doesNotContain("CARD_NOT_FOUND", "ENROLL_ALREADY_DONE", "SUCCESSFUL",
                        "EXECUTE_THIRTY_SERVICE_BY_SYS_ERROR");
    }

    @Test
    void noiRoViSaoTungMaDiToiDuoc() {
        assertThat(spec.errorCodes()).extracting(ErrorCode::trigger)
                .containsExactly(
                        "ném tại EventService.post()",
                        "khi request không thoả ràng buộc dữ liệu vào",
                        "mọi lỗi không khớp handler cụ thể nào");
    }

    @Test
    void batDuocMaCuaNhanhValidationDuLaOverrideKhongCoAnnotation() {
        // handleMethodArgumentNotValid là override của ResponseEntityExceptionHandler - không có
        // @ExceptionHandler. Repo thật dùng đúng kiểu này.
        assertThat(spec.errorCodesFrom(Origin.VALIDATION))
                .extracting(ErrorCode::name).containsExactly("INVALID_FIELD");
    }

    @Test
    void khongGanNhamHandlerCuaBaseExceptionVaoNhomBatTatCa() {
        // "BaseException".contains("Exception") là true -> khớp bằng contains sẽ gán sai.
        assertThat(spec.errorCodesFrom(Origin.CATCH_ALL))
                .extracting(ErrorCode::name).containsExactly("SYSTEMS_ERROR");
    }

    @Test
    void giuNguyenPlaceholderTrongThongDiep() {
        ErrorCode code = spec.errorCodes().stream()
                .filter(item -> item.name().equals("CALL_EVENT_PROCESS_ONE"))
                .findFirst().orElseThrow();

        // Xoá %s đi thì người đọc không biết chỗ nào được điền động lúc runtime.
        assertThat(code.message()).isEqualTo("Has error call package way4 '%s' with mes '%s'");
        assertThat(code.label()).isEqualTo("1025 CALL_EVENT_PROCESS_ONE");
    }

    @Test
    void sinhDanChungChoTungMaKemLyDo() {
        assertThat(spec.byKind(CodeEvidence.Kind.ERROR_MAPPING))
                .anySatisfy(item -> assertThat(item.statement())
                        .contains("API có thể trả về mã lỗi CommonErrorCode.CALL_EVENT_PROCESS_ONE")
                        .contains("mã 1025")
                        .contains("HTTP INTERNAL_SERVER_ERROR")
                        .contains("ném tại EventService.post()"));
    }

    @Test
    void bangMaLoiNhomTheoDuongDiToiTrongMarkdown() {
        String markdown = MarkdownReportRenderer.render(flow, MermaidRenderer.render(flow),
                FlowSummarizer.extract(flow), spec, null, null, null);

        assertThat(markdown).contains("## Mã lỗi API này có thể trả về");
        assertThat(markdown).contains("**Không** phải toàn bộ bảng mã lỗi của hệ thống");
        assertThat(markdown).contains("### Ném tường minh trong luồng");
        assertThat(markdown).contains("### Dữ liệu vào không thoả ràng buộc");
        assertThat(markdown).contains("### Lỗi ngoài dự kiến - handler bắt tất cả");
        assertThat(markdown).contains("| 1025 | `CALL_EVENT_PROCESS_ONE` | INTERNAL_SERVER_ERROR |");
        // Mã do hệ thống ngoài trả về (như "00" của Way4) không nằm ở đây - phải nói rõ.
        assertThat(markdown).contains("Mã do hệ thống ngoài trả về");
        assertThat(markdown).doesNotContain("CARD_NOT_FOUND");
    }

    @Test
    void htmlCoCotDuongDiToi() {
        String html = HtmlReportRenderer.render(flow, FlowSummarizer.extract(flow), spec, null, null,
                null);

        assertThat(html).contains("<h2>Mã lỗi API này có thể trả về</h2>");
        assertThat(html).contains("<th>Đường đi tới</th>");
        assertThat(html).contains("Ném tường minh trong luồng");
        assertThat(html).doesNotContain("CARD_NOT_FOUND");
    }

    @Test
    void promptDuaCaLyDoDeModelVietDuocKhiNaoTraVeMaNao() {
        String prompt = SpecReportRenderer.promptText(spec);

        assertThat(prompt).contains("=== MÃ LỖI API NÀY CÓ THỂ TRẢ VỀ");
        assertThat(prompt).contains("1025 CALL_EVENT_PROCESS_ONE [HTTP INTERNAL_SERVER_ERROR] "
                + "- ném tại EventService.post()");
        assertThat(prompt).contains("tuyệt đối không suy ra mã mới");
        assertThat(prompt).doesNotContain("CARD_NOT_FOUND");
    }

    @Test
    void khongCoEnumMaLoiThiKhongCoMucNao() {
        CodeSpec orderSpec = FixtureRepo.analyzer()
                .collectSpec(FixtureRepo.REPO_URL, "master", "POST", "/api/orders")
                .spec();

        assertThat(orderSpec.errorCodes()).isEmpty();
        String markdown = MarkdownReportRenderer.render(flow, MermaidRenderer.render(flow),
                FlowSummarizer.extract(flow), orderSpec, null, null, null);
        assertThat(markdown).doesNotContain("## Mã lỗi API này có thể trả về");
    }
}
