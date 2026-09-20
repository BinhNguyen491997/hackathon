package com.example.aihackathon.codeanalysis;

import java.util.List;

import com.example.aihackathon.codeanalysis.model.CodeSpec;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse;

/**
 * Mục "Procedure và package database" trong tài liệu.
 *
 * <p>Tách ra dùng chung cho cả báo cáo sơ đồ luồng và tài liệu đặc tả, ở cả hai định dạng markdown
 * và HTML - cùng lý do như {@link AiSectionRenderer}: bốn định dạng sinh từ một nguồn sự thật thì
 * không được lệch nội dung, mà cách chắc nhất để không lệch là chỉ có một chỗ để sửa.
 *
 * <p>Mục này luôn nói rõ giới hạn: tài liệu chỉ khẳng định endpoint <b>có gọi</b> procedure nào,
 * không khẳng định procedure đó <b>làm gì</b>. Ranh giới đó phải hiện ngay cạnh bảng, không phải
 * nằm lẫn trong danh sách câu hỏi ở cuối tài liệu.
 */
final class StoredProcedureSection {

    private static final String CAVEAT = "Tài liệu này dựng từ source Java nên chỉ đọc được TÊN "
            + "procedure và chỗ gọi, KHÔNG đọc được thân procedure. Mọi quy tắc nghiệp vụ, kiểm tra "
            + "dữ liệu và cập nhật bảng nằm trong PL/SQL đều chưa có ở đây.";

    private StoredProcedureSection() {
    }

    static void appendMarkdown(StringBuilder out, CodeSpec spec) {
        if (spec == null || spec.procedures().isEmpty()) {
            return;
        }
        out.append("\n## Procedure và package database được sử dụng\n");
        out.append("\n> ⚠️ ").append(CAVEAT).append('\n');

        List<String> packages = spec.databasePackages();
        if (!packages.isEmpty()) {
            out.append("\n**Package database endpoint này chạm tới:** ")
                    .append(String.join(", ", packages.stream().map(name -> "`" + name + "`").toList()))
                    .append('\n');
        }

        out.append("\n| Package | Procedure / function | Input | Output | Gọi từ | Cách gọi | Nguồn |\n");
        out.append("|---|---|---|---|---|---|---|\n");
        for (StoredProcedureUse use : spec.procedures()) {
            out.append("| ").append(use.hasPackage() ? "`" + use.packageName() + "`" : "_chưa xác định_")
                    .append(" | `").append(use.routineName())
                    .append("` | ").append(arguments(use.inputs()))
                    .append(" | ").append(arguments(use.outputs()))
                    .append(" | `").append(use.calledIn())
                    .append("` | ").append(MarkdownReportRenderer.escape(use.callStyle().title()))
                    .append(" | `").append(use.sourceFile()).append(':').append(use.line())
                    .append("` |\n");
        }
    }

    /**
     * Danh sách tham số cho một ô bảng.
     *
     * <p>Không có tham số nào đọc được thì ghi rõ "chưa đọc được" chứ KHÔNG để trống: ô trống đọc
     * thành "procedure không có tham số", mà đó là kết luận ta không có quyền đưa ra.
     */
    private static String arguments(List<StoredProcedureUse.Argument> arguments) {
        if (arguments.isEmpty()) {
            return "_chưa đọc được_";
        }
        return String.join(", ", arguments.stream()
                .map(argument -> "`" + MarkdownReportRenderer.escape(argument.label()) + "`")
                .toList());
    }

    static void appendHtml(StringBuilder out, CodeSpec spec) {
        if (spec == null || spec.procedures().isEmpty()) {
            return;
        }
        out.append("<h2>Procedure và package database được sử dụng</h2>\n");
        out.append("<div class=\"callout warn\">⚠️ ").append(esc(CAVEAT)).append("</div>\n");

        List<String> packages = spec.databasePackages();
        if (!packages.isEmpty()) {
            out.append("<p><strong>Package database endpoint này chạm tới:</strong> ");
            for (int i = 0; i < packages.size(); i++) {
                out.append(i == 0 ? "" : ", ").append("<code>").append(esc(packages.get(i)))
                        .append("</code>");
            }
            out.append("</p>\n");
        }

        out.append("<table>\n<tr><th>Package</th><th>Procedure / function</th><th>Input</th>"
                + "<th>Output</th><th>Gọi từ</th><th>Cách gọi</th><th>Nguồn</th><th>Code</th></tr>\n");
        for (StoredProcedureUse use : spec.procedures()) {
            out.append("<tr><td>")
                    .append(use.hasPackage()
                            ? "<code>" + esc(use.packageName()) + "</code>"
                            : "<em>chưa xác định</em>")
                    .append("</td><td><code>").append(esc(use.routineName()))
                    .append("</code></td><td>").append(argumentsHtml(use.inputs()))
                    .append("</td><td>").append(argumentsHtml(use.outputs()))
                    .append("</td><td><code>").append(esc(use.calledIn()))
                    .append("</code></td><td>").append(esc(use.callStyle().title()))
                    .append("</td><td><code>").append(esc(use.sourceFile())).append(':')
                    .append(use.line())
                    .append("</code></td><td><code>").append(esc(use.snippet()))
                    .append("</code></td></tr>\n");
        }
        out.append("</table>\n");
    }

    private static String argumentsHtml(List<StoredProcedureUse.Argument> arguments) {
        if (arguments.isEmpty()) {
            return "<em>chưa đọc được</em>";
        }
        return String.join(", ", arguments.stream()
                .map(argument -> "<code>" + esc(argument.label()) + "</code>")
                .toList());
    }

    /**
     * Khối dành cho prompt của LLM.
     *
     * <p>Đặt tên procedure vào prompt là cần thiết: không có nó, model sẽ mô tả lời gọi DAO như thể
     * đó là điểm cuối của luồng. Nhưng phải kèm lệnh cấm suy diễn nội dung procedure - cái tên
     * {@code PKG_SETTLEMENT.POST_ENTRY} rất dễ dụ model viết ra một đoạn nghiệp vụ nghe hợp lý mà
     * hoàn toàn bịa.
     */
    static void appendPromptText(StringBuilder out, CodeSpec spec) {
        if (spec == null || spec.procedures().isEmpty()) {
            return;
        }
        out.append("\n=== PROCEDURE DATABASE ENDPOINT NÀY GỌI (chỉ biết tên và tham số, KHÔNG biết "
                + "nội dung) ===\n");
        for (StoredProcedureUse use : spec.procedures()) {
            out.append("  ").append(use.signature()).append(" - gọi từ ").append(use.calledIn())
                    .append('\n');
        }
        out.append("LƯU Ý: tuyệt đối không mô tả procedure đó làm gì bên trong. Chỉ được nói hệ "
                + "thống có gọi tới nó, nêu dữ liệu truyền vào/nhận về, và nêu rõ phần logic trong "
                + "procedure chưa được mô tả.\n");
    }

    private static String esc(String value) {
        return HtmlReportRenderer.escapeHtml(value);
    }
}
