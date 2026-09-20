package com.example.aihackathon.codeanalysis;

import java.util.List;

import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.CodeSpec;
import com.example.aihackathon.codeanalysis.model.FlowComparison;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mục "Chưa xác định được" và nhóm "bước chỉ AI tìm ra" nói về cùng một chỗ nhưng trước đây nằm rời
 * nhau: người đọc phải tự đối chiếu nguyên văn lời gọi với nhãn bước của AI mới nhận ra, việc mà
 * không ai làm. Nhóm test này khẳng định gợi ý được lồng đúng vào điểm nó nói về, và không lồng bừa.
 */
class UnresolvedLinkerTests {

    private static ApiFlow flowWithUnresolved;

    private static CodeSpec spec;

    /** Lời gọi qua biến nên parser không suy được lớp đích - đúng dạng ghi vào flow.unresolved(). */
    private static final String UNRESOLVED =
            "client.verify(request)  (không suy được lớp đích: UnsolvedSymbolException)";

    private static final String OTHER_UNRESOLVED =
            "helper.buildPayload(order)  (không suy được lớp đích: UnsolvedSymbolException)";

    @BeforeAll
    static void buildFlow() {
        JavaSourceIndex index = ProcedureFixtureRepo.index();
        var endpoint = EndpointScanner.scan(index).stream()
                .filter(candidate -> candidate.path().equals("/api/settlements/{id}/post"))
                .findFirst()
                .orElseThrow();
        ApiFlow real = new CallFlowBuilder(index, ProcedureFixtureRepo.properties())
                .build(endpoint, ProcedureFixtureRepo.REPO_URL, ProcedureFixtureRepo.BRANCH,
                        ProcedureFixtureRepo.COMMIT_SHA);

        // Repo mẫu không có điểm unresolved nào (đó là điều tốt), nên dựng thẳng ApiFlow để test
        // đúng phần ghép thay vì phải cố tình làm symbol solver gãy - cách đó phụ thuộc phiên bản
        // JavaParser và sẽ hỏng lặng lẽ khi nâng cấp.
        flowWithUnresolved = new ApiFlow(real.endpoint(), real.participants(), real.nodes(),
                List.of(UNRESOLVED, OTHER_UNRESOLVED), real.warnings(), real.repoUrl(),
                real.branch(), real.commitSha(), real.databaseName());
        spec = EvidenceCollector.collect(index, real);
    }

    private static FlowComparison comparisonWith(FlowComparison.Step... onlyByAi) {
        return new FlowComparison(List.of(), List.of(), List.of(onlyByAi), null, true);
    }

    // ------------------------------------------------------------------
    // Ghép
    // ------------------------------------------------------------------

    @Test
    void ghepBuocCuaAiVaoDiemUnresolvedCoCungTenMethod() {
        FlowComparison comparison = comparisonWith(
                new FlowComparison.Step("SettlementService", "post", "FraudCheckClient", "verify",
                        "gọi qua RestTemplate"));

        List<FlowComparison.Step> candidates =
                UnresolvedLinker.candidatesFor(UNRESOLVED, comparison);

        assertThat(candidates).hasSize(1);
        assertThat(UnresolvedLinker.label(candidates.get(0)))
                .isEqualTo("AI đề xuất (CHƯA XÁC NHẬN): SettlementService.post -> "
                        + "FraudCheckClient.verify - gọi qua RestTemplate");
    }

    @Test
    void khongGhepKhiTenMethodKhongHeXuatHienTrongLoiGoi() {
        FlowComparison comparison = comparisonWith(
                new FlowComparison.Step("SettlementService", "post", "AuditWriter", "write", null));

        assertThat(UnresolvedLinker.candidatesFor(UNRESOLVED, comparison)).isEmpty();
    }

    @Test
    void khongGhepKhiTenMethodChiLaMotPhanCuaTenBien() {
        // "verifyResult" chứa "verify" nhưng không phải lời gọi verify() -> không được ghép.
        String unresolved = "verifyResult.getStatus()  (không suy được lớp đích: X)";
        FlowComparison comparison = comparisonWith(
                new FlowComparison.Step("A", "m", "FraudCheckClient", "verify", null));

        assertThat(UnresolvedLinker.candidatesFor(unresolved, comparison)).isEmpty();
    }

