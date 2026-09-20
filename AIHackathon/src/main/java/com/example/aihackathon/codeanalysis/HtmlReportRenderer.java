package com.example.aihackathon.codeanalysis;

import java.util.List;

import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.CodeSpec;
import com.example.aihackathon.codeanalysis.model.FlowComparison;

/**
 * Sinh một file HTML duy nhất mà BA chỉ cần double-click là xem được.
 *
 * <p>Không cần cài gì: mọi máy đều có browser, và file không phụ thuộc javascript nào - toàn bộ
 * nội dung là HTML thuần nên mở offline vẫn đầy đủ.
 *
 * <p><b>Cố tình KHÔNG có sơ đồ tuần tự.</b> Báo cáo HTML là nơi đọc chữ: bảng thông tin, dữ liệu
 * bị tác động, điều kiện rẽ nhánh, nhánh lỗi. Sơ đồ nằm ở file .puml - định dạng đó vẽ được chi
 * tiết hơn nhiều (tầng database tường minh, mã HTTP khi lỗi, kiểu trả về) mà không bị giới hạn bởi
 * việc phải render trong browser.
 *
 * <p>Mọi nội dung lấy từ repo đều đi qua {@link #escapeHtml}: tên method, điều kiện if, câu
 * query đều là dữ liệu không đáng tin (repo có thể chứa chuỗi trông như thẻ HTML), và file này
 * còn được serve qua HTTP nên không escape là mở đường cho XSS.
 */
final class HtmlReportRenderer {

    private HtmlReportRenderer() {
    }

    static String render(ApiFlow flow, MarkdownReportRenderer.FlowFacts facts) {
        return render(flow, facts, null, null, null, null);
    }

    static String render(ApiFlow flow, MarkdownReportRenderer.FlowFacts facts, CodeSpec spec,
            String narrative, String aiNote, FlowComparison comparison) {
        StringBuilder out = new StringBuilder(8192);
        String title = flow.endpoint().label();

        out.append("<!DOCTYPE html>\n<html lang=\"vi\">\n<head>\n");
        out.append("<meta charset=\"utf-8\">\n");
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        out.append("<title>").append(escapeHtml(title)).append("</title>\n");
        appendStyle(out);
        out.append("</head>\n<body>\n");

        out.append("<h1>").append(escapeHtml(title)).append("</h1>\n");
        if (flow.endpoint().summary() != null && !flow.endpoint().summary().isBlank()) {
            out.append("<p class=\"summary\">").append(escapeHtml(flow.endpoint().summary()))
                    .append("</p>\n");
        }

        appendProvenance(out, flow);
        appendCaveats(out, flow, comparison);
        AiSectionRenderer.appendCitationExplanationHtml(out, spec, narrative, aiNote);
        AiSectionRenderer.appendNarrativeHtml(out, narrative, aiNote);
        appendParticipants(out, flow);

        appendList(out, "Dữ liệu bị tác động", facts.dataAccess());
        StoredProcedureSection.appendHtml(out, spec);
        appendList(out, "Gọi ra ngoài hệ thống", facts.externals());
        appendList(out, "Điều kiện rẽ nhánh", facts.conditions());
        appendList(out, "Nhánh lỗi", facts.errors());
        ErrorCodeSection.appendHtml(out, spec);

        appendDetails(out, "Trình tự chi tiết", facts.outline(), true);

        AiSectionRenderer.appendEvidenceHtml(out, spec);
        AiSectionRenderer.appendComparisonHtml(out, comparison);

        out.append("<footer>")
                .append(escapeHtml(MarkdownReportRenderer.provenanceFooter(narrative, aiNote,
                        comparison)))
                .append("<br>Sơ đồ tuần tự nằm ở file .puml đi kèm - mở bằng plugin PlantUML."
                        + "</footer>\n");

        out.append("</body>\n</html>\n");
        return out.toString();
    }

