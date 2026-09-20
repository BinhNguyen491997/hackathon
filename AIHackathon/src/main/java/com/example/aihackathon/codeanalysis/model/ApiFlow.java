package com.example.aihackathon.codeanalysis.model;

import java.util.List;

/**
 * Kết quả phân tích một endpoint: cây luồng + danh sách participant + những gì không chắc chắn.
 *
 * @param unresolved   các chỗ không suy ra được, để BA biết diagram còn lỗ hổng ở đâu
 * @param warnings     cảnh báo về giới hạn phân tích (chạm max-depth, nhiều implementation...)
 * @param databaseName tên database mà tầng repository nói chuyện với; đi kèm luồng để mọi renderer
 *                     gọi đúng tên nghiệp vụ thay vì chỉ nêu tên class repository
 */
public record ApiFlow(
        ApiEndpoint endpoint,
        List<Participant> participants,
        List<FlowNode> nodes,
        List<String> unresolved,
        List<String> warnings,
        String repoUrl,
        String branch,
        String commitSha,
        String databaseName) {

    public ApiFlow {
        participants = List.copyOf(participants);
        nodes = List.copyOf(nodes);
        unresolved = List.copyOf(unresolved);
        warnings = List.copyOf(warnings);
    }

    /**
     * Một lớp tham gia luồng.
     *
     * @param alias id dùng trong file .puml (chỉ chữ, số, gạch dưới)
     * @param note  thông tin thêm hiện dưới participant: bảng DB, tên interface gốc...
     */
    public record Participant(String alias, String displayName, String typeFqn, ParticipantKind kind, String note) {
    }
}
