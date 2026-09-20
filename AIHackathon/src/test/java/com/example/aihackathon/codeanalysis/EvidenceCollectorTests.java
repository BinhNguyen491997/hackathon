package com.example.aihackathon.codeanalysis;

import java.util.List;

import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.CodeEvidence;
import com.example.aihackathon.codeanalysis.model.CodeSpec;
import com.example.aihackathon.codeanalysis.model.SpecQuestion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra phần làm nên độ tin cậy của tài liệu: mọi dữ kiện phải có vị trí thật trong repo, và
 * những chỗ đọc code không kết luận được phải tự động thành câu hỏi cho dev chứ không bị im lặng
 * bỏ qua.
 */
class EvidenceCollectorTests {

    private static CodeSpec spec;

    @BeforeAll
    static void collectOnce() {
        JavaSourceIndex index = FixtureRepo.index();
        ApiEndpoint endpoint = EndpointScanner.scan(index).stream()
                .filter(candidate -> candidate.matches("POST", "/api/orders"))
                .findFirst()
                .orElseThrow();
        ApiFlow flow = new CallFlowBuilder(index, FixtureRepo.properties())
                .build(endpoint, FixtureRepo.REPO_URL, FixtureRepo.BRANCH, FixtureRepo.COMMIT_SHA);
        spec = EvidenceCollector.collect(index, flow);
    }

    // ------------------------------------------------------------------
    // Điều kiện bắt buộc: dẫn chứng phải truy vết được
    // ------------------------------------------------------------------

    @Test
    void moiDanChungDeuCoViTriThatTrongRepo() {
        assertThat(spec.evidence()).isNotEmpty();
        for (CodeEvidence item : spec.evidence()) {
            assertThat(item.sourceFile())
                    .as("dẫn chứng %s thiếu file nguồn: %s", item.id(), item.statement())
                    .isNotBlank()
                    .endsWith(".java");
            assertThat(item.line())
                    .as("dẫn chứng %s thiếu số dòng: %s", item.id(), item.statement())
                    .isPositive();
        }
    }

    @Test
    void maDanChungDuyNhatVaLienTuc() {
        List<String> ids = spec.evidence().stream().map(CodeEvidence::id).toList();

        assertThat(ids).doesNotHaveDuplicates();
        for (int i = 0; i < ids.size(); i++) {
            assertThat(ids.get(i)).isEqualTo("E" + (i + 1));
        }
    }

    // ------------------------------------------------------------------
    // Dữ kiện mà sequence diagram cố tình bỏ
    // ------------------------------------------------------------------

    @Test
    void docDuocSchemaDauVaoDuDiagramDaLocBoDto() {
        assertThat(statements(CodeEvidence.Kind.REQUEST_FIELD))
                .anyMatch(text -> text.contains("customerCode"))
                .anyMatch(text -> text.contains("total"));
    }

    @Test
    void docDuocRangBuocTuAnnotationValidation() {
        assertThat(statements(CodeEvidence.Kind.VALIDATION))
                .anyMatch(text -> text.contains("NotBlank")
                        && text.contains("Mã khách không được để trống"))
                .anyMatch(text -> text.contains("Positive")
                        && text.contains("Tổng tiền phải lớn hơn 0"));
    }

    /**
     * Quy tắc thật nằm trong OrderValidator - lớp mà diagram không mở ruột (kind SUPPORT). Đây
     * chính là lý do tài liệu đặc tả cần một bộ trích xuất riêng chứ không dùng lại cây luồng.
     */
    @Test
    void mORuotLopPhuTroDeLayQuyTacNghiepVu() {
        assertThat(statements(CodeEvidence.Kind.VALIDATION))
                .anyMatch(text -> text.contains("OrderValidator.validate")
                        && text.contains("request.total() <= 0"));
    }

    @Test
    void docDuocDieuKienPhanQuyen() {
        assertThat(statements(CodeEvidence.Kind.SECURITY))
                .anyMatch(text -> text.contains("ORDER_CREATE"));
    }

    @Test
    void docDuocBangMaLoi() {
        assertThat(statements(CodeEvidence.Kind.ERROR_MAPPING))
                .anyMatch(text -> text.contains("OrderNotFoundException") && text.contains("NOT_FOUND"));
    }

