package com.example.aihackathon.codeanalysis.model;

/**
 * Một dữ kiện đọc được từ code, kèm vị trí chính xác trong repo.
 *
 * <p>Điểm cốt tử: {@code sourceFile} và {@code line} do parser sinh ra, KHÔNG bao giờ do model
 * ngôn ngữ viết. Nếu để model tự dẫn nguồn, nó sẽ bịa số dòng nghe rất thuyết phục - và người
 * đọc tài liệu (BA) không có cách nào phát hiện. Model chỉ được phép tham chiếu tới {@code id},
 * còn ánh xạ id -&gt; file:line thuộc về phía tất định.
 *
 * @param id        mã ngắn để trích dẫn trong văn bản, dạng E1, E2...
 * @param kind      loại dữ kiện, quyết định nó được đưa vào mục nào của tài liệu
 * @param statement phát biểu ngắn, đã diễn giải sang ngôn ngữ nghiệp vụ nhưng chưa suy luận thêm
 * @param snippet   nguyên văn đoạn code, để người đọc đối chiếu mà không cần mở IDE
 */
public record CodeEvidence(
        String id,
        Kind kind,
        String statement,
        String sourceFile,
        int line,
        String snippet) {

    /** Loại dữ kiện. Thứ tự enum cũng là thứ tự trình bày trong tài liệu. */
    public enum Kind {

        ENDPOINT("Điểm vào API"),
        SECURITY("Điều kiện phân quyền"),
        REQUEST_FIELD("Trường dữ liệu đầu vào"),
        VALIDATION("Quy tắc kiểm tra dữ liệu"),
        BUSINESS_RULE("Quy tắc nghiệp vụ trong code"),
        BRANCH("Điều kiện rẽ nhánh"),
        DATA_ACCESS("Truy cập dữ liệu"),
        STORED_PROCEDURE("Stored procedure / package database"),
        EXTERNAL_CALL("Gọi ra ngoài hệ thống"),
        TRANSACTION("Phạm vi giao dịch"),
        ERROR_MAPPING("Mã lỗi trả về"),
        ERROR_PATH("Nhánh lỗi");

        private final String title;

        Kind(String title) {
            this.title = title;
        }

        public String title() {
            return this.title;
        }
    }

    /** Dạng dùng để trích dẫn trong tài liệu: {@code [E3] src/.../OrderService.java:42}. */
    public String citation() {
        return "[" + this.id + "] " + this.sourceFile + ":" + this.line;
    }
}
