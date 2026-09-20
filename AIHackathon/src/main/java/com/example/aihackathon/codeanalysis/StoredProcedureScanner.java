package com.example.aihackathon.codeanalysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.aihackathon.codeanalysis.JavaSourceIndex.IndexedType;
import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.FlowNode;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse.Argument;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse.Argument.Direction;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse.CallStyle;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tìm các stored procedure / package database mà một endpoint gọi tới, kèm tham số vào/ra.
 *
 * <p>Vì sao cần lớp này: với hệ thống chạy trên Oracle (Way4, core thẻ, core banking), phần lớn
 * nghiệp vụ nằm trong package PL/SQL. Phía Java chỉ còn một dòng gọi, nên sequence diagram dựng từ
 * AST Java sẽ dừng ở tầng repository/DAO và tài liệu trông như thể luồng đã được mô tả đầy đủ.
 * Nêu tên procedure và tham số ra là cách duy nhất để người đọc biết còn một tầng logic nữa mà tài
 * liệu này KHÔNG nhìn thấy, và biết dữ liệu nào chảy qua ranh giới đó.
 *
 * <p>Toàn bộ lớp này tất định và chỉ đọc ở mức cú pháp - cùng lý do như {@link Annotations}: bản
 * clone shallow không có jar của Spring/JPA nên symbol solver không resolve được annotation.
 *
 * <p><b>Phạm vi quét:</b> chỉ những method Java nằm trong luồng của endpoint (method vào của
 * controller + mọi method là đích của một lời gọi trong cây {@link FlowNode}). Quét cả lớp sẽ kéo
 * theo procedure của những API khác dùng chung DAO - đúng kiểu nhiễu làm người đọc mất tin tưởng
 * vào tài liệu.
 */
final class StoredProcedureScanner {

    private static final Logger log = LoggerFactory.getLogger(StoredProcedureScanner.class);

    /**
     * Cú pháp escape của JDBC: {@code {call P(?)}} và {@code {? = call F(?)}}.
     *
     * <p>Đây là dạng chắc chắn nhất trong các dạng đọc từ chuỗi SQL: dấu ngoặc nhọn không xuất hiện
     * trong SQL thường, nên gần như không có dương tính giả.
     */
    private static final Pattern JDBC_ESCAPE = Pattern.compile(
            "\\{\\s*(?:\\?\\s*=\\s*)?call\\s+([A-Za-z_$][\\w$#.]*)", Pattern.CASE_INSENSITIVE);

    /**
     * {@code call PKG.P(...)} / {@code exec PKG.P} đứng đầu chuỗi, không có ngoặc nhọn.
     *
     * <p>Bắt buộc tên routine phải theo sau bởi {@code (}, {@code ;} hoặc hết chuỗi. Không có ràng
     * buộc này thì mọi câu log tiếng Anh bắt đầu bằng "Call " đều bị đọc thành SQL - đã gặp thật:
     * {@code log.info("Call method processEvent contractNo: {} ...")} cho ra một procedure tên
     * {@code METHOD}. Dạng bare call vốn đã là dạng yếu nhất trong ba dạng, nên phải siết chặt nhất.
     */
    private static final Pattern BARE_CALL = Pattern.compile(
            "^\\s*(?:call|exec|execute)\\s+([A-Za-z_$][\\w$#.]*)\\s*(?:\\(|;|$)",
            Pattern.CASE_INSENSITIVE);

    /** Placeholder của SLF4J. Có nó thì chuỗi là template log, không phải câu lệnh SQL. */
    private static final Pattern LOG_PLACEHOLDER = Pattern.compile("\\{}");

    /** Khối PL/SQL vô danh: {@code BEGIN PKG.P(:1); END;}. */
    private static final Pattern PLSQL_BLOCK = Pattern.compile(
            "\\bbegin\\b\\s+([A-Za-z_$][\\w$#.]*)\\s*[(;]", Pattern.CASE_INSENSITIVE);

    /** Tham số đặt tên trong câu SQL: {@code :id}, {@code :p_amount}. */
    private static final Pattern NAMED_PARAMETER = Pattern.compile(":([A-Za-z_$][\\w$]*)");

    /** Tham số theo vị trí kiểu Oracle: {@code :1}, {@code :2}. */
    private static final Pattern POSITIONAL_PARAMETER = Pattern.compile(":(\\d+)");

