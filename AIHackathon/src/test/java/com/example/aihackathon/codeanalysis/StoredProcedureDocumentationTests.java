package com.example.aihackathon.codeanalysis;

import java.util.List;

import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.CodeEvidence;
import com.example.aihackathon.codeanalysis.model.CodeSpec;
import com.example.aihackathon.codeanalysis.model.FlowComparison;
import com.example.aihackathon.codeanalysis.model.SpecQuestion;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse.Argument;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse.Argument.Direction;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse.CallStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Với hệ thống chạy trên Oracle, phần lớn nghiệp vụ nằm trong package PL/SQL chứ không nằm trong
 * code Java. Tài liệu không nêu tên procedure ra thì người đọc sẽ tưởng luồng đã mô tả đủ - đó là
 * cách sai nguy hiểm nhất của tài liệu sinh tự động: nó im lặng.
 *
 * <p>Nhóm test này khẳng định hai việc: procedure/package được nhận ra tất định từ nhiều kiểu gọi
 * khác nhau, và chúng xuất hiện trong CẢ BỐN định dạng tài liệu cùng prompt của LLM.
 */
class StoredProcedureDocumentationTests {

    private static ApiFlow flow;

    private static CodeSpec spec;

    @BeforeAll
    static void analyzeOnce() {
        JavaSourceIndex index = ProcedureFixtureRepo.index();
        var endpoint = EndpointScanner.scan(index).stream()
                .filter(candidate -> candidate.path().equals("/api/settlements/{id}/post"))
                .findFirst()
                .orElseThrow();
        flow = new CallFlowBuilder(index, ProcedureFixtureRepo.properties())
                .build(endpoint, ProcedureFixtureRepo.REPO_URL, ProcedureFixtureRepo.BRANCH,
                        ProcedureFixtureRepo.COMMIT_SHA);
        spec = EvidenceCollector.collect(index, flow);
    }

    // ------------------------------------------------------------------
    // Nhận dạng
    // ------------------------------------------------------------------

    @Test
    void nhanRaDuCacKieuGoiProcedureKhacNhau() {
        assertThat(spec.procedures())
                .extracting(StoredProcedureUse::packageName, StoredProcedureUse::routineName,
                        StoredProcedureUse::callStyle)
                .contains(
                        // @Procedure(procedureName = "PKG_SETTLEMENT.RECALC_BALANCE")
                        tuple("PKG_SETTLEMENT", "RECALC_BALANCE", CallStyle.SPRING_DATA_PROCEDURE),
                        // @Query(value = "{ call PKG_SETTLEMENT.SYNC_STATUS(:id) }", nativeQuery = true)
                        tuple("PKG_SETTLEMENT", "SYNC_STATUS", CallStyle.NATIVE_QUERY),
                        // @Procedure(name = ...) -> tra tiếp @NamedStoredProcedureQuery trên entity
                        tuple("PKG_ARCHIVE", "MOVE_OLD", CallStyle.NAMED_STORED_PROCEDURE),
                        // withCatalogName + withProcedureName
                        tuple("PKG_SETTLEMENT", "POST_ENTRY", CallStyle.SIMPLE_JDBC_CALL),
                        // prepareCall(HẰNG_SỐ) - literal không nằm tại chỗ gọi
                        tuple("PKG_SETTLEMENT", "REVERSE_ENTRY", CallStyle.CALLABLE_STATEMENT),
                        // BEGIN ... END gửi thẳng xuống database
                        tuple("PKG_AUDIT", "LOG_EVENT", CallStyle.PLSQL_BLOCK));
    }

    @Test
    void khongDoanPackageKhiCodeChiGoiTenTran() {
        StoredProcedureUse legacy = spec.procedures().stream()
                .filter(use -> use.routineName().equals("SP_LEGACY_SYNC"))
                .findFirst()
                .orElseThrow();

        assertThat(legacy.packageName()).isNull();
        assertThat(legacy.hasPackage()).isFalse();
        assertThat(legacy.qualifiedName()).isEqualTo("SP_LEGACY_SYNC");
    }

    @Test
    void gomPackageDuocDungLaiThanhMotDanhSachGonKhongTrung() {
        // PKG_SETTLEMENT xuất hiện ở 4 lời gọi khác nhau nhưng chỉ được tính là một package.
        assertThat(spec.databasePackages())
                .containsExactlyInAnyOrder("PKG_SETTLEMENT", "PKG_ARCHIVE", "PKG_AUDIT")
                .doesNotContain("SP_LEGACY_SYNC");
    }

    @Test
    void moiProcedureTruyDuocVeMotDongCodeThat() {
        assertThat(spec.procedures()).isNotEmpty().allSatisfy(use -> {
            assertThat(use.sourceFile()).endsWith(".java");
            assertThat(use.line()).isPositive();
            assertThat(use.calledIn()).endsWith("()");
            assertThat(use.snippet()).isNotBlank();
        });
    }

