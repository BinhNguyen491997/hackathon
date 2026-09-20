package com.example.aihackathon.codeanalysis;

import java.util.List;

import com.example.aihackathon.codeanalysis.model.CodeEvidence;
import com.example.aihackathon.codeanalysis.model.CodeSpec;
import com.example.aihackathon.codeanalysis.model.FlowComparison;

/**
 * Render các phần do AI đóng góp: mô tả bằng văn xuôi và bảng đối chiếu chéo.
 *
 * <p>Tách ra khỏi từng renderer vì cả báo cáo sơ đồ luồng và tài liệu đặc tả đều cần chúng. Trước
 * đây hai cờ {@code useAi}/{@code crossCheckWithAi} chỉ có tác dụng ở một endpoint, endpoint còn
 * lại nhận cờ rồi bỏ qua im lặng - đúng kiểu lỗi làm người dùng mất thời gian mà không có dấu hiệu
 * gì. Gom về một chỗ để không thể lệch nhau lần nữa.
 */
final class AiSectionRenderer {

    private AiSectionRenderer() {
    }

    // ------------------------------------------------------------------
    // Phần mô tả do AI viết
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Bảng dẫn chứng
    // ------------------------------------------------------------------

    /**
     * Câu giải thích cách đọc mã trích dẫn, đặt NGAY TRƯỚC phần mô tả.
     *
     * <p>Phải nằm trước phần mô tả, không phải cạnh bảng dẫn chứng ở cuối tài liệu: người đọc gặp
     * mã {@code [E2]} lần đầu ở phần mô tả, và đó là lúc họ cần biết phải làm gì với nó. Đặt câu
     * giải thích ở cuối thì họ đã đọc xong mô tả mà không biết tra ở đâu.
     *
     * <p>Chỉ chèn khi có dẫn chứng (spec khác null): báo cáo sơ đồ luồng gọi qua
     * {@code CallFlowBuilder} thẳng không đi qua {@code EvidenceCollector} thì không có mã nào để
     * giải thích, chèn câu này vào là thừa và gây khó hiểu.
     */
    static void appendCitationExplanationMarkdown(StringBuilder out, CodeSpec spec, String narrative,
            String aiNote) {
        if (spec == null || ((narrative == null || narrative.isBlank())
                && (aiNote == null || aiNote.isBlank()))) {
            return;
        }
        out.append("\n> 📌 **Cách đọc mã trích dẫn:** mỗi mã dạng `[E2]`, `[E3]` trong phần mô "
                + "tả dưới đây trỏ tới một dòng trong bảng dẫn chứng ở cuối tài liệu này (mục "
                + "\"Dẫn chứng từ code\"), nơi có sẵn đường dẫn `file:line` để mở đúng chỗ trong "
                + "code. Bấm vào mã để nhảy tới đó, hoặc Ctrl+F tìm đúng mã trong bảng.\n");
    }

    static void appendCitationExplanationHtml(StringBuilder out, CodeSpec spec, String narrative,
            String aiNote) {
        if (spec == null || ((narrative == null || narrative.isBlank())
                && (aiNote == null || aiNote.isBlank()))) {
            return;
        }
        out.append("<div class=\"callout info\">📌 <strong>Cách đọc mã trích dẫn:</strong> mỗi "
                + "mã dạng <code>[E2]</code>, <code>[E3]</code> trong phần mô tả dưới đây là "
                + "link nhảy tới đúng hàng trong bảng dẫn chứng ở cuối trang này (mục \"Dẫn chứng "
                + "từ code\"), nơi có sẵn <code>file:line</code> để mở đúng chỗ trong code.</div>\n");
    }

