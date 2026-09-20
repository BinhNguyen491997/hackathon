package com.example.aihackathon.codeanalysis.model;

import java.util.List;
import java.util.Locale;

/**
 * Một stored procedure / function trong database mà endpoint gọi tới, kèm package chứa nó và
 * danh sách tham số vào/ra.
 *
 * <p>Đây là điểm mù lớn nhất của phân tích tĩnh source Java: với hệ thống chạy trên Oracle, phần
 * lớn nghiệp vụ nằm trong package PL/SQL chứ không nằm trong code Java. Java chỉ có một dòng
 * {@code {call PKG_SETTLEMENT.POST_ENTRY(?)}}, còn quy tắc thật nằm sau cái tên đó. Tài liệu không
 * nói ra chỗ này thì người đọc sẽ tưởng luồng đã được mô tả đầy đủ.
 *
 * <p>Vì vậy record này chỉ khẳng định thứ đọc được từ Java: <b>tên</b> procedure/package,
 * <b>tham số</b> truyền vào/nhận về, và <b>chỗ gọi</b>. Nội dung bên trong procedure KHÔNG có ở
 * đây, và {@code EvidenceCollector} sinh sẵn câu hỏi để mang đi hỏi dev/DBA.
 *
 * @param packageName tên package (Oracle) hoặc catalog/schema chứa routine; null khi code chỉ gọi
 *                    tên trần và không xác định được nó thuộc package nào
 * @param routineName tên procedure/function
 * @param callStyle   cách Java gọi tới nó - quyết định mức độ tin cậy của tên đọc được
 * @param arguments   tham số vào/ra đọc được từ phía Java, theo đúng thứ tự khai báo
 * @param calledIn    lớp và method Java chứa lời gọi, dạng {@code SettlementDao.postEntry()}
 * @param sourceFile  file Java chứa lời gọi, tương đối so với gốc repo
 * @param line        dòng của lời gọi
 * @param snippet     nguyên văn đoạn code, để đối chiếu mà không cần mở IDE
 */