    private static void appendStyle(StringBuilder out) {
        out.append("""
                <style>
                  :root { --line: #d8dee4; --muted: #5b6672; --warn-bg: #fff8e1; --warn-line: #e6c65c;
                          --err-bg: #fdecea; --err-line: #e2a09a; }
                  body { font-family: -apple-system, "Segoe UI", Roboto, Arial, sans-serif;
                         line-height: 1.6; color: #1f2328; max-width: 1100px; margin: 0 auto;
                         padding: 24px 20px 64px; }
                  h1 { font-size: 26px; margin: 0 0 4px; }
                  h2 { font-size: 18px; margin: 32px 0 10px; padding-bottom: 6px;
                       border-bottom: 1px solid var(--line); }
                  p.summary { font-size: 16px; color: var(--muted); margin: 0 0 20px; }
                  table { border-collapse: collapse; width: 100%; font-size: 14px; }
                  th, td { border: 1px solid var(--line); padding: 7px 10px; text-align: left;
                           vertical-align: top; }
                  th { background: #f4f6f8; font-weight: 600; }
                  td.key { width: 150px; font-weight: 600; background: #fafbfc; }
                  code, pre { font-family: "Cascadia Code", Consolas, "Courier New", monospace; }
                  code { background: #f2f4f6; padding: 1px 5px; border-radius: 3px; font-size: 13px; }
                  pre { background: #f6f8fa; border: 1px solid var(--line); border-radius: 6px;
                        padding: 12px; overflow-x: auto; font-size: 13px; line-height: 1.45; }
                  ul { padding-left: 22px; } li { margin: 3px 0; font-size: 14px; }
                  li.ai-hint { color: #8a6d1f; font-style: italic; font-size: 13px; }
                  .callout { border-radius: 6px; padding: 12px 16px; margin: 16px 0; font-size: 14px; }
                  .callout.warn { background: var(--warn-bg); border-left: 4px solid var(--warn-line); }
                  .callout.err  { background: var(--err-bg);  border-left: 4px solid var(--err-line); }
                  .callout.ok   { background: #eaf6ec; border-left: 4px solid #8fc79a; }
                  .callout h3 { margin: 0 0 6px; font-size: 14px; }
                  #diagram { border: 1px solid var(--line); border-radius: 6px; padding: 16px;
                             overflow-x: auto; background: #fff; }
                  #diagram-fallback { display: none; }
                  details { margin: 12px 0; } summary { cursor: pointer; font-weight: 600;
                            padding: 6px 0; font-size: 14px; }
                  footer { margin-top: 48px; padding-top: 12px; border-top: 1px solid var(--line);
                           color: var(--muted); font-size: 13px; }
                  .tag { display: inline-block; padding: 1px 7px; border-radius: 10px;
                         background: #eef1f4; font-size: 12px; color: var(--muted); }
                """);
        out.append(AiSectionRenderer.aiStyles());
        out.append("</style>\n");
    }

    private static void appendProvenance(StringBuilder out, ApiFlow flow) {
        out.append("<h2>Thông tin nguồn</h2>\n<table>\n");
        keyValue(out, "Endpoint", "<code>" + escapeHtml(flow.endpoint().label()) + "</code>");
        keyValue(out, "Repository", escapeHtml(flow.repoUrl()));
        keyValue(out, "Branch", escapeHtml(flow.branch()));
        keyValue(out, "Commit", "<code>" + escapeHtml(
                flow.commitSha() == null ? "n/a" : flow.commitSha()) + "</code>");
        keyValue(out, "Xử lý tại", "<code>" + escapeHtml(flow.endpoint().controllerSimpleName()
                + "." + flow.endpoint().methodName() + "()") + "</code>");
        keyValue(out, "File nguồn", "<code>" + escapeHtml(flow.endpoint().sourceFile()
                + ":" + flow.endpoint().line()) + "</code>");
        out.append("</table>\n");
    }

