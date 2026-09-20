package com.example.aihackathon.codeanalysis.model;

import java.util.List;
import java.util.Optional;

/**
 * Tập dữ kiện đọc được từ code cho một endpoint, kèm những điểm cần xác nhận với developer.
 *
 * <p>Đây là ranh giới trách nhiệm của hệ thống: phần {@code evidence} và {@code questions} do phân
 * tích tĩnh sinh ra và không thể sai về mặt vị trí; phần diễn giải thành văn xuôi mới là việc của
 * model ngôn ngữ, và nó chỉ được tham chiếu tới {@code id} trong đây.
 *
 * @param procedures các stored procedure/package database mà endpoint gọi tới. Tách thành danh
 *                   sách riêng bên cạnh {@code evidence} vì tài liệu cần một bảng gọn "API này
 *                   dùng những procedure nào", chứ không chỉ các dòng dẫn chứng rải rác; mỗi phần
 *                   tử vẫn có dẫn chứng tương ứng trong {@code evidence}.
 * @param errorCodes các mã lỗi mà <b>endpoint này</b> có thể trả về, kèm lý do đi tới được. KHÔNG
 *                   phải bảng mã lỗi của cả source: bảng đó thường vài chục hằng và hầu hết không
 *                   liên quan tới endpoint đang xét.
 */
public record CodeSpec(
        ApiEndpoint endpoint,
        List<CodeEvidence> evidence,
        List<SpecQuestion> questions,
        List<StoredProcedureUse> procedures,
        List<ErrorCode> errorCodes) {

    public CodeSpec {
        evidence = List.copyOf(evidence);
        questions = List.copyOf(questions);
        procedures = List.copyOf(procedures);
        errorCodes = List.copyOf(errorCodes);
    }

    public Optional<CodeEvidence> byId(String id) {
        return this.evidence.stream().filter(item -> item.id().equals(id)).findFirst();
    }

    public List<CodeEvidence> byKind(CodeEvidence.Kind kind) {
        return this.evidence.stream().filter(item -> item.kind() == kind).toList();
    }

    public List<SpecQuestion> bySeverity(SpecQuestion.Severity severity) {
        return this.questions.stream().filter(item -> item.severity() == severity).toList();
    }

    /** Có điểm nào nghiêm trọng tới mức không nên dùng tài liệu trước khi hỏi dev hay không. */
    public boolean hasBlockingQuestions() {
        return !bySeverity(SpecQuestion.Severity.BLOCKING).isEmpty();
    }

    /**
     * Các package database mà endpoint chạm tới, đã gộp trùng và giữ thứ tự xuất hiện.
     *
     * <p>Procedure gọi bằng tên trần (không xác định được package) KHÔNG được đoán vào package nào -
     * nó chỉ nằm trong {@link #procedures()} kèm câu hỏi cho dev.
     */
    public List<String> databasePackages() {
        return this.procedures.stream()
                .filter(StoredProcedureUse::hasPackage)
                .map(StoredProcedureUse::packageName)
                .distinct()
                .toList();
    }

    /** Các mã lỗi đi tới được bằng một đường cụ thể (ném tường minh, validation, handler...). */
    public List<ErrorCode> errorCodesFrom(ErrorCode.Origin origin) {
        return this.errorCodes.stream().filter(code -> code.origin() == origin).toList();
    }
}