public record StoredProcedureUse(
        String packageName,
        String routineName,
        CallStyle callStyle,
        List<Argument> arguments,
        String calledIn,
        String sourceFile,
        int line,
        String snippet) {

    public StoredProcedureUse {
        arguments = List.copyOf(arguments);
    }

    /**
     * Cách Java gọi tới routine.
     *
     * <p>Thứ tự enum cũng là thứ tự trình bày trong tài liệu, và xếp theo mức "tên đọc được có
     * chắc là tên thật trong database hay không": {@code @Procedure(procedureName=...)} là chắc
     * nhất, còn {@link #PLSQL_BLOCK} thì tên chỉ là thứ xuất hiện đầu tiên trong khối PL/SQL.
     */
    public enum CallStyle {

        SPRING_DATA_PROCEDURE("@Procedure của Spring Data JPA"),

        NAMED_STORED_PROCEDURE("@NamedStoredProcedureQuery trên entity"),

        JPA_STORED_PROCEDURE_QUERY("EntityManager.createStoredProcedureQuery"),

        SIMPLE_JDBC_CALL("SimpleJdbcCall của Spring JDBC"),

        CALLABLE_STATEMENT("CallableStatement / prepareCall - cú pháp {call ...}"),

        NATIVE_QUERY("@Query nativeQuery gọi procedure"),

        MYBATIS_CALLABLE("Annotation MyBatis dạng {call ...}"),

        PLSQL_BLOCK("Khối PL/SQL BEGIN ... END gửi thẳng xuống database");

        private final String title;

        CallStyle(String title) {
            this.title = title;
        }

        public String title() {
            return this.title;
        }
    }

    /**
     * Một tham số của routine, đọc từ phía Java.
     *
     * <p>Chiều (IN/OUT) là thông tin quan trọng nhất ở đây và cũng là thứ dễ sai nhất: chỉ
     * {@code @StoredProcedureParameter(mode = ...)}, {@code registerStoredProcedureParameter} và
     * {@code SqlOutParameter}/{@code registerOutParameter} nói rõ chiều. Các cách gọi khác chỉ cho
     * biết Java truyền gì xuống, nên chiều được ghi là {@link Direction#IN} - đúng với điều quan sát
     * được, không phải suy đoán về chữ ký thật trong database.
     *
     * @param name      tên tham số, hoặc {@code ?1}, {@code ?2} khi code chỉ dùng vị trí
     * @param direction chiều dữ liệu đọc được từ Java
     * @param type      kiểu đọc được (kiểu Java hoặc hằng {@code java.sql.Types}); null khi không biết
     * @param hint      tên biến Java được gán vào tham số này, ví dụ {@code contractNumber} từ
     *                  {@code setParameter(2, request.contractNumber)}. Tách riêng khỏi {@code name}
     *                  vì đây KHÔNG phải tên tham số trong database - chỉ là gợi ý để người đọc biết
     *                  vị trí đó mang dữ liệu gì. null khi không có.
     */
    public record Argument(String name, Direction direction, String type, String hint) {

        public Argument(String name, Direction direction, String type) {
            this(name, direction, type, null);
        }

        public enum Direction {

            IN("vào"),
            OUT("ra"),
            INOUT("vào-ra"),
            RETURN("giá trị trả về");

            private final String title;

            Direction(String title) {
                this.title = title;
            }

            public String title() {
                return this.title;
            }
        }

        /** Vị trí nếu tham số chỉ được nêu bằng số thứ tự: {@code ?5} -&gt; "5". */
        public String position() {
            if (this.name == null || !this.name.startsWith("?") || this.name.length() < 2) {
                return null;
            }
            String rest = this.name.substring(1);
            return rest.chars().allMatch(Character::isDigit) ? rest : null;
        }

        /** Tên để hiển thị: tên thật nếu có, không thì tên biến Java gợi ý. */
        public String displayName() {
            return position() == null ? this.name : this.hint;
        }

        /** Dạng ngắn để in trong bảng/diagram: {@code p_id: Long}, {@code ?2 (contractNumber): String}. */
        public String label() {
            StringBuilder out = new StringBuilder(this.name);
            if (this.hint != null && !this.hint.isBlank() && !this.hint.equals(this.name)) {
                out.append(" (").append(this.hint).append(')');
            }
            if (this.type != null && !this.type.isBlank()) {
                out.append(": ").append(this.type);
            }
            return out.toString();
        }
    }

    public boolean hasPackage() {
        return this.packageName != null && !this.packageName.isBlank();
    }

    /** Tên đầy đủ như DBA sẽ tra: {@code PKG_SETTLEMENT.POST_ENTRY}. */
    public String qualifiedName() {
        return hasPackage() ? this.packageName + "." + this.routineName : this.routineName;
    }

    public List<Argument> inputs() {
        return this.arguments.stream()
                .filter(argument -> argument.direction() == Argument.Direction.IN
                        || argument.direction() == Argument.Direction.INOUT)
                .toList();
    }

    /** Tham số ra, gồm cả INOUT và giá trị trả về - tất cả đều là dữ liệu chảy ngược về Java. */
    public List<Argument> outputs() {
        return this.arguments.stream()
                .filter(argument -> argument.direction() != Argument.Direction.IN)
                .toList();
    }

    /**
     * Chữ ký gọn để in trên mũi tên diagram: {@code PKG.POST_ENTRY(p_id: Long) -> p_result: VARCHAR}.
     *
     * <p>Không có tham số nào đọc được thì chỉ in tên kèm {@code (?)} - nói rõ là chưa đọc được,
     * khác hẳn với việc in {@code ()} như thể procedure không có tham số.
     */
    public String signature() {
        String in = this.arguments.isEmpty()
                ? "?"
                : String.join(", ", inputs().stream().map(Argument::label).toList());
        String out = String.join(", ", outputs().stream().map(Argument::label).toList());
        return qualifiedName() + "(" + in + ")" + (out.isEmpty() ? "" : " -> " + out);
    }

    /**
     * Khoá gộp trùng.
     *
     * <p>Có vị trí trong khoá là cố ý: cùng một procedure được gọi ở hai chỗ khác nhau là hai dữ
     * kiện khác nhau (hai chỗ cần kiểm tra), nhưng cùng một lời gọi bị hai luật nhận dạng bắt được
     * thì chỉ nên hiện một lần.
     */
    public String key() {
        return qualifiedName().toUpperCase(Locale.ROOT) + "|" + this.callStyle + "|"
                + this.sourceFile + ":" + this.line;
    }
}