    // ------------------------------------------------------------------
    // Tham số vào / ra
    // ------------------------------------------------------------------

    @Test
    void docDuocChieuInOutTuDeclareParametersCuaSimpleJdbcCall() {
        StoredProcedureUse use = procedure("POST_ENTRY");

        assertThat(use.arguments())
                .extracting(Argument::name, Argument::direction, Argument::type)
                .containsExactly(
                        tuple("p_id", Direction.IN, "NUMERIC"),
                        tuple("p_result_code", Direction.OUT, "VARCHAR"));
        assertThat(use.signature())
                .isEqualTo("PKG_SETTLEMENT.POST_ENTRY(p_id: NUMERIC) -> p_result_code: VARCHAR");
    }

    @Test
    void docDuocChieuInOutTuStoredProcedureParameterTrenEntity() {
        StoredProcedureUse use = procedure("MOVE_OLD");

        assertThat(use.arguments())
                .extracting(Argument::name, Argument::direction, Argument::type)
                .containsExactly(
                        tuple("p_cutoff_date", Direction.IN, "LocalDate"),
                        tuple("p_moved_rows", Direction.OUT, "Long"));
    }

    @Test
    void docDuocOutParameterDangKyTheoViTriCuaCallableStatement() {
        StoredProcedureUse use = procedure("REVERSE_ENTRY");

        assertThat(use.arguments())
                .extracting(Argument::name, Argument::direction, Argument::type, Argument::hint)
                .containsExactly(
                        // hint "id" lấy từ setLong(1, id) - tên biến Java, tách riêng khỏi vị trí
                        tuple("?1", Direction.IN, "Long", "id"),
                        tuple("?2", Direction.OUT, "NUMERIC", null));
        assertThat(use.outputs()).hasSize(1);
    }

    @Test
    void docDuocThamSoDatTenTuCauSqlVaChuKyJava() {
        // @Query("{ call PKG_SETTLEMENT.SYNC_STATUS(:id) }") -> tên tham số nằm trong câu SQL
        assertThat(procedure("SYNC_STATUS").inputs())
                .extracting(Argument::name).containsExactly("id");

        // @Procedure(procedureName = ...) không nói gì về tham số -> đọc chữ ký method Java
        assertThat(procedure("RECALC_BALANCE").inputs())
                .extracting(Argument::name, Argument::type).containsExactly(tuple("id", "Long"));

        // Khối PL/SQL dùng placeholder vị trí kiểu Oracle
        assertThat(procedure("LOG_EVENT").inputs())
                .extracting(Argument::name).containsExactly(":1");
    }

    @Test
    void khongVoDoanLaProcedureKhongCoThamSoKhiChuaDocDuoc() {
        StoredProcedureUse legacy = procedure("SP_LEGACY_SYNC");

        assertThat(legacy.arguments()).isEmpty();
        // "(?)" chứ không phải "()": chưa đọc được khác hẳn với không có tham số.
        assertThat(legacy.signature()).isEqualTo("SP_LEGACY_SYNC(?)");
    }

    @Test
    void bangTrongTaiLieuGhiRoChuaDocDuocThayViDeTrong() {
        String markdown = MarkdownReportRenderer.render(flow, MermaidRenderer.render(flow),
                FlowSummarizer.extract(flow), spec, null, null, null);

        assertThat(markdown).contains("| Package | Procedure / function | Input | Output |");
        assertThat(markdown).contains("`p_result_code: VARCHAR`");
        assertThat(markdown).contains("_chưa đọc được_");
    }

    @Test
    void khongBaoCaoProcedureKhiRepoKhongCo() {
        CodeSpec orderSpec = FixtureRepo.analyzer()
                .collectSpec(FixtureRepo.REPO_URL, "master", "POST", "/api/orders")
                .spec();

        assertThat(orderSpec.procedures()).isEmpty();
        assertThat(orderSpec.databasePackages()).isEmpty();
        assertThat(SpecReportRenderer.promptText(orderSpec))
                .doesNotContain("PROCEDURE DATABASE ENDPOINT NÀY GỌI");
    }

    // ------------------------------------------------------------------
    // Dẫn chứng và câu hỏi
    // ------------------------------------------------------------------

    @Test
    void sinhDanChungRiengChoTungProcedure() {
        List<CodeEvidence> items = spec.byKind(CodeEvidence.Kind.STORED_PROCEDURE);

        assertThat(items).hasSize(spec.procedures().size());
        assertThat(items).anySatisfy(item -> {
            assertThat(item.statement()).contains("PKG_SETTLEMENT.POST_ENTRY");
            assertThat(item.statement()).contains("Way4");
            assertThat(item.sourceFile()).endsWith("SettlementDao.java");
        });
    }

