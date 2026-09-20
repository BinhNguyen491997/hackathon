package com.example.aihackathon.codeanalysis;

import java.util.List;

import com.example.aihackathon.codeanalysis.model.CodeEvidence;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nhắc trong system prompt là chưa đủ để model chịu dẫn nguồn. Lớp này biến yêu cầu đó thành phép
 * kiểm tra chạy được, nên chính nó phải được test kỹ - nếu nó bỏ sót, dẫn chứng bịa sẽ đi thẳng
 * vào tài liệu.
 */
class CitationValidatorTests {

    private static final List<CodeEvidence> EVIDENCE = List.of(
            evidence("E1", "Endpoint POST /api/orders"),
            evidence("E2", "Trường customerCode phải NotBlank"),
            evidence("E3", "Nếu customer == null thì báo lỗi"));

    private static CodeEvidence evidence(String id, String statement) {
        return new CodeEvidence(id, CodeEvidence.Kind.BUSINESS_RULE, statement,
                "src/main/java/com/shop/order/OrderServiceImpl.java", 42, "snippet");
    }

    @Test
    void nhanTrichDanHopLe() {
        CitationValidator.Result result = CitationValidator.validate(
                "Hệ thống nhận đơn hàng [E1] và bắt buộc có mã khách [E2].", EVIDENCE);

        assertThat(result.trustworthy()).isTrue();
        assertThat(result.citedIds()).containsExactly("E1", "E2");
        assertThat(result.invalidIds()).isEmpty();
    }

    @Test
    void nhanNhieuMaTrongMotCapNgoac() {
        CitationValidator.Result result = CitationValidator.validate(
                "Có hai ràng buộc [E2, E3] cần chú ý.", EVIDENCE);

        assertThat(result.citedIds()).containsExactly("E2", "E3");
        assertThat(result.trustworthy()).isTrue();
    }

    @Test
    void batMaDanChungBiBia() {
        CitationValidator.Result result = CitationValidator.validate(
                "Đơn hàng bị chặn nếu quá hạn mức [E9].", EVIDENCE);

        assertThat(result.trustworthy()).isFalse();
        assertThat(result.invalidIds()).containsExactly("E9");
        assertThat(result.describe()).contains("dẫn chứng bịa");
    }

    /**
     * Đây là dạng bịa nguy hiểm nhất: model tự gõ đường dẫn kèm số dòng. Câu văn trông cực kỳ có
     * căn cứ, mà số dòng thì không ai đối chiếu.
     */
    @Test
    void batViecTuGoFileVaSoDong() {
        CitationValidator.Result result = CitationValidator.validate(
                "Quy tắc này nằm ở OrderServiceImpl.java:57 trong phần kiểm tra khách hàng.",
                EVIDENCE);

        assertThat(result.trustworthy()).isFalse();
        assertThat(result.handWritten()).containsExactly("OrderServiceImpl.java:57");
        assertThat(result.describe()).contains("Không được tự viết file:line");
    }

    @Test
    void batCaDuongDanDayDu() {
        CitationValidator.Result result = CitationValidator.validate(
                "Xem src/main/java/com/shop/order/OrderValidator.java:12", EVIDENCE);

        assertThat(result.handWritten())
                .containsExactly("src/main/java/com/shop/order/OrderValidator.java:12");
    }

    @Test
    void vanBanTrongCoiNhuKhongCoTrichDan() {
        assertThat(CitationValidator.validate(null, EVIDENCE).citedIds()).isEmpty();
        assertThat(CitationValidator.validate("", EVIDENCE).trustworthy()).isTrue();
    }

    @Test
    void khongGomTrungMaKhiDanNhieuLan() {
        CitationValidator.Result result = CitationValidator.validate(
                "Điều này [E1] và điều kia [E1] cùng một nguồn.", EVIDENCE);

        assertThat(result.citedIds()).containsExactly("E1");
    }

    @Test
    void chiRaNhomDanChungChuaDuocNhacDenTrongTaiLieu() {
        List<CodeEvidence> mixed = List.of(
                evidence("E1", "quy tắc nghiệp vụ"),
                new CodeEvidence("E2", CodeEvidence.Kind.SECURITY, "phân quyền",
                        "src/main/java/A.java", 5, "snippet"));

        CitationValidator.Result result = CitationValidator.validate("Chỉ nói về [E1].", mixed);

        assertThat(result.trustworthy()).isTrue();
        assertThat(result.uncitedKinds()).containsExactly(CodeEvidence.Kind.SECURITY);
    }

    @Test
    void khongBaoDongGiaVoiTenFileKhongKemSoDong() {
        CitationValidator.Result result = CitationValidator.validate(
                "Logic nằm trong OrderServiceImpl.java [E1].", EVIDENCE);

        assertThat(result.handWritten()).isEmpty();
        assertThat(result.trustworthy()).isTrue();
    }
}