    @Test
    void khongGhepTenMethodQuaNganVaDeTrungNhu_of() {
        FlowComparison comparison = comparisonWith(
                new FlowComparison.Step("A", "m", "Mapper", "of", null));

        assertThat(UnresolvedLinker.candidatesFor("list.of(x)  (không suy được lớp đích: X)",
                comparison)).isEmpty();
    }

    @Test
    void khongBatCoDoiChieuThiKhongCoGoiYNao() {
        assertThat(UnresolvedLinker.candidatesFor(UNRESOLVED, FlowComparison.notRun())).isEmpty();
        assertThat(UnresolvedLinker.candidatesFor(UNRESOLVED, null)).isEmpty();
    }

    @Test
    void moiDiemChiNhanUngVienCuaRiengNo() {
        FlowComparison comparison = comparisonWith(
                new FlowComparison.Step("SettlementService", "post", "FraudCheckClient", "verify",
                        null),
                new FlowComparison.Step("SettlementService", "post", "PayloadHelper", "buildPayload",
                        null));

        assertThat(UnresolvedLinker.candidatesFor(UNRESOLVED, comparison))
                .extracting(FlowComparison.Step::calleeMethod).containsExactly("verify");
        assertThat(UnresolvedLinker.candidatesFor(OTHER_UNRESOLVED, comparison))
                .extracting(FlowComparison.Step::calleeMethod).containsExactly("buildPayload");
    }

    // ------------------------------------------------------------------
    // Hiển thị trong tài liệu
    // ------------------------------------------------------------------

    @Test
    void pumlLongGoiYNgayDuoiDiemUnresolved() {
        FlowComparison comparison = comparisonWith(
                new FlowComparison.Step("SettlementService", "post", "FraudCheckClient", "verify",
                        "gọi qua RestTemplate"));

        String puml = PlantUmlRenderer.render(flowWithUnresolved, spec, comparison, true);

        int point = puml.indexOf("client.verify(request)");
        int hint = puml.indexOf("-> AI đề xuất (CHƯA XÁC NHẬN): SettlementService.post -> "
                + "FraudCheckClient.verify");
        int nextPoint = puml.indexOf("helper.buildPayload(order)");

        assertThat(point).isPositive();
        // Gợi ý phải nằm GIỮA điểm nó nói về và điểm kế tiếp, không dồn xuống cuối legend.
        assertThat(hint).isGreaterThan(point);
        assertThat(hint).isLessThan(nextPoint);
    }

    @Test
    void markdownLongGoiYThanhGachDauDongConDuoiDiemUnresolved() {
        FlowComparison comparison = comparisonWith(
                new FlowComparison.Step("SettlementService", "post", "FraudCheckClient", "verify",
                        null));

        String markdown = MarkdownReportRenderer.render(flowWithUnresolved,
                MermaidRenderer.render(flowWithUnresolved), FlowSummarizer.extract(flowWithUnresolved),
                spec, null, null, comparison);

        assertThat(markdown).contains("- client.verify(request)  (không suy được lớp đích: "
                + "UnsolvedSymbolException)\n"
                + "  - AI đề xuất (CHƯA XÁC NHẬN): SettlementService.post -> FraudCheckClient.verify");
    }

    @Test
    void htmlLongGoiYThanhListCon() {
        FlowComparison comparison = comparisonWith(
                new FlowComparison.Step("SettlementService", "post", "FraudCheckClient", "verify",
                        null));

        String html = HtmlReportRenderer.render(flowWithUnresolved,
                FlowSummarizer.extract(flowWithUnresolved), spec, null, null, comparison);

        assertThat(html).contains("Chưa xác định được - cần người kiểm tra (Chưa có AI)");
        assertThat(html).contains("<li class=\"ai-hint\">AI đề xuất (CHƯA XÁC NHẬN): "
                + "SettlementService.post -&gt; FraudCheckClient.verify</li>");
        assertThat(html).contains("li.ai-hint {");
    }

    @Test
    void khongCoDoiChieuThiMucUnresolvedGiuNguyenNhuCu() {
        String puml = PlantUmlRenderer.render(flowWithUnresolved, spec, FlowComparison.notRun(), true);

        assertThat(puml).contains("client.verify(request)");
        assertThat(puml).doesNotContain("AI đề xuất");
    }
}