    @Test
    void hoiDevVeNoiDungProcedureOMucBlocking() {
        List<SpecQuestion> blocking = spec.bySeverity(SpecQuestion.Severity.BLOCKING);

        assertThat(blocking).anySatisfy(question -> {
            assertThat(question.question()).contains("PKG_SETTLEMENT.POST_ENTRY");
            assertThat(question.reason()).contains("KHÔNG đọc được thân procedure");
            assertThat(question.evidenceIds()).isNotEmpty();
        });
    }

    @Test
    void hoiDevVePackageCuaProcedureGoiBangTenTran() {
        assertThat(spec.bySeverity(SpecQuestion.Severity.IMPORTANT)).anySatisfy(question ->
                assertThat(question.question())
                        .isEqualTo("Procedure SP_LEGACY_SYNC nằm trong package/schema nào?"));
    }

    // ------------------------------------------------------------------
    // Bốn định dạng tài liệu + prompt
    // ------------------------------------------------------------------

    @Test
    void baoCaoMarkdownCoBangProcedure() {
        String markdown = MarkdownReportRenderer.render(flow, MermaidRenderer.render(flow),
                FlowSummarizer.extract(flow), spec, null, null, null);

        assertThat(markdown).contains("## Procedure và package database được sử dụng");
        assertThat(markdown).contains(
                "| Package | Procedure / function | Input | Output | Gọi từ | Cách gọi | Nguồn |");
        assertThat(markdown).contains("`PKG_SETTLEMENT`");
        assertThat(markdown).contains("`POST_ENTRY`");
        assertThat(markdown).contains("**Package database endpoint này chạm tới:**");
        assertThat(markdown).contains("KHÔNG đọc được thân procedure");
    }

    @Test
    void baoCaoHtmlCoBangProcedure() {
        String html = HtmlReportRenderer.render(flow, FlowSummarizer.extract(flow), spec, null, null,
                null);

        assertThat(html).contains("<h2>Procedure và package database được sử dụng</h2>");
        assertThat(html).contains("PKG_AUDIT");
        assertThat(html).contains("<em>chưa xác định</em>");
    }

    @Test
    void taiLieuDacTaCoBangProcedureVaDemTrongBangThongTinNguon() {
        String markdown = SpecReportRenderer.markdown(flow, spec, MermaidRenderer.render(flow), null,
                null, null);
        String html = SpecReportRenderer.html(flow, spec, null, null, null);

        assertThat(markdown).contains("| **Procedure database** | 7 lời gọi trong 3 package |");
        assertThat(markdown).contains("## Procedure và package database được sử dụng");
        assertThat(html).contains("Procedure database");
        assertThat(html).contains("<h2>Procedure và package database được sử dụng</h2>");
    }

    @Test
    void promptChoModelNeuTenProcedureVaCamSuyDienNoiDung() {
        String prompt = SpecReportRenderer.promptText(spec);

        assertThat(prompt).contains("=== PROCEDURE DATABASE ENDPOINT NÀY GỌI");
        assertThat(prompt).contains("PKG_SETTLEMENT.POST_ENTRY(p_id: NUMERIC) -> p_result_code: "
                + "VARCHAR - gọi từ SettlementDao.postEntry()");
        assertThat(prompt).contains("tuyệt đối không mô tả procedure đó làm gì bên trong");
    }

    @Test
    void legendCuaPumlNeuTenProcedure() {
        String puml = PlantUmlRenderer.render(flow, spec, true);

        assertThat(puml).contains("== Procedure / package database được gọi ==");
        assertThat(puml).contains("PKG_SETTLEMENT.POST_ENTRY");
        assertThat(puml).contains("Thân procedure KHÔNG đọc được từ source Java");
    }

    @Test
    void muiTenToiDatabaseMangChuKyProcedureKemThamSoVaoRa() {
        String puml = PlantUmlRenderer.render(flow, spec, true);

        // Nhãn mũi tên gọn; chi tiết tham số nằm ở bảng trong note ngay bên dưới.
        assertThat(puml).contains("gọi procedure POST_ENTRY");
        assertThat(puml).contains("<b>PKG_SETTLEMENT.POST_ENTRY</b>");
        assertThat(puml).contains("| -- | vào | p_id | NUMERIC |");
        assertThat(puml).contains("| -- | ra | p_result_code | VARCHAR |");
        assertThat(puml).contains("trả về p_result_code: VARCHAR");
    }

    // ------------------------------------------------------------------
    // Bước chỉ AI tìm ra: nét đứt, nhãn chưa xác nhận
    // ------------------------------------------------------------------

