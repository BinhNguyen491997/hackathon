package com.example.aihackathon.codeanalysis;

import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import com.example.aihackathon.codeanalysis.model.ApiFlow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hai định dạng dành cho người không cài công cụ gì: HTML mở bằng browser, Markdown dán vào
 * GitLab. Điều kiện bắt buộc của cả hai: đọc được ngay cả khi KHÔNG vẽ được sơ đồ.
 */
class ReportRendererTests {

    private static ApiFlow flow;

    private static String mermaid;

    private static String html;

    private static String markdown;

    @BeforeAll
    static void renderOnce() {
        JavaSourceIndex index = FixtureRepo.index();
        ApiEndpoint endpoint = EndpointScanner.scan(index).stream()
                .filter(candidate -> candidate.matches("POST", "/api/orders"))
                .findFirst()
                .orElseThrow();
        flow = new CallFlowBuilder(index, FixtureRepo.properties())
                .build(endpoint, FixtureRepo.REPO_URL, FixtureRepo.BRANCH, FixtureRepo.COMMIT_SHA);

        MarkdownReportRenderer.FlowFacts facts = FlowSummarizer.extract(flow);
        mermaid = MermaidRenderer.render(flow);
        markdown = MarkdownReportRenderer.render(flow, mermaid, facts);
        html = HtmlReportRenderer.render(flow, facts);
    }

    // ------------------------------------------------------------------
    // HTML
    // ------------------------------------------------------------------

    @Test
    void htmlLaFileHoanChinhMoDuocBangBrowser() {
        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("<html lang=\"vi\">").endsWith("</html>\n");
        assertThat(html).contains("<meta charset=\"utf-8\">");
        assertThat(html).contains("<title>POST /api/orders</title>");
        assertThat(html).contains("<style>");
    }

    @Test
    void htmlChuaDuThongTinNghiepVuChuKhongChiCoHinh() {
        assertThat(html)
                .contains("Thông tin nguồn")
                .contains("Các lớp tham gia")
                .contains("Dữ liệu bị tác động")
                .contains("Gọi ra ngoài hệ thống")
                .contains("Điều kiện rẽ nhánh")
                .contains("Nhánh lỗi")
                .contains("Trình tự chi tiết")
                .contains("bảng: orders")
                .contains(FixtureRepo.COMMIT_SHA);
    }

    /**
     * HTML là nơi đọc chữ, sơ đồ nằm ở file .puml. Định dạng .puml vẽ được chi tiết hơn nhiều
     * (tầng database tường minh, mã HTTP khi lỗi, kiểu trả về) mà không bị giới hạn bởi việc phải
     * render trong browser.
     */
    @Test
    void htmlKhongConSoDoTuanTu() {
        // kiem tra khong con NOI DUNG so do; rieng footer van duoc phep nhac de chi duong toi .puml
        assertThat(html)
                .doesNotContain("<h2>Sơ đồ tuần tự</h2>")
                .doesNotContain("class=\"mermaid\"")
                .doesNotContain("sequenceDiagram")
                .doesNotContain("Mã nguồn sơ đồ");
    }

    @Test
    void htmlKhongConPhuThuocJavascript() {
        // bo so do thi khong con can mermaid.js -> file mo offline la day du tuyet doi
        assertThat(html).doesNotContain("<script");
        assertThat(html).doesNotContain("mermaid.min.js");
    }

    @Test
    void htmlChiDuongDanNguoiDocToiFilePuml() {
        assertThat(html).contains("Sơ đồ tuần tự nằm ở file .puml đi kèm");
    }

    @Test
    void htmlVanDayDuThongTinDuKhongCoHinh() {
        // mat hinh ve khong duoc mat thong tin
        assertThat(html)
                .contains("OrderServiceImpl")
                .contains("PaymentClient")
                .contains("Trình tự chi tiết")
                .contains("bảng: orders");
    }

    @Test
    void htmlDatCanhBaoTruocSoDo() {
        // nguoi doc phai biet gioi han truoc khi tin vao hinh ve
        int caveat = html.indexOf("callout");
        int diagram = html.indexOf("Sơ đồ tuần tự");
        assertThat(caveat).isPositive();
        assertThat(caveat).isLessThan(diagram);
    }

    @Test
    void htmlEscapeNoiDungLayTuRepo() {
        // source repo co the chua chuoi trong nhu the HTML, va file nay con duoc serve qua HTTP
        assertThat(HtmlReportRenderer.escapeHtml("<script>alert(1)</script>"))
                .isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(HtmlReportRenderer.escapeHtml("a & b")).isEqualTo("a &amp; b");
        assertThat(HtmlReportRenderer.escapeHtml("nói \"xin chào\""))
                .isEqualTo("nói &quot;xin chào&quot;");
        assertThat(HtmlReportRenderer.escapeHtml("it's")).isEqualTo("it&#39;s");
    }