    /**
     * Bảng dẫn chứng đầy đủ, nhóm theo loại. Dùng chung cho cả báo cáo sơ đồ luồng và tài liệu đặc
     * tả - hai nơi này trước đây có hai bản riêng và bị lệch nhau (một có mục này, một không), nên
     * giờ chỉ còn một chỗ để sửa.
     *
     * <p>Hiện bảng này bất kể có bật AI hay không, miễn có dẫn chứng: bảng tất định kèm
     * {@code file:line} có giá trị riêng, không phụ thuộc việc có phần mô tả bằng văn xuôi.
     */
    static void appendEvidenceMarkdown(StringBuilder out, CodeSpec spec) {
        if (spec == null || spec.evidence().isEmpty()) {
            return;
        }
        out.append("\n## Dẫn chứng từ code\n");
        out.append("\nMỗi phát biểu trong tài liệu đều truy được về một dòng code cụ thể. "
                + "Mã `[En]` ở phần mô tả phía trên trỏ tới đúng hàng có cùng mã trong các bảng "
                + "dưới đây.\n");
        for (CodeEvidence.Kind kind : CodeEvidence.Kind.values()) {
            List<CodeEvidence> items = spec.byKind(kind);
            if (items.isEmpty()) {
                continue;
            }
            out.append("\n### ").append(kind.title()).append("\n\n");
            out.append("| Mã | Dữ kiện | Nguồn |\n|---|---|---|\n");
            for (CodeEvidence item : items) {
                // Neo <a id> để mã [En] trong phần mô tả nhảy đúng tới đây. GitLab/GitHub render
                // Markdown vẫn giữ được thẻ <a> trần này.
                out.append("| <a id=\"").append(item.id()).append("\"></a>`").append(item.id())
                        .append("` | ").append(escapeTableCell(item.statement())).append(" | `")
                        .append(item.sourceFile()).append(':').append(item.line())
                        .append("` |\n");
            }
        }
    }

    static void appendEvidenceHtml(StringBuilder out, CodeSpec spec) {
        if (spec == null || spec.evidence().isEmpty()) {
            return;
        }
        out.append("<h2>Dẫn chứng từ code</h2>\n");
        out.append("<p>Mỗi phát biểu đều truy được về một dòng code cụ thể. "
                + "Vị trí do parser cung cấp, không do model ngôn ngữ viết.</p>\n");
        for (CodeEvidence.Kind kind : CodeEvidence.Kind.values()) {
            List<CodeEvidence> items = spec.byKind(kind);
            if (items.isEmpty()) {
                continue;
            }
            out.append("<h3>").append(esc(kind.title())).append("</h3>\n");
            out.append("<table>\n<tr><th>Mã</th><th>Dữ kiện</th><th>Nguồn</th><th>Code</th></tr>\n");
            for (CodeEvidence item : items) {
                out.append("<tr id=\"").append(item.id()).append("\">")
                        .append("<td><code>").append(item.id()).append("</code></td>")
                        .append("<td>").append(esc(item.statement())).append("</td>")
                        .append("<td><code>").append(esc(item.sourceFile())).append(':')
                        .append(item.line()).append("</code></td>")
                        .append("<td><code>").append(esc(item.snippet())).append("</code></td>")
                        .append("</tr>\n");
            }
            out.append("</table>\n");
        }
    }

    private static String escapeTableCell(String value) {
        return value == null ? "" : value.replace("|", "\\|");
    }

    // ------------------------------------------------------------------
    // Phần mô tả do AI viết
    // ------------------------------------------------------------------

    static void appendNarrativeMarkdown(StringBuilder out, String narrative, String aiNote) {
        if (aiNote != null && !aiNote.isBlank()) {
            out.append("\n## Mô tả chức năng (do AI viết)\n");
            out.append("\n> **Nguồn gốc phần này:** ").append(aiNote).append('\n');
            if (narrative != null && !narrative.isBlank()) {
                out.append('\n').append(withMarkdownCitationLinks(narrative.strip())).append('\n');
            }
            return;
        }
        if (narrative != null && !narrative.isBlank()) {
            out.append("\n## Mô tả chức năng\n\n")
                    .append(withMarkdownCitationLinks(narrative.strip())).append('\n');
        }
    }

    /**
     * Biến {@code [E3]} thành link nhảy tới hàng có {@code <a id="E3">} trong bảng dẫn chứng.
     *
     * <p>Cùng cơ chế với {@code withCitationLinks} dùng cho HTML - hai định dạng phải nhất quán,
     * nếu không người đọc quen cách này ở một định dạng lại bị chặn ở định dạng khác.
     */
    private static String withMarkdownCitationLinks(String text) {
        return text.replaceAll("\\[(E\\d+)]", "[[$1]](#$1)");
    }

    static void appendNarrativeHtml(StringBuilder out, String narrative, String aiNote) {
        if (aiNote != null && !aiNote.isBlank()) {
            out.append("<h2>Mô tả chức năng <span class=\"badge-ai\">do AI viết</span></h2>\n");
            out.append("<div class=\"callout ai\"><strong>Nguồn gốc phần này:</strong> ")
                    .append(esc(aiNote)).append("</div>\n");
            if (narrative != null && !narrative.isBlank()) {
                out.append("<div class=\"narrative\">").append(narrativeToHtml(narrative))
                        .append("</div>\n");
            }
            return;
        }
        if (narrative != null && !narrative.isBlank()) {
            out.append("<h2>Mô tả chức năng</h2>\n<div class=\"narrative\">")
                    .append(narrativeToHtml(narrative)).append("</div>\n");
        }
    }

