package com.example.aihackathon.codeanalysis.model;

import java.util.List;

/**
 * Một điểm KHÔNG kết luận được chỉ bằng đọc code, cần hỏi lại developer.
 *
 * <p>Đây là phần quan trọng nhất của tài liệu sinh tự động. Code cho biết hệ thống đang
 * <em>làm</em> gì, nhưng không cho biết <em>vì sao</em> và <em>có đúng ý</em> không. Một con số
 * 10_000_000 trong code là dữ kiện; "ngưỡng đơn giá trị cao là 10 triệu" là suy luận; còn
 * "ngưỡng này do nghiệp vụ quy định hay lập trình viên tự đặt" thì chỉ dev trả lời được.
 *
 * <p>Danh sách này được sinh tất định từ các dấu hiệu trong code, không do model ngôn ngữ nghĩ ra,
 * nên nó không thể bị bỏ sót vì model "quên" cảnh báo.
 *
 * @param question    câu hỏi cụ thể để mang đi hỏi dev, không phải nhận xét chung
 * @param reason      vì sao đọc code không trả lời được câu này
 * @param evidenceIds các dữ kiện liên quan, để dev biết đang nói về chỗ nào
 * @param severity    mức ưu tiên khi đi xác nhận
 */
public record SpecQuestion(
        String question,
        String reason,
        List<String> evidenceIds,
        Severity severity) {

    public SpecQuestion {
        evidenceIds = List.copyOf(evidenceIds);
    }

    public enum Severity {

        /** Sai hiểu ở đây là sai cả đặc tả: luồng chạy thật có thể khác hẳn tài liệu. */
        BLOCKING("Phải xác nhận trước khi dùng tài liệu"),

        /** Ảnh hưởng tới nội dung một phần của đặc tả. */
        IMPORTANT("Nên xác nhận"),

        /** Chủ yếu để làm rõ ý nghĩa nghiệp vụ, không làm sai luồng. */
        CLARIFY("Làm rõ thêm");

        private final String title;

        Severity(String title) {
            this.title = title;
        }

        public String title() {
            return this.title;
        }
    }
}