    /** Cặp (name, procedureName) trong {@code @NamedStoredProcedureQuery}. */
    private static final Pattern NAMED_PROCEDURE = Pattern.compile(
            "name\\s*=\\s*\"([^\"]+)\"[^)]*?procedureName\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);

    /** SimpleJdbcCall: tên routine. */
    private static final Set<String> ROUTINE_SETTERS = Set.of("withProcedureName", "withFunctionName");

    /**
     * SimpleJdbcCall: chỗ đặt tên package.
     *
     * <p>Với Oracle, {@code withCatalogName} chính là tên package - Spring đặt tên theo thuật ngữ
     * JDBC chung chứ không theo Oracle, nên đọc code dễ tưởng nó là tên database.
     */
    private static final Set<String> CATALOG_SETTERS = Set.of("withCatalogName", "withSchemaName");

    private static final Set<String> JPA_FACTORIES =
            Set.of("createStoredProcedureQuery", "createNamedStoredProcedureQuery");

    /** Nơi chuỗi {@code {call ...}} được truyền vào mà không nhất thiết là literal tại chỗ. */
    private static final Set<String> SQL_STRING_SINKS =
            Set.of("prepareCall", "execute", "call", "update", "query", "queryForObject", "createNativeQuery");

    /** Annotation mang câu SQL/lệnh gọi của MyBatis. */
    private static final Set<String> MYBATIS_ANNOTATIONS =
            Set.of("Select", "Update", "Insert", "Delete", "SelectKey");

    /** Lớp tham số của Spring JDBC -> chiều dữ liệu. Đây là nguồn duy nhất nói rõ chiều. */
    private static final Map<String, Direction> SQL_PARAMETER_TYPES = Map.of(
            "SqlParameter", Direction.IN,
            "SqlOutParameter", Direction.OUT,
            "SqlInOutParameter", Direction.INOUT,
            "SqlReturnResultSet", Direction.OUT);

    private final JavaSourceIndex index;

    private final Map<String, StoredProcedureUse> found = new LinkedHashMap<>();

    private StoredProcedureScanner(JavaSourceIndex index) {
        this.index = index;
    }

    static List<StoredProcedureUse> scan(JavaSourceIndex index, ApiFlow flow) {
        StoredProcedureScanner scanner = new StoredProcedureScanner(index);

        scanner.scanMethod(flow.endpoint().controllerFqn(), flow.endpoint().methodName());
        Set<String> targets = new LinkedHashSet<>();
        collectTargets(flow.nodes(), targets);
        for (String target : targets) {
            int separator = target.lastIndexOf('#');
            if (separator > 0) {
                scanner.scanMethod(target.substring(0, separator), target.substring(separator + 1));
            }
        }

        List<StoredProcedureUse> result = List.copyOf(scanner.found.values());
        if (!result.isEmpty()) {
            log.info("{} gọi tới {} procedure/function database: {}", flow.endpoint().label(),
                    result.size(), result.stream().map(StoredProcedureUse::signature).toList());
        }
        return result;
    }

    /**
     * Mọi method là đích của một lời gọi trong luồng, kể cả lời gọi KHÔNG được mở rộng.
     *
     * <p>Lấy cả lời gọi không mở rộng là có chủ ý: DAO gọi procedure hay bị chặn ở
     * {@code analysis.max-depth} hoặc bị coi là lớp phụ trợ, mà đó lại đúng là chỗ có procedure.
     * Bỏ qua chúng thì tính năng này sẽ im lặng đúng vào lúc cần nhất.
     */
    private static void collectTargets(List<FlowNode> nodes, Set<String> sink) {
        for (FlowNode node : nodes) {
            if (node instanceof FlowNode.Call call) {
                sink.add(call.target().typeFqn() + "#" + call.target().methodName());
                collectTargets(call.children(), sink);
            }
            else if (node instanceof FlowNode.Choice choice) {
                choice.alternatives().forEach(alternative -> collectTargets(alternative.body(), sink));
            }
            else if (node instanceof FlowNode.Loop loop) {
                collectTargets(loop.body(), sink);
            }
            else if (node instanceof FlowNode.Guarded guarded) {
                collectTargets(guarded.body(), sink);
                guarded.handlers().forEach(handler -> collectTargets(handler.body(), sink));
                collectTargets(guarded.cleanup(), sink);
            }
        }
    }

    /**
     * Một lời gọi procedure đã nhận ra, trước khi đọc tham số.
     *
     * <p>Tách thành hai pha vì việc đọc tham số cần biết method Java này gọi <b>mấy</b> procedure:
     * nếu chỉ một thì mọi {@code setLong}/{@code addValue} trong cả method đều thuộc về nó; nếu
     * nhiều thì phải bó hẹp vào từng câu lệnh, không thì tham số của procedure này bị gán cho
     * procedure kia.
     */
    private record Detection(Reference reference, CallStyle style, Node at, String sql,
            AnnotationExpr annotation) {
    }

    /** Tên đã tách thành (package, routine). */
    private record Reference(String packageName, String routineName) {
    }

    private void scanMethod(String typeFqn, String methodName) {
        Optional<IndexedType> type = this.index.type(typeFqn);
        if (type.isEmpty()) {
            return;
        }
        for (MethodDeclaration method : type.get().declaration().getMethodsByName(methodName)) {
            List<Detection> detections = new ArrayList<>();
            detectAnnotations(type.get(), method, detections);
            method.getBody().ifPresent(body -> {
                detectCalls(type.get(), method, body, detections);
                detectLiterals(body, detections);
            });

            boolean onlyOne = detections.size() == 1;
            for (Detection detection : detections) {
                add(detection, arguments(type.get(), method, detection, onlyOne), type.get(), method);
            }
        }
    }

    // ------------------------------------------------------------------
    // Pha 1: nhận ra lời gọi
    // ------------------------------------------------------------------

    private void detectAnnotations(IndexedType type, MethodDeclaration method,
            List<Detection> sink) {

        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = simpleName(annotation);
            if (name.equals("Procedure")) {
                detectSpringDataProcedure(method, annotation, sink);
                continue;
            }
            if (name.equals("Query")) {
                textOf(annotation, "value").ifPresent(sql -> fromSql(sql).ifPresent(reference ->
                        sink.add(new Detection(reference, CallStyle.NATIVE_QUERY, annotation, sql,
                                annotation))));
                continue;
            }
            if (MYBATIS_ANNOTATIONS.contains(name)) {
                textOf(annotation, "value").ifPresent(sql -> fromSql(sql).ifPresent(reference ->
                        sink.add(new Detection(reference, CallStyle.MYBATIS_CALLABLE, annotation, sql,
                                annotation))));
            }
        }
    }

