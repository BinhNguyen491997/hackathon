package com.example.aihackathon.codeanalysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.aihackathon.codeanalysis.JavaSourceIndex.IndexedType;
import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.ErrorCode;
import com.example.aihackathon.codeanalysis.model.ErrorCode.Origin;
import com.example.aihackathon.codeanalysis.model.FlowNode;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Suy ra danh sách mã lỗi mà <b>một endpoint cụ thể</b> có thể trả về.
 *
 * <p>Không liệt kê cả bảng mã lỗi của source. Một enum mã lỗi thật có vài chục hằng và hầu hết không
 * liên quan gì tới endpoint đang xét; đưa hết vào tài liệu thì người đọc mất khả năng biết mã nào
 * thật sự cần xử lý khi tích hợp với API này.
 *
 * <p>Bốn đường một mã đi tới được endpoint, và cả bốn đều đọc được tất định:
 *
 * <ol>
 *   <li><b>Ném tường minh</b>: hằng mã lỗi xuất hiện trong thân method thuộc luồng.
 *   <li><b>Validation</b>: request DTO có ràng buộc, nên handler cho
 *       {@code MethodArgumentNotValidException} sẽ chạy - mã lấy từ thân handler đó.
 *   <li><b>Handler tập trung</b>: exception ném trong luồng bị {@code @ExceptionHandler} tương ứng
 *       bắt và đổi thành mã khác.
 *   <li><b>Handler bắt tất cả</b>: {@code @ExceptionHandler(Exception.class)} áp dụng cho mọi lỗi
 *       ngoài dự kiến - luôn nằm trong danh sách vì mọi API đều có thể rơi vào đó.
 * </ol>
 *
 * <p>Tìm handler bằng hai cách vì Spring có hai kiểu: {@code @ExceptionHandler(X.class)} tường minh,
 * và <b>override</b> method của {@code ResponseEntityExceptionHandler} (như
 * {@code handleMethodArgumentNotValid}) - loại thứ hai KHÔNG có annotation nào, chỉ nhận ra được qua
 * tên method. Repo thật dùng đúng kiểu thứ hai cho nhánh validation.
 */
final class ErrorCodeScanner {

    private static final Logger log = LoggerFactory.getLogger(ErrorCodeScanner.class);

    /** Tên lớp khai báo mã lỗi, theo quy ước đặt tên. */
    private static final List<String> ERROR_TYPE_SUFFIXES =
            List.of("ErrorCode", "ErrorCodes", "ErrorEnum", "ResponseCode", "ResponseCodes",
                    "StatusCode", "ErrorType", "ErrorMessage", "ErrorMessages", "Errors");

    /** Exception Spring ném khi dữ liệu vào không thoả ràng buộc. */
    private static final List<String> VALIDATION_EXCEPTIONS =
            List.of("MethodArgumentNotValid", "BindException", "ConstraintViolation");

    private ErrorCodeScanner() {
    }

    /**
     * @param thrownExceptions tên ngắn các exception được ném trong luồng, để tra handler tương ứng
     * @param hasValidation    request DTO có ràng buộc validation hay không
     */
    static List<ErrorCode> scan(JavaSourceIndex index, ApiFlow flow, Set<String> thrownExceptions,
            boolean hasValidation) {

        Map<String, ErrorCode> byName = new LinkedHashMap<>();
        List<IndexedType> advices = advices(index);

        collectThrownInFlow(index, flow, byName);
        if (hasValidation) {
            collectFromHandlers(index, advices, byName, Origin.VALIDATION,
                    ErrorCodeScanner::handlesValidation,
                    "khi request không thoả ràng buộc dữ liệu vào");
        }
        for (String exception : thrownExceptions) {
            collectFromHandlers(index, advices, byName, Origin.HANDLER,
                    method -> handles(method, exception),
                    "khi " + exception + " thoát ra khỏi luồng");
        }
        collectFromHandlers(index, advices, byName, Origin.CATCH_ALL,
                method -> handles(method, "Exception"),
                "mọi lỗi không khớp handler cụ thể nào");

        List<ErrorCode> codes = new ArrayList<>(byName.values());
        codes.sort((left, right) -> left.origin().compareTo(right.origin()));

        if (!codes.isEmpty()) {
            log.info("{}: suy ra {} mã lỗi endpoint có thể trả về: {}", flow.endpoint().label(),
                    codes.size(), codes.stream().map(ErrorCode::label).toList());
        }
        return List.copyOf(codes);
    }