    /** Đặt trước sơ đồ có chủ ý: người đọc phải biết giới hạn trước khi tin vào hình vẽ. */
    private static void appendCaveats(StringBuilder out, ApiFlow flow, FlowComparison comparison) {
        if (flow.unresolved().isEmpty() && flow.warnings().isEmpty()) {
            out.append("<div class=\"callout ok\">Phân tích tĩnh phủ hết luồng này, "
                    + "không có điểm mờ nào.</div>\n");
            return;
        }
        if (!flow.unresolved().isEmpty()) {
            out.append("<div class=\"callout err\">\n<h3>Chưa xác định được - cần người kiểm tra (Chưa có AI)</h3>\n");
            out.append("<ul>\n");
            for (String item : flow.unresolved()) {
                out.append("<li>").append(escapeHtml(item));
                // Gợi ý của AI lồng vào đúng điểm nó nói về. Cùng nguyên nhân gốc: parser không
                // resolve được vì thiếu jar, còn LLM đọc source thì vẫn đoán ra lớp nào.
                List<FlowComparison.Step> candidates =
                        UnresolvedLinker.candidatesFor(item, comparison);
                if (!candidates.isEmpty()) {
                    out.append("\n<ul>\n");
                    candidates.forEach(step -> out.append("<li class=\"ai-hint\">")
                            .append(escapeHtml(UnresolvedLinker.label(step))).append("</li>\n"));
                    out.append("</ul>\n");
                }
                out.append("</li>\n");
            }
            out.append("</ul>\n");
            out.append("</div>\n");
        }
        if (!flow.warnings().isEmpty()) {
            out.append("<div class=\"callout warn\">\n<h3>Giới hạn của lần phân tích này</h3>\n");
            appendUl(out, flow.warnings());
            out.append("</div>\n");
        }
    }

    private static void appendParticipants(StringBuilder out, ApiFlow flow) {
        out.append("<h2>Các lớp tham gia</h2>\n<table>\n<tr><th>Lớp</th><th>Vai</th>"
                + "<th>Ghi chú</th></tr>\n");
        for (ApiFlow.Participant participant : flow.participants()) {
            out.append("<tr><td><code>").append(escapeHtml(participant.displayName()))
                    .append("</code></td><td><span class=\"tag\">")
                    .append(escapeHtml(participant.kind().stereotype())).append("</span></td><td>")
                    .append(participant.note() == null ? "" : escapeHtml(participant.note()))
                    .append("</td></tr>\n");
        }
        out.append("</table>\n");
    }

    private static void appendList(StringBuilder out, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        out.append("<h2>").append(escapeHtml(title)).append("</h2>\n");
        appendUl(out, items);
    }

    private static void appendUl(StringBuilder out, List<String> items) {
        out.append("<ul>\n");
        items.forEach(item -> out.append("<li>").append(escapeHtml(item)).append("</li>\n"));
        out.append("</ul>\n");
    }

    private static void appendDetails(StringBuilder out, String title, String content, boolean open) {
        out.append("<details").append(open ? " open" : "").append(">\n<summary>")
                .append(escapeHtml(title)).append("</summary>\n<pre>")
                .append(escapeHtml(content)).append("</pre>\n</details>\n");
    }

    private static void keyValue(StringBuilder out, String key, String valueHtml) {
        out.append("<tr><td class=\"key\">").append(escapeHtml(key)).append("</td><td>")
                .append(valueHtml).append("</td></tr>\n");
    }

    /**
     * Escape HTML cho nội dung lấy từ repo được phân tích.
     *
     * <p>Không phải chuyện thẩm mỹ: source code hoàn toàn có thể chứa chuỗi trông như thẻ HTML
     * (ví dụ một literal {@code "<script>"} trong test data), và file HTML này còn được serve
     * qua endpoint HTTP. Thiếu escape ở đây là lỗ XSS thật.
     */
    static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static String escapeHtmlAttribute(String value) {
        return escapeHtml(value).replace("`", "&#96;");
    }
}