    @Test
    void docDuocQuyTacNghiepVuDangIfThiBaoLoi() {
        assertThat(statements(CodeEvidence.Kind.BUSINESS_RULE))
                .anyMatch(text -> text.contains("customer == null")
                        && text.contains("OrderNotFoundException"));
    }

    @Test
    void docDuocTruyCapDuLieuVaGoiRaNgoai() {
        assertThat(statements(CodeEvidence.Kind.DATA_ACCESS))
                .anyMatch(text -> text.contains("OrderRepository.save") && text.contains("bảng: orders"));
        assertThat(statements(CodeEvidence.Kind.EXTERNAL_CALL))
                .anyMatch(text -> text.contains("PaymentClient.charge"));
    }

    @Test
    void docDuocPhamViGiaoDich() {
        assertThat(statements(CodeEvidence.Kind.TRANSACTION))
                .anyMatch(text -> text.contains("OrderServiceImpl.create")
                        && text.contains("@Transactional"));
    }

    /**
     * Dẫn chứng phải trỏ vào chỗ KHAI BÁO annotation, không phải chỗ gọi. Trỏ sai chỗ là kiểu sai
     * âm thầm nhất: người đọc mở đúng dòng được dẫn, không thấy gì liên quan, và mất tin vào cả
     * tài liệu.
     */
    @Test
    void danChungGiaoDichTroVaoChoKhaiBaoChuKhongPhaiChoGoi() {
        CodeEvidence transaction = spec.byKind(CodeEvidence.Kind.TRANSACTION).get(0);

        assertThat(transaction.sourceFile()).endsWith("OrderServiceImpl.java");
        assertThat(transaction.sourceFile()).doesNotContain("OrderController");
    }

    // ------------------------------------------------------------------
    // Câu hỏi cho dev: sinh tất định, không phụ thuộc model có nhớ hay không
    // ------------------------------------------------------------------

    @Test
    void phatHienLoiBiBoQuaTrongCatch() {
        assertThat(questions())
                .anyMatch(text -> text.contains("bỏ qua hoàn toàn"));
        assertThat(spec.bySeverity(SpecQuestion.Severity.IMPORTANT)).isNotEmpty();
    }

    @Test
    void phatHienNguongSoKhongCoTen() {
        assertThat(questions())
                .anyMatch(text -> text.contains("10_000_000") && text.contains("nghiệp vụ quy định"));
        assertThat(spec.bySeverity(SpecQuestion.Severity.CLARIFY)).isNotEmpty();
    }

    @Test
    void phatHienGoiRaNgoaiTrongGiaoDich() {
        assertThat(questions())
                .anyMatch(text -> text.contains("có gọi ra ngoài hệ thống")
                        && text.contains("rollback"));
    }

    @Test
    void moiCauHoiDeuNoiRoViSaoKhongTuTraLoiDuoc() {
        assertThat(spec.questions()).isNotEmpty();
        for (SpecQuestion question : spec.questions()) {
            assertThat(question.question()).as("câu hỏi trống").isNotBlank();
            assertThat(question.reason())
                    .as("câu hỏi \"%s\" không nói vì sao cần hỏi", question.question())
                    .isNotBlank();
        }
    }

    @Test
    void cauHoiDanToiDanChungDeuDanDungMaCoThat() {
        for (SpecQuestion question : spec.questions()) {
            for (String id : question.evidenceIds()) {
                assertThat(spec.byId(id))
                        .as("câu hỏi dẫn tới mã không tồn tại: %s", id)
                        .isPresent();
            }
        }
    }

    @Test
    void banGonChoModelKhongChuaSoDong() {
        // Model khong duoc thay file:line, neu thay no se co xu huong tu go lai vao van ban
        String promptText = SpecReportRenderer.promptText(spec);

        assertThat(promptText).contains("E1").contains("DỮ KIỆN ĐỌC ĐƯỢC TỪ CODE");
        assertThat(promptText).doesNotContain(".java:");
    }

    private static List<String> statements(CodeEvidence.Kind kind) {
        return spec.byKind(kind).stream().map(CodeEvidence::statement).toList();
    }

    private static List<String> questions() {
        return spec.questions().stream().map(SpecQuestion::question).toList();
    }
}
