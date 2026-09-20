package com.example.aihackathon.codeanalysis;

import java.util.List;

import com.example.aihackathon.codeanalysis.model.CodeSpec;
import com.example.aihackathon.codeanalysis.model.ErrorCode;

/**
 * Mục "Mã lỗi API này có thể trả về".
 *
 * <p>Chỉ liệt kê mã <b>đi tới được từ endpoint đang xét</b>, không phải bảng mã lỗi của cả source.
 * Bảng mã lỗi hệ thống thật có vài chục hằng và hầu hết không liên quan; in hết ra thì người đọc đặc
 * tả một API mất khả năng biết mã nào cần xử lý khi tích hợp.
 *
 * <p>Nhóm theo {@link ErrorCode.Origin} vì đó là thông tin quyết định cách đối tác xử lý: mã ném
 * tường minh là nghiệp vụ (cần xử lý riêng), còn mã từ handler bắt tất cả là lỗi hệ thống (chỉ cần
 * retry hoặc báo lỗi chung).
 */
final class ErrorCodeSection {

    private ErrorCodeSection() {
    }

    static void appendMarkdown(StringBuilder out, CodeSpec spec) {
        if (spec == null || spec.errorCodes().isEmpty()) {
            return;
        }
        out.append("\n## Mã lỗi API này có thể trả về\n");
        out.append("\nSuy ra tất định từ code: mã ném tường minh trong luồng, mã của handler "
                + "validation, và mã của các `@ExceptionHandler` bắt được exception thoát ra. "
                + "**Không** phải toàn bộ bảng mã lỗi của hệ thống.\n");

        for (ErrorCode.Origin origin : ErrorCode.Origin.values()) {
            List<ErrorCode> codes = spec.errorCodesFrom(origin);
            if (codes.isEmpty()) {
                continue;
            }
            out.append("\n### ").append(origin.title()).append("\n\n");
            out.append("| Mã | Tên hằng | HTTP | Khi nào | Thông điệp | Nguồn |\n");
            out.append("|---|---|---|---|---|---|\n");
            for (ErrorCode code : codes) {
                out.append("| ").append(dash(code.code()))
                        .append(" | `").append(code.name())
                        .append("` | ").append(dash(code.httpStatus()))
                        .append(" | ").append(MarkdownReportRenderer.escape(dash(code.trigger())))
                        .append(" | ").append(MarkdownReportRenderer.escape(dash(code.message())))
                        .append(" | `").append(code.sourceFile()).append(':').append(code.line())
                        .append("` |\n");
            }
        }
        out.append("\n> Mã do hệ thống ngoài trả về (ví dụ `errorCode` của Way4) KHÔNG nằm ở đây - "
                + "chúng không được khai báo trong source Java. Xem mục \"Điều kiện rẽ nhánh\" để "
                + "biết luồng so sánh với những giá trị nào.\n");
    }

    static void appendHtml(StringBuilder out, CodeSpec spec) {
        if (spec == null || spec.errorCodes().isEmpty()) {
            return;
        }
        out.append("<h2>Mã lỗi API này có thể trả về</h2>\n");
        out.append("<p>Suy ra tất định từ code: mã ném tường minh trong luồng, mã của handler "
                + "validation, và mã của các <code>@ExceptionHandler</code> bắt được exception thoát "
                + "ra. <strong>Không</strong> phải toàn bộ bảng mã lỗi của hệ thống.</p>\n");

        out.append("<table>\n<tr><th>Đường đi tới</th><th>Mã</th><th>Tên hằng</th><th>HTTP</th>"
                + "<th>Khi nào</th><th>Thông điệp</th><th>Nguồn</th></tr>\n");
        for (ErrorCode code : spec.errorCodes()) {
            out.append("<tr><td>").append(esc(code.origin().title()))
                    .append("</td><td>").append(esc(dash(code.code())))
                    .append("</td><td><code>").append(esc(code.name()))
                    .append("</code></td><td>").append(esc(dash(code.httpStatus())))
                    .append("</td><td>").append(esc(dash(code.trigger())))
                    .append("</td><td>").append(esc(dash(code.message())))
                    .append("</td><td><code>").append(esc(code.sourceFile())).append(':')
                    .append(code.line()).append("</code></td></tr>\n");
        }
        out.append("</table>\n");
        out.append("<div class=\"callout info\">Mã do hệ thống ngoài trả về (ví dụ "
                + "<code>errorCode</code> của Way4) không nằm ở đây - chúng không được khai báo trong "
                + "source Java.</div>\n");
    }

    /**
     * Khối cho prompt của LLM.
     *
     * <p>Kèm cả lý do mã đó đi tới được, vì đó chính là thứ model cần để viết "khi nào API trả về mã
     * này" bằng ngôn ngữ nghiệp vụ. Và cấm tường minh việc nghĩ ra mã mới - bịa mã lỗi là dạng bịa
     * tệ nhất với tài liệu tích hợp, đối tác sẽ viết code theo nó.
     */
    static void appendPromptText(StringBuilder out, CodeSpec spec) {
        if (spec == null || spec.errorCodes().isEmpty()) {
            return;
        }
        out.append("\n=== MÃ LỖI API NÀY CÓ THỂ TRẢ VỀ (đọc từ code, KHÔNG được thêm mã nào khác) ===\n");
        for (ErrorCode code : spec.errorCodes()) {
            out.append("  ").append(code.label());
            if (code.httpStatus() != null) {
                out.append(" [HTTP ").append(code.httpStatus()).append(']');
            }
            out.append(" - ").append(code.trigger());
            if (code.message() != null) {
                out.append(" - \"").append(code.message()).append('"');
            }
            out.append('\n');
        }
        out.append("LƯU Ý: chỉ dùng đúng các mã trên. Không rõ mã nào ứng với tình huống nghiệp vụ nào "
                + "thì nói là chưa rõ, tuyệt đối không suy ra mã mới.\n");
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String esc(String value) {
        return HtmlReportRenderer.escapeHtml(value);
    }
}
