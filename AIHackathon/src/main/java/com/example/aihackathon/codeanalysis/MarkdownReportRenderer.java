package com.example.aihackathon.codeanalysis;

import java.util.ArrayList;
import java.util.List;

import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.CodeSpec;
import com.example.aihackathon.codeanalysis.model.FlowComparison;

/**
 * Sinh báo cáo Markdown có nhúng sơ đồ Mermaid.
 *
 * <p>Dán nội dung này vào GitLab wiki, mô tả MR hoặc issue là BA thấy sơ đồ ngay - GitLab render
 * Mermaid sẵn trong markdown. Không cài gì, và tài liệu nằm luôn trong hạ tầng công ty.
 */
final class MarkdownReportRenderer {

    private MarkdownReportRenderer() {
    }

    static String render(ApiFlow flow, String mermaid, FlowFacts facts) {
        return render(flow, mermaid, facts, null, null, null, null);
    }

    static String render(ApiFlow flow, String mermaid, FlowFacts facts, CodeSpec spec,
            String narrative, String aiNote, FlowComparison comparison) {
        StringBuilder out = new StringBuilder(4096);

        out.append("# ").append(escape(flow.endpoint().label())).append('\n');
        if (flow.endpoint().summary() != null && !flow.endpoint().summary().isBlank()) {
            out.append('\n').append("> ").append(escape(flow.endpoint().summary())).append('\n');
        }

        out.append("\n## Thông tin nguồn\n\n");
        out.append("| | |\n|---|---|\n");
        row(out, "Endpoint", "`" + flow.endpoint().label() + "`");
        row(out, "Repository", flow.repoUrl());
        row(out, "Branch", flow.branch());
        row(out, "Commit", flow.commitSha() == null ? "n/a" : "`" + flow.commitSha() + "`");
        row(out, "Xử lý tại", "`" + flow.endpoint().controllerSimpleName() + "."
                + flow.endpoint().methodName() + "()`");
        row(out, "File nguồn", "`" + flow.endpoint().sourceFile() + ":" + flow.endpoint().line() + "`");

        // Cảnh báo đặt TRƯỚC sơ đồ: người đọc phải biết diagram còn lỗ hổng trước khi tin vào nó.
        if (!flow.unresolved().isEmpty() || !flow.warnings().isEmpty()) {
            out.append("\n## Cần lưu ý trước khi dùng tài liệu này\n");
            if (!flow.unresolved().isEmpty()) {
                out.append("\n**Phân tích tĩnh chưa xác định được các điểm sau, cần người kiểm tra:**\n\n");
                for (String item : flow.unresolved()) {
                    out.append("- ").append(escape(item)).append('\n');
                    // Gợi ý của AI nằm thụt vào ngay dưới điểm nó nói về, không phải ở mục riêng
                    // cuối tài liệu: đây là chỗ người đọc đang thắc mắc "vậy nó gọi vào đâu".
                    UnresolvedLinker.candidatesFor(item, comparison).forEach(step ->
                            out.append("  - ").append(escape(UnresolvedLinker.label(step)))
                                    .append('\n'));
                }
            }
            if (!flow.warnings().isEmpty()) {
                out.append("\n**Giới hạn của lần phân tích này:**\n\n");
                flow.warnings().forEach(item -> out.append("- ").append(escape(item)).append('\n'));
            }
        }
        else {
            out.append("\n> Phân tích tĩnh phủ hết luồng này, không có điểm mờ.\n");
        }

        out.append("\n## Sơ đồ tuần tự\n\n");
        out.append("```mermaid\n").append(mermaid).append("```\n");

        AiSectionRenderer.appendCitationExplanationMarkdown(out, spec, narrative, aiNote);
        AiSectionRenderer.appendNarrativeMarkdown(out, narrative, aiNote);

        out.append("\n## Các lớp tham gia\n\n");
        out.append("| Lớp | Vai | Ghi chú |\n|---|---|---|\n");
        for (ApiFlow.Participant participant : flow.participants()) {
            out.append("| `").append(participant.displayName()).append("` | ")
                    .append(participant.kind().stereotype()).append(" | ")
                    .append(participant.note() == null ? "" : escape(participant.note()))
                    .append(" |\n");
        }

        section(out, "Dữ liệu bị tác động", facts.dataAccess());
        StoredProcedureSection.appendMarkdown(out, spec);
        section(out, "Gọi ra ngoài hệ thống", facts.externals());
        section(out, "Điều kiện rẽ nhánh", facts.conditions());
        section(out, "Nhánh lỗi", facts.errors());
        ErrorCodeSection.appendMarkdown(out, spec);

        out.append("\n## Trình tự chi tiết\n\n");
        out.append("```\n").append(facts.outline()).append("```\n");

        AiSectionRenderer.appendEvidenceMarkdown(out, spec);
        AiSectionRenderer.appendComparisonMarkdown(out, comparison);

        out.append("\n---\n");
        out.append('*').append(provenanceFooter(narrative, aiNote, comparison)).append("*\n");
        return out.toString();
    }

