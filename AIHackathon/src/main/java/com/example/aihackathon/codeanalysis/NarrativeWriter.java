package com.example.aihackathon.codeanalysis;

/**
 * Nguồn cung phần văn xuôi cho tài liệu đặc tả.
 *
 * <p>Đây là interface thuần, không import bất cứ thứ gì của Spring AI. Nhờ vậy package
 * {@code codeanalysis} giữ được tính chất quan trọng nhất của nó: phần dựng call graph và thu dẫn
 * chứng hoàn toàn tất định, không phụ thuộc model ngôn ngữ, và test được mà không cần API key.
 * Bản hiện thực dùng LLM nằm ở package khác.
 */
public interface NarrativeWriter {

    /** Không dùng AI: tài liệu chỉ gồm phần dữ kiện tất định. */
    NarrativeWriter NONE = (promptText, feedback) -> null;

    /**
     * @param promptText bản gọn gồm dữ kiện (kèm mã [En]) và các điểm cần xác nhận
     * @param feedback   null ở lần đầu; ở lần thử lại là mô tả lỗi truy vết của lần trước, để bên
     *                   viết tự sửa thay vì lặp lại đúng lỗi đó
     * @return văn bản mô tả, hoặc null nếu không tạo được (thiếu cấu hình, lỗi mạng...)
     */
    String write(String promptText, String feedback);
}