    @Test
    void veBuocChiAiTimRaBangMuiTenNetDutTrongGroupRieng() {
        FlowComparison comparison = new FlowComparison(
                List.of(),
                List.of(),
                List.of(new FlowComparison.Step("SettlementService", "post", "FraudCheckClient",
                        "verify", "gọi qua RestTemplate, parser không resolve được")),
                null, true);

        String puml = PlantUmlRenderer.render(flow, spec, comparison, true);

        assertThat(puml).contains("group #FFF9E6 Bước chỉ AI tìm ra - CHƯA XÁC NHẬN");
        // "-->" là nét đứt của PlantUML; nhãn nhắc lại mức độ tin cậy ngay trên mũi tên.
        assertThat(puml).contains(" --> AI_FraudCheckClient: (?) verify() [chưa xác nhận]");
        assertThat(puml).contains("participant \"FraudCheckClient\\n(chỉ AI đề xuất)\" "
                + "as AI_FraudCheckClient <<chưa xác nhận>> #FFF9E6");
        assertThat(puml).contains("== Bước chỉ AI tìm ra (nét đứt, nhóm cuối) ==");
        assertThat(puml).contains("Thứ tự trong nhóm KHÔNG phải thứ tự thực thi");
    }

    @Test
    void khongBatCoDoiChieuThiPumlKhongCoGiCuaAi() {
        String puml = PlantUmlRenderer.render(flow, spec, FlowComparison.notRun(), true);

        assertThat(puml).doesNotContain("chưa xác nhận");
        assertThat(puml).doesNotContain("AI_");
        // Giống hệt bản không truyền comparison: hai cờ AI không được làm đổi file .puml.
        assertThat(puml).isEqualTo(PlantUmlRenderer.render(flow, spec, true));
    }

    @Test
    void buocCuaAiDungLaiCotSanCoKhiLopDaLaParticipant() {
        FlowComparison comparison = new FlowComparison(List.of(), List.of(),
                List.of(new FlowComparison.Step("SettlementService", "post", "SettlementDao",
                        "rollback", null)),
                null, true);

        String puml = PlantUmlRenderer.render(flow, spec, comparison, true);

        // SettlementDao đã là participant thật -> không được tạo thêm cột AI_SettlementDao.
        assertThat(puml).doesNotContain("AI_SettlementDao");
        assertThat(puml).contains("(?) rollback() [chưa xác nhận]");
    }

    @Test
    void banDacTaSinhQuaAnalyzerCungCoProcedure() {
        ApiFlowAnalyzer.SpecResult result = ProcedureFixtureRepo.analyzer().collectSpec(
                ProcedureFixtureRepo.REPO_URL, "master", "POST", "/api/settlements/{id}/post");

        assertThat(result.spec().procedures()).isNotEmpty();
        assertThat(result.markdown().content()).contains("PKG_SETTLEMENT");
        assertThat(result.html().content()).contains("PKG_SETTLEMENT");
    }

    @Test
    void baoCaoSoDoLuongSinhQuaAnalyzerCungCoProcedure() {
        ApiFlowAnalyzer.AnalysisResult result = ProcedureFixtureRepo.analyzer().analyzeByPath(
                ProcedureFixtureRepo.REPO_URL, "master", "POST", "/api/settlements/{id}/post");

        assertThat(result.markdown().content()).contains("Procedure và package database được sử dụng");
        assertThat(result.html().content()).contains("Procedure và package database được sử dụng");
        assertThat(result.puml().content()).contains("PKG_SETTLEMENT.POST_ENTRY");
    }

    /**
     * Đường dẫn trả ra API phải cùng một kiểu với {@code sourceFile} (đã được chuẩn hoá về dấu
     * {@code /}), và phải dán được vào Explorer. Trên Windows, mỗi {@code \} trong JSON bị escape
     * thành {@code \\} nên đường dẫn thô không dùng được.
     */
    @Test
    void duongDanFileTraVeLuonDungDauGachCheoXuoiKeCaTrenWindows() {
        ApiFlowAnalyzer.AnalysisResult result = ProcedureFixtureRepo.analyzer().analyzeByPath(
                ProcedureFixtureRepo.REPO_URL, "master", "POST", "/api/settlements/{id}/post");

        assertThat(result.puml().filePath()).isNotNull().doesNotContain("\\").contains("/");
        assertThat(result.html().filePath()).doesNotContain("\\");
        assertThat(result.markdown().filePath()).doesNotContain("\\");
        assertThat(result.mermaid().filePath()).doesNotContain("\\");
        assertThat(result.puml().filePath()).endsWith(".puml");
    }

    private static StoredProcedureUse procedure(String routineName) {
        return spec.procedures().stream()
                .filter(use -> use.routineName().equals(routineName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("không tìm thấy procedure " + routineName));
    }
}