    @Test
    void htmlKhongDeLotTheScriptTuNoiDungRepo() {
        // source repo co the chua chuoi trong nhu the HTML; bo so do roi nen cang khong duoc co script
        assertThat(html).doesNotContain("<script>alert");
        assertThat(html).doesNotContain("<script");
    }

    // ------------------------------------------------------------------
    // Mã PlantUML nhúng trong HTML
    // ------------------------------------------------------------------

    /**
     * File HTML phải tự chứa đủ: người nhận qua chat/email không có file .puml trên máy chạy
     * server, nên mã sơ đồ phải nằm ngay trong trang.
     */
    @Test
    void htmlNhungNguyenVanMaPuml() {
        String withPuml = HtmlReportRenderer.render(flow, FlowSummarizer.extract(flow), null, null,
                null, null, PlantUmlRenderer.render(flow));

        assertThat(withPuml)
                .contains("Sơ đồ tuần tự (mã PlantUML)")
                .contains("@startuml")
                .contains("@enduml")
                .contains("OrderServiceImpl");
    }

    /** Thấy khối chữ lạ mà không có chú thích thì người đọc kết luận "báo cáo lỗi". */
    @Test
    void htmlNoiRoDoLaMaSoDoVaCanToolDeXemHinh() {
        String withPuml = HtmlReportRenderer.render(flow, FlowSummarizer.extract(flow), null, null,
                null, null, PlantUmlRenderer.render(flow));

        assertThat(withPuml)
                .contains("MÃ NGUỒN của sơ đồ, không phải hình ảnh")
                .contains("PlantUML Integration")
                .contains("jebbs.plantuml")
                .contains("plantuml.jar")
                .contains("post-api-orders.puml");
        // cach xem online (khong can cai gi) phai co, va dat truoc cac cach phai cai dat
        assertThat(withPuml)
                .contains("https://www.plantuml.com/plantuml/uml/")
                .contains("https://editor.plantuml.com/");
        assertThat(withPuml.indexOf("Xem online")).isLessThan(withPuml.indexOf("IntelliJ IDEA"));
        // nhung van phai noi ro cai gia cua cach online
        assertThat(withPuml).contains("Lưu ý khi dùng cách online");
        // footer khong duoc tro nguoi doc di tim file ben ngoai nua
        assertThat(withPuml).doesNotContain("Sơ đồ tuần tự nằm ở file .puml đi kèm");
    }

    @Test
    void maPumlNhungVaoHtmlVanDuocEscape() {
        String withPuml = HtmlReportRenderer.render(flow, FlowSummarizer.extract(flow), null, null,
                null, null, "@startuml\nnote over X: <script>alert(1)</script>\n@enduml\n");

        assertThat(withPuml)
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<script");
    }

    // ------------------------------------------------------------------
    // Markdown
    // ------------------------------------------------------------------

    @Test
    void markdownNhungSoDoTheoCuPhapGitLabHieu() {
        assertThat(markdown).contains("```mermaid\n");
        assertThat(markdown).contains("sequenceDiagram");
        // khoi mermaid phai duoc dong lai, neu khong GitLab render loi toan bo trang
        assertThat(countOccurrences(markdown, "```")).isEven();
    }

    @Test
    void markdownCoTieuDeVaBangThongTinNguon() {
        assertThat(markdown)
                .contains("# POST /api/orders")
                .contains("> Tạo đơn hàng mới cho khách")
                .contains("## Thông tin nguồn")
                .contains("| **Repository** |")
                .contains("## Các lớp tham gia")
                .contains("## Trình tự chi tiết");
    }

    @Test
    void markdownEscapeDauGachDungDeKhongVoBang() {
        assertThat(MarkdownReportRenderer.escape("a | b")).isEqualTo("a \\| b");
        assertThat(MarkdownReportRenderer.escape("dòng 1\ndòng 2")).isEqualTo("dòng 1 dòng 2");
        assertThat(MarkdownReportRenderer.escape(null)).isEmpty();
    }

    @Test
    void markdownNoiRoKhiKhongConDiemMo() {
        assertThat(flow.unresolved()).isEmpty();
        assertThat(markdown).contains("Phân tích tĩnh phủ hết luồng này, không có điểm mờ.");
    }

    @Test
    void caHaiDinhDangDeuGhiRoLaSinhTuPhanTichTinh() {
        assertThat(html).contains("Không dùng model ngôn ngữ");
        assertThat(markdown).contains("Không dùng model ngôn ngữ");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