    /**
     * {@code @Procedure} của Spring Data JPA, cả bốn cách khai báo.
     *
     * <p>Thứ tự ưu tiên theo đúng cách Spring đọc: {@code procedureName} là tên thật trong
     * database; {@code name}/{@code value} là tên của một {@code @NamedStoredProcedureQuery} nên
     * phải tra tiếp mới ra tên thật; không khai báo gì thì Spring lấy tên method.
     */
    private void detectSpringDataProcedure(MethodDeclaration method, AnnotationExpr annotation,
            List<Detection> sink) {

        Optional<String> procedureName = Annotations.stringValue(annotation, "procedureName");
        if (procedureName.isPresent()) {
            sink.add(new Detection(split(procedureName.get()), CallStyle.SPRING_DATA_PROCEDURE,
                    annotation, null, annotation));
            return;
        }
        Optional<String> named = Annotations.stringValue(annotation, "name", "value");
        if (named.isPresent()) {
            Optional<AnnotationExpr> declaration = namedStoredProcedure(named.get());
            Optional<Reference> resolved = declaration.flatMap(value -> procedureNameIn(value,
                    named.get()));
            sink.add(new Detection(resolved.orElseGet(() -> split(named.get())),
                    resolved.isPresent() ? CallStyle.NAMED_STORED_PROCEDURE
                            : CallStyle.SPRING_DATA_PROCEDURE,
                    annotation, null, declaration.orElse(annotation)));
            return;
        }
        sink.add(new Detection(split(method.getNameAsString()), CallStyle.SPRING_DATA_PROCEDURE,
                annotation, null, annotation));
    }

