package com.example.aihackathon.codeanalysis;

import java.util.Locale;

import com.example.aihackathon.codeanalysis.model.ApiFlow;

/**
 * Nhúng mã sơ đồ PlantUML vào báo cáo HTML, dùng chung cho báo cáo luồng và tài liệu đặc tả.
 *
 * <p><b>Vì sao nhúng mã chứ không nhúng hình.</b> Render PlantUML thành ảnh ở phía server đều vướng:
 * nhúng thư viện PlantUML vào app kéo theo ràng buộc giấy phép GPL, gọi ra plantuml.com thì
 * <em>gửi cấu trúc code nội bộ ra server của người khác</em>, còn dựng Kroki riêng thì thêm một
 * thành phần phải deploy. Trước đây báo cáo chỉ ghi một câu "sơ đồ nằm ở file .puml đi kèm" - mà
 * "đi kèm" là đường dẫn trên máy chạy server, người nhận file HTML qua chat/email không có file đó.
 * Nhúng nguyên văn mã vào trang thì file HTML tự chứa đủ, chép ra là dựng lại được sơ đồ.
 *
 * <p>Đi cùng mã bắt buộc phải có phần chú thích: người đọc mở ra thấy một khối chữ lạ thì kết luận
 * "báo cáo lỗi" chứ không tự biết đó là mã sơ đồ và cần tool mới xem được hình. Vì vậy khối này nói
 * rõ ba điều: đây là mã nguồn không phải hình, xem bằng cách nào (online cho nhanh, plugin IDE hoặc
 * {@code plantuml.jar} khi không muốn mã đi ra ngoài), và mỗi cách đánh đổi gì.
 *
 * <p>Hướng dẫn online đặt đầu tiên vì đó là cách duy nhất không đòi cài đặt - phần lớn người đọc
 * báo cáo này là BA. Khác với việc server tự gọi plantuml.com, ở đây <em>người đọc chủ động</em>
 * dán mã lên trang đó và đã được cảnh báo về hệ quả ngay bên dưới danh sách; agent không tự gửi
 * nội dung nào ra ngoài.
 *
 * <p>Không dùng javascript: báo cáo phải mở được offline và không phụ thuộc CDN nào.
 */
final class PlantUmlSection {

    private PlantUmlSection() {
    }

