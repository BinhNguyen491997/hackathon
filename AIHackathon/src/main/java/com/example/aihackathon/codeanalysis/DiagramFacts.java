package com.example.aihackathon.codeanalysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.CodeEvidence;
import com.example.aihackathon.codeanalysis.model.CodeSpec;
import com.example.aihackathon.codeanalysis.model.FlowNode;
import com.example.aihackathon.codeanalysis.model.ParticipantKind;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse;

/**
 * Những dữ kiện mà cả hai sơ đồ (.puml và Mermaid) đều phải nói giống nhau.
 *
 * <p>Lý do tồn tại của lớp này là chống trôi dạt. Trước đây chỉ {@link PlantUmlRenderer} biết cách
 * suy ra "save() nghĩa là ghi dữ liệu trên bảng orders" hay "exception này ứng với HTTP 404", nên
 * bản Mermaid nghèo hơn hẳn. Chép logic sang là cách chắc chắn nhất để hai hình dần dần nói khác
 * nhau về cùng một commit. Ở đây quyết định <em>nói gì</em>; mỗi renderer tự lo <em>viết thế nào</em>
 * (cú pháp, escape, màu).
 *
 * <p>Không trả về chuỗi đã escape: escape là việc của từng định dạng và hai định dạng escape khác
 * nhau ({@code #60;} của Mermaid vs {@code &lt;} của creole).
 */
final class DiagramFacts {

    /** Số điều kiện tiền đề in ra tối đa; phần còn lại chỉ đếm và trỏ về tài liệu đặc tả. */
    static final int MAX_PRECONDITIONS = 12;

    private final ApiFlow flow;

    /** Có thể null: khi đó sơ đồ vẫn vẽ được, chỉ thiếu điều kiện tiền đề, mã lỗi và procedure. */
    private final CodeSpec spec;

    private DiagramFacts(ApiFlow flow, CodeSpec spec) {
        this.flow = flow;
        this.spec = spec;
    }

    static DiagramFacts of(ApiFlow flow, CodeSpec spec) {
        return new DiagramFacts(flow, spec);
    }

    boolean hasRepository() {
        return this.flow.participants().stream()
                .anyMatch(participant -> participant.kind() == ParticipantKind.REPOSITORY);
    }

    String databaseName() {
        String name = this.flow.databaseName();
        return (name == null || name.isBlank()) ? "Database" : name;
    }

