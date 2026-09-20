package com.example.aihackathon.codeanalysis;

import java.util.List;

import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import com.example.aihackathon.codeanalysis.model.ApiFlow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trọng tâm là phần escape. Nhãn trên sơ đồ là code thật lấy từ repo (điều kiện if, biểu thức,
 * câu query) nên hoàn toàn có thể chứa ký tự phá cú pháp Mermaid. Một ký tự sai là vỡ cả sơ đồ,
 * và BA sẽ chỉ thấy một khối lỗi đỏ chứ không phải tài liệu.
 */
class MermaidRendererTests {

    private static String mermaid;

    @BeforeAll
    static void renderOnce() {
        JavaSourceIndex index = FixtureRepo.index();
        ApiEndpoint endpoint = EndpointScanner.scan(index).stream()
                .filter(candidate -> candidate.matches("POST", "/api/orders"))
                .findFirst()
                .orElseThrow();
        ApiFlow flow = new CallFlowBuilder(index, FixtureRepo.properties())
                .build(endpoint, FixtureRepo.REPO_URL, FixtureRepo.BRANCH, FixtureRepo.COMMIT_SHA);
        mermaid = MermaidRenderer.render(flow);
    }

    // ------------------------------------------------------------------
    // Escape: bốn cái bẫy của Mermaid
    // ------------------------------------------------------------------

    @Test
    void bocTokenEndDeKhongVoSoDo() {
        // "end" tran trong nhan lam Mermaid tuong la dong khoi -> vo ca so do
        assertThat(MermaidRenderer.escape("if (end)")).contains("(end)");
        assertThat(MermaidRenderer.escape("gọi end rồi return")).contains("(end)");
        assertThat(MermaidRenderer.escape("END")).isEqualTo("(END)");
    }

    @Test
    void khongBocKhiEndChiLaMotPhanCuaTen() {
        assertThat(MermaidRenderer.escape("endDate")).isEqualTo("endDate");
        assertThat(MermaidRenderer.escape("weekend")).isEqualTo("weekend");
        assertThat(MermaidRenderer.escape("append_end_marker")).isEqualTo("append_end_marker");
    }

    @Test
    void escapeKyTuPhaCuPhap() {
        assertThat(MermaidRenderer.escape("a#b")).isEqualTo("a#35;b");
        assertThat(MermaidRenderer.escape("a;b")).isEqualTo("a#59;b");
        assertThat(MermaidRenderer.escape("total > 1000")).isEqualTo("total #62; 1000");
        assertThat(MermaidRenderer.escape("x < y")).isEqualTo("x #60; y");
        assertThat(MermaidRenderer.escape("nói \"xin chào\"")).isEqualTo("nói #34;xin chào#34;");
    }

    @Test
    void giuNguyenDauHaiChamViMermaidChiTachODauDauTien() {
        // dau ':' dau tien do renderer tu dat, moi dau ':' sau do la ky tu thuong
        assertThat(MermaidRenderer.escape("query: select o from Order o"))
                .isEqualTo("query: select o from Order o");
        assertThat(MermaidRenderer.escape("bảng: orders")).isEqualTo("bảng: orders");
    }

    @Test
    void boBacktickViMermaid11BaoLoiCodespan() {
        assertThat(MermaidRenderer.escape("dùng `code` ở đây")).isEqualTo("dùng 'code' ở đây");
    }

    /**
     * Đây là lỗi dễ mắc nhất: escape bằng chuỗi replace() nối tiếp thì replace('#') sinh ra ';'
     * và replace(';') sinh ra '#', hai bước tự phá kết quả của nhau. Phải escape một lượt.
     */
    @Test
    void escapeMotLuotKhongTuPhaKetQuaCuaNhau() {
        assertThat(MermaidRenderer.escape("#")).isEqualTo("#35;");
        assertThat(MermaidRenderer.escape(";")).isEqualTo("#59;");
        assertThat(MermaidRenderer.escape("#;")).isEqualTo("#35;#59;");
        assertThat(MermaidRenderer.escape("a > b; c # d"))
                .isEqualTo("a #62; b#59; c #35; d");
    }

    @Test
    void gopKyTuXuongDongThanhKhoangTrang() {
        assertThat(MermaidRenderer.escape("dòng 1\ndòng 2")).isEqualTo("dòng 1 dòng 2");
        assertThat(MermaidRenderer.escape("a\tb")).isEqualTo("a b");
    }

    // ------------------------------------------------------------------
    // Cấu trúc sơ đồ
    // ------------------------------------------------------------------

    @Test
    void coKhungSequenceDiagram() {
        assertThat(mermaid).startsWith("sequenceDiagram");
        assertThat(mermaid).contains("autonumber");
        assertThat(mermaid).contains("actor CLIENT as Client");
    }

    @Test
    void khaiBaoParticipantKemVaiTrenMotDong() {
        assertThat(mermaid).contains("participant OrderServiceImpl as OrderServiceImpl [service]");
        assertThat(mermaid).contains("OrderRepository [repository] - bảng: orders");
    }

    /**
     * Mermaid dùng chính chữ "end" để đóng khối, nên nhãn nhiều dòng bằng thẻ {@code <br/>} chỉ
     * hoạt động khi securityLevel là 'loose' - mức cho phép HTML từ nhãn, tức là mở đường XSS vì
     * nhãn sinh từ source repo lạ. Vì vậy sơ đồ này phải không có thẻ HTML nào.
     */
    @Test
    void khongCoTheHtmlNaoTrongSoDo() {
        assertThat(mermaid).doesNotContain("<br").doesNotContain("<div").doesNotContain("<script");
    }

    @Test
    void canBangKhoiAltLoopVaActivate() {
        int open = 0;
        for (String line : mermaid.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("alt ") || trimmed.startsWith("loop ")
                    || trimmed.startsWith("opt ") || trimmed.startsWith("rect ")) {
                open++;
            }
            else if (trimmed.equals("end")) {
                open--;
            }
            assertThat(open).as("'end' thừa ở dòng: " + trimmed).isNotNegative();
        }
        assertThat(open).as("khối chưa đóng").isZero();

        long activate = mermaid.lines().filter(line -> line.trim().startsWith("activate ")).count();
        long deactivate = mermaid.lines().filter(line -> line.trim().startsWith("deactivate ")).count();
        assertThat(activate).isEqualTo(deactivate);
    }

    @Test
    void giuDuocReNhanhVongLapVaNhanhLoi() {
        assertThat(mermaid).contains("alt luồng bình thường");
        assertThat(mermaid).contains("else lỗi RuntimeException");
        assertThat(mermaid).contains("loop với mỗi");
        assertThat(mermaid).contains("else ngược lại");
        assertThat(mermaid).contains("ném lỗi:");
    }

    @Test
    void moiMuiTenDeuCoNhanKhongDeTrong() {
        List<String> messages = mermaid.lines()
                .map(String::trim)
                .filter(line -> line.contains("->>"))
                .toList();

        assertThat(messages).isNotEmpty();
        for (String message : messages) {
            int colon = message.indexOf(": ");
            assertThat(colon).as("mũi tên thiếu nhãn: " + message).isPositive();
            assertThat(message.substring(colon + 2)).as("nhãn trống: " + message).isNotBlank();
        }
    }

    @Test
    void neuCoDiemMoThiPhaiNoiRaTrenSoDo() {
        // repo mau khong co diem mo -> phai ghi ro dieu do
        assertThat(mermaid).contains("Phân tích tĩnh phủ hết luồng này");
    }
}