    /**
     * Câu cuối tài liệu nói đúng nguồn gốc của nội dung bên trên.
     *
     * <p>Trước đây câu này ghi cứng "Không dùng model ngôn ngữ, nên cùng một commit luôn cho ra cùng
     * kết quả" - đúng với bản không bật AI, nhưng <b>sai hẳn</b> với bản có mục "Mô tả chức năng (do
     * AI viết)" ngay phía trên. Người chỉ đọc footer sẽ kết luận sai về toàn bộ tài liệu, và footer
     * là chỗ người ta hay đọc để biết có tin được hay không.
     */
    static String provenanceFooter(String narrative, String aiNote, FlowComparison comparison) {
        boolean hasNarrative = (narrative != null && !narrative.isBlank())
                || (aiNote != null && !aiNote.isBlank());
        boolean hasComparison = comparison != null && comparison.aiResponded();

        if (!hasNarrative && !hasComparison) {
            return "Sinh tự động bằng phân tích tĩnh source Java (JavaParser). Không dùng model "
                    + "ngôn ngữ, nên cùng một commit luôn cho ra cùng kết quả.";
        }
        List<String> aiParts = new ArrayList<>();
        if (hasNarrative) {
            aiParts.add("phần \"Mô tả chức năng\"");
        }
        if (hasComparison) {
            aiParts.add("phần \"Đối chiếu chéo\"");
        }
        return "Sơ đồ, bảng dẫn chứng và mọi vị trí file:line do phân tích tĩnh source Java "
                + "(JavaParser) sinh ra - tất định, cùng một commit luôn cho ra cùng kết quả. "
                + "Riêng " + String.join(" và ", aiParts) + " có model ngôn ngữ tham gia, nên "
                + "KHÔNG tái lập được nguyên văn giữa hai lần chạy.";
    }

    private static void section(StringBuilder out, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        out.append("\n## ").append(title).append("\n\n");
        items.forEach(item -> out.append("- ").append(escape(item)).append('\n'));
    }

    private static void row(StringBuilder out, String key, String value) {
        out.append("| **").append(key).append("** | ").append(escape(value)).append(" |\n");
    }

    /**
     * Chỉ escape ký tự phá cấu trúc Markdown ở mức bảng và nhấn mạnh.
     *
     * <p>Không escape toàn bộ: nội dung ở đây là code, escape quá tay sẽ làm nó khó đọc hơn là
     * dễ đọc. Nguy hiểm thật sự chỉ có dấu {@code |} (vỡ bảng) và xuống dòng (vỡ hàng).
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|")
                .replace("\r", " ")
                .replace("\n", " ")
                .trim();
    }

    /** Dữ kiện đã rút ra từ cây luồng, dùng chung cho cả markdown và HTML. */
    record FlowFacts(
            List<String> dataAccess,
            List<String> externals,
            List<String> conditions,
            List<String> errors,
            String outline) {

        FlowFacts {
            dataAccess = List.copyOf(dataAccess);
            externals = List.copyOf(externals);
            conditions = List.copyOf(conditions);
            errors = List.copyOf(errors);
        }
    }
}