    // ------------------------------------------------------------------
    // Bảng đối chiếu chéo
    // ------------------------------------------------------------------

    /**
     * Nhóm "chỉ AI tìm ra" đặt TRƯỚC nhóm "chỉ parser", vì đó là nhóm hành động được: ứng viên cho
     * những bước phân tích tĩnh bỏ sót.
     */
    static void appendComparisonMarkdown(StringBuilder out, FlowComparison comparison) {
        if (comparison == null || !comparison.aiResponded()) {
            return;
        }
        out.append("\n## Đối chiếu chéo: phân tích tĩnh so với AI\n");
        out.append("\nHai phương pháp làm việc độc lập trên cùng đoạn code: parser dựa vào AST, "
                + "AI dựa vào đọc hiểu. Bảng dưới KHÔNG nói bên nào đúng - nó khoanh vùng chỗ hai "
                + "bên không đồng ý, và đó là chỗ cần người xem lại.\n");

        out.append("\n| | Số bước |\n|---|---|\n");
        out.append("| Hai bên cùng tìm ra | ").append(comparison.agreed().size()).append(" |\n");
        out.append("| Chỉ AI tìm ra | ").append(comparison.onlyByAi().size()).append(" |\n");
        out.append("| Chỉ phân tích tĩnh tìm ra | ").append(comparison.onlyByParser().size())
                .append(" |\n");
        out.append("| Mức đồng thuận | ").append(comparison.agreementPercent()).append("% |\n");

        if (!comparison.onlyByAi().isEmpty()) {
            out.append("\n### Chỉ AI tìm ra - cần kiểm tra thủ công\n");
            out.append("\nCó thể là bước thật mà phân tích tĩnh bỏ sót (thường do thiếu jar "
                    + "dependency nên không resolve được kiểu), hoặc là AI nhìn sai. "
                    + "**Chưa xác nhận thì đừng đưa vào tài liệu chính thức.**\n\n");
            out.append("| Bước | Ghi chú của AI |\n|---|---|\n");
            comparison.onlyByAi().forEach(step -> out.append("| `").append(step.label())
                    .append("` | ").append(escapeTable(step.note())).append(" |\n"));
        }

        if (!comparison.onlyByParser().isEmpty()) {
            out.append("\n### Chỉ phân tích tĩnh tìm ra\n");
            out.append("\nParser khẳng định các bước này (có vị trí code cụ thể) nhưng AI không "
                    + "nhắc tới. Thường là AI bỏ qua vì cho là chi tiết kỹ thuật.\n\n");
            out.append("| Bước |\n|---|\n");
            comparison.onlyByParser().forEach(step -> out.append("| `").append(step.label())
                    .append("` |\n"));
        }

        if (!comparison.agreed().isEmpty()) {
            out.append("\n### Hai bên cùng tìm ra\n\n| Bước |\n|---|\n");
            comparison.agreed().forEach(step -> out.append("| `").append(step.label())
                    .append("` |\n"));
        }

        if (comparison.aiNoteText() != null && !comparison.aiNoteText().isBlank()) {
            out.append("\n### AI tự nhận xét về chỗ chưa chắc\n\n> ")
                    .append(comparison.aiNoteText().replace("\n", "\n> ")).append('\n');
        }
    }

