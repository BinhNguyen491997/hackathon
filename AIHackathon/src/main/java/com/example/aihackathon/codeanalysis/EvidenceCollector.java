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
import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.CodeEvidence;
import com.example.aihackathon.codeanalysis.model.CodeSpec;
import com.example.aihackathon.codeanalysis.model.ErrorCode;
import com.example.aihackathon.codeanalysis.model.FlowNode;
import com.example.aihackathon.codeanalysis.model.ParticipantKind;
import com.example.aihackathon.codeanalysis.model.SpecQuestion;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rút ra các dữ kiện có thể khẳng định từ code, mỗi dữ kiện kèm {@code file:line}, cộng với danh
 * sách câu hỏi cần mang đi xác nhận với developer.
 *
 * <p>Toàn bộ lớp này tất định. Đó là điều kiện để tài liệu đáng tin: model ngôn ngữ chỉ được diễn
 * giải các dữ kiện ở đây và tham chiếu tới id của chúng, chứ không được tự viết {@code file:line}.
 * Nếu để model tự dẫn nguồn, nó sẽ sinh ra số dòng nghe rất thuyết phục nhưng sai, và BA - người
 * không đọc code - không có cách nào phát hiện.
 *
 * <p>Danh sách câu hỏi cũng được sinh tất định, không phải do model "nhớ" cảnh báo. Nhờ vậy không
 * bao giờ có chuyện model bỏ sót phần rủi ro để tài liệu trông gọn gàng hơn.
 */
