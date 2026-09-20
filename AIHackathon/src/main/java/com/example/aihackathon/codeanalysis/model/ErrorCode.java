package com.example.aihackathon.codeanalysis.model;

/**
 * Một mã lỗi mà <b>endpoint đang xét</b> có thể trả về.
 *
 * <p>Đây KHÔNG phải bảng mã lỗi của cả source. Bảng mã lỗi hệ thống thường có vài chục hằng (repo
 * thật: 53), và liệt kê hết ra là làm hại tài liệu: người đọc đặc tả một API không có cách nào biết
 * mã nào thật sự liên quan tới API đó. Danh sách này chỉ gồm mã <b>đi tới được</b> từ endpoint, và
 * mỗi mã nói rõ đi tới bằng đường nào qua {@link Origin}.
 *
 * <p>Bốn đường đi tới, xếp theo thứ tự từ chắc chắn nhất:
 * <ol>
 *   <li>{@link Origin#THROWN} - code trong luồng ném tường minh mã này
 *   <li>{@link Origin#VALIDATION} - request không thoả ràng buộc, handler validation trả mã này
 *   <li>{@link Origin#HANDLER} - exception ném trong luồng bị handler tập trung bắt và đổi thành mã này
 *   <li>{@link Origin#CATCH_ALL} - handler bắt tất cả, áp dụng cho mọi lỗi ngoài dự kiến
 * </ol>
 *
 * <p>Tất định hoàn toàn. Model ngôn ngữ không được sinh ra mã lỗi: bịa một mã lỗi là dạng bịa tệ nhất
 * với tài liệu tích hợp, vì đối tác sẽ viết code theo nó.
 *
 * @param code       mã số nghiệp vụ nếu khai báo có, ví dụ {@code 1025}; null khi không đọc được
 * @param message    thông điệp lỗi, giữ nguyên cả placeholder {@code %s} vì đó là thứ có trong code
 * @param trigger    vì sao endpoint này có thể trả về mã đó
 * @param sourceFile vị trí khai báo hằng mã lỗi
 */
public record ErrorCode(
        String name,
        String declaringType,
        String code,
        String message,
        String httpStatus,
        Origin origin,
        String trigger,
        String sourceFile,
        int line) {

    /** Thứ tự enum cũng là thứ tự trình bày: chắc chắn nhất lên trước. */
    public enum Origin {

        THROWN("Ném tường minh trong luồng"),

        VALIDATION("Dữ liệu vào không thoả ràng buộc"),

        HANDLER("Exception trong luồng bị handler tập trung đổi thành mã này"),

        CATCH_ALL("Lỗi ngoài dự kiến - handler bắt tất cả");

        private final String title;

        Origin(String title) {
            this.title = title;
        }

        public String title() {
            return this.title;
        }
    }

    /** Nhãn gọn: {@code 1025 CALL_EVENT_PROCESS_ONE}. */
    public String label() {
        return (this.code == null || this.code.isBlank() ? "" : this.code + " ") + this.name;
    }
}