    static void appendComparisonHtml(StringBuilder out, FlowComparison comparison) {
        if (comparison == null || !comparison.aiResponded()) {
            return;
        }
        out.append("<h2>Đối chiếu chéo <span class=\"badge-ai\">phân tích tĩnh vs AI</span></h2>\n");
        out.append("<p>Hai phương pháp làm việc độc lập trên cùng đoạn code: parser dựa vào AST, "
                + "AI dựa vào đọc hiểu. Bảng dưới không nói bên nào đúng - nó khoanh vùng chỗ hai "
                + "bên không đồng ý, và đó là chỗ cần người xem lại.</p>\n");

        out.append("<table>\n");
        row(out, "Hai bên cùng tìm ra", String.valueOf(comparison.agreed().size()));
        row(out, "Chỉ AI tìm ra", String.valueOf(comparison.onlyByAi().size()));
        row(out, "Chỉ phân tích tĩnh", String.valueOf(comparison.onlyByParser().size()));
        row(out, "Mức đồng thuận", comparison.agreementPercent() + "%");
        out.append("</table>\n");

        if (!comparison.onlyByAi().isEmpty()) {
            out.append("<h3>Chỉ AI tìm ra - cần kiểm tra thủ công</h3>\n");
            out.append("<div class=\"callout warn\">Có thể là bước thật mà phân tích tĩnh bỏ sót "
                    + "(thường do thiếu jar dependency nên không resolve được kiểu), hoặc là AI "
                    + "nhìn sai. <strong>Chưa xác nhận thì đừng đưa vào tài liệu chính thức.</strong>"
                    + "</div>\n");
            out.append("<table>\n<tr><th>Bước</th><th>Ghi chú của AI</th></tr>\n");
            comparison.onlyByAi().forEach(step -> out.append("<tr><td><code>")
                    .append(esc(step.label())).append("</code></td><td>")
                    .append(esc(step.note() == null ? "" : step.note())).append("</td></tr>\n"));
            out.append("</table>\n");
        }

        if (!comparison.onlyByParser().isEmpty()) {
            out.append("<h3>Chỉ phân tích tĩnh tìm ra</h3>\n");
            out.append("<p>Parser khẳng định các bước này (có vị trí code cụ thể) nhưng AI không "
                    + "nhắc tới.</p>\n<table>\n<tr><th>Bước</th></tr>\n");
            comparison.onlyByParser().forEach(step -> out.append("<tr><td><code>")
                    .append(esc(step.label())).append("</code></td></tr>\n"));
            out.append("</table>\n");
        }

        if (!comparison.agreed().isEmpty()) {
            out.append("<h3>Hai bên cùng tìm ra</h3>\n<table>\n<tr><th>Bước</th></tr>\n");
            comparison.agreed().forEach(step -> out.append("<tr><td><code>")
                    .append(esc(step.label())).append("</code></td></tr>\n"));
            out.append("</table>\n");
        }

        if (comparison.aiNoteText() != null && !comparison.aiNoteText().isBlank()) {
            out.append("<h3>AI tự nhận xét về chỗ chưa chắc</h3>\n<div class=\"callout ai\">")
                    .append(esc(comparison.aiNoteText())).append("</div>\n");
        }
    }

    /** CSS cho nhãn AI, cần có ở mọi báo cáo có phần AI. */
    static String aiStyles() {
        return """
                  .callout.ai { background:#eef2ff; border-left:4px solid #6d7ee0; }
                  .badge-ai { font-size:11px; font-weight:600; color:#fff; background:#6d7ee0;
                              padding:2px 8px; border-radius:10px; vertical-align:middle; }
                  .narrative p { margin:8px 0; } .narrative li { margin:3px 0; }
                  a.cite { text-decoration:none; font-size:12px; vertical-align:super; }
                """;
    }

    /** Văn bản do model viết: chỉ nhận đoạn và gạch đầu dòng, không nhận HTML thô. */
    private static String narrativeToHtml(String narrative) {
        StringBuilder out = new StringBuilder(narrative.length() + 256);
        boolean inList = false;
        for (String rawLine : narrative.split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            boolean bullet = line.startsWith("- ") || line.startsWith("* ");
            if (bullet && !inList) {
                out.append("<ul>\n");
                inList = true;
            }
            else if (!bullet && inList) {
                out.append("</ul>\n");
                inList = false;
            }
            if (bullet) {
                out.append("<li>").append(withCitationLinks(line.substring(2))).append("</li>\n");
            }
            else {
                out.append("<p>").append(withCitationLinks(line)).append("</p>\n");
            }
        }
        if (inList) {
            out.append("</ul>\n");
        }
        return out.toString();
    }

    /** Biến [E3] thành link nhảy tới hàng tương ứng trong bảng dẫn chứng. */
    private static String withCitationLinks(String text) {
        return esc(text).replaceAll("\\[(E\\d+)]", "<a href=\"#$1\" class=\"cite\">[$1]</a>");
    }

    private static void row(StringBuilder out, String key, String value) {
        out.append("<tr><td class=\"key\">").append(esc(key)).append("</td><td>")
                .append(esc(value)).append("</td></tr>\n");
    }

    private static String escapeTable(String value) {
        return value == null ? "" : value.replace("|", "\\|");
    }

    private static String esc(String value) {
        return HtmlReportRenderer.escapeHtml(value);
    }
}
