package com.example.aihackathon.codeanalysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.aihackathon.codeanalysis.JavaSourceIndex.IndexedType;
import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.FlowNode;
import com.example.aihackathon.codeanalysis.model.MethodRef;
import com.example.aihackathon.codeanalysis.model.ParticipantKind;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dựng cây luồng thực thi cho một endpoint bằng cách đi từ method controller xuống.
 *
 * <p><b>Thứ tự resolve</b>: thử theo cú pháp trước (tìm khai báo của biến/field rồi tra tên type
 * qua import), chỉ khi thất bại mới nhờ symbol solver. Lý do: với Spring, phần lớn lời gọi là
 * trên field được inject nên nhánh cú pháp đã đúng tuyệt đối, mà nhanh hơn symbol solver hàng
 * chục lần - symbol solver phải parse lại file để resolve từng lời gọi.
 *
 * <p>Không thread-safe: mỗi lần phân tích tạo một instance mới.
 */
final class CallFlowBuilder {

    private static final Logger log = LoggerFactory.getLogger(CallFlowBuilder.class);

    /** Tên method không mang thông tin nghiệp vụ, chỉ áp dụng cho lớp phụ trợ/dữ liệu. */
    private static final Set<String> ACCESSOR_PREFIXES = Set.of("get", "set", "is", "has");

    private static final Set<String> BOILERPLATE_METHODS = Set.of(
            "toString", "equals", "hashCode", "builder", "build", "of", "valueOf", "values",
            "name", "ordinal", "clone", "compareTo", "iterator", "stream", "toList", "copy");

    /** Tên field logger do Lombok {@code @Slf4j} sinh, hoặc do quy ước tự khai báo. */
    private static final Set<String> LOGGER_NAMES = Set.of("log", "logger", "LOG", "LOGGER");

    private static final Set<String> LOG_METHODS = Set.of("trace", "debug", "info", "warn", "error");

    private final JavaSourceIndex index;

    private final AnalysisProperties properties;

    /** Participant theo FQN, giữ thứ tự xuất hiện để cột trên diagram đọc theo luồng. */
    private final Map<String, ApiFlow.Participant> participants = new LinkedHashMap<>();

    private final Set<String> usedAliases = new LinkedHashSet<>();

    /** Method đã mở rộng chi tiết một lần; lần sau chỉ vẽ mũi tên kèm ghi chú. */
    private final Set<String> expanded = new LinkedHashSet<>();

    /** Ngăn xếp lời gọi hiện tại, để phát hiện đệ quy. */
    private final Deque<String> callStack = new ArrayDeque<>();

    private final Set<String> unresolved = new LinkedHashSet<>();

    private final Set<String> warnings = new LinkedHashSet<>();

    private int remainingNodes;

    CallFlowBuilder(JavaSourceIndex index, AnalysisProperties properties) {
        this.index = index;
        this.properties = properties;
        this.remainingNodes = properties.getMaxNodes();
    }

    ApiFlow build(ApiEndpoint endpoint, String repoUrl, String branch, String commitSha) {
        IndexedType controller = this.index.type(endpoint.controllerFqn())
                .orElseThrow(() -> new IllegalStateException(
                        "Không tìm lại được controller " + endpoint.controllerFqn()));

        MethodDeclaration entry = findMethod(controller, endpoint.methodName(), endpoint.argCount())
                .orElseThrow(() -> new IllegalStateException("Không tìm lại được method "
                        + endpoint.methodName() + " trong " + endpoint.controllerFqn()));

        registerParticipant(controller, null);
        String entryKey = controller.fqn() + "#" + endpoint.methodName() + "/" + endpoint.argCount();
        this.expanded.add(entryKey);
        this.callStack.push(entryKey);

        Scope scope = new Scope(controller, entry, 0, false);
        List<FlowNode> nodes = walkBody(entry, scope);

        this.warnings.addAll(this.index.warnings());
        transactionalNote(entry).ifPresent(note ->
                this.warnings.add("Method controller có " + note));

        log.info("dựng luồng {} -> {} participant, {} node gốc, {} điểm chưa resolve",
                endpoint.label(), this.participants.size(), nodes.size(), this.unresolved.size());

        return new ApiFlow(endpoint, List.copyOf(this.participants.values()), nodes,
                List.copyOf(this.unresolved), List.copyOf(this.warnings), repoUrl, branch, commitSha,
                this.properties.getDatabase().getName());
    }

    /** Ngữ cảnh khi đang đi trong một method. */
    private record Scope(IndexedType owner, MethodDeclaration method, int depth, boolean insideBranch) {

        Scope inBranch() {
            return new Scope(this.owner, this.method, this.depth, true);
        }
    }