    // ------------------------------------------------------------------
    // Đường 1: ném tường minh trong luồng
    // ------------------------------------------------------------------

    private static void collectThrownInFlow(JavaSourceIndex index, ApiFlow flow,
            Map<String, ErrorCode> sink) {

        for (Map.Entry<String, Node> entry : bodiesInFlow(index, flow).entrySet()) {
            for (FieldAccessExpr access : entry.getValue().findAll(FieldAccessExpr.class)) {
                String scope = access.getScope().toString();
                if (!looksLikeErrorType(scope)) {
                    continue;
                }
                constant(index, scope, access.getNameAsString()).ifPresent(code ->
                        put(sink, code, Origin.THROWN, "ném tại " + entry.getKey()));
            }
        }
    }

    // ------------------------------------------------------------------
    // Đường 2-4: qua handler tập trung
    // ------------------------------------------------------------------

    private static List<IndexedType> advices(JavaSourceIndex index) {
        List<IndexedType> advices = new ArrayList<>();
        for (IndexedType type : index.allTypes()) {
            if (Annotations.has(type.declaration(), "RestControllerAdvice", "ControllerAdvice")) {
                advices.add(type);
            }
        }
        return advices;
    }

    private static void collectFromHandlers(JavaSourceIndex index, List<IndexedType> advices,
            Map<String, ErrorCode> sink, Origin origin,
            java.util.function.Predicate<MethodDeclaration> matches, String trigger) {

        for (IndexedType advice : advices) {
            for (MethodDeclaration method : advice.declaration().getMethods()) {
                if (!matches.test(method) || method.getBody().isEmpty()) {
                    continue;
                }
                for (FieldAccessExpr access : method.getBody().get().findAll(FieldAccessExpr.class)) {
                    String scope = access.getScope().toString();
                    if (!looksLikeErrorType(scope)) {
                        continue;
                    }
                    constant(index, scope, access.getNameAsString()).ifPresent(code ->
                            put(sink, code, origin, trigger));
                }
            }
        }
    }