    /**
     * Tìm {@code @NamedStoredProcedureQuery} khai báo một named stored procedure.
     *
     * <p>Phải quét toàn bộ type vì annotation này nằm trên entity, không nằm trong luồng gọi. Chỉ
     * chạy khi gặp {@code @Procedure(name = ...)} nên không ảnh hưởng đường chạy thường.
     */
    private Optional<AnnotationExpr> namedStoredProcedure(String queryName) {
        for (IndexedType type : this.index.allTypes()) {
            for (AnnotationExpr annotation : type.declaration().getAnnotations()) {
                String name = simpleName(annotation);
                if (!name.equals("NamedStoredProcedureQuery")
                        && !name.equals("NamedStoredProcedureQueries")) {
                    continue;
                }
                if (procedureNameIn(annotation, queryName).isPresent()) {
                    return Optional.of(annotation);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Tên thật của procedure trong một {@code @NamedStoredProcedureQuery}.
     *
     * <p>Đọc ở mức text thay vì đi AST từng thuộc tính: {@code @NamedStoredProcedureQueries} gói
     * nhiều annotation con, và ta chỉ cần cặp {@code (name, procedureName)} đứng cạnh nhau.
     */
    private static Optional<Reference> procedureNameIn(AnnotationExpr annotation, String queryName) {
        Matcher matcher = NAMED_PROCEDURE.matcher(annotation.toString());
        while (matcher.find()) {
            if (matcher.group(1).equals(queryName)) {
                return Optional.of(split(matcher.group(2)));
            }
        }
        return Optional.empty();
    }

    private void detectCalls(IndexedType type, MethodDeclaration method, Node body,
            List<Detection> sink) {

        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();

            if (ROUTINE_SETTERS.contains(name)) {
                firstString(type, call).ifPresent(routine ->
                        sink.add(new Detection(new Reference(catalogNear(type, call), routine),
                                CallStyle.SIMPLE_JDBC_CALL, call, null, null)));
                continue;
            }
            if (JPA_FACTORIES.contains(name)) {
                firstString(type, call).ifPresent(reference ->
                        sink.add(new Detection(split(reference),
                                CallStyle.JPA_STORED_PROCEDURE_QUERY, call, null, null)));
                continue;
            }
            if (SQL_STRING_SINKS.contains(name)) {
                // Chỉ xử lý đối số KHÔNG phải literal tại chỗ: chuỗi SQL hay được gom vào hằng số
                // (private static final String CALL_POST = ...), lúc đó nhánh quét literal bỏ sót.
                // Literal tại chỗ để nhánh 3 lo, nếu bắt ở cả hai nơi thì cùng một lời gọi ra hai
                // dòng khi câu lệnh trải trên nhiều dòng (số dòng khác nhau -> khoá gộp khác nhau).
                Optional<String> sql = firstConstantString(type, call);
                sql.flatMap(StoredProcedureScanner::fromSql).ifPresent(reference ->
                        sink.add(new Detection(reference, styleFor(sql.get()), call, sql.get(), null)));
            }
        }
    }

    private void detectLiterals(Node body, List<Detection> sink) {
        for (StringLiteralExpr literal : body.findAll(StringLiteralExpr.class)) {
            fromSql(literal.getValue()).ifPresent(reference ->
                    sink.add(new Detection(reference, styleFor(literal.getValue()), literal,
                            literal.getValue(), null)));
        }
    }

    /**
     * Tên catalog/package khai báo trong cùng chuỗi gọi {@code SimpleJdbcCall}.
     *
     * <p>Tìm trong phạm vi câu lệnh chứa lời gọi, không chỉ trong chuỗi {@code .withX().withY()}:
     * nhiều dự án tách thành hai câu lệnh trên cùng một biến. Không thấy thì để null thay vì đoán -
     * package sai còn tệ hơn không có package.
     */
    private String catalogNear(IndexedType type, MethodCallExpr call) {
        for (MethodCallExpr sibling : scopeOf(call).findAll(MethodCallExpr.class)) {
            if (CATALOG_SETTERS.contains(sibling.getNameAsString())) {
                Optional<String> catalog = firstString(type, sibling);
                if (catalog.isPresent()) {
                    return catalog.get();
                }
            }
        }
        return null;
    }

    private static Node scopeOf(Node node) {
        return node.findAncestor(Statement.class).map(Node.class::cast)
                .orElseGet(() -> node.findAncestor(MethodDeclaration.class).map(Node.class::cast)
                        .orElse(node));
    }

    private static CallStyle styleFor(String sql) {
        return PLSQL_BLOCK.matcher(sql).find() && !JDBC_ESCAPE.matcher(sql).find()
                ? CallStyle.PLSQL_BLOCK
                : CallStyle.CALLABLE_STATEMENT;
    }

    /**
     * Rút tên routine từ một chuỗi SQL.
     *
     * <p>Thứ tự thử khớp đi từ dạng ít dương tính giả nhất tới dạng nhiều nhất. Cụ thể là
     * {@link #PLSQL_BLOCK} xét cuối: một chuỗi vừa có {@code BEGIN} vừa có {@code {call ...}} thì
     * cái thứ hai mới là tên procedure.
     */
    private static Optional<Reference> fromSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return Optional.empty();
        }
        // Chuỗi có {} là template log của SLF4J, không phải SQL. Kiểm trước mọi luật khác vì đây là
        // dấu hiệu chắc chắn nhất, và log là loại chuỗi đông đảo nhất trong một DAO.
        if (LOG_PLACEHOLDER.matcher(sql).find()) {
            return Optional.empty();
        }
        for (Pattern pattern : List.of(JDBC_ESCAPE, BARE_CALL, PLSQL_BLOCK)) {
            Matcher matcher = pattern.matcher(sql);
            if (matcher.find()) {
                return Optional.of(split(matcher.group(1)));
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Pha 2: đọc tham số vào/ra
    // ------------------------------------------------------------------

    /**
     * Tham số của một lời gọi, đọc từ nguồn chính xác nhất còn có.
     *
     * @param wholeMethod cho phép mở rộng phạm vi ra cả method Java. Chỉ đúng khi method này gọi
     *                    duy nhất một procedure; nhiều procedure trong một method thì phải bó hẹp
     *                    vào câu lệnh, không thì tham số bị gán chéo.
     */
    private List<Argument> arguments(IndexedType type, MethodDeclaration method, Detection detection,
            boolean wholeMethod) {

        Node scope = wholeMethod ? method : scopeOf(detection.at());
        List<Argument> declared = switch (detection.style()) {
            case NAMED_STORED_PROCEDURE -> storedProcedureParameters(detection.annotation());
            case SPRING_DATA_PROCEDURE -> signatureParameters(method);
            case NATIVE_QUERY, MYBATIS_CALLABLE -> sqlParameters(detection.sql());
            case JPA_STORED_PROCEDURE_QUERY, SIMPLE_JDBC_CALL, CALLABLE_STATEMENT, PLSQL_BLOCK ->
                    apiParameters(type, detection, scope);
        };
        if (!declared.isEmpty()) {
            return declared;
        }
        // Dự phòng theo thứ tự "còn đọc được gì": placeholder trong câu SQL phản ánh đúng lời gọi
        // thật, chữ ký Java chỉ là suy ra.
        List<Argument> fromSql = sqlParameters(detection.sql());
        return fromSql.isEmpty() ? signatureParameters(method) : fromSql;
    }

    /**
     * {@code @StoredProcedureParameter(mode = ParameterMode.OUT, name = "p_total", type = Long.class)}.
     *
     * <p>Đây là nguồn tốt nhất: nó khai báo tường minh cả tên, chiều và kiểu - đúng chữ ký thật của
     * procedure trong database, không phải suy từ phía Java.
     */
    private static List<Argument> storedProcedureParameters(AnnotationExpr annotation) {
        if (annotation == null || !(annotation instanceof NormalAnnotationExpr normal)) {
            return List.of();
        }
        List<Argument> arguments = new ArrayList<>();
        for (AnnotationExpr parameter : normal.findAll(AnnotationExpr.class)) {
            if (!simpleName(parameter).equals("StoredProcedureParameter")) {
                continue;
            }
            String name = Annotations.stringValue(parameter, "name").orElse("?");
            Direction direction = Annotations.enumValues(parameter, "mode").stream()
                    .findFirst()
                    .map(StoredProcedureScanner::direction)
                    .orElse(Direction.IN);
            arguments.add(new Argument(name, direction, classValue(parameter)));
        }
        return arguments;
    }

    /**
     * Kiểu khai báo trong {@code type = Long.class}.
     *
     * <p>Không dùng {@code Annotations.enumValues}: hàm đó cắt từ dấu chấm cuối nên
     * {@code Long.class} ra "class". Ở đây cần phần TRƯỚC dấu chấm, và bỏ cả package nếu người viết
     * ghi {@code java.math.BigDecimal.class}.
     */
    private static String classValue(AnnotationExpr annotation) {
        if (!(annotation instanceof NormalAnnotationExpr normal)) {
            return null;
        }
        for (MemberValuePair pair : normal.getPairs()) {
            if (!pair.getNameAsString().equals("type")) {
                continue;
            }
            String text = pair.getValue().toString().trim();
            if (text.endsWith(".class")) {
                text = text.substring(0, text.length() - ".class".length());
            }
            return simpleName(text);
        }
        return null;
    }

    private static Direction direction(String mode) {
        return switch (mode.toUpperCase(Locale.ROOT)) {
            case "OUT" -> Direction.OUT;
            case "INOUT" -> Direction.INOUT;
            case "REF_CURSOR" -> Direction.OUT;
            default -> Direction.IN;
        };
    }

    /**
     * Chữ ký của method Java: tham số là dữ liệu vào, kiểu trả về là dữ liệu ra.
     *
     * <p>Tên tham số ưu tiên {@code @Param("p_id")} vì đó là tên Spring gửi xuống database; tên
     * biến Java chỉ là phương án dự phòng và có thể khác tên thật của procedure.
     */
    private static List<Argument> signatureParameters(MethodDeclaration method) {
        List<Argument> arguments = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            String name = Annotations.find(parameter, "Param")
                    .flatMap(annotation -> Annotations.stringValue(annotation, "value"))
                    .orElseGet(parameter::getNameAsString);
            arguments.add(new Argument(name, Direction.IN, parameter.getType().asString()));
        }
        if (!method.getType().isVoidType()) {
            arguments.add(new Argument("kết quả", Direction.RETURN, method.getType().asString()));
        }
        return arguments;
    }

    /**
     * Placeholder trong câu SQL.
     *
     * <p>Ba dạng, xét theo thứ tự "nói được nhiều nhất": {@code :ten} có tên thật, {@code :1} chỉ có
     * vị trí (cú pháp Oracle), {@code ?} thì chỉ đếm được số lượng.
     */
    private static List<Argument> sqlParameters(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        List<Argument> arguments = new ArrayList<>();
        Matcher named = NAMED_PARAMETER.matcher(sql);
        while (named.find()) {
            arguments.add(new Argument(named.group(1), Direction.IN, null));
        }
        if (!arguments.isEmpty()) {
            return arguments;
        }
        Matcher positional = POSITIONAL_PARAMETER.matcher(sql);
        while (positional.find()) {
            arguments.add(new Argument(":" + positional.group(1), Direction.IN, null));
        }
        if (!arguments.isEmpty()) {
            return arguments;
        }
        int placeholders = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                placeholders++;
            }
        }
        // Dấu ? đầu trong "{? = call F(?)}" là giá trị trả về của function, không phải tham số vào.
        boolean returnsValue = sql.matches("(?s).*\\{\\s*\\?\\s*=\\s*call.*");
        if (returnsValue && placeholders > 0) {
            arguments.add(new Argument("kết quả", Direction.RETURN, null));
            placeholders--;
        }
        for (int i = 1; i <= placeholders; i++) {
            arguments.add(new Argument("?" + i, Direction.IN, null));
        }
        return arguments;
    }

    /**
     * Tham số đọc từ API gọi procedure, cả JPA và Spring JDBC.
     *
     * <p>Đây là nguồn tốt thứ hai sau {@code @StoredProcedureParameter}, và trong thực tế là nguồn
     * hay gặp nhất: hai API dưới đây khai báo <b>chiều</b> tường minh, thứ mà chữ ký method Java
     * không bao giờ nói được.
     *
     * <ul>
     *   <li>JPA: {@code registerStoredProcedureParameter(1, String.class, ParameterMode.INOUT)}
     *   <li>Spring JDBC: {@code declareParameters(new SqlOutParameter("p_code", Types.VARCHAR))},
     *       {@code registerOutParameter(2, Types.NUMERIC)}
     * </ul>
     *
     * <p>Bỏ qua nguồn này là mất thông tin quan trọng nhất của một procedure. Ví dụ thật:
     * {@code prc_validateInfoToCTP} khai báo 3 tham số IN + 1 REF_CURSOR + 2 OUT, nhưng chữ ký Java
     * chỉ là {@code validateInfoToCTP(String, String, String)} - đọc theo chữ ký thì tài liệu che mất
     * cả ba tham số ra.
     */
    private List<Argument> apiParameters(IndexedType type, Detection detection, Node scope) {
        Map<String, Argument> byKey = new LinkedHashMap<>();
        Map<String, String> hints = new LinkedHashMap<>();

        for (ObjectCreationExpr creation : scope.findAll(ObjectCreationExpr.class)) {
            String created = simpleName(creation.getType().getNameAsString());
            Direction direction = SQL_PARAMETER_TYPES.get(created);
            if (direction != null) {
                String name = creation.getArguments().stream().findFirst()
                        .flatMap(argument -> stringOf(type, argument))
                        .orElse("?");
                put(byKey, new Argument(name, direction, sqlType(creation.getArguments(), 1)));
            }
            else if (created.equals("MapSqlParameterSource") && creation.getArguments().size() >= 2) {
                stringOf(type, creation.getArgument(0)).ifPresent(name ->
                        put(byKey, new Argument(name, Direction.IN, null)));
            }
        }

        for (MethodCallExpr call : scope.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();

            if (name.equals("registerStoredProcedureParameter") && call.getArguments().size() >= 3) {
                // API JPA: (vị trí | tên, Class, ParameterMode) - khai báo đầy đủ nhất trong ba API.
                String mode = lastSegment(call.getArgument(2).toString());
                put(byKey, new Argument(positionOrName(type, call.getArgument(0)), direction(mode),
                        parameterType(call.getArgument(1).toString(), mode)));
            }
            else if (name.equals("registerOutParameter") && !call.getArguments().isEmpty()) {
                put(byKey, new Argument(positionOrName(type, call.getArgument(0)), Direction.OUT,
                        sqlType(call.getArguments(), 1)));
            }
            else if (name.equals("addValue") && !call.getArguments().isEmpty()) {
                stringOf(type, call.getArgument(0)).ifPresent(parameter ->
                        put(byKey, new Argument(parameter, Direction.IN, null)));
            }
            else if (name.equals("withReturnValue")) {
                put(byKey, new Argument("kết quả", Direction.RETURN, null));
            }
            else if (name.equals("setParameter") && call.getArguments().size() == 2) {
                // Không tự tạo tham số mới: setParameter chỉ nói GIÁ TRỊ nào được gán vào vị trí
                // nào, còn chiều thì registerStoredProcedureParameter mới biết. Dùng nó để đặt tên
                // gợi nhớ cho vị trí, vì "?2 (contractNumber)" đọc được còn "?2" thì không.
                hintFor(call.getArgument(1)).ifPresent(hint ->
                        hints.putIfAbsent(positionOrName(type, call.getArgument(0)), hint));
            }
            else if (name.startsWith("set") && name.length() > 3 && call.getArguments().size() == 2
                    && Character.isUpperCase(name.charAt(3))) {
                // setLong(1, id) / setString("p_code", code): kiểu nằm ngay trong tên method.
                put(byKey, new Argument(positionOrName(type, call.getArgument(0)), Direction.IN,
                        name.substring(3)));
                hintFor(call.getArgument(1)).ifPresent(hint ->
                        hints.putIfAbsent(positionOrName(type, call.getArgument(0)), hint));
            }
        }

        List<Argument> fromSql = sqlParameters(detection.sql());
        if (byKey.isEmpty()) {
            return fromSql;
        }
        List<Argument> merged = new ArrayList<>();
        byKey.forEach((key, argument) -> merged.add(withHint(argument, hints.get(key))));
        // Câu SQL cho biết TỔNG số placeholder; API cho biết tên và chiều. Thiếu bao nhiêu thì bù
        // bằng placeholder để người đọc thấy đúng số tham số thật, không tưởng là đã đủ.
        for (int i = merged.size(); i < fromSql.size(); i++) {
            merged.add(fromSql.get(i));
        }
        return merged;
    }

    private static Argument withHint(Argument argument, String hint) {
        if (hint == null || hint.isBlank() || hint.equals(argument.name())) {
            return argument;
        }
        return new Argument(argument.name(), argument.direction(), argument.type(), hint);
    }

    /**
     * Tên gợi nhớ cho một vị trí, rút từ biểu thức được gán vào đó.
     *
     * <p>{@code processEventRequest.contractNumber} -> {@code contractNumber};
     * {@code Integer.parseInt(cardId)} -> {@code cardId}. Đây chỉ là tên biến phía Java, KHÔNG phải
     * tên tham số trong database - nên nó được đặt trong ngoặc, cạnh vị trí, chứ không thay thế vị trí.
     */
    private static Optional<String> hintFor(Expression expression) {
        if (expression instanceof FieldAccessExpr access) {
            return Optional.of(access.getNameAsString());
        }
        if (expression instanceof NameExpr name) {
            return Optional.of(name.getNameAsString());
        }
        if (expression instanceof MethodCallExpr call) {
            // Integer.parseInt(cardId) / CommonUtils.formatCardNum(x): giá trị thật nằm ở đối số.
            return call.getArguments().stream().findFirst().flatMap(StoredProcedureScanner::hintFor);
        }
        return Optional.empty();
    }

    /**
     * Kiểu tham số của API JPA: {@code String.class} -> "String".
     *
     * <p>{@code void.class} với {@code REF_CURSOR} thì in "REF_CURSOR": nói "void" cho một con trỏ
     * result set là vô nghĩa với người đọc tài liệu.
     */
    private static String parameterType(String classExpression, String mode) {
        if ("REF_CURSOR".equalsIgnoreCase(mode)) {
            return "REF_CURSOR";
        }
        String text = classExpression.trim();
        if (text.endsWith(".class")) {
            text = text.substring(0, text.length() - ".class".length());
        }
        return simpleName(text);
    }

    private static String lastSegment(String value) {
        return simpleName(value.trim());
    }

    private static void put(Map<String, Argument> sink, Argument argument) {
        sink.merge(argument.name(), argument, (existing, candidate) ->
                // Chiều nói rõ OUT thắng chiều suy ra IN: setXxx rồi registerOutParameter trên
                // cùng một vị trí nghĩa là tham số INOUT, và OUT là phần dễ bị mất.
                existing.direction() == Direction.IN && candidate.direction() != Direction.IN
                        ? candidate
                        : existing);
    }

    private String positionOrName(IndexedType type, Expression expression) {
        Optional<String> name = stringOf(type, expression);
        return name.map(value -> value).orElseGet(() -> "?" + expression);
    }

    /** Hằng {@code java.sql.Types.NUMERIC} -> "NUMERIC". */
    private static String sqlType(NodeList<Expression> arguments, int position) {
        if (arguments.size() <= position) {
            return null;
        }
        String text = arguments.get(position).toString();
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1) : text;
    }