    /**
     * Vị trí thật của một node AST trong repo.
     *
     * <p>Gắn vị trí ngay lúc dựng cây là điều kiện để tài liệu đặc tả dẫn được file:line cho từng
     * phát biểu. Nếu để bước sau tự đi tìm lại, nó sẽ phải lặp lại toàn bộ logic resolve - đúng
     * phần phức tạp và dễ sai nhất của lớp này.
     */
    private static FlowNode.Source source(Node node, Scope scope) {
        return new FlowNode.Source(scope.owner().relativePath(),
                node.getBegin().map(position -> position.line).orElse(0));
    }

    // ------------------------------------------------------------------
    // Đi qua statement, giữ lại cấu trúc điều khiển
    // ------------------------------------------------------------------

    private List<FlowNode> walkBody(MethodDeclaration method, Scope scope) {
        return method.getBody().map(body -> walkStatements(body.getStatements(), scope)).orElseGet(List::of);
    }

    private List<FlowNode> walkStatements(NodeList<Statement> statements, Scope scope) {
        List<FlowNode> nodes = new ArrayList<>();
        for (Statement statement : statements) {
            nodes.addAll(walkStatement(statement, scope));
        }
        return nodes;
    }

    private List<FlowNode> walkStatement(Statement statement, Scope scope) {
        List<FlowNode> out = new ArrayList<>();
        if (this.remainingNodes <= 0) {
            return out;
        }

        if (statement instanceof BlockStmt block) {
            return walkStatements(block.getStatements(), scope);
        }
        if (statement instanceof ExpressionStmt expressionStmt) {
            collectCalls(expressionStmt.getExpression(), scope, out);
            return out;
        }
        if (statement instanceof IfStmt ifStmt) {
            collectCalls(ifStmt.getCondition(), scope, out);
            List<FlowNode.Alternative> alternatives = new ArrayList<>();
            flattenIf(ifStmt, scope, alternatives);
            if (!alternatives.isEmpty()) {
                out.add(consume(new FlowNode.Choice("", source(ifStmt, scope), alternatives)));
            }
            return out;
        }
        if (statement instanceof SwitchStmt switchStmt) {
            collectCalls(switchStmt.getSelector(), scope, out);
            List<FlowNode.Alternative> alternatives = new ArrayList<>();
            for (SwitchEntry entry : switchStmt.getEntries()) {
                List<FlowNode> body = walkStatements(entry.getStatements(), scope.inBranch());
                if (!body.isEmpty()) {
                    alternatives.add(new FlowNode.Alternative(switchEntryLabel(entry),
                            source(entry, scope), body));
                }
            }
            if (!alternatives.isEmpty()) {
                out.add(consume(new FlowNode.Choice(text(switchStmt.getSelector()),
                        source(switchStmt, scope), alternatives)));
            }
            return out;
        }
        if (statement instanceof ForEachStmt forEach) {
            collectCalls(forEach.getIterable(), scope, out);
            addLoop(out, "với mỗi " + forEach.getVariable() + " trong " + text(forEach.getIterable()),
                    forEach, forEach.getBody(), scope);
            return out;
        }
        if (statement instanceof ForStmt forStmt) {
            forStmt.getCompare().ifPresent(compare -> collectCalls(compare, scope, out));
            addLoop(out, "lặp " + forStmt.getCompare().map(CallFlowBuilder::text).orElse("..."),
                    forStmt, forStmt.getBody(), scope);
            return out;
        }
        if (statement instanceof WhileStmt whileStmt) {
            collectCalls(whileStmt.getCondition(), scope, out);
            addLoop(out, "trong khi " + text(whileStmt.getCondition()), whileStmt,
                    whileStmt.getBody(), scope);
            return out;
        }
        if (statement instanceof DoStmt doStmt) {
            addLoop(out, "lặp tới khi " + text(doStmt.getCondition()), doStmt, doStmt.getBody(), scope);
            return out;
        }
        if (statement instanceof TryStmt tryStmt) {
            List<FlowNode> body = new ArrayList<>();
            tryStmt.getResources().forEach(resource -> collectCalls(resource, scope, body));
            body.addAll(walkStatements(tryStmt.getTryBlock().getStatements(), scope));

            List<FlowNode.Alternative> handlers = new ArrayList<>();
            for (CatchClause catchClause : tryStmt.getCatchClauses()) {
                List<FlowNode> handlerBody =
                        walkStatements(catchClause.getBody().getStatements(), scope.inBranch());
                handlers.add(new FlowNode.Alternative(
                        "lỗi " + catchClause.getParameter().getType(),
                        source(catchClause, scope), handlerBody));
            }
            List<FlowNode> cleanup = tryStmt.getFinallyBlock()
                    .map(block -> walkStatements(block.getStatements(), scope))
                    .orElseGet(List::of);

            if (!body.isEmpty() || !handlers.isEmpty()) {
                out.add(consume(new FlowNode.Guarded(source(tryStmt, scope), body, handlers, cleanup)));
            }
            return out;
        }
        if (statement instanceof ThrowStmt throwStmt) {
            collectCalls(throwStmt.getExpression(), scope, out);
            out.add(consume(new FlowNode.Terminal(FlowNode.Terminal.Kind.THROW,
                    text(throwStmt.getExpression()), source(throwStmt, scope))));
            return out;
        }
        if (statement instanceof ReturnStmt returnStmt) {
            returnStmt.getExpression().ifPresent(expression -> collectCalls(expression, scope, out));
            if (scope.insideBranch()) {
                out.add(consume(new FlowNode.Terminal(FlowNode.Terminal.Kind.RETURN,
                        returnStmt.getExpression().map(CallFlowBuilder::text).orElse(""),
                        source(returnStmt, scope))));
            }
            return out;
        }
        if (statement instanceof SynchronizedStmt synchronizedStmt) {
            return walkStatements(synchronizedStmt.getBody().getStatements(), scope);
        }
        if (statement instanceof LabeledStmt labeledStmt) {
            return walkStatement(labeledStmt.getStatement(), scope);
        }
        if (statement instanceof YieldStmt yieldStmt) {
            collectCalls(yieldStmt.getExpression(), scope, out);
            return out;
        }
        if (statement instanceof AssertStmt assertStmt) {
            collectCalls(assertStmt.getCheck(), scope, out);
            return out;
        }
        return out;
    }