    /**
     * Điều kiện phải thoả TRƯỚC khi luồng nghiệp vụ bắt đầu: phân quyền và kiểm tra dữ liệu vào.
     *
     * <p>Những thứ này không phải lời gọi nên không xuất hiện trong cây luồng, nhưng với BA thì
     * chúng là phần đặc tả quan trọng: "ai được gọi" và "dữ liệu nào bị từ chối ngay".
     */
    List<String> preconditionLines() {
        if (this.spec == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        this.spec.byKind(CodeEvidence.Kind.SECURITY)
                .forEach(item -> lines.add("[phân quyền] " + item.statement()));
        this.spec.byKind(CodeEvidence.Kind.VALIDATION)
                .forEach(item -> lines.add("[kiểm tra dữ liệu] " + item.statement()));
        return lines;
    }

    /**
     * Stored procedure ứng với một lời gọi repository/DAO.
     *
     * <p>Khớp theo {@code TênLớp.tênMethod()} vì đó là thứ {@code StoredProcedureScanner} ghi vào
     * {@code calledIn}. Một method Java gọi nhiều procedure thì lấy cái đầu tiên.
     */
    Optional<StoredProcedureUse> procedureFor(FlowNode.Call call) {
        if (this.spec == null) {
            return Optional.empty();
        }
        String where = call.target().typeSimpleName() + "." + call.target().methodName() + "()";
        return this.spec.procedures().stream()
                .filter(use -> use.calledIn().equals(where))
                .findFirst();
    }

    /**
     * Loại thao tác trên database, suy từ tên method theo quy ước Spring Data.
     *
     * <p>Chỉ kết luận khi tên method có tiền tố rõ ràng. Không khớp gì thì dùng nhãn trung tính
     * "thao tác dữ liệu" chứ KHÔNG mặc định là đọc: những method như {@code reserveStock} là ghi,
     * gán nhãn đọc cho nó là nói sai chiều tác động - đúng loại sai mà tài liệu này phải tránh.
     */
    String databaseOperation(FlowNode.Call call) {
        // Procedure là thao tác thật chạy trên database. Nhãn chỉ mang TÊN NGẮN; package đầy đủ và
        // danh sách tham số nằm ở bảng trong note ngay bên dưới, để nhãn mũi tên còn đọc được.
        Optional<StoredProcedureUse> procedure = procedureFor(call);
        if (procedure.isPresent()) {
            return "gọi procedure " + procedure.get().routineName();
        }
        String note = call.note();
        if (note != null && note.contains("query:")) {
            int start = note.indexOf("query:");
            return note.substring(start + "query:".length()).trim();
        }
        String method = call.target().methodName().toLowerCase(Locale.ROOT);
        String table = tableFrom(call.target().typeFqn());
        String suffix = table == null ? "" : " trên " + table;
        String signature = " (" + call.target().methodName() + ")";

        if (startsWithAny(method, "save", "insert", "persist", "create", "add", "store")) {
            return "ghi dữ liệu" + suffix + signature;
        }
        if (startsWithAny(method, "update", "merge", "modify", "set")) {
            return "cập nhật dữ liệu" + suffix + signature;
        }
        if (startsWithAny(method, "delete", "remove", "purge", "truncate")) {
            return "xoá dữ liệu" + suffix + signature;
        }
        if (startsWithAny(method, "count", "exists")) {
            return "đếm/kiểm tra tồn tại" + suffix + signature;
        }
        if (startsWithAny(method, "find", "get", "select", "query", "read", "search", "list",
                "fetch", "load", "stream")) {
            return "đọc dữ liệu" + suffix + signature;
        }
        return "thao tác dữ liệu (chưa suy được đọc hay ghi)" + suffix + signature;
    }

    /** Dữ liệu chảy ngược từ database về repository. */
    String dataResultLabel(FlowNode.Call call) {
        // Procedure có tham số OUT: đó chính là dữ liệu chảy ngược về. Một tham số thì nêu tên,
        // nhiều thì chỉ nêu số lượng - chi tiết đã có trong bảng, nhắc lại làm nhãn dài.
        Optional<StoredProcedureUse> procedure = procedureFor(call);
        if (procedure.isPresent() && !procedure.get().outputs().isEmpty()) {
            List<StoredProcedureUse.Argument> outputs = procedure.get().outputs();
            return outputs.size() == 1
                    ? "trả về " + outputs.get(0).label()
                    : "trả về " + outputs.size() + " tham số ra (xem bảng)";
        }
        String returnType = call.target().returnType();
        if (returnType == null || returnType.isBlank() || "void".equals(returnType)) {
            return "kết quả thao tác";
        }
        return returnType;
    }

    /**
     * Kiểu trả về của lời gọi, để đọc sơ đồ không phải mở IDE tra chữ ký method.
     *
     * @return kiểu trả về, {@code "(void)"} khi là void, hoặc null khi không biết
     */
    String returnType(FlowNode.Call call) {
        String returnType = call.target().returnType();
        if (returnType == null || returnType.isBlank()) {
            return null;
        }
        return "void".equals(returnType) ? "(void)" : returnType;
    }

    /**
     * Tra mã HTTP của exception được ném, dựa trên dẫn chứng ERROR_MAPPING đã thu tất định.
     *
     * <p>Đây là câu BA hỏi nhiều nhất về nhánh lỗi - "client nhận được gì" - và nó không nằm trong
     * cây luồng vì mapping do {@code @ResponseStatus}/{@code @ExceptionHandler} quyết định.
     */
    Optional<String> httpStatusFor(String throwDetail) {
        if (this.spec == null || throwDetail == null) {
            return Optional.empty();
        }
        for (CodeEvidence item : this.spec.byKind(CodeEvidence.Kind.ERROR_MAPPING)) {
            String statement = item.statement();
            int space = statement.indexOf(' ');
            if (space <= 0) {
                continue;
            }
            String exception = statement.substring(0, space);
            if (throwDetail.contains(exception)) {
                int http = statement.indexOf("HTTP ");
                if (http >= 0) {
                    return Optional.of(statement.substring(http + "HTTP ".length()).trim());
                }
            }
        }
        return Optional.empty();
    }

    /** Ghi chú của participant repository mang dạng "bảng: orders" hoặc "entity: Customer". */
    private String tableFrom(String repositoryFqn) {
        return this.flow.participants().stream()
                .filter(participant -> participant.typeFqn().equals(repositoryFqn))
                .map(ApiFlow.Participant::note)
                .filter(note -> note != null && !note.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
