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
 * @param gitToken   personal access token GitLab (scope read_repository) của chính người gọi.
 *                   Để trống thì dùng cấu hình server ({@code GITLAB_TOKEN} hoặc
 *                   {@code GIT_USERNAME}+{@code GIT_PASSWORD}). Truyền vào đây thì quyền đọc repo
 *                   đúng bằng quyền của người gọi, và server không cần giữ credential nào.
 *                   <b>Chỉ nhận qua body của POST, không bao giờ qua query param</b> (URL bị ghi
 *                   vào access log). Giá trị này không được log, không trả lại trong response và
 *                   không ghi vào file báo cáo.
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

        Boolean crossCheckWithAi,

        // CỐ TÌNH không có @Size/@Pattern ở đây: khi bean validation fail, Spring đưa "rejected
        // value" vào message của MethodArgumentNotValidException và message đó đi vào log. Một ràng
        // buộc trên trường này đổi lấy việc token nằm nguyên văn trong log - không đáng.
        String gitToken) {

    public boolean hasPath() {
        return this.path != null && !this.path.isBlank();
    }

    public boolean hasSummary() {
        return this.summary != null && !this.summary.isBlank();
    }

    /** Có token của người gọi hay không - dùng để log, không bao giờ log giá trị. */
    public boolean hasGitToken() {
        return this.gitToken != null && !this.gitToken.isBlank();
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

    /**
     * toString của record in ra mọi field, kể cả token - chặn sẵn ở đây.
     *
     * <p>Không phải phòng xa: chỉ cần một dòng {@code log.info("request={}", request)} lúc debug
     * là token của người dùng nằm trong log, và log thường được gom về nơi nhiều người đọc được.
     */
    @Override
    public String toString() {
        return "ApiFlowRequest[repoUrl=%s, branch=%s, httpMethod=%s, path=%s, summary=%s, "
                .formatted(this.repoUrl, this.branch, this.httpMethod, this.path, this.summary)
                + "useAi=%s, crossCheckWithAi=%s, gitToken=%s]"
                        .formatted(this.useAi, this.crossCheckWithAi,
                                hasGitToken() ? "***" : "(trống)");
    }
}