    /** Gộp chuỗi else-if thành nhiều nhánh của cùng một alt, thay vì alt lồng alt. */
    private void flattenIf(IfStmt ifStmt, Scope scope, List<FlowNode.Alternative> sink) {
        List<FlowNode> thenBody = walkStatement(ifStmt.getThenStmt(), scope.inBranch());
        sink.add(new FlowNode.Alternative(text(ifStmt.getCondition()),
                source(ifStmt.getCondition(), scope), thenBody));

        Optional<Statement> elseStmt = ifStmt.getElseStmt();
        if (elseStmt.isEmpty()) {
            return;
        }
        if (elseStmt.get() instanceof IfStmt elseIf) {
            collectCallsIntoLastAlternative(elseIf, scope, sink);
            flattenIf(elseIf, scope, sink);
            return;
        }
        List<FlowNode> elseBody = walkStatement(elseStmt.get(), scope.inBranch());
        if (!elseBody.isEmpty()) {
            sink.add(new FlowNode.Alternative("ngược lại", source(elseStmt.get(), scope), elseBody));
        }
    }

    /** Lời gọi nằm trong điều kiện của else-if cũng phải hiện ra, không được rơi mất. */
    private void collectCallsIntoLastAlternative(IfStmt elseIf, Scope scope,
            List<FlowNode.Alternative> sink) {
        List<FlowNode> conditionCalls = new ArrayList<>();
        collectCalls(elseIf.getCondition(), scope, conditionCalls);
        if (conditionCalls.isEmpty() || sink.isEmpty()) {
            return;
        }
        FlowNode.Alternative last = sink.remove(sink.size() - 1);
        List<FlowNode> merged = new ArrayList<>(last.body());
        merged.addAll(conditionCalls);
        sink.add(new FlowNode.Alternative(last.label(), last.source(), merged));
    }

    private void addLoop(List<FlowNode> out, String label, Node loopStatement, Statement body,
            Scope scope) {

        List<FlowNode> loopBody = walkStatement(body, scope.inBranch());
        if (!loopBody.isEmpty()) {
            out.add(consume(new FlowNode.Loop(label, source(loopStatement, scope), loopBody)));
        }
    }

    private static String switchEntryLabel(SwitchEntry entry) {
        if (entry.getLabels().isEmpty()) {
            return "mặc định";
        }
        List<String> labels = entry.getLabels().stream().map(CallFlowBuilder::text).toList();
        return String.join(" hoặc ", labels);
    }

    // ------------------------------------------------------------------
    // Trích lời gọi từ biểu thức
    // ------------------------------------------------------------------

