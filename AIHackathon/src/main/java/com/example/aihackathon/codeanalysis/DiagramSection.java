package com.example.aihackathon.codeanalysis;

/**
 * Nhúng sơ đồ tuần tự vào báo cáo HTML, dùng chung cho cả báo cáo luồng và tài liệu đặc tả.
 *
 * <p><b>Vì sao là Mermaid chứ không phải PlantUML.</b> Nguồn sơ đồ có hai bản: {@code .puml} vẽ chi
 * tiết hơn (tầng database tường minh, mã HTTP khi lỗi) nhưng phải có PlantUML mới xem được — mà
 * người nhận tài liệu là BA, không cài gì. Mermaid render được ngay trong browser bằng một thư viện
 * javascript, nên báo cáo HTML mở là thấy hình. Bản {@code .puml} vẫn sinh song song cho developer.
 *
 * <p><b>Sơ đồ đã là ảnh vector.</b> Mermaid sinh ra SVG, không phải bitmap, nên phóng to bao nhiêu
 * cũng không rỗ. Thứ cần thêm chỉ là bộ điều khiển: zoom, kéo để di chuyển, toàn màn hình, và tải
 * ra file {@code .svg} để mở bằng công cụ khác. Tất cả đều xử lý ngay trong browser.
 *
 * <p><b>Vì sao không render sẵn ảnh ở phía server.</b> Ba lựa chọn đều bị loại: nhúng thư viện
 * PlantUML vào app kéo theo ràng buộc giấy phép GPL; gọi ra plantuml.com/mermaid.ink công khai thì
 * <em>gửi cấu trúc code nội bộ ra server của người khác</em>; còn dựng Kroki riêng thì thêm một
 * thành phần phải deploy và vận hành. CDN của Mermaid chỉ tải về một file javascript, không có dữ
 * liệu nào của repo đi ra ngoài.
 *
 * <p><b>Không có internet thì sao.</b> Mã nguồn sơ đồ nằm ngay trong thẻ {@code <pre>}, nên script
 * không tải được thì trang hiện nguyên văn mã Mermaid chứ không mất nội dung. Đây là lý do dùng
 * {@code <pre>} thay vì {@code <div>}: xuống dòng được giữ nguyên khi hiển thị thô. Thanh công cụ
 * mặc định bị ẩn và chỉ hiện khi đã render được SVG - không có hình thì nút zoom chỉ gây bối rối.
 *
 * <p>Phiên bản Mermaid được ghim cứng. Dùng dải version ({@code @11}) là để người khác quyết định
 * trang của mình chạy code nào — bản mới có thể đổi cú pháp và làm vỡ sơ đồ đang đúng.
 */
final class DiagramSection {

    /** Ghim version cụ thể, không dùng {@code @11} hay {@code @latest}. */
    private static final String MERMAID_URL =
            "https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js";

    private DiagramSection() {
    }

    /**
     * Khối sơ đồ. Gọi ở chỗ muốn hình xuất hiện.
     *
     * @param mermaid mã sơ đồ do {@link MermaidRenderer} sinh; null/blank thì không in gì
     */
    static void appendHtml(StringBuilder out, String mermaid) {
        if (mermaid == null || mermaid.isBlank()) {
            return;
        }
        out.append("<h2>Sơ đồ tuần tự</h2>\n");

        // Thanh công cụ nằm TRƯỚC khung sơ đồ và không nằm trong nó: khung sơ đồ cuộn được, để nút
        // bên trong thì kéo hình một lúc là nút trôi mất khỏi tầm nhìn.
        out.append("<div id=\"diagram-toolbar\" hidden>\n");
        button(out, "diagram-zoom-out", "Thu nhỏ", "−");
        button(out, "diagram-zoom-in", "Phóng to", "+");
        button(out, "diagram-fit", "Vừa khung", "Vừa khung");
        button(out, "diagram-reset", "Kích thước thật", "100%");
        button(out, "diagram-full", "Xem toàn màn hình", "Toàn màn hình");
        button(out, "diagram-download", "Tải sơ đồ dạng SVG", "Tải .svg");
        out.append("<span id=\"diagram-zoom-level\" aria-live=\"polite\">100%</span>\n");
        out.append("</div>\n");

        out.append("<div id=\"diagram\" tabindex=\"0\" role=\"group\" "
                + "aria-label=\"Sơ đồ tuần tự, kéo để di chuyển, Ctrl kèm lăn chuột để phóng to\">\n");
        out.append("<pre class=\"mermaid\">\n");
        // Mã sơ đồ chứa tên lớp/method lấy từ repo được phân tích - dữ liệu không đáng tin, và
        // trang này được serve qua HTTP. Escape ở đây; browser tự giải lại entity khi đọc
        // textContent nên Mermaid vẫn nhận đúng mã gốc.
        out.append(HtmlReportRenderer.escapeHtml(mermaid));
        out.append("</pre>\n</div>\n");
        out.append("<p class=\"diagram-note\">Sơ đồ là ảnh vector (SVG) nên phóng to không bị rỗ: "
                + "dùng nút trên, hoặc giữ <kbd>Ctrl</kbd> và lăn chuột; kéo chuột để di chuyển. "
                + "Không có mạng thì phần trên hiện nguyên văn mã sơ đồ, nội dung không mất. "
                + "Bản <code>.puml</code> đi kèm vẽ chi tiết hơn (tầng database tường minh, "
                + "mã HTTP khi lỗi) - dành cho developer có plugin PlantUML.</p>\n");
    }