    // ------------------------------------------------------------------
    // Đọc chuỗi: literal tại chỗ hoặc hằng số trong cùng repo
    // ------------------------------------------------------------------

    /** Đối số chuỗi đầu tiên của một lời gọi, giải cả trường hợp nó là hằng số. */
    private Optional<String> firstString(IndexedType type, MethodCallExpr call) {
        for (Expression argument : call.getArguments()) {
            Optional<String> value = stringOf(type, argument);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    /** Như {@link #firstString} nhưng bỏ qua literal tại chỗ - xem giải thích ở nhánh SQL sink. */
    private Optional<String> firstConstantString(IndexedType type, MethodCallExpr call) {
        for (Expression argument : call.getArguments()) {
            if (argument instanceof StringLiteralExpr) {
                continue;
            }
            Optional<String> value = stringOf(type, argument);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private Optional<String> stringOf(IndexedType type, Expression expression) {
        if (expression instanceof StringLiteralExpr literal) {
            return Optional.of(literal.getValue());
        }
        if (expression instanceof NameExpr name) {
            return constantIn(type, name.getNameAsString());
        }
        if (expression instanceof FieldAccessExpr access) {
            // Dạng Constants.CALL_POST_ENTRY: tra trong lớp được nêu tên, nếu lớp đó thuộc repo.
            Optional<String> inOwner = this.index
                    .resolveTypeName(type.unit(), access.getScope().toString())
                    .flatMap(this.index::type)
                    .flatMap(owner -> constantIn(owner, access.getNameAsString()));
            return inOwner.isPresent() ? inOwner : constantIn(type, access.getNameAsString());
        }
        return Optional.empty();
    }

    /** Giá trị của một field String có initializer là literal (ghép chuỗi cũng được). */
    private Optional<String> constantIn(IndexedType type, String fieldName) {
        for (FieldDeclaration field : type.declaration().getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                if (!variable.getNameAsString().equals(fieldName)) {
                    continue;
                }
                return variable.getInitializer().flatMap(StoredProcedureScanner::concatLiteral);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> concatLiteral(Expression expression) {
        if (expression instanceof StringLiteralExpr literal) {
            return Optional.of(literal.getValue());
        }
        if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<String> left = concatLiteral(binary.getLeft());
            Optional<String> right = concatLiteral(binary.getRight());
            if (left.isPresent() && right.isPresent()) {
                return Optional.of(left.get() + right.get());
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Gom kết quả
    // ------------------------------------------------------------------

    /**
     * Tách {@code PKG_SETTLEMENT.POST_ENTRY} thành package + routine.
     *
     * <p>Phần cuối luôn là routine; toàn bộ phần đầu là package (Oracle cho phép
     * {@code SCHEMA.PACKAGE.PROCEDURE} nên không cắt thành đúng một cấp). Tên trần một cấp thì
     * package để null - đó là thông tin ta thật sự KHÔNG biết, và câu hỏi cho dev sẽ nêu ra.
     */
    private static Reference split(String reference) {
        String cleaned = reference == null ? "" : reference.trim().replaceAll("[\"';]", "");
        int dot = cleaned.lastIndexOf('.');
        if (dot <= 0 || dot == cleaned.length() - 1) {
            return new Reference(null, cleaned);
        }
        return new Reference(cleaned.substring(0, dot), cleaned.substring(dot + 1));
    }

    private void add(Detection detection, List<Argument> arguments, IndexedType type,
            MethodDeclaration method) {

        Reference reference = detection.reference();
        if (reference.routineName() == null || reference.routineName().isBlank()) {
            return;
        }
        StoredProcedureUse use = new StoredProcedureUse(
                normalize(reference.packageName()),
                normalize(reference.routineName()),
                detection.style(),
                arguments,
                type.simpleName() + "." + method.getNameAsString() + "()",
                type.relativePath(),
                detection.at().getBegin().map(position -> position.line).orElse(0),
                snippet(detection.at()));
        this.found.putIfAbsent(use.key(), use);
    }

    /**
     * Chuẩn hoá tên về chữ hoa.
     *
     * <p>Oracle lưu tên không dấu nháy thành chữ hoa, nên {@code pkg_settlement.post_entry} trong
     * code Java và {@code PKG_SETTLEMENT.POST_ENTRY} trong database là cùng một thứ. Không chuẩn
     * hoá thì cùng một procedure viết hai kiểu sẽ ra hai dòng trong tài liệu.
     */
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String snippet(Node node) {
        String text = node.toString().replaceAll("\\s+", " ").trim();
        return text.length() <= 160 ? text : text.substring(0, 157) + "...";
    }

    private static String simpleName(AnnotationExpr annotation) {
        return simpleName(annotation.getNameAsString());
    }

    private static String simpleName(String name) {
        return name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
    }

    /** Giá trị text của một thuộc tính annotation, bỏ qua dạng hằng số không đọc được. */
    private static Optional<String> textOf(AnnotationExpr annotation, String... members) {
        return Annotations.stringValues(annotation, members).stream()
                .filter(value -> !value.startsWith("${"))
                .findFirst();
    }
}