    private void collectCalls(Expression expression, Scope scope, List<FlowNode> out) {
        if (this.remainingNodes <= 0) {
            return;
        }
        if (expression instanceof MethodCallExpr call) {
            // đối số và scope được đánh giá trước lời gọi ngoài -> vẽ theo đúng thứ tự đó
            call.getScope().ifPresent(inner -> collectCalls(inner, scope, out));
            call.getArguments().forEach(argument -> collectCalls(argument, scope, out));
            emitCall(call, scope, out);
            return;
        }
        if (expression instanceof MethodReferenceExpr reference) {
            emitMethodReference(reference, scope, out);
            return;
        }
        if (expression instanceof ConditionalExpr conditional) {
            collectCalls(conditional.getCondition(), scope, out);
            List<FlowNode> whenTrue = new ArrayList<>();
            List<FlowNode> whenFalse = new ArrayList<>();
            collectCalls(conditional.getThenExpr(), scope, whenTrue);
            collectCalls(conditional.getElseExpr(), scope, whenFalse);
            if (whenTrue.isEmpty() && whenFalse.isEmpty()) {
                return;
            }
            List<FlowNode.Alternative> alternatives = new ArrayList<>();
            alternatives.add(new FlowNode.Alternative(text(conditional.getCondition()),
                    source(conditional, scope), whenTrue));
            if (!whenFalse.isEmpty()) {
                alternatives.add(new FlowNode.Alternative("ngược lại",
                        source(conditional.getElseExpr(), scope), whenFalse));
            }
            out.add(consume(new FlowNode.Choice("", source(conditional, scope), alternatives)));
            return;
        }
        if (expression instanceof LambdaExpr lambda) {
            if (lambda.getBody() instanceof BlockStmt block) {
                out.addAll(walkStatements(block.getStatements(), scope));
            }
            else if (lambda.getBody() instanceof ExpressionStmt expressionStmt) {
                collectCalls(expressionStmt.getExpression(), scope, out);
            }
            return;
        }
        if (expression instanceof ObjectCreationExpr creation) {
            creation.getArguments().forEach(argument -> collectCalls(argument, scope, out));
            return;
        }
        // "Order saved = orderRepository.save(order);" - initializer nằm trong VariableDeclarator,
        // vốn KHÔNG phải Expression nên nhánh đệ quy tổng quát bên dưới không chạm tới được.
        // Bỏ qua chỗ này là mất trắng mọi lời gọi được gán vào biến, tức là phần lớn luồng.
        if (expression instanceof VariableDeclarationExpr declaration) {
            for (VariableDeclarator declarator : declaration.getVariables()) {
                declarator.getInitializer()
                        .ifPresent(initializer -> collectCalls(initializer, scope, out));
            }
            return;
        }
        if (expression instanceof SwitchExpr switchExpr) {
            collectCalls(switchExpr.getSelector(), scope, out);
            List<FlowNode.Alternative> alternatives = new ArrayList<>();
            for (SwitchEntry entry : switchExpr.getEntries()) {
                List<FlowNode> body = walkStatements(entry.getStatements(), scope.inBranch());
                if (!body.isEmpty()) {
                    alternatives.add(new FlowNode.Alternative(switchEntryLabel(entry),
                            source(entry, scope), body));
                }
            }
            if (!alternatives.isEmpty()) {
                out.add(consume(new FlowNode.Choice(text(switchExpr.getSelector()),
                        source(switchExpr, scope), alternatives)));
            }
            return;
        }
        for (Node child : expression.getChildNodes()) {
            if (child instanceof Expression childExpression) {
                collectCalls(childExpression, scope, out);
            }
        }
    }

    private void emitCall(MethodCallExpr call, Scope scope, List<FlowNode> out) {
        Optional<Target> target = resolveTarget(call.getScope().orElse(null),
                call.getNameAsString(), scope, text(call));
        target.ifPresent(value -> emitResolved(value, call.getNameAsString(),
                call.getArguments().size(), argumentLabel(call), source(call, scope), scope, out));
    }

    /**
     * {@code this::convert} hay {@code mapper::toDto} cũng là lời gọi thật, rất phổ biến trong
     * stream. Bỏ qua thì luồng sẽ thiếu hẳn bước chuyển đổi dữ liệu.
     */
    private void emitMethodReference(MethodReferenceExpr reference, Scope scope, List<FlowNode> out) {
        Optional<Target> target = resolveTarget(reference.getScope(), reference.getIdentifier(),
                scope, text(reference));
        target.ifPresent(value -> emitResolved(value, reference.getIdentifier(), 1,
                reference.getIdentifier() + "()", source(reference, scope), scope, out));
    }

