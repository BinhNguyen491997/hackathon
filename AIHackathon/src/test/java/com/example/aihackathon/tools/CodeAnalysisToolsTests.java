package com.example.aihackathon.tools;

import com.example.aihackathon.codeanalysis.FixtureRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool phải luôn trả về String đọc được cho model, kể cả khi không tìm thấy gì - ném exception
 * ra khỏi tool sẽ làm vòng tool calling bị cắt và model mất ngữ cảnh để trả lời người dùng.
 */
class CodeAnalysisToolsTests {

    private CodeAnalysisTools tools;

    @BeforeEach
    void setUp() {
        this.tools = new CodeAnalysisTools(FixtureRepo.analyzer());
    }

    @Test
    void lietKeEndpointKemMoTa() {
        String result = this.tools.listApiEndpoints(FixtureRepo.REPO_URL, "master", null);

        assertThat(result)
                .contains("POST /api/orders")
                .contains("GET /api/orders/{id}")
                .contains("Tạo đơn hàng mới cho khách");
    }

    @Test
    void locEndpointTheoTuKhoa() {
        assertThat(this.tools.listApiEndpoints(FixtureRepo.REPO_URL, "master", "chi tiết"))
                .contains("GET /api/orders/{id}")
                .doesNotContain("POST /api/orders\n");
    }

    @Test
    void bienBaoRoRangKhiFilterKhongKhop() {
        assertThat(this.tools.listApiEndpoints(FixtureRepo.REPO_URL, "master", "invoice"))
                .contains("Không có endpoint nào khớp");
    }

    @Test
    void moTaLuongTheoPath() {
        String result = this.tools.describeApiFlow(FixtureRepo.REPO_URL, "master", "POST", "/api/orders");

        assertThat(result)
                .contains("ENDPOINT: POST /api/orders")
                .contains("OrderServiceImpl")
                .contains("bảng: orders");
    }

    @Test
    void moTaLuongTheoTuKhoaKhiKhongBietPath() {
        assertThat(this.tools.describeApiFlow(FixtureRepo.REPO_URL, "master", null, "đơn hàng mới"))
                .contains("ENDPOINT: POST /api/orders");
    }

    @Test
    void traVeThongBaoKemGoiYThayViNemLoiKhiKhongTimThay() {
        String result = this.tools.describeApiFlow(FixtureRepo.REPO_URL, "master", "DELETE",
                "/api/khong-ton-tai");

        assertThat(result)
                .contains("Không có thông tin cần tìm")
                .contains("Các endpoint đang có trong repo")
                .contains("/api/orders");
    }

    @Test
    void sinhBaoCaoTraVeDuongDanHtmlMarkdownVaMaMermaid() {
        String result = this.tools.generateSequenceDiagram(FixtureRepo.REPO_URL, "master", "POST",
                "/api/orders");

        assertThat(result)
                .contains("Đã sinh báo cáo cho POST /api/orders")
                .contains("HTML (mở bằng browser, không cần cài gì)")
                .contains(".html")
                .contains("Markdown (dán vào GitLab wiki/MR để hiện sơ đồ)")
                .contains(".md")
                .contains("```mermaid")
                .contains("sequenceDiagram")
                .contains("Tóm tắt luồng:");
    }

    @Test
    void khongNhoiNoiDungHtmlVaoContextCuaModel() {
        String result = this.tools.generateSequenceDiagram(FixtureRepo.REPO_URL, "master", "POST",
                "/api/orders");

        // HTML dai hang chuc nghin ky tu - chi duoc tra duong dan, khong tra noi dung
        assertThat(result).doesNotContain("<!DOCTYPE html>").doesNotContain("<style>");
    }

    @Test
    void diagramKhongTimThayCungTraVeThongBaoDocDuoc() {
        assertThat(this.tools.generateSequenceDiagram(FixtureRepo.REPO_URL, "master", "GET",
                "/api/vo-nghia"))
                .contains("Không có thông tin cần tìm");
    }
}
