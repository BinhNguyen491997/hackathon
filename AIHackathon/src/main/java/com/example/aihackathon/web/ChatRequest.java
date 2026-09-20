package com.example.aihackathon.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body cho POST /api/chat.
 *
 * @param conversationId để trống thì server tự sinh; giữ nguyên giá trị này ở các
 *                       lượt sau để agent nhớ được ngữ cảnh.
 * @param message        câu hỏi của người dùng
 */
public record ChatRequest(
        String conversationId,

        @NotBlank(message = "message không được để trống")
        @Size(max = 8000, message = "message quá dài (tối đa 8000 ký tự)")
        String message) {
}