    /**
     * Khối mã PlantUML kèm hướng dẫn xem. Không in gì nếu {@code puml} trống.
     *
     * @param flow dùng để gợi ý tên file khi người đọc chép mã ra
     * @param puml mã do {@link PlantUmlRenderer} sinh
     */
    static void appendHtml(StringBuilder out, ApiFlow flow, String puml) {
        if (puml == null || puml.isBlank()) {
            return;
        }
        String fileName = suggestedFileName(flow);

        out.append("<h2>Sơ đồ tuần tự (mã PlantUML)</h2>\n");
        out.append("<div class=\"callout puml\">\n");
        out.append("<p><strong>Khối bên dưới là MÃ NGUỒN của sơ đồ, không phải hình ảnh.</strong> "
                + "Đây là mã PlantUML - một định dạng văn bản mô tả sequence diagram. Để xem thành "
                + "hình, chọn một trong các cách sau:</p>\n");
        out.append("<ul>\n");
        out.append("<li><strong>Xem online, không cần cài gì</strong> (nhanh nhất): mở "
                + "<a href=\"https://www.plantuml.com/plantuml/uml/\" target=\"_blank\" "
                + "rel=\"noreferrer noopener\">plantuml.com/plantuml/uml</a> hoặc "
                + "<a href=\"https://editor.plantuml.com/\" target=\"_blank\" "
                + "rel=\"noreferrer noopener\">editor.plantuml.com</a>, xoá mã mẫu trong khung bên "
                + "trái, dán toàn bộ mã dưới đây vào rồi bấm <em>Submit</em> (editor.plantuml.com tự "
                + "vẽ lại khi bạn dán). Hình hiện ngay bên cạnh và tải được ra PNG/SVG.</li>\n");
        out.append("<li><strong>IntelliJ IDEA:</strong> cài plugin <em>PlantUML Integration</em> "
                + "(<code>Settings → Plugins → Marketplace</code>), rồi mở file <code>.puml</code> - "
                + "hình hiện ở panel bên cạnh.</li>\n");
        out.append("<li><strong>VS Code:</strong> cài extension <em>PlantUML</em> "
                + "(<code>jebbs.plantuml</code>), mở file <code>.puml</code> rồi bấm "
                + "<kbd>Alt</kbd>+<kbd>D</kbd> để xem trước.</li>\n");
        out.append("<li><strong>Dòng lệnh:</strong> tải <code>plantuml.jar</code> từ plantuml.com "
                + "rồi chạy <code>java -jar plantuml.jar ")
                .append(HtmlReportRenderer.escapeHtml(fileName))
                .append("</code> để sinh ra file PNG/SVG (cần có Java; sơ đồ tuần tự không cần "
                        + "Graphviz).</li>\n");
        out.append("</ul>\n");
        out.append("<p><strong>Lưu ý khi dùng cách online:</strong> mã sơ đồ chứa tên lớp, tên method "
                + "và câu query của hệ thống - dán lên trang công khai là gửi những thông tin đó tới "
                + "server của bên thứ ba. Với repo nội bộ hoặc code thuộc diện bảo mật, hãy dùng một "
                + "trong ba cách còn lại: chúng render ngay trên máy bạn, không có dữ liệu nào đi ra "
                + "ngoài.</p>\n");
        out.append("</div>\n");

        out.append("<details open>\n<summary>Mã PlantUML - chọn toàn bộ, lưu thành file <code>")
                .append(HtmlReportRenderer.escapeHtml(fileName))
                .append("</code></summary>\n");
        // Mã chứa tên lớp/method/query lấy từ repo được phân tích - dữ liệu không đáng tin, và
        // trang này còn được serve qua HTTP, nên phải escape.
        out.append("<pre class=\"puml\">").append(HtmlReportRenderer.escapeHtml(puml))
                .append("</pre>\n");
        out.append("</details>\n");
        out.append("<p class=\"puml-file\">Phần <code>@startuml</code> ... <code>@enduml</code> phải "
                + "được chép nguyên vẹn, kể cả dòng đầu và dòng cuối, nếu không tool sẽ báo lỗi cú "
                + "pháp.</p>\n");
    }

    /** Câu nhắc ở footer, để người đọc không đi tìm file .puml bên ngoài nữa. */
    static String footerNote(String puml) {
        return puml == null || puml.isBlank()
                ? "Sơ đồ tuần tự nằm ở file .puml đi kèm - mở bằng plugin PlantUML."
                : "Mã sơ đồ tuần tự được nhúng ngay trong trang này (mục \"Sơ đồ tuần tự (mã "
                        + "PlantUML)\"); cần plugin/tool PlantUML mới xem được thành hình.";
    }

    /** Tên file gợi ý, chỉ gồm chữ số và dấu gạch nối nên mọi filesystem đều nhận. */
    static String suggestedFileName(ApiFlow flow) {
        if (flow == null || flow.endpoint() == null) {
            return "sequence.puml";
        }
        String name = (flow.endpoint().httpMethod() + "-" + flow.endpoint().path())
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("(^-|-$)", "")
                .toLowerCase(Locale.ROOT);
        return (name.isBlank() ? "sequence" : name) + ".puml";
    }

    /** CSS riêng của khối, để hai renderer HTML không định nghĩa lệch nhau. */
    static String styles() {
        return """
                  .callout.puml { background:#f1f4f9; border-left:4px solid #6d8ec4; }
                  .callout.puml p { margin:0 0 8px; }
                  .callout.puml p:last-child { margin-bottom:0; }
                  .callout.puml ul { margin:8px 0; padding-left:22px; }
                  .callout.puml li { margin:4px 0; font-size:13.5px; }
                  .callout.puml a { color:#2b5fa8; }
                  .callout.puml kbd { font-family:Consolas,monospace; font-size:12px;
                             border:1px solid var(--line); border-radius:3px; padding:0 4px;
                             background:#fff; }
                  pre.puml { max-height:560px; overflow:auto; }
                  p.puml-file { color:var(--muted); font-size:13px; margin:6px 0 0; }
                """;
    }
}