    private static void button(StringBuilder out, String id, String ariaLabel, String text) {
        out.append("<button type=\"button\" id=\"").append(id).append("\" aria-label=\"")
                .append(HtmlReportRenderer.escapeHtml(ariaLabel)).append("\">")
                .append(HtmlReportRenderer.escapeHtml(text)).append("</button>\n");
    }

    /**
     * Script nạp Mermaid rồi lắp bộ điều khiển zoom. Đặt ở cuối {@code <body>} để DOM đã có sẵn
     * khối sơ đồ khi script chạy.
     *
     * <p>Chủ động {@code startOnLoad: false} rồi tự gọi {@code run()}: bản UMD của Mermaid tự
     * render lúc {@code DOMContentLoaded}, để nó tự chạy thì thời điểm render phụ thuộc vào hành vi
     * mặc định của từng bản thư viện. Gọi tay thì biết chắc khi nào SVG đã có để gắn bộ điều khiển.
     *
     * <p><b>{@code useMaxWidth: false} là thiết lập quan trọng nhất ở đây.</b> Mặc định Mermaid co
     * cả SVG về vừa bề rộng thẻ chứa. Sơ đồ một API thật có hơn 10 lane, rộng vài nghìn pixel, nên
     * "vừa khung" nghĩa là chữ bị thu nhỏ tới mức không đọc nổi. Tắt nó đi thì sơ đồ giữ kích thước
     * thật và người xem tự quyết định mức phóng.
     *
     * <p>Zoom bằng cách đặt {@code style.width} theo pixel chứ không dùng {@code transform: scale}:
     * transform không làm thay đổi kích thước bố cục, nên thanh cuộn của khung không nới ra theo và
     * phóng to xong là mất nửa hình không cuộn tới được.
     */
    static void appendScript(StringBuilder out, String mermaid) {
        if (mermaid == null || mermaid.isBlank()) {
            return;
        }
        out.append("<script src=\"").append(MERMAID_URL).append("\"></script>\n");
        out.append("""
                <script>
                (function () {
                  if (!window.mermaid) { return; }
                  var box = document.getElementById('diagram');
                  var bar = document.getElementById('diagram-toolbar');
                  if (!box) { return; }

                  mermaid.initialize({
                    startOnLoad: false,
                    securityLevel: 'strict',
                    theme: 'neutral',
                    sequence: {
                      useMaxWidth: false,
                      mirrorActors: false,
                      actorFontSize: 14,
                      messageFontSize: 13,
                      noteFontSize: 12,
                      actorMargin: 45,
                      boxMargin: 12,
                      diagramMarginX: 16,
                      diagramMarginY: 16
                    }
                  });

                  mermaid.run({ querySelector: 'pre.mermaid' }).then(function () {
                    var svg = box.querySelector('svg');
                    if (!svg) { return; }

                    // Kich thuoc that lay tu viewBox - dang tin hon width/height vi khong bi
                    // anh huong boi CSS da ap vao.
                    var vb = (svg.getAttribute('viewBox') || '').split(/[ ,]+/);
                    var baseW = parseFloat(vb[2]) || svg.getBoundingClientRect().width;
                    var baseH = parseFloat(vb[3]) || svg.getBoundingClientRect().height;
                    if (!baseW) { return; }
                    svg.removeAttribute('height');
                    svg.style.maxWidth = 'none';

                    var zoom = 1;
                    var label = document.getElementById('diagram-zoom-level');

                    function apply() {
                      zoom = Math.min(8, Math.max(0.2, zoom));
                      svg.style.width = (baseW * zoom) + 'px';
                      svg.style.height = (baseH * zoom) + 'px';
                      if (label) { label.textContent = Math.round(zoom * 100) + '%'; }
                    }
                    function by(factor) { zoom *= factor; apply(); }
                    function fit() {
                      var usable = box.clientWidth - 24;
                      zoom = usable > 0 ? usable / baseW : 1;
                      apply();
                      box.scrollLeft = 0;
                    }
                    function on(id, handler) {
                      var el = document.getElementById(id);
                      if (el) { el.addEventListener('click', handler); }
                    }

                    on('diagram-zoom-in', function () { by(1.25); });
                    on('diagram-zoom-out', function () { by(0.8); });
                    on('diagram-fit', fit);
                    on('diagram-reset', function () { zoom = 1; apply(); });
                    on('diagram-full', function () {
                      if (document.fullscreenElement) { document.exitFullscreen(); }
                      else if (box.requestFullscreen) { box.requestFullscreen(); }
                    });
                    on('diagram-download', function () {
                      // Serialize chinh SVG dang hien tren trang -> khong goi server nao,
                      // khong gui noi dung so do ra ngoai.
                      var copy = svg.cloneNode(true);
                      copy.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
                      copy.setAttribute('width', baseW);
                      copy.setAttribute('height', baseH);
                      var blob = new Blob(
                        ['<?xml version="1.0" encoding="UTF-8"?>\\n'
                          + new XMLSerializer().serializeToString(copy)],
                        { type: 'image/svg+xml;charset=utf-8' });
                      var url = URL.createObjectURL(blob);
                      var a = document.createElement('a');
                      a.href = url;
                      a.download = (document.title || 'sequence').replace(/[^\\w.-]+/g, '_') + '.svg';
                      document.body.appendChild(a);
                      a.click();
                      document.body.removeChild(a);
                      URL.revokeObjectURL(url);
                    });

                    // Ctrl + lan chuot de phong. Khong chiem lan chuot tran: cuon trang la hanh vi
                    // nguoi dung mong doi, chi khi giu Ctrl moi doi y nghia thanh zoom.
                    box.addEventListener('wheel', function (event) {
                      if (!event.ctrlKey) { return; }
                      event.preventDefault();
                      by(event.deltaY < 0 ? 1.1 : 0.9);
                    }, { passive: false });

                    // Keo de di chuyen. Chi bat khi con cho de cuon, khong thi de nguyen con tro.
                    var dragging = false, startX = 0, startY = 0, fromLeft = 0, fromTop = 0;
                    box.addEventListener('pointerdown', function (event) {
                      if (event.button !== 0) { return; }
                      dragging = true;
                      startX = event.clientX; startY = event.clientY;
                      fromLeft = box.scrollLeft; fromTop = box.scrollTop;
                      box.setPointerCapture(event.pointerId);
                      box.classList.add('dragging');
                    });
                    box.addEventListener('pointermove', function (event) {
                      if (!dragging) { return; }
                      box.scrollLeft = fromLeft - (event.clientX - startX);
                      box.scrollTop = fromTop - (event.clientY - startY);
                    });
                    box.addEventListener('pointerup', function () {
                      dragging = false;
                      box.classList.remove('dragging');
                    });

                    if (bar) { bar.hidden = false; }
                    // Mo o kich thuoc THAT, khong tu "vua khung": vua khung nghia la co nho lai,
                    // dung lai dung cai loi chu qua nho ma useMaxWidth:false vua go bo.
                    apply();
                  });
                })();
                </script>
                """);
    }

