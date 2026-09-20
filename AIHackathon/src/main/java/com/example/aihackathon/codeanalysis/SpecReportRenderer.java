package com.example.aihackathon.codeanalysis;

import java.util.List;

import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.CodeEvidence;
import com.example.aihackathon.codeanalysis.model.CodeSpec;
import com.example.aihackathon.codeanalysis.model.FlowComparison;
import com.example.aihackathon.codeanalysis.model.SpecQuestion;

/**
 * Sinh tài liệu đặc tả chức năng hiện trạng: dữ kiện đọc được từ code, và những gì cần hỏi dev.
 *
 * <p>Cố tình gọi là "hiện trạng" chứ không phải URD. Từ code chỉ suy ra được hệ thống <em>đang
 * làm</em> gì; nói người dùng <em>cần</em> gì là suy đoán về ý định. Tài liệu này tách rõ hai phần
 * đó để BA không vô tình mang suy đoán đi làm yêu cầu.
 *
 * <p>Phần "cần xác nhận với dev" đặt ngay đầu tài liệu, trước cả phần mô tả. Nếu để cuối, nó sẽ
 * bị đọc như một phụ lục cho đủ thủ tục.
 */
final class SpecReportRenderer {

    private SpecReportRenderer() {
    }

    /**
     * Bản gọn để đưa cho model đọc trước khi viết mô tả.
     *
     * <p>Cố tình không đưa {@code file:line} vào đây. Model chỉ cần biết mã {@code [En]} để trích
     * dẫn; ánh xạ mã sang vị trí do phía tất định giữ. Nếu model nhìn thấy số dòng, nó sẽ có xu
     * hướng tự gõ lại vào văn bản - và gõ lại là lúc số dòng bắt đầu sai.
     */
    static String promptText(CodeSpec spec) {
        StringBuilder out = new StringBuilder(2048);
        out.append("ENDPOINT: ").append(spec.endpoint().label()).append('\n');
        if (spec.endpoint().summary() != null && !spec.endpoint().summary().isBlank()) {
            out.append("Mô tả trong code: ").append(spec.endpoint().summary()).append('\n');
        }

        out.append("\n=== DỮ KIỆN ĐỌC ĐƯỢC TỪ CODE (chỉ được dùng những mục này) ===\n");
        for (CodeEvidence.Kind kind : CodeEvidence.Kind.values()) {
            List<CodeEvidence> items = spec.byKind(kind);
            if (items.isEmpty()) {
                continue;
            }
            out.append("\n[").append(kind.title()).append("]\n");
            items.forEach(item -> out.append("  ").append(item.id()).append(": ")
                    .append(item.statement()).append('\n'));
        }

        if (spec.questions().isEmpty()) {
            out.append("\n=== KHÔNG CÓ ĐIỂM NÀO CẦN XÁC NHẬN ===\n");
        }
        else {
            out.append("\n=== ĐIỂM CẦN XÁC NHẬN VỚI DEV (đã sinh sẵn, PHẢI nhắc lại trong câu trả lời) ===\n");
            spec.questions().forEach(question -> out.append("  [").append(question.severity())
                    .append("] ").append(question.question()).append('\n'));
        }
        StoredProcedureSection.appendPromptText(out, spec);
        ErrorCodeSection.appendPromptText(out, spec);
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Markdown
    // ------------------------------------------------------------------

    static String markdown(ApiFlow flow, CodeSpec spec, String mermaid, String narrative,
            String aiNote, FlowComparison comparison) {
        StringBuilder out = new StringBuilder(8192);

        out.append("# Đặc tả chức năng hiện trạng: ").append(flow.endpoint().label()).append('\n');
        if (flow.endpoint().summary() != null && !flow.endpoint().summary().isBlank()) {
            out.append("\n> ").append(flow.endpoint().summary()).append('\n');
        }
        out.append("\n> **Tài liệu này mô tả hệ thống ĐANG làm gì, không phải người dùng CẦN gì.** "
                + "Nội dung được rút từ source code tại một commit cụ thể; phần suy luận về ý định "
                + "nghiệp vụ được tách riêng ở mục \"Cần xác nhận với developer\".\n");

        out.append("\n| | |\n|---|---|\n");
        out.append("| **Repository** | ").append(flow.repoUrl()).append(" |\n");
        out.append("| **Branch** | ").append(flow.branch()).append(" |\n");
        out.append("| **Commit** | `").append(flow.commitSha()).append("` |\n");
        out.append("| **Điểm vào** | `").append(flow.endpoint().sourceFile()).append(':')
                .append(flow.endpoint().line()).append("` |\n");
        out.append("| **Số dẫn chứng** | ").append(spec.evidence().size()).append(" |\n");
        out.append("| **Điểm cần xác nhận** | ").append(spec.questions().size()).append(" |\n");
        if (!spec.procedures().isEmpty()) {
            out.append("| **Procedure database** | ").append(spec.procedures().size())
                    .append(" lời gọi");
            if (!spec.databasePackages().isEmpty()) {
                out.append(" trong ").append(spec.databasePackages().size()).append(" package");
            }
            out.append(" |\n");
        }

        appendQuestionsMarkdown(out, spec);

        AiSectionRenderer.appendCitationExplanationMarkdown(out, spec, narrative, aiNote);
        AiSectionRenderer.appendNarrativeMarkdown(out, narrative, aiNote);

        StoredProcedureSection.appendMarkdown(out, spec);
        ErrorCodeSection.appendMarkdown(out, spec);

        AiSectionRenderer.appendEvidenceMarkdown(out, spec);

        out.append("\n## Sơ đồ luồng\n\n```mermaid\n").append(mermaid).append("```\n");

        AiSectionRenderer.appendComparisonMarkdown(out, comparison);

        out.append("\n---\n");
        out.append("*Dẫn chứng và danh sách câu hỏi được sinh tất định bằng phân tích tĩnh "
                + "(JavaParser); vị trí `file:line` do parser cung cấp, không do model ngôn ngữ "
                + "viết ra.*\n");
        return out.toString();
    }

    private static void appendQuestionsMarkdown(StringBuilder out, CodeSpec spec) {
        if (spec.questions().isEmpty()) {
            out.append("\n## Cần xác nhận với developer\n\n"
                    + "Không phát hiện điểm nào cần xác nhận thêm cho luồng này.\n");
            return;
        }
        out.append("\n## Cần xác nhận với developer\n");
        out.append("\nĐây là những điểm **không kết luận được chỉ bằng đọc code**. "
                + "Đừng đưa vào tài liệu chính thức trước khi hỏi lại.\n");

        for (SpecQuestion.Severity severity : SpecQuestion.Severity.values()) {
            List<SpecQuestion> items = spec.bySeverity(severity);
            if (items.isEmpty()) {
                continue;
            }
            out.append("\n### ").append(severity.title()).append('\n');
            int number = 1;
            for (SpecQuestion question : items) {
                out.append("\n**").append(number++).append(". ").append(question.question())
                        .append("**\n");
                out.append("\n- *Vì sao không tự trả lời được:* ").append(question.reason())
                        .append('\n');
                if (!question.evidenceIds().isEmpty()) {
                    out.append("- *Dẫn chứng liên quan:* ");
                    out.append(String.join(", ", question.evidenceIds().stream()
                            .map(id -> "`" + id + "`").toList()));
                    out.append('\n');
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // HTML
    // ------------------------------------------------------------------

    static String html(ApiFlow flow, CodeSpec spec, String narrative, String aiNote,
            FlowComparison comparison) {

        StringBuilder out = new StringBuilder(12288);
        String title = "Đặc tả chức năng hiện trạng: " + flow.endpoint().label();

        out.append("<!DOCTYPE html>\n<html lang=\"vi\">\n<head>\n<meta charset=\"utf-8\">\n");
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        out.append("<title>").append(esc(title)).append("</title>\n");
        appendStyle(out);
        out.append("</head>\n<body>\n");

        out.append("<h1>").append(esc(title)).append("</h1>\n");
        if (flow.endpoint().summary() != null && !flow.endpoint().summary().isBlank()) {
            out.append("<p class=\"summary\">").append(esc(flow.endpoint().summary())).append("</p>\n");
        }
        out.append("<div class=\"callout scope\"><strong>Phạm vi tài liệu:</strong> mô tả hệ thống "
                + "ĐANG làm gì, không phải người dùng CẦN gì. Nội dung rút từ source code tại một "
                + "commit cụ thể. Phần suy luận về ý định nghiệp vụ được tách riêng bên dưới.</div>\n");

        out.append("<table>\n");
        row(out, "Repository", esc(flow.repoUrl()));
        row(out, "Branch", esc(flow.branch()));
        row(out, "Commit", "<code>" + esc(flow.commitSha()) + "</code>");
        row(out, "Điểm vào", "<code>" + esc(flow.endpoint().sourceFile() + ":"
                + flow.endpoint().line()) + "</code>");
        row(out, "Số dẫn chứng", String.valueOf(spec.evidence().size()));
        row(out, "Điểm cần xác nhận", String.valueOf(spec.questions().size()));
        if (!spec.procedures().isEmpty()) {
            row(out, "Procedure database", spec.procedures().size() + " lời gọi"
                    + (spec.databasePackages().isEmpty() ? ""
                            : " trong " + spec.databasePackages().size() + " package"));
        }
        out.append("</table>\n");

        appendQuestionsHtml(out, spec);

        AiSectionRenderer.appendCitationExplanationHtml(out, spec, narrative, aiNote);
        AiSectionRenderer.appendNarrativeHtml(out, narrative, aiNote);

        StoredProcedureSection.appendHtml(out, spec);
        ErrorCodeSection.appendHtml(out, spec);

        AiSectionRenderer.appendEvidenceHtml(out, spec);

        AiSectionRenderer.appendComparisonHtml(out, comparison);

        out.append("<footer>Dẫn chứng và danh sách câu hỏi được sinh tất định bằng phân tích tĩnh "
                + "(JavaParser). Vị trí file:line do parser cung cấp.<br>"
                + "Sơ đồ tuần tự nằm ở file .puml đi kèm - mở bằng plugin PlantUML.</footer>\n");
        out.append("</body>\n</html>\n");
        return out.toString();
    }

    private static void appendQuestionsHtml(StringBuilder out, CodeSpec spec) {
        out.append("<h2>Cần xác nhận với developer</h2>\n");
        if (spec.questions().isEmpty()) {
            out.append("<div class=\"callout ok\">Không phát hiện điểm nào cần xác nhận thêm "
                    + "cho luồng này.</div>\n");
            return;
        }
        out.append("<p>Đây là những điểm <strong>không kết luận được chỉ bằng đọc code</strong>. "
                + "Đừng đưa vào tài liệu chính thức trước khi hỏi lại developer.</p>\n");

        for (SpecQuestion.Severity severity : SpecQuestion.Severity.values()) {
            List<SpecQuestion> items = spec.bySeverity(severity);
            if (items.isEmpty()) {
                continue;
            }
            String cssClass = switch (severity) {
                case BLOCKING -> "err";
                case IMPORTANT -> "warn";
                case CLARIFY -> "info";
            };
            out.append("<h3>").append(esc(severity.title())).append("</h3>\n");
            for (SpecQuestion question : items) {
                out.append("<div class=\"callout ").append(cssClass).append("\">\n");
                out.append("<p class=\"q\">").append(esc(question.question())).append("</p>\n");
                out.append("<p class=\"why\"><em>Vì sao không tự trả lời được:</em> ")
                        .append(esc(question.reason())).append("</p>\n");
                if (!question.evidenceIds().isEmpty()) {
                    out.append("<p class=\"refs\">Dẫn chứng liên quan: ");
                    out.append(String.join(", ", question.evidenceIds().stream()
                            .map(id -> "<a href=\"#" + esc(id) + "\"><code>" + esc(id) + "</code></a>")
                            .toList()));
                    out.append("</p>\n");
                }
                out.append("</div>\n");
            }
        }
    }

    private static void appendStyle(StringBuilder out) {
        out.append("""
                <style>
                  :root { --line:#d8dee4; --muted:#5b6672; }
                  body { font-family:-apple-system,"Segoe UI",Roboto,Arial,sans-serif; line-height:1.6;
                         color:#1f2328; max-width:1100px; margin:0 auto; padding:24px 20px 64px; }
                  h1 { font-size:25px; margin:0 0 6px; }
                  h2 { font-size:19px; margin:34px 0 10px; padding-bottom:6px;
                       border-bottom:1px solid var(--line); }
                  h3 { font-size:15px; margin:22px 0 8px; color:#333; }
                  p.summary { color:var(--muted); margin:0 0 16px; }
                  table { border-collapse:collapse; width:100%; font-size:13.5px; margin:8px 0 4px; }
                  th,td { border:1px solid var(--line); padding:6px 9px; text-align:left;
                          vertical-align:top; }
                  th { background:#f4f6f8; } td.key { width:160px; font-weight:600; background:#fafbfc; }
                  code { font-family:Consolas,"Courier New",monospace; background:#f2f4f6;
                         padding:1px 4px; border-radius:3px; font-size:12.5px; }
                  pre { background:#f6f8fa; border:1px solid var(--line); border-radius:6px;
                        padding:12px; overflow-x:auto; font-size:13px; }
                  .callout { border-radius:6px; padding:11px 15px; margin:12px 0; font-size:14px; }
                  .callout.err   { background:#fdecea; border-left:4px solid #d9534f; }
                  .callout.warn  { background:#fff8e1; border-left:4px solid #e6c65c; }
                  .callout.info  { background:#eef4fb; border-left:4px solid #7aa7d9; }
                  .callout.ok    { background:#eaf6ec; border-left:4px solid #8fc79a; }
                  .callout.scope { background:#f4f0fa; border-left:4px solid #9b86c4; }
                  .callout.ai    { background:#eef2ff; border-left:4px solid #6d7ee0; }
                  .badge-ai { font-size:11px; font-weight:600; color:#fff; background:#6d7ee0;
                              padding:2px 8px; border-radius:10px; vertical-align:middle; }
                  .callout p.q { font-weight:600; margin:0 0 6px; }
                  .callout p.why, .callout p.refs { margin:4px 0 0; font-size:13px; color:#3c4550; }
                  .narrative p { margin:8px 0; } .narrative li { margin:3px 0; }
                  a.cite { text-decoration:none; font-size:12px; vertical-align:super; }
                  tr:target { background:#fff6d6; }
                  footer { margin-top:44px; padding-top:12px; border-top:1px solid var(--line);
                           color:var(--muted); font-size:13px; }
                </style>
                """);
    }

    private static void row(StringBuilder out, String key, String valueHtml) {
        out.append("<tr><td class=\"key\">").append(esc(key)).append("</td><td>")
                .append(valueHtml).append("</td></tr>\n");
    }

    private static String escapeTable(String value) {
        return value == null ? "" : value.replace("|", "\\|");
    }

    private static String esc(String value) {
        return HtmlReportRenderer.escapeHtml(value);
    }
}