final class EvidenceCollector {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCollector.class);

    private static final Set<String> VALIDATION_ANNOTATIONS = Set.of(
            "NotNull", "NotBlank", "NotEmpty", "Size", "Min", "Max", "Positive", "PositiveOrZero",
            "Negative", "NegativeOrZero", "Email", "Pattern", "Past", "PastOrPresent", "Future",
            "FutureOrPresent", "Digits", "DecimalMin", "DecimalMax", "AssertTrue", "AssertFalse");

    private static final Set<String> SECURITY_ANNOTATIONS = Set.of(
            "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "PreFilter", "PostFilter");

    /** Số nguyên dài hoặc có dấu gạch dưới: gần như luôn là ngưỡng nghiệp vụ không tên. */
    private static final Pattern MAGIC_NUMBER = Pattern.compile("(?<![A-Za-z0-9_.])(\\d[\\d_]{3,})L?\\b");

    private static final Pattern THROWN_EXCEPTION = Pattern.compile("new\\s+([A-Z]\\w*)");

    private final JavaSourceIndex index;

    private final ApiFlow flow;

    private final List<CodeEvidence> evidence = new ArrayList<>();

    private final List<SpecQuestion> questions = new ArrayList<>();

    /** Exception được ném ra trong luồng -> để đối chiếu với bảng mã lỗi. */
    private final Set<String> thrownExceptions = new LinkedHashSet<>();

    /** Câu hỏi đã sinh, chống trùng khi cùng một dấu hiệu xuất hiện nhiều lần. */
    private final Set<String> seenQuestions = new LinkedHashSet<>();

    /** Stored procedure / package database mà endpoint gọi tới. */
    private final List<StoredProcedureUse> procedures = new ArrayList<>();

    /** Bảng mã lỗi của hệ thống, tìm qua các mã mà luồng tham chiếu. */
    private final List<ErrorCode> errorCodes = new ArrayList<>();

    private int nextId = 1;

    private EvidenceCollector(JavaSourceIndex index, ApiFlow flow) {
        this.index = index;
        this.flow = flow;
    }

    static CodeSpec collect(JavaSourceIndex index, ApiFlow flow) {
        EvidenceCollector collector = new EvidenceCollector(index, flow);
        collector.run();
        log.info("thu được {} dẫn chứng và {} câu hỏi cho {}", collector.evidence.size(),
                collector.questions.size(), flow.endpoint().label());
        return new CodeSpec(flow.endpoint(), collector.evidence, collector.questions,
                collector.procedures, collector.errorCodes);
    }

    private void run() {
        Optional<IndexedType> controller = this.index.type(this.flow.endpoint().controllerFqn());
        Optional<MethodDeclaration> entry = controller.flatMap(this::entryMethod);

        collectEndpoint();
        controller.ifPresent(type -> entry.ifPresent(method -> {
            collectSecurity(type, method);
            collectRequestModel(type, method);
        }));

        walk(this.flow.nodes());

        collectStoredProcedures();
        collectErrorCodes();
        collectErrorMapping();
        collectAnalysisLimits();
    }

    private Optional<MethodDeclaration> entryMethod(IndexedType controller) {
        return controller.declaration().getMethodsByName(this.flow.endpoint().methodName()).stream()
                .filter(method -> method.getParameters().size() == this.flow.endpoint().argCount())
                .findFirst();
    }

    // ------------------------------------------------------------------
    // Dữ kiện ở tầng vào
    // ------------------------------------------------------------------

    private void collectEndpoint() {
        ApiEndpoint endpoint = this.flow.endpoint();
        String statement = "API " + endpoint.label() + " được xử lý bởi "
                + endpoint.controllerSimpleName() + "." + endpoint.methodName() + "()";
        if (endpoint.summary() != null && !endpoint.summary().isBlank()) {
            statement += ", mô tả trong code: \"" + endpoint.summary() + "\"";
        }
        add(CodeEvidence.Kind.ENDPOINT, statement, endpoint.sourceFile(), endpoint.line(),
                endpoint.label());
    }

    private void collectSecurity(IndexedType controller, MethodDeclaration method) {
        boolean found = false;
        for (AnnotationExpr annotation : method.getAnnotations()) {
            if (SECURITY_ANNOTATIONS.contains(simpleName(annotation))) {
                add(CodeEvidence.Kind.SECURITY,
                        "Chỉ gọi được khi thoả điều kiện phân quyền " + annotation,
                        controller.relativePath(), lineOf(annotation), annotation.toString());
                found = true;
            }
        }
        for (AnnotationExpr annotation : controller.declaration().getAnnotations()) {
            if (SECURITY_ANNOTATIONS.contains(simpleName(annotation))) {
                add(CodeEvidence.Kind.SECURITY,
                        "Cả controller bị chặn bởi " + annotation,
                        controller.relativePath(), lineOf(annotation), annotation.toString());
                found = true;
            }
        }
        if (!found) {
            ask("API này có yêu cầu đăng nhập hoặc quyền gì không?",
                    "Không tìm thấy annotation phân quyền nào ở method hay controller. Phân quyền có "
                            + "thể được cấu hình tập trung (SecurityFilterChain, gateway) - chỗ đó "
                            + "không đọc được từ luồng của một endpoint.",
                    List.of(), SpecQuestion.Severity.IMPORTANT);
        }
    }

    /**
     * Schema đầu vào và các quy tắc kiểm tra khai báo bằng annotation.
     *
     * <p>Sequence diagram cố tình lọc bỏ DTO (kind DATA) để hình dễ đọc, nên đây là lần đầu các
     * trường dữ liệu và ràng buộc của chúng được đưa vào tài liệu.
     */
    private void collectRequestModel(IndexedType controller, MethodDeclaration method) {
        for (Parameter parameter : method.getParameters()) {
            if (!Annotations.has(parameter, "RequestBody")) {
                continue;
            }
            boolean validated = Annotations.has(parameter, "Valid", "Validated");
            Optional<IndexedType> body = this.index
                    .resolveTypeName(controller.unit(), parameter.getType().asString())
                    .flatMap(this.index::type);

            if (body.isEmpty()) {
                continue;
            }
            List<CodeEvidence> constraints = collectFieldConstraints(body.get());

            if (!constraints.isEmpty() && !validated) {
                // Đây là bug thật hay gặp: annotation có mà thiếu @Valid thì Spring KHÔNG kiểm tra
                ask("Các ràng buộc trên " + body.get().simpleName()
                                + " có thực sự được kiểm tra không?",
                        "Tham số @RequestBody thiếu @Valid nên Spring sẽ BỎ QUA toàn bộ annotation "
                                + "validation trên DTO. Cần xác nhận đây là cố ý hay là thiếu sót.",
                        constraints.stream().map(CodeEvidence::id).toList(),
                        SpecQuestion.Severity.BLOCKING);
            }
        }
    }

    private List<CodeEvidence> collectFieldConstraints(IndexedType type) {
        List<CodeEvidence> constraints = new ArrayList<>();

        if (type.declaration() instanceof RecordDeclaration record) {
            for (Parameter component : record.getParameters()) {
                add(CodeEvidence.Kind.REQUEST_FIELD,
                        "Trường \"" + component.getNameAsString() + "\" kiểu "
                                + component.getType().asString(),
                        type.relativePath(), lineOf(component), component.toString());
                constraints.addAll(addConstraints(type, component, component.getNameAsString()));
            }
            return constraints;
        }
        for (FieldDeclaration field : type.declaration().getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                add(CodeEvidence.Kind.REQUEST_FIELD,
                        "Trường \"" + variable.getNameAsString() + "\" kiểu "
                                + variable.getType().asString(),
                        type.relativePath(), lineOf(field), variable.toString());
                constraints.addAll(addConstraints(type, field, variable.getNameAsString()));
            }
        }
        return constraints;
    }

    private List<CodeEvidence> addConstraints(IndexedType type, NodeWithAnnotations<?> holder,
            String fieldName) {

        List<CodeEvidence> added = new ArrayList<>();
        for (AnnotationExpr annotation : holder.getAnnotations()) {
            if (!VALIDATION_ANNOTATIONS.contains(simpleName(annotation))) {
                continue;
            }
            String message = Annotations.stringValue(annotation, "message").orElse(null);
            String statement = "Trường \"" + fieldName + "\" phải thoả " + simpleName(annotation)
                    + (message == null ? "" : " - thông báo lỗi: \"" + message + "\"");
            added.add(add(CodeEvidence.Kind.VALIDATION, statement, type.relativePath(),
                    lineOf(annotation), annotation.toString()));
        }
        return added;
    }

    // ------------------------------------------------------------------
    // Dữ kiện lấy từ cây luồng
    // ------------------------------------------------------------------

    private void walk(List<FlowNode> nodes) {
        for (FlowNode node : nodes) {
            if (node instanceof FlowNode.Call call) {
                walkCall(call);
            }
            else if (node instanceof FlowNode.Choice choice) {
                walkChoice(choice);
            }
            else if (node instanceof FlowNode.Loop loop) {
                walk(loop.body());
            }
            else if (node instanceof FlowNode.Guarded guarded) {
                walkGuarded(guarded);
            }
            else if (node instanceof FlowNode.Terminal terminal) {
                walkTerminal(terminal);
            }
            else if (node instanceof FlowNode.Unresolved unresolved) {
                ask("Ở bước " + unresolved.expression() + ", runtime thực tế chạy vào đâu?",
                        "Phân tích tĩnh không kết luận được: " + unresolved.reason()
                                + ". Toàn bộ nhánh sau điểm này CHƯA có trong tài liệu.",
                        List.of(), SpecQuestion.Severity.BLOCKING);
            }
        }
    }

    private void walkCall(FlowNode.Call call) {
        String where = call.target().typeSimpleName() + "." + call.target().methodName() + "()";

        if (call.target().kind() == ParticipantKind.REPOSITORY) {
            // Tên bảng/entity nằm ở participant (suy từ generic của JpaRepository), không nằm ở
            // note của từng lời gọi. BA cần biết chạm vào bảng nào, nên phải ghép vào đây.
            String details = joinDetails(call.note(), participantNote(call.target().typeFqn()));
            add(CodeEvidence.Kind.DATA_ACCESS, "Gọi vào database " + databaseName() + " qua " + where
                    + (details.isEmpty() ? "" : " (" + details + ")"),
                    call.source().file(), call.source().line(), call.label());
        }
        else if (call.target().kind() == ParticipantKind.EXTERNAL) {
            add(CodeEvidence.Kind.EXTERNAL_CALL, "Gọi ra ngoài hệ thống: " + where,
                    call.source().file(), call.source().line(), call.label());
        }
        if (call.note() != null && call.note().contains("@Transactional")) {
            // Dẫn chứng phải trỏ vào chỗ KHAI BÁO @Transactional trong lớp service, không phải
            // chỗ gọi ở controller. Trỏ sai chỗ là kiểu sai làm mất hết tin cậy: người đọc mở
            // đúng dòng được dẫn và không thấy gì liên quan.
            FlowNode.Source declaration = transactionalDeclaration(call).orElse(call.source());
            add(CodeEvidence.Kind.TRANSACTION, where + " chạy trong giao dịch (" + call.note() + ")",
                    declaration.file(), declaration.line(), call.note());
            checkExternalCallInsideTransaction(call);
        }

        // Lớp phụ trợ (validator, mapper) không được mở ruột trên diagram cho gọn, nhưng quy tắc
        // nghiệp vụ hay nằm đúng ở đó -> đọc thẳng AST của method đích.
        if (!call.expanded() && call.target().kind() == ParticipantKind.SUPPORT) {
            collectGuardClauses(call.target().typeFqn(), call.target().methodName());
        }
        walk(call.children());
    }

    private void walkChoice(FlowNode.Choice choice) {
        for (FlowNode.Alternative alternative : choice.alternatives()) {
            String condition = choice.subject().isBlank()
                    ? alternative.label()
                    : choice.subject() + " là " + alternative.label();

            Optional<String> thrown = onlyThrow(alternative.body());
            CodeEvidence recorded = thrown
                    .map(detail -> add(CodeEvidence.Kind.BUSINESS_RULE,
                            "Nếu " + condition + " thì hệ thống báo lỗi: " + detail,
                            alternative.source().file(), alternative.source().line(), condition))
                    .orElseGet(() -> add(CodeEvidence.Kind.BRANCH,
                            "Rẽ nhánh khi " + condition,
                            alternative.source().file(), alternative.source().line(), condition));

            checkMagicNumber(condition, recorded);
            walk(alternative.body());
        }
    }

    private void walkGuarded(FlowNode.Guarded guarded) {
        walk(guarded.body());
        for (FlowNode.Alternative handler : guarded.handlers()) {
            if (handler.body().isEmpty()) {
                CodeEvidence recorded = add(CodeEvidence.Kind.ERROR_PATH,
                        "Bắt " + handler.label() + " rồi bỏ qua, không xử lý gì",
                        handler.source().file(), handler.source().line(), handler.label());
                ask("Khi " + handler.label() + " xảy ra, bỏ qua hoàn toàn có đúng nghiệp vụ không?",
                        "Code bắt lỗi rồi không làm gì cả. Đọc code không biết được đây là quyết định "
                                + "nghiệp vụ (lỗi phụ, không được làm fail luồng chính) hay là sót.",
                        List.of(recorded.id()), SpecQuestion.Severity.IMPORTANT);
            }
            else {
                add(CodeEvidence.Kind.ERROR_PATH, "Khi " + handler.label() + " thì xử lý theo nhánh riêng",
                        handler.source().file(), handler.source().line(), handler.label());
            }
            walk(handler.body());
        }
        walk(guarded.cleanup());
    }

    private void walkTerminal(FlowNode.Terminal terminal) {
        if (terminal.kind() != FlowNode.Terminal.Kind.THROW) {
            return;
        }
        add(CodeEvidence.Kind.ERROR_PATH, "Ném lỗi: " + terminal.detail(),
                terminal.source().file(), terminal.source().line(), terminal.detail());
        Matcher matcher = THROWN_EXCEPTION.matcher(terminal.detail());
        if (matcher.find()) {
            this.thrownExceptions.add(matcher.group(1));
        }
    }

    /** Quy tắc dạng {@code if (sai) throw ...} trong lớp phụ trợ. */
    private void collectGuardClauses(String typeFqn, String methodName) {
        Optional<IndexedType> type = this.index.type(typeFqn);
        if (type.isEmpty()) {
            return;
        }
        for (MethodDeclaration method : type.get().declaration().getMethodsByName(methodName)) {
            for (IfStmt ifStmt : method.findAll(IfStmt.class)) {
                List<ThrowStmt> throwStatements = ifStmt.getThenStmt().findAll(ThrowStmt.class);
                if (throwStatements.isEmpty()) {
                    continue;
                }
                String condition = compact(ifStmt.getCondition().toString());
                String thrown = compact(throwStatements.get(0).getExpression().toString());
                CodeEvidence recorded = add(CodeEvidence.Kind.VALIDATION,
                        "Trong " + type.get().simpleName() + "." + methodName + "(): nếu " + condition
                                + " thì báo lỗi " + thrown,
                        type.get().relativePath(), lineOf(ifStmt), condition);
                checkMagicNumber(condition, recorded);

                Matcher matcher = THROWN_EXCEPTION.matcher(thrown);
                if (matcher.find()) {
                    this.thrownExceptions.add(matcher.group(1));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Stored procedure / package database
    // ------------------------------------------------------------------

    /**
     * Procedure và package database mà endpoint gọi tới.
     *
     * <p>Đây là phần tài liệu mà phân tích tĩnh source Java <b>không</b> nói được nội dung, chỉ nói
     * được sự tồn tại. Với hệ thống Oracle, nghiệp vụ thật hay nằm trong package PL/SQL; nếu tài
     * liệu im lặng về chỗ đó thì người đọc sẽ tưởng luồng đã mô tả đủ. Nên mỗi procedure vừa sinh
     * một dẫn chứng (có file:line của lời gọi), vừa sinh một câu hỏi để mang đi hỏi dev/DBA.
     */
    private void collectStoredProcedures() {
        this.procedures.addAll(StoredProcedureScanner.scan(this.index, this.flow));
        if (this.procedures.isEmpty()) {
            return;
        }

        List<String> ids = new ArrayList<>();
        for (StoredProcedureUse use : this.procedures) {
            String statement = "Gọi stored procedure " + use.qualifiedName() + " trên database "
                    + databaseName()
                    + (use.hasPackage() ? " (package " + use.packageName() + ")" : "")
                    + ", từ " + use.calledIn() + " theo cách: " + use.callStyle().title();
            CodeEvidence recorded = add(CodeEvidence.Kind.STORED_PROCEDURE, statement,
                    use.sourceFile(), use.line(), use.snippet());
            ids.add(recorded.id());

            if (!use.hasPackage()) {
                ask("Procedure " + use.routineName() + " nằm trong package/schema nào?",
                        "Code Java chỉ gọi tên trần, không nêu package. Phân tích tĩnh không đoán "
                                + "thay: tên package có thể đến từ cấu hình datasource, synonym, "
                                + "hoặc default schema của user kết nối.",
                        List.of(recorded.id()), SpecQuestion.Severity.IMPORTANT);
            }
        }

        // Một câu hỏi chung cho cả nhóm: nội dung procedure là tầng logic nằm ngoài repo Java.
        // Đây là điểm mờ lớn nhất của tài liệu, nên để mức BLOCKING.
        List<String> names = this.procedures.stream()
                .map(StoredProcedureUse::qualifiedName)
                .distinct()
                .toList();
        ask("Các procedure " + String.join(", ", names) + " làm gì bên trong, và có quy tắc nghiệp "
                        + "vụ nào chỉ tồn tại ở đó?",
                "Tài liệu này dựng từ source Java nên KHÔNG đọc được thân procedure. Mọi kiểm tra, "
                        + "tính toán, cập nhật bảng và nhánh lỗi nằm trong PL/SQL đều không có ở "
                        + "đây - cần dev/DBA cung cấp đặc tả procedure hoặc source PL/SQL.",
                ids, SpecQuestion.Severity.BLOCKING);
    }

    // ------------------------------------------------------------------
    // Bảng mã lỗi của hệ thống
    // ------------------------------------------------------------------

    /**
     * Mã lỗi mà endpoint này có thể trả về, và dẫn chứng cho từng mã.
     *
     * <p>Chạy SAU {@link #walk} và sau {@link #collectRequestModel} là bắt buộc: nó cần
     * {@code thrownExceptions} (biết exception nào ném trong luồng để tra handler tương ứng) và cần
     * biết request có ràng buộc validation hay chưa.
     */
    private void collectErrorCodes() {
        boolean hasValidation = !byKind(CodeEvidence.Kind.VALIDATION).isEmpty();
        this.errorCodes.addAll(ErrorCodeScanner.scan(this.index, this.flow, this.thrownExceptions,
                hasValidation));

        for (ErrorCode code : this.errorCodes) {
            StringBuilder statement = new StringBuilder("API có thể trả về mã lỗi ")
                    .append(code.declaringType()).append('.').append(code.name());
            if (code.code() != null) {
                statement.append(" (mã ").append(code.code()).append(')');
            }
            if (code.httpStatus() != null) {
                statement.append(", HTTP ").append(code.httpStatus());
            }
            statement.append(" - ").append(code.trigger());
            if (code.message() != null) {
                statement.append(", thông điệp: \"").append(code.message()).append('"');
            }
            add(CodeEvidence.Kind.ERROR_MAPPING, statement.toString(), code.sourceFile(),
                    code.line(), code.name());
        }
    }

    private List<CodeEvidence> byKind(CodeEvidence.Kind kind) {
        return this.evidence.stream().filter(item -> item.kind() == kind).toList();
    }

    // ------------------------------------------------------------------
    // Bảng mã lỗi
    // ------------------------------------------------------------------

    private void collectErrorMapping() {
        Map<String, CodeEvidence> mapped = new LinkedHashMap<>();

        for (String exception : this.thrownExceptions) {
            this.index.typesBySimpleName(exception).stream().findFirst().ifPresent(type ->
                    Annotations.find(type.declaration(), "ResponseStatus").ifPresent(annotation ->
                            mapped.put(exception, add(CodeEvidence.Kind.ERROR_MAPPING,
                                    exception + " trả về HTTP " + statusOf(annotation),
                                    type.relativePath(), lineOf(annotation), annotation.toString()))));
        }

        for (IndexedType advice : this.index.allTypes()) {
            if (!Annotations.has(advice.declaration(), "RestControllerAdvice", "ControllerAdvice")) {
                continue;
            }
            for (MethodDeclaration handler : advice.declaration().getMethods()) {
                Optional<AnnotationExpr> annotation = Annotations.find(handler, "ExceptionHandler");
                if (annotation.isEmpty()) {
                    continue;
                }
                String handled = compact(annotation.get().toString());
                String status = Annotations.find(handler, "ResponseStatus")
                        .map(EvidenceCollector::statusOf)
                        .orElse("mã mặc định của handler");
                for (String exception : this.thrownExceptions) {
                    if (handled.contains(exception)) {
                        mapped.putIfAbsent(exception, add(CodeEvidence.Kind.ERROR_MAPPING,
                                exception + " được xử lý tập trung, trả về HTTP " + status,
                                advice.relativePath(), lineOf(handler), handled));
                    }
                }
            }
        }

        for (String exception : this.thrownExceptions) {
            if (!mapped.containsKey(exception)) {
                ask("Khi " + exception + " xảy ra, API trả về mã HTTP và body lỗi như thế nào?",
                        "Không tìm thấy @ResponseStatus trên exception, cũng không thấy "
                                + "@ExceptionHandler nào bắt nó trong repo. Có thể được xử lý ở module "
                                + "khác hoặc ở framework mặc định (500).",
                        List.of(), SpecQuestion.Severity.IMPORTANT);
            }
        }
    }

    // ------------------------------------------------------------------
    // Các dấu hiệu cần hỏi dev
    // ------------------------------------------------------------------

    private void checkMagicNumber(String condition, CodeEvidence relatedEvidence) {
        Matcher matcher = MAGIC_NUMBER.matcher(condition);
        while (matcher.find()) {
            ask("Ngưỡng " + matcher.group(1) + " trong điều kiện \"" + condition
                            + "\" do nghiệp vụ quy định hay do lập trình viên tự đặt?",
                    "Con số nằm trực tiếp trong code, không qua hằng số có tên hay cấu hình, nên "
                            + "không suy ra được ý nghĩa nghiệp vụ và ai có quyền đổi nó.",
                    List.of(relatedEvidence.id()), SpecQuestion.Severity.CLARIFY);
        }
    }

    /**
     * Gọi ra ngoài hệ thống bên trong một giao dịch database. Không phải lỗi chắc chắn, nhưng là
     * thứ BA cần biết: nếu bên ngoài chậm hoặc lỗi, giao dịch có thể bị rollback hoặc giữ lock lâu.
     */
    private void checkExternalCallInsideTransaction(FlowNode.Call transactionalCall) {
        List<String> externals = new ArrayList<>();
        collectExternalNames(transactionalCall.children(), externals);
        if (externals.isEmpty()) {
            return;
        }
        ask("Trong giao dịch của " + transactionalCall.target().typeSimpleName() + "."
                        + transactionalCall.target().methodName() + "() có gọi ra ngoài hệ thống ("
                        + String.join(", ", externals) + "). Nếu bên ngoài lỗi hoặc chậm thì dữ liệu "
                        + "đã ghi sẽ được giữ hay bị rollback?",
                "Đọc code không quyết định được: phụ thuộc cấu hình transaction, timeout, và cách "
                        + "bên ngoài báo lỗi.",
                List.of(), SpecQuestion.Severity.IMPORTANT);
    }

    private void collectExternalNames(List<FlowNode> nodes, List<String> sink) {
        for (FlowNode node : nodes) {
            if (node instanceof FlowNode.Call call) {
                if (call.target().kind() == ParticipantKind.EXTERNAL
                        && !sink.contains(call.target().typeSimpleName())) {
                    sink.add(call.target().typeSimpleName());
                }
                collectExternalNames(call.children(), sink);
            }
            else if (node instanceof FlowNode.Choice choice) {
                choice.alternatives().forEach(alt -> collectExternalNames(alt.body(), sink));
            }
            else if (node instanceof FlowNode.Loop loop) {
                collectExternalNames(loop.body(), sink);
            }
            else if (node instanceof FlowNode.Guarded guarded) {
                collectExternalNames(guarded.body(), sink);
                guarded.handlers().forEach(handler -> collectExternalNames(handler.body(), sink));
            }
        }
    }

    /** Giới hạn của chính lần phân tích: phải nói ra, vì nó tương đương "tài liệu còn thiếu". */
    private void collectAnalysisLimits() {
        for (String warning : this.flow.warnings()) {
            ask("Phần luồng bị ảnh hưởng bởi giới hạn phân tích có gì quan trọng không?",
                    warning, List.of(), SpecQuestion.Severity.CLARIFY);
        }
    }

    // ------------------------------------------------------------------
    // Tiện ích
    // ------------------------------------------------------------------

    private CodeEvidence add(CodeEvidence.Kind kind, String statement, String file, int line,
            String snippet) {

        CodeEvidence recorded = new CodeEvidence("E" + this.nextId++, kind, compact(statement),
                file == null ? "" : file, line, compact(snippet));
        this.evidence.add(recorded);
        return recorded;
    }

    private void ask(String question, String reason, List<String> evidenceIds,
            SpecQuestion.Severity severity) {

        if (!this.seenQuestions.add(question)) {
            return;
        }
        this.questions.add(new SpecQuestion(compact(question), compact(reason), evidenceIds, severity));
    }

    /** Vị trí khai báo {@code @Transactional} trên method đích (hoặc trên lớp của nó). */
    private Optional<FlowNode.Source> transactionalDeclaration(FlowNode.Call call) {
        Optional<IndexedType> type = this.index.type(call.target().typeFqn());
        if (type.isEmpty()) {
            return Optional.empty();
        }
        for (MethodDeclaration method : type.get().declaration()
                .getMethodsByName(call.target().methodName())) {
            Optional<AnnotationExpr> annotation = Annotations.find(method, "Transactional");
            if (annotation.isPresent()) {
                return Optional.of(new FlowNode.Source(type.get().relativePath(),
                        lineOf(annotation.get())));
            }
        }
        return Annotations.find(type.get().declaration(), "Transactional")
                .map(annotation -> new FlowNode.Source(type.get().relativePath(),
                        lineOf(annotation)));
    }

    /** Tên database mà tầng repository nói chuyện với, lấy từ cấu hình qua ApiFlow. */
    private String databaseName() {
        String name = this.flow.databaseName();
        return (name == null || name.isBlank()) ? "database" : name;
    }

    /** Ghi chú trên participant, ví dụ "bảng: orders" suy từ generic của JpaRepository. */
    private String participantNote(String typeFqn) {
        return this.flow.participants().stream()
                .filter(participant -> participant.typeFqn().equals(typeFqn))
                .map(ApiFlow.Participant::note)
                .filter(note -> note != null && !note.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String joinDetails(String first, String second) {
        List<String> parts = new ArrayList<>();
        if (first != null && !first.isBlank()) {
            parts.add(first);
        }
        if (second != null && !second.isBlank()) {
            parts.add(second);
        }
        return String.join(" | ", parts);
    }

    private static Optional<String> onlyThrow(List<FlowNode> body) {        if (body.size() == 1 && body.get(0) instanceof FlowNode.Terminal terminal
                && terminal.kind() == FlowNode.Terminal.Kind.THROW) {
            return Optional.of(terminal.detail());
        }
        return Optional.empty();
    }

    private static String statusOf(AnnotationExpr annotation) {
        List<String> values = Annotations.enumValues(annotation, "value");
        if (!values.isEmpty()) {
            return values.get(0);
        }
        String text = annotation.toString();
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1).replace(")", "") : text;
    }

    private static String simpleName(AnnotationExpr annotation) {
        String name = annotation.getNameAsString();
        return name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
    }

    private static int lineOf(com.github.javaparser.ast.Node node) {
        return node.getBegin().map(position -> position.line).orElse(0);
    }

    private static String compact(String value) {
        if (value == null) {
            return "";
        }
        String single = value.replaceAll("\\s+", " ").trim();
        return single.length() <= 200 ? single : single.substring(0, 197) + "...";
    }
}