    private void emitResolved(Target target, String methodName, int argCount, String label,
            FlowNode.Source callSite, Scope scope, List<FlowNode> out) {

        if (this.remainingNodes <= 0) {
            return;
        }
        if (target.external()) {
            ApiFlow.Participant participant = registerExternal(target.externalName());
            MethodRef ref = new MethodRef(participant.typeFqn(), participant.displayName(), methodName,
                    argCount, ParticipantKind.EXTERNAL, null);
            // không gắn note "ra ngoài hệ thống": stereotype <<external>> và note trên chính
            // participant đã nói điều đó, lặp lại ở mỗi mũi tên chỉ làm diagram rối
            out.add(consume(new FlowNode.Call(ref, label, null, callSite, List.of())));
            return;
        }

        IndexedType declared = target.type();
        if (!shouldEmit(declared, methodName)) {
            return;
        }

        List<String> notes = new ArrayList<>();
        if (target.note() != null) {
            notes.add(target.note());
        }
        IndexedType actual = resolveImplementation(declared, methodName, notes);
        if (actual == null) {
            // nhiều implementation: không được chọn bừa một cái rồi vẽ như thể chắc chắn
            out.add(consume(new FlowNode.Unresolved(declared.simpleName() + "." + methodName + "()",
                    "interface có nhiều implementation, không xác định được runtime dùng cái nào",
                    callSite)));
            return;
        }

        Optional<MethodDeclaration> declaration = findMethod(actual, methodName, argCount);
        declaration.flatMap(CallFlowBuilder::transactionalNote).ifPresent(notes::add);
        if (actual.kind() == ParticipantKind.REPOSITORY) {
            repositoryNote(actual, methodName).ifPresent(notes::add);
        }

        MethodRef ref = new MethodRef(actual.fqn(), actual.simpleName(), methodName, argCount,
                actual.kind(), declaration.map(method -> method.getType().asString()).orElse(null));
        registerParticipant(actual, entityNote(actual));

        String stackKey = ref.key();
        if (this.callStack.contains(stackKey)) {
            out.add(consume(new FlowNode.Call(ref, label, joinNotes(notes, "đệ quy"), callSite,
                    List.of())));
            return;
        }
        if (this.expanded.contains(stackKey)) {
            out.add(consume(new FlowNode.Call(ref, label, joinNotes(notes, "chi tiết đã mô tả ở trên"),
                    callSite, List.of())));
            return;
        }
        if (!shouldExpand(actual, scope.depth()) || declaration.isEmpty()
                || declaration.get().getBody().isEmpty()) {
            out.add(consume(new FlowNode.Call(ref, label, joinNotes(notes, null), callSite, List.of())));
            return;
        }

        this.expanded.add(stackKey);
        this.callStack.push(stackKey);
        FlowNode.Call placeholder = consume(new FlowNode.Call(ref, label, joinNotes(notes, null),
                callSite, List.of()));
        List<FlowNode> children = walkBody(declaration.get(),
                new Scope(actual, declaration.get(), scope.depth() + 1, false));
        this.callStack.pop();

        out.add(new FlowNode.Call(placeholder.target(), placeholder.label(), placeholder.note(),
                placeholder.source(), children));
    }

    // ------------------------------------------------------------------
    // Xác định lớp đích của một lời gọi
    // ------------------------------------------------------------------

    /**
     * @param scopeExpression phần trước dấu chấm; null nghĩa là gọi method của chính lớp hiện tại
     * @param sourceText      nguyên văn lời gọi, dùng để ghi vào danh sách unresolved
     */
    private Optional<Target> resolveTarget(Expression scopeExpression, String methodName, Scope scope,
            String sourceText) {

        if (scopeExpression == null || scopeExpression instanceof ThisExpr) {
            return Optional.of(Target.inRepo(scope.owner(), null));
        }
        if (scopeExpression instanceof SuperExpr) {
            return superType(scope.owner()).map(parent -> Target.inRepo(parent, "gọi lên lớp cha"));
        }

        Optional<String> declaredTypeName = lexicalTypeName(scopeExpression, scope);
        if (declaredTypeName.isPresent()) {
            String typeName = declaredTypeName.get();
            Optional<IndexedType> inRepo = this.index
                    .resolveTypeName(scope.owner().unit(), typeName)
                    .flatMap(this.index::type);
            if (inRepo.isPresent()) {
                return Optional.of(Target.inRepo(inRepo.get(), null));
            }
            // bỏ generic trước khi so khớp: KafkaTemplate<String, String> vẫn phải nhận ra là hạ tầng
            String bare = simpleName(typeName);
            if (TypeClassifier.classifyExternal(bare) == ParticipantKind.EXTERNAL) {
                return Optional.of(Target.external(bare));
            }
            // type nằm ngoài repo (thư viện, JDK, module khác) -> không phải lỗ hổng đáng báo
            return Optional.empty();
        }

        return resolveViaSymbolSolver(scopeExpression, methodName, sourceText);
    }

    /**
     * Nhánh dự phòng: nhờ symbol solver khi không suy được type theo cú pháp (chuỗi gọi lồng
     * nhau, biến suy kiểu bằng var, generic...). Chậm nên chỉ dùng khi cần.
     */
    private Optional<Target> resolveViaSymbolSolver(Expression scopeExpression, String methodName,
            String sourceText) {

        if (!(scopeExpression.getParentNode().orElse(null) instanceof MethodCallExpr parentCall)) {
            return Optional.empty();
        }
        try {
            ResolvedMethodDeclaration resolved = parentCall.resolve();
            String fqn = resolved.declaringType().getQualifiedName();
            Optional<IndexedType> inRepo = this.index.type(fqn);
            if (inRepo.isPresent()) {
                return Optional.of(Target.inRepo(inRepo.get(), null));
            }
            if (TypeClassifier.classifyExternal(fqn) == ParticipantKind.EXTERNAL) {
                return Optional.of(Target.external(simpleName(fqn)));
            }
            return Optional.empty();
        }
        catch (RuntimeException | StackOverflowError ex) {
            if (!isBoilerplate(methodName) && !isGeneratedMemberNoise(scopeExpression, methodName)) {
                this.unresolved.add(sourceText + "  (không suy được lớp đích: "
                        + ex.getClass().getSimpleName() + ")");
            }
            return Optional.empty();
        }
    }

