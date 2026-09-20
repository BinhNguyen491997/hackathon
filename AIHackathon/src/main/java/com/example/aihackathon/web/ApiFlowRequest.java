package com.example.aihackathon.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body cho POST /api/analyze/api-flow và /api/analyze/spec.
 *
 * @param repoUrl    URL HTTPS của repo GitLab
 * @param branch     branch cần đọc; để trống thì dùng analysis.git.default-branch (master)
 * @param httpMethod GET/POST/PUT/DELETE/PATCH; để trống khi tìm theo summary
 * @param path       path của API, ví dụ /api/orders/{id}
 * @param summary    từ khoá khớp @Operation(summary) - phương án dự phòng khi không biết path
 * @param useAi      false (mặc định) = chỉ phân tích tĩnh, không gọi LLM, không tốn token, kết quả
 *                   tất định. true = thêm phần diễn giải và suy luận do LLM viết, được tách riêng
 *                   và gắn nhãn rõ trong tài liệu; phần dẫn chứng vẫn do phân tích tĩnh sinh ra.
 *                   Ở chế độ này LLM chỉ nhận danh sách dữ kiện, KHÔNG nhận source code.
 * @param crossCheckWithAi false (mặc định) = không đối chiếu chéo. true = cho LLM tự đọc source và
 *                   dựng call graph riêng, rồi so với kết quả của parser và chỉ ra chỗ lệch.
 *                   <b>Cờ này GỬI NGUYÊN VĂN SOURCE CODE tới nhà cung cấp model</b>, nên nó tách
 *                   riêng khỏi useAi thay vì gộp chung: hai việc có mức rủi ro khác nhau.
 */
public record ApiFlowRequest(

        @NotBlank(message = "repoUrl không được để trống")
        @Size(max = 500)
        String repoUrl,

        @Size(max = 200)
        String branch,

        @Pattern(regexp = "(?i)|GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|ANY",
                message = "httpMethod chỉ nhận GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS")
        String httpMethod,

        @Size(max = 500)
        String path,

        @Size(max = 300)
        String summary,

        Boolean useAi,

        Boolean crossCheckWithAi) {

    public boolean hasPath() {
        return this.path != null && !this.path.isBlank();
    }

    public boolean hasSummary() {
        return this.summary != null && !this.summary.isBlank();
    }

    /** Mặc định KHÔNG dùng AI: người gọi phải chủ động bật, không bị tốn token ngoài ý muốn. */
    public boolean aiEnabled() {
        return Boolean.TRUE.equals(this.useAi);
    }

    /** Mặc định KHÔNG bật: đây là cờ làm source code rời khỏi hạ tầng của bạn. */
    public boolean crossCheckEnabled() {
        return Boolean.TRUE.equals(this.crossCheckWithAi);
    }

    /** Path nếu có, không thì từ khoá mô tả. */
    public String target() {
        return hasPath() ? this.path : this.summary;
    }
}