    /**
     * CSS của khối sơ đồ, dùng chung để hai renderer HTML không định nghĩa lệch nhau.
     *
     * <p>Khối sơ đồ cố tình phá khung {@code max-width} của phần chữ: phần chữ hẹp thì dễ đọc,
     * còn sơ đồ thì càng rộng càng đỡ phải cuộn. {@code left: 50%} cộng {@code translateX(-50%)}
     * căn nó theo viewport thay vì theo cột chữ.
     */
    static String styles() {
        return """
                  #diagram-toolbar { display: flex; gap: 6px; align-items: center; margin: 10px 0 6px;
                             flex-wrap: wrap; }
                  #diagram-toolbar button { font: inherit; font-size: 13px; padding: 4px 10px;
                             border: 1px solid var(--line); border-radius: 5px; background: #f6f8fa;
                             color: #1f2328; cursor: pointer; }
                  #diagram-toolbar button:hover { background: #eaeef2; }
                  #diagram-toolbar button:focus-visible { outline: 2px solid #4a7fd0;
                             outline-offset: 1px; }
                  #diagram-zoom-level { font-size: 13px; color: var(--muted); min-width: 48px; }
                  #diagram { border: 1px solid var(--line); border-radius: 6px; padding: 12px;
                             overflow: auto; background: #fff; box-sizing: border-box;
                             position: relative; left: 50%; transform: translateX(-50%);
                             width: 96vw; max-width: 1800px; max-height: 80vh; cursor: grab;
                             overscroll-behavior: contain; }
                  #diagram.dragging { cursor: grabbing; }
                  #diagram:focus-visible { outline: 2px solid #4a7fd0; outline-offset: 2px; }
                  #diagram:fullscreen { width: 100vw; max-width: none; max-height: none;
                             left: 0; transform: none; border: 0; border-radius: 0; }
                  #diagram pre.mermaid { background: none; border: none; padding: 0; margin: 0;
                             overflow: visible; }
                  #diagram svg { display: block; }
                  p.diagram-note { color: var(--muted); font-size: 13px; margin: 6px 0 0; }
                  p.diagram-note kbd { font-family: Consolas, monospace; font-size: 12px;
                             border: 1px solid var(--line); border-radius: 3px; padding: 0 4px;
                             background: #f6f8fa; }
                """;
    }
}