    /**
     * Lời gọi vào thành viên do Lombok sinh lúc compile - không đáng báo là "chưa xác định được".
     *
     * <p>Symbol solver chắc chắn thất bại ở đây vì các thành viên này KHÔNG tồn tại trong source:
     * {@code @Slf4j} sinh field {@code log}, {@code @Builder} sinh cả một lớp builder. Nhưng cả hai
     * đều không phải bước nghiệp vụ: {@code log.info()} là ghi log, còn chuỗi {@code .builder()} là
     * dựng DTO - đúng thứ mà diagram vốn đã chủ động lọc bỏ.
     *
     * <p>Đo trên repo thật: một endpoint báo 4 điểm "chưa xác định được", cả 4 đều là hai dạng này.
     * Báo chúng lên làm tài liệu trông kém tin cậy hơn thực tế, và làm loãng những điểm mờ thật.
     */
    private static boolean isGeneratedMemberNoise(Expression scopeExpression, String methodName) {
        if (scopeExpression == null) {
            return false;
        }
        String scope = scopeExpression.toString();
        if (LOGGER_NAMES.contains(scope) && LOG_METHODS.contains(methodName.toLowerCase(Locale.ROOT))) {
            return true;
        }
        // Cả chuỗi X.builder().a(..).b(..) đều gãy, nên xét theo sự có mặt của ".builder()" trong
        // scope chứ không chỉ xét mắt cuối.
        return scope.endsWith("builder()") || scope.contains(".builder()");
    }

    /**
     * Tìm tên type của biểu thức theo khai báo trong code: field của lớp, tham số method,
     * biến cục bộ, hoặc chính nó là tên một lớp (lời gọi static).
     */
    private Optional<String> lexicalTypeName(Expression expression, Scope scope) {
        String name;
        if (expression instanceof NameExpr nameExpr) {
            name = nameExpr.getNameAsString();
        }
        else if (expression instanceof FieldAccessExpr fieldAccess
                && fieldAccess.getScope() instanceof ThisExpr) {
            name = fieldAccess.getNameAsString();
        }
        else if (expression instanceof TypeExpr typeExpr) {
            return Optional.of(typeExpr.getType().asString());
        }
        else {
            return Optional.empty();
        }

        Optional<String> fromLocals = localVariableType(scope.method(), name);
        if (fromLocals.isPresent()) {
            return fromLocals;
        }
        for (Parameter parameter : scope.method().getParameters()) {
            if (parameter.getNameAsString().equals(name)) {
                return Optional.of(parameter.getType().asString());
            }
        }
        Optional<String> fromField = fieldType(scope.owner(), name, new LinkedHashSet<>());
        if (fromField.isPresent()) {
            return fromField;
        }
        // Không phải biến -> có thể là tên lớp gọi static, ví dụ OrderMapper.toDto(...)
        if (Character.isUpperCase(name.charAt(0))) {
            return Optional.of(name);
        }
        return Optional.empty();
    }

    private static Optional<String> localVariableType(MethodDeclaration method, String name) {
        for (VariableDeclarator declarator : method.findAll(VariableDeclarator.class)) {
            if (!declarator.getNameAsString().equals(name)) {
                continue;
            }
            Type type = declarator.getType();
            if (type.isVarType()) {
                // var: nhường cho symbol solver
                return Optional.empty();
            }
            return Optional.of(type.asString());
        }
        return Optional.empty();
    }

    /** Field của lớp, có leo lên lớp cha trong repo (base controller/service khá phổ biến). */
    private Optional<String> fieldType(IndexedType type, String name, Set<String> visited) {
        if (type == null || !visited.add(type.fqn())) {
            return Optional.empty();
        }
        for (FieldDeclaration field : type.declaration().getFields()) {
            for (VariableDeclarator declarator : field.getVariables()) {
                if (declarator.getNameAsString().equals(name)) {
                    return Optional.of(declarator.getType().asString());
                }
            }
        }
        return superType(type).flatMap(parent -> fieldType(parent, name, visited));
    }

