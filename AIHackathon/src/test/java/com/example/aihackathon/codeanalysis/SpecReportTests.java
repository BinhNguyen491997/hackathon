package com.example.aihackathon.codeanalysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tài liệu đặc tả phải làm được ba việc: nói rõ phạm vi (hiện trạng, không phải yêu cầu), nêu
 * bật phần cần xác nhận với dev, và cho phép truy từng câu về dòng code.
 */
class SpecReportTests {

    private ApiFlowAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        this.analyzer = FixtureRepo.analyzer();
    }

    @Test
    void banTatDinhChayDuocKhiChuaCoVanBanCuaModel() {
        ApiFlowAnalyzer.SpecResult result = this.analyzer
                .collectSpec(FixtureRepo.REPO_URL, "master", "POST", "/api/orders");

        assertThat(result.spec().evidence()).isNotEmpty();
        assertThat(result.markdown().content()).contains("# Đặc tả chức năng hiện trạng");
        assertThat(result.html().content()).startsWith("<!DOCTYPE html>");
        assertThat(result.markdown().file()).isNotNull();
        assertThat(result.html().file()).isNotNull();
    }

    @Test
    void noiRoPhamViLaHienTrangChuKhongPhaiYeuCau() {
        ApiFlowAnalyzer.SpecResult result = this.analyzer
                .collectSpec(FixtureRepo.REPO_URL, "master", "POST", "/api/orders");

        assertThat(result.markdown().content())
                .contains("mô tả hệ thống ĐANG làm gì, không phải người dùng CẦN gì");
        assertThat(result.html().content()).contains("Phạm vi tài liệu");
    }

    /**
     * Tài liệu đặc tả không ghi file .puml riêng, nên mã sơ đồ phải nằm trong chính file HTML -
     * kèm chú thích để người đọc biết cần cài tool mới xem được hình.
     */
    @Test
    void htmlDacTaNhungMaPumlKemHuongDanXem() {
        String html = this.analyzer
                .collectSpec(FixtureRepo.REPO_URL, "master", "POST", "/api/orders")
                .html().content();

        assertThat(html)
                .contains("Sơ đồ tuần tự (mã PlantUML)")
                .contains("@startuml")
                .contains("@enduml")
                .contains("MÃ NGUỒN của sơ đồ, không phải hình ảnh")
                .contains("https://www.plantuml.com/plantuml/uml/")
                .contains("PlantUML Integration")
                .contains("post-api-orders.puml")
                .doesNotContain("Sơ đồ tuần tự nằm ở file .puml đi kèm");
    }

    @Test
    void mucCanXacNhanDatTruocPhanMoTa() {
        String markdown = this.analyzer
                .collectSpec(FixtureRepo.REPO_URL, "master", "POST", "/api/orders")
                .markdown().content();

        int questions = markdown.indexOf("Cần xác nhận với developer");
        int evidence = markdown.indexOf("Dẫn chứng từ code");

        assertThat(questions).isPositive();
        assertThat(questions).isLessThan(evidence);
    }

    @Test
    void bangDanChungCoDuMaViTriVaDoanCode() {
        String markdown = this.analyzer
                .collectSpec(FixtureRepo.REPO_URL, "master", "POST", "/api/orders")
                .markdown().content();

        assertThat(markdown).contains("| Mã | Dữ kiện | Nguồn |");
        assertThat(markdown).contains("OrderController.java:");
        assertThat(markdown).contains("`E1`");
    }

    @Test
    void htmlBienTrichDanThanhLinkNhayToiBangDanChung() {
        ApiFlowAnalyzer.SpecResult result = this.analyzer.writeSpec(FixtureRepo.REPO_URL, "master",
                "POST", "/api/orders",
                "Hệ thống tiếp nhận yêu cầu tạo đơn hàng [E1].");

        assertThat(result.citations().trustworthy()).isTrue();
        assertThat(result.html().content()).contains("href=\"#E1\"");
        assertThat(result.html().content()).contains("<tr id=\"E1\">");
    }

    /**
     * Bug thật đã xảy ra: mã [E2] xuất hiện trong phần mô tả nhưng markdown không có gì kết nối
     * tới bảng dẫn chứng, và không có câu nào giải thích "mã này tra ở đâu". Người đọc thấy [E2]
     * mà không biết phải làm gì với nó.
     */
    @Test
    void markdownBienTrichDanThanhLinkNhayToiBangDanChung() {
        ApiFlowAnalyzer.SpecResult result = this.analyzer.writeSpec(FixtureRepo.REPO_URL, "master",
                "POST", "/api/orders",
                "Hệ thống tiếp nhận yêu cầu tạo đơn hàng [E1].");

        String markdown = result.markdown().content();
        // [E1] trong phan mo ta phai la link markdown toi anchor #E1
        assertThat(markdown).contains("[[E1]](#E1)");
        // hang tuong ung trong bang dan chung phai co anchor E1 de link nhay toi dung cho
        assertThat(markdown).contains("<a id=\"E1\"></a>`E1`");
    }

    @Test
    void markdownGiaiThichCachDocMaTrichDanNgayTruocPhanMoTa() {
        ApiFlowAnalyzer.SpecResult result = this.analyzer.writeSpec(FixtureRepo.REPO_URL, "master",
                "POST", "/api/orders", "Tiếp nhận đơn hàng [E1].");

        String markdown = result.markdown().content();
        int explanation = markdown.indexOf("Cách đọc mã trích dẫn");
        int narrativeSection = markdown.indexOf("## Mô tả chức năng");
        int evidenceTable = markdown.indexOf("## Dẫn chứng từ code");

        assertThat(explanation).as("thiếu câu giải thích cách đọc mã").isPositive();
        assertThat(explanation).as("câu giải thích phải đứng trước phần mô tả")
                .isLessThan(narrativeSection);
        assertThat(narrativeSection).isLessThan(evidenceTable);
    }

    @Test
    void khongCauGiaiThichKhiKhongCoPhanMoTa() {
        // khong bat AI thi khong co ma [En] nao de giai thich -> khong nen chen cau thua
        ApiFlowAnalyzer.SpecResult result = this.analyzer
                .collectSpec(FixtureRepo.REPO_URL, "master", "POST", "/api/orders");

        assertThat(result.markdown().content()).doesNotContain("Cách đọc mã trích dẫn");
    }

    @Test
    void tuChoiVanBanDanChungBia() {
        ApiFlowAnalyzer.SpecResult result = this.analyzer.writeSpec(FixtureRepo.REPO_URL, "master",
                "POST", "/api/orders",
                "Đơn hàng bị chặn khi vượt hạn mức tín dụng [E999].");

        assertThat(result.citations().trustworthy()).isFalse();
        assertThat(result.citations().invalidIds()).contains("E999");
    }

    @Test
    void tuChoiVanBanTuGoSoDong() {
        ApiFlowAnalyzer.SpecResult result = this.analyzer.writeSpec(FixtureRepo.REPO_URL, "master",
                "POST", "/api/orders",
                "Quy tắc nằm ở OrderValidator.java:9.");

        assertThat(result.citations().trustworthy()).isFalse();
        assertThat(result.citations().handWritten()).isNotEmpty();
    }

    @Test
    void banGonChoModelCoCaDanChungVaCauHoi() {
        String promptText = this.analyzer
                .collectSpec(FixtureRepo.REPO_URL, "master", "POST", "/api/orders")
                .promptText();

        assertThat(promptText)
                .contains("ENDPOINT: POST /api/orders")
                .contains("DỮ KIỆN ĐỌC ĐƯỢC TỪ CODE")
                .contains("ĐIỂM CẦN XÁC NHẬN VỚI DEV");
    }

    @Test
    void timDuocEndpointTheoMoTaChuKhongChiTheoPath() {
        ApiFlowAnalyzer.SpecResult result = this.analyzer
                .collectSpec(FixtureRepo.REPO_URL, "master", null, "chi tiết đơn hàng");

        assertThat(result.flow().endpoint().path()).isEqualTo("/api/orders/{id}");
    }

    @Test
    void ghiFileDacTaRiengKhongGhiDeLenFileSoDo() {
        ApiFlowAnalyzer.SpecResult result = this.analyzer
                .collectSpec(FixtureRepo.REPO_URL, "master", "POST", "/api/orders");

        assertThat(result.markdown().file().getFileName().toString()).endsWith("__spec.md");
        assertThat(result.html().file().getFileName().toString()).endsWith("__spec.html");
    }
}