    /**
     * Handler có {@code @ExceptionHandler} khai báo đúng loại exception này hay không.
     *
     * <p>So khớp <b>chính xác</b> tên lớp trong {@code X.class}, không dùng {@code contains}:
     * {@code "BaseException".contains("Exception")} là true, nên contains sẽ gán handler của
     * {@code BaseException} vào nhóm "bắt tất cả" và báo sai mã lỗi cho người đọc.
     */
    private static boolean handles(MethodDeclaration method, String exceptionSimpleName) {
        Optional<AnnotationExpr> annotation = Annotations.find(method, "ExceptionHandler");
        if (annotation.isEmpty()) {
            return false;
        }
        Matcher matcher = HANDLED_CLASS.matcher(annotation.get().toString());
        while (matcher.find()) {
            if (matcher.group(1).equals(exceptionSimpleName)) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern HANDLED_CLASS = Pattern.compile("(\\w+)\\.class");

    /**
     * Handler cho lỗi validation.
     *
     * <p>Đây là chỗ DUY NHẤT phải xét tên method, vì Spring xử lý validation qua <b>override</b> của
     * {@code ResponseEntityExceptionHandler} ({@code handleMethodArgumentNotValid}) - không có
     * annotation nào. Repo thật dùng đúng kiểu đó, nên bỏ nhánh này là bỏ mã lỗi mà mọi API có DTO
     * đều có thể trả về.
     */
    private static boolean handlesValidation(MethodDeclaration method) {
        for (String exception : VALIDATION_EXCEPTIONS) {
            if (handles(method, exception) || handles(method, exception + "Exception")) {
                return true;
            }
            if (Annotations.find(method, "ExceptionHandler").isEmpty()
                    && method.getNameAsString().contains(exception)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Đọc hằng mã lỗi
    // ------------------------------------------------------------------

    /**
     * Gộp vào danh sách, giữ {@link Origin} <b>cụ thể nhất</b>.
     *
     * <p>Một mã có thể tới được bằng nhiều đường - ví dụ mã vừa được ném tường minh vừa nằm trong
     * handler bắt tất cả. Lúc đó "ném tường minh" mới là thông tin hữu ích cho người đọc.
     */
    private static void put(Map<String, ErrorCode> sink, ErrorCode code, Origin origin,
            String trigger) {

        ErrorCode candidate = new ErrorCode(code.name(), code.declaringType(), code.code(),
                code.message(), code.httpStatus(), origin, trigger, code.sourceFile(), code.line());
        sink.merge(code.name(), candidate,
                (existing, fresh) -> existing.origin().compareTo(fresh.origin()) <= 0
                        ? existing
                        : fresh);
    }

    /** Một hằng cụ thể trong enum mã lỗi, kèm mã số / thông điệp / HTTP status khai báo cùng nó. */
    private static Optional<ErrorCode> constant(JavaSourceIndex index, String typeName,
            String constantName) {

        String simple = typeName.contains(".")
                ? typeName.substring(typeName.lastIndexOf('.') + 1)
                : typeName;

        for (IndexedType type : index.typesBySimpleName(simple)) {
            if (!(type.declaration() instanceof EnumDeclaration declaration)) {
                continue;
            }
            for (EnumConstantDeclaration entry : declaration.getEntries()) {
                if (!entry.getNameAsString().equals(constantName)) {
                    continue;
                }
                Map<String, String> parts = classifyArguments(entry);
                return Optional.of(new ErrorCode(constantName, type.simpleName(), parts.get("code"),
                        parts.get("message"), parts.get("http"), Origin.THROWN, null,
                        type.relativePath(),
                        entry.getBegin().map(position -> position.line).orElse(0)));
            }
        }
        return Optional.empty();
    }

    /**
     * Phân loại đối số của một hằng enum theo hình dạng, không theo vị trí.
     *
     * <p>{@code CALL_EVENT_PROCESS_ONE(1025, "Has error call package way4 '%s'",
     * HttpStatus.INTERNAL_SERVER_ERROR)} cho ra code=1025, message=câu trên, http=INTERNAL_SERVER_ERROR.
     * Không giả định thứ tự để chịu được dự án khai báo khác kiểu hoặc thiếu một trong ba.
     */
    private static Map<String, String> classifyArguments(EnumConstantDeclaration constant) {
        Map<String, String> parts = new LinkedHashMap<>();
        for (Expression argument : constant.getArguments()) {
            if (argument instanceof StringLiteralExpr literal) {
                parts.putIfAbsent("message", literal.getValue());
            }
            else if (argument instanceof IntegerLiteralExpr || argument instanceof LongLiteralExpr) {
                parts.putIfAbsent("code", argument.toString());
            }
            else if (argument instanceof UnaryExpr unary
                    && unary.getOperator() == UnaryExpr.Operator.MINUS) {
                // Mã âm kiểu SYSTEMS_ERROR(-1, ...) là UnaryExpr chứ không phải literal.
                parts.putIfAbsent("code", unary.toString());
            }
            else if (argument instanceof FieldAccessExpr access
                    && access.getScope().toString().endsWith("HttpStatus")) {
                parts.putIfAbsent("http", access.getNameAsString());
            }
        }
        return parts;
    }

    private static boolean looksLikeErrorType(String scope) {
        if (scope == null || scope.isBlank() || scope.contains("(")) {
            return false;
        }
        String simple = scope.contains(".") ? scope.substring(scope.lastIndexOf('.') + 1) : scope;
        return ERROR_TYPE_SUFFIXES.stream().anyMatch(simple::endsWith);
    }

    /** Thân các method trong luồng, khoá là {@code Lớp.method()} để ghi vào trigger. */
    private static Map<String, Node> bodiesInFlow(JavaSourceIndex index, ApiFlow flow) {
        Set<String> targets = new LinkedHashSet<>();
        targets.add(flow.endpoint().controllerFqn() + "#" + flow.endpoint().methodName());
        collectTargets(flow.nodes(), targets);

        Map<String, Node> bodies = new LinkedHashMap<>();
        for (String target : targets) {
            int separator = target.lastIndexOf('#');
            if (separator <= 0) {
                continue;
            }
            String fqn = target.substring(0, separator);
            String methodName = target.substring(separator + 1);
            index.type(fqn).ifPresent(type -> {
                for (MethodDeclaration method : type.declaration().getMethodsByName(methodName)) {
                    method.getBody().ifPresent(body ->
                            bodies.putIfAbsent(type.simpleName() + "." + methodName + "()", body));
                }
            });
        }
        return bodies;
    }

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
}