    private Optional<IndexedType> superType(IndexedType type) {
        if (!(type.declaration() instanceof ClassOrInterfaceDeclaration declaration)) {
            return Optional.empty();
        }
        for (ClassOrInterfaceType parent : declaration.getExtendedTypes()) {
            Optional<IndexedType> resolved = this.index
                    .resolveTypeName(type.unit(), parent.getNameAsString())
                    .flatMap(this.index::type);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    /**
     * Đổi interface thành lớp hiện thực thật - mắt xích mà cách làm sơ sài hay đứt: Spring
     * inject qua interface nên nếu dừng ở interface thì diagram không còn nghiệp vụ nào.
     *
     * @return null khi có nhiều implementation và không thể chọn
     */
    /** Ghi chú đánh dấu một lời gọi đã được resolve từ interface sang lớp hiện thực. */
    static final String IMPLEMENTATION_NOTE = "hiện thực của ";

    private IndexedType resolveImplementation(IndexedType declared, String methodName, List<String> notes) {
        if (!declared.isInterface() && !declared.isAbstract()) {
            return declared;
        }
        if (declared.kind() == ParticipantKind.REPOSITORY) {
            // Spring Data sinh implementation lúc runtime, không có source để đi vào
            return declared;
        }
        List<IndexedType> candidates = this.index.implementations(declared.fqn());
        if (candidates.isEmpty()) {
            return declared;
        }
        List<IndexedType> declaring = candidates.stream()
                .filter(candidate -> !candidate.declaration().getMethodsByName(methodName).isEmpty())
                .toList();
        List<IndexedType> effective = declaring.isEmpty() ? candidates : declaring;

        if (effective.size() == 1) {
            IndexedType implementation = effective.get(0);
            if (!implementation.fqn().equals(declared.fqn())) {
                notes.add(IMPLEMENTATION_NOTE + declared.simpleName());
            }
            return implementation;
        }
        this.warnings.add(declared.simpleName() + "." + methodName + "() có "
                + effective.size() + " implementation ("
                + effective.stream().map(IndexedType::simpleName).limit(5).toList()
                + ") - diagram không đoán bừa, cần người xác nhận nhánh nào chạy thật.");
        return null;
    }

    private Optional<MethodDeclaration> findMethod(IndexedType type, String name, int argCount) {
        return findMethod(type, name, argCount, new LinkedHashSet<>());
    }

    private Optional<MethodDeclaration> findMethod(IndexedType type, String name, int argCount,
            Set<String> visited) {

        if (type == null || !visited.add(type.fqn())) {
            return Optional.empty();
        }
        List<MethodDeclaration> candidates = type.declaration().getMethodsByName(name);
        Optional<MethodDeclaration> exact = candidates.stream()
                .filter(candidate -> candidate.getParameters().size() == argCount)
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        Optional<MethodDeclaration> withBody = candidates.stream()
                .filter(candidate -> candidate.getBody().isPresent())
                .findFirst();
        if (withBody.isPresent()) {
            return withBody;
        }
        if (!candidates.isEmpty()) {
            return Optional.of(candidates.get(0));
        }
        return superType(type).flatMap(parent -> findMethod(parent, name, argCount, visited));
    }

    // ------------------------------------------------------------------
    // Bộ lọc: quyết định hiện gì và đi sâu tới đâu
    // ------------------------------------------------------------------

    private boolean shouldEmit(IndexedType target, String methodName) {
        if (isBoilerplate(methodName)) {
            return false;
        }
        if (target.kind() == ParticipantKind.DATA) {
            return false;
        }
        if (target.kind() == ParticipantKind.SUPPORT || target.kind() == ParticipantKind.UNKNOWN) {
            // với lớp phụ trợ/không rõ vai, getter-setter chỉ làm rối diagram.
            // Với SERVICE thì KHÔNG lọc, vì getOrderById() là nghiệp vụ thật.
            if (isAccessor(methodName)) {
                return false;
            }
        }
        if (target.kind() == ParticipantKind.UNKNOWN) {
            return !target.declaration().getMethodsByName(methodName).isEmpty();
        }
        return true;
    }

    private boolean shouldExpand(IndexedType target, int depth) {
        if (depth + 1 > this.properties.getMaxDepth()) {
            this.warnings.add("Đã chạm giới hạn độ sâu " + this.properties.getMaxDepth()
                    + " (analysis.max-depth) - các lời gọi sâu hơn chỉ hiện mũi tên, không mở chi tiết.");
            return false;
        }
        if (target.kind() == ParticipantKind.SUPPORT) {
            return this.properties.isExpandSupportTypes();
        }
        return target.kind().businessLogic();
    }

    private static boolean isAccessor(String methodName) {
        for (String prefix : ACCESSOR_PREFIXES) {
            if (methodName.length() > prefix.length() && methodName.startsWith(prefix)
                    && Character.isUpperCase(methodName.charAt(prefix.length()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBoilerplate(String methodName) {
        return BOILERPLATE_METHODS.contains(methodName);
    }

    // ------------------------------------------------------------------
    // Participant và ghi chú
    // ------------------------------------------------------------------

    private void registerParticipant(IndexedType type, String note) {
        this.participants.computeIfAbsent(type.fqn(), key -> new ApiFlow.Participant(
                nextAlias(type.simpleName()), type.simpleName(), type.fqn(), type.kind(), note));
    }

    private ApiFlow.Participant registerExternal(String simpleName) {
        String pseudoFqn = "external:" + simpleName;
        return this.participants.computeIfAbsent(pseudoFqn, key -> new ApiFlow.Participant(
                nextAlias(simpleName), simpleName, pseudoFqn, ParticipantKind.EXTERNAL,
                "hạ tầng/ngoài hệ thống"));
    }

    private String nextAlias(String simpleName) {
        String base = simpleName.replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isEmpty() || Character.isDigit(base.charAt(0))) {
            base = "P" + base;
        }
        String candidate = base;
        int suffix = 2;
        while (!this.usedAliases.add(candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private static Optional<String> transactionalNote(MethodDeclaration method) {
        Optional<AnnotationExpr> annotation = Annotations.find(method, "Transactional");
        if (annotation.isEmpty()) {
            return Optional.empty();
        }
        String propagation = Annotations.enumValues(annotation.get(), "propagation").stream()
                .findFirst().orElse("");
        boolean readOnly = Annotations.find(method, "Transactional")
                .map(value -> value.toString().contains("readOnly = true"))
                .orElse(false);
        StringBuilder note = new StringBuilder("@Transactional");
        if (!propagation.isBlank()) {
            note.append(" ").append(propagation);
        }
        if (readOnly) {
            note.append(" readOnly");
        }
        return Optional.of(note.toString());
    }

    /** Với repository: hiện luôn câu @Query nếu có, đó là thứ BA hỏi nhiều nhất. */
    private Optional<String> repositoryNote(IndexedType repository, String methodName) {
        return repository.declaration().getMethodsByName(methodName).stream()
                .findFirst()
                .flatMap(method -> Annotations.find(method, "Query"))
                .flatMap(annotation -> Annotations.stringValue(annotation, "value"))
                .map(query -> "query: " + truncate(query.replaceAll("\\s+", " "), 140));
    }

    /** Bảng/entity mà một Spring Data repository làm việc trên đó. */
    private String entityNote(IndexedType type) {
        if (type.kind() != ParticipantKind.REPOSITORY
                || !(type.declaration() instanceof ClassOrInterfaceDeclaration declaration)) {
            return null;
        }
        for (ClassOrInterfaceType parent : declaration.getExtendedTypes()) {
            Optional<NodeList<Type>> arguments = parent.getTypeArguments();
            if (arguments.isEmpty() || arguments.get().isEmpty()) {
                continue;
            }
            String entityName = arguments.get().get(0).asString();
            Optional<IndexedType> entity = this.index.resolveTypeName(type.unit(), entityName)
                    .flatMap(this.index::type);
            String table = entity
                    .flatMap(value -> Annotations.find(value.declaration(), "Table"))
                    .flatMap(annotation -> Annotations.stringValue(annotation, "name"))
                    .orElse(null);
            return table == null ? "entity: " + entityName : "bảng: " + table;
        }
        return null;
    }

    private static String joinNotes(List<String> notes, String extra) {
        List<String> all = new ArrayList<>(notes);
        if (extra != null) {
            all.add(extra);
        }
        return all.isEmpty() ? null : String.join(" | ", all);
    }

    /** Nhãn mũi tên: tên method kèm tên đối số, đủ để đọc mà không lộ cả biểu thức dài. */
    private static String argumentLabel(MethodCallExpr call) {
        List<String> arguments = call.getArguments().stream()
                .map(argument -> {
                    String rendered = text(argument);
                    return rendered.length() > 24 ? "..." : rendered;
                })
                .toList();
        return call.getNameAsString() + "(" + String.join(", ", arguments) + ")";
    }

    private <T extends FlowNode> T consume(T node) {
        this.remainingNodes--;
        if (this.remainingNodes == 0) {
            this.warnings.add("Diagram đã đạt giới hạn " + this.properties.getMaxNodes()
                    + " node (analysis.max-nodes) - phần còn lại của luồng bị cắt.");
        }
        return node;
    }

    private static String simpleName(String typeName) {
        String withoutGenerics = typeName.contains("<")
                ? typeName.substring(0, typeName.indexOf('<'))
                : typeName;
        int dot = withoutGenerics.lastIndexOf('.');
        return dot >= 0 ? withoutGenerics.substring(dot + 1) : withoutGenerics;
    }

    private static String text(Node node) {
        return truncate(node.toString().replaceAll("\\s+", " ").trim(), 120);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    /**
     * Lớp đích của một lời gọi. Hoặc là một type trong repo (có source, đi sâu được), hoặc là
     * một thành phần hạ tầng bên ngoài (chỉ vẽ ranh giới).
     */
    private record Target(IndexedType type, String externalName, String note) {

        static Target inRepo(IndexedType type, String note) {
            return new Target(type, null, note);
        }

        static Target external(String simpleName) {
            return new Target(null, simpleName, null);
        }

        boolean external() {
            return this.type == null;
        }
    }
}
