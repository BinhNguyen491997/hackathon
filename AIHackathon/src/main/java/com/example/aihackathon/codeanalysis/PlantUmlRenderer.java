package com.example.aihackathon.codeanalysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.CodeEvidence;
import com.example.aihackathon.codeanalysis.model.CodeSpec;
import com.example.aihackathon.codeanalysis.model.FlowComparison;
import com.example.aihackathon.codeanalysis.model.FlowNode;
import com.example.aihackathon.codeanalysis.model.ParticipantKind;
import com.example.aihackathon.codeanalysis.model.StoredProcedureUse;

/**
 * Sinh file .puml sequence diagram từ cây {@link FlowNode}.
 *
 * <p>Phần luồng gọi thuần tất định - không có LLM tham gia, nên cùng một commit luôn cho ra cùng
 * một hình và BA có thể tin rằng mũi tên trên hình đúng là lời gọi có trong code.
 *
 * <p>Ngoại lệ duy nhất: group "Bước chỉ AI tìm ra" ở cuối, chỉ xuất hiện khi người dùng bật cờ
 * {@code crossCheckWithAi}. Nó nằm tách khỏi luồng, vẽ bằng mũi tên nét đứt và mang nhãn "chưa xác
 * nhận" - ba lớp phân biệt để không lẫn với kết luận của parser.
 *
 * <p>Đây là định dạng sơ đồ chính của hệ thống, nên nó được làm chi tiết nhất: ngoài luồng gọi,
 * diagram còn thể hiện tầng database tường minh, điều kiện tiền đề (phân quyền + validation), mã
 * HTTP khi ném lỗi, kiểu trả về trên mũi tên return, và chữ ký stored procedure kèm tham số vào/ra.
 * Những dữ kiện này lấy từ {@link CodeSpec} - cùng nguồn với tài liệu đặc tả, nên hai thứ không thể
 * nói khác nhau.
 *
 * <p>Phần {@code legend} ở cuối liệt kê những chỗ phân tích tĩnh không kết luận được. Đây là
 * phần quan trọng nhất về mặt tin cậy: diagram thà thiếu và nói rõ là thiếu, còn hơn liền
 * mạch nhưng bịa.
 */
final class PlantUmlRenderer {

    /** Màu theo vai để mắt phân biệt tầng ngay lập tức. */
    private static final Map<ParticipantKind, String> COLORS = Map.of(
            ParticipantKind.CONTROLLER, "#E8F4FD",
            ParticipantKind.SERVICE, "#E8FDE8",
            ParticipantKind.REPOSITORY, "#FDF6E3",
            ParticipantKind.EXTERNAL, "#FDE8E8",
            ParticipantKind.SUPPORT, "#F3F0FA",
            ParticipantKind.COMPONENT, "#F0F0F0");

    private static final String CLIENT_ALIAS = "CLIENT";

    /** Alias của participant database. Không trùng với alias sinh từ tên class. */
    private static final String DATABASE_ALIAS = "DB__";

    /**
     * FQN -> alias, lấy nguyên từ danh sách participant.
     *
     * <p>Không tự sinh lại alias từ tên lớp: hai lớp khác package có thể trùng tên ngắn
     * (OrderService ở hai module), lúc đó builder đã đặt alias khác nhau và renderer phải
     * dùng đúng alias đó, nếu không diagram sẽ nối sai cột.
     */
    private final Map<String, String> aliasByFqn = new LinkedHashMap<>();

    private final ApiFlow flow;

    /** Có thể null: khi đó diagram vẫn vẽ được, chỉ thiếu phần điều kiện tiền đề và mã lỗi. */
    private final CodeSpec spec;

    /** Kết quả đối chiếu với AI; {@link FlowComparison#notRun()} khi người dùng không bật cờ. */
    private final FlowComparison comparison;

    private final boolean showDatabase;

    private final StringBuilder out = new StringBuilder(8192);

    private PlantUmlRenderer(ApiFlow flow, CodeSpec spec, FlowComparison comparison,
            boolean showDatabase) {

        this.flow = flow;
        this.spec = spec;
        this.comparison = comparison == null ? FlowComparison.notRun() : comparison;
        this.showDatabase = showDatabase && hasRepository(flow);
        flow.participants().forEach(participant ->
                this.aliasByFqn.put(participant.typeFqn(), participant.alias()));
    }

    static String render(ApiFlow flow) {
        return new PlantUmlRenderer(flow, null, FlowComparison.notRun(), true).build();
    }

    static String render(ApiFlow flow, CodeSpec spec, boolean showDatabase) {
        return new PlantUmlRenderer(flow, spec, FlowComparison.notRun(), showDatabase).build();
    }

    static String render(ApiFlow flow, CodeSpec spec, FlowComparison comparison,
            boolean showDatabase) {

        return new PlantUmlRenderer(flow, spec, comparison, showDatabase).build();
    }

    private static boolean hasRepository(ApiFlow flow) {
        return flow.participants().stream()
                .anyMatch(participant -> participant.kind() == ParticipantKind.REPOSITORY);
    }

    private String build() {
        String controllerAlias = this.flow.participants().isEmpty()
                ? CLIENT_ALIAS
                : this.flow.participants().get(0).alias();

        appendHeader();
        appendParticipants();
        appendProvenanceNote();
        appendPreconditions(controllerAlias);

        this.out.append(CLIENT_ALIAS).append(" -> ").append(controllerAlias).append(": ")
                .append(escape(this.flow.endpoint().label())).append('\n');
        this.out.append("activate ").append(controllerAlias).append('\n');

        renderNodes(this.flow.nodes(), controllerAlias, 1);

        this.out.append(controllerAlias).append(" --> ").append(CLIENT_ALIAS).append(": ")
                .append(escape(responseLabel())).append('\n');
        this.out.append("deactivate ").append(controllerAlias).append("\n\n");

        appendAiOnlySteps();
        appendLegend();
        this.out.append("@enduml\n");
        return this.out.toString();
    }

    private void appendHeader() {
        this.out.append("@startuml api-flow\n");
        this.out.append("title ").append(escape(this.flow.endpoint().label()));
        String summary = this.flow.endpoint().summary();
        if (summary != null && !summary.isBlank()) {
            this.out.append("\\n").append(escape(summary));
        }
        this.out.append('\n');
        this.out.append("autonumber\n");
        this.out.append("skinparam responseMessageBelowArrow true\n");
        this.out.append("skinparam maxMessageSize 220\n");
        this.out.append("skinparam sequenceMessageAlign left\n");
        this.out.append("skinparam sequenceGroupBodyBackgroundColor transparent\n\n");
    }

    private void appendParticipants() {
        this.out.append("actor \"Client\" as ").append(CLIENT_ALIAS).append('\n');
        for (ApiFlow.Participant participant : this.flow.participants()) {
            this.out.append(declare(participant)).append('\n');
        }
        if (this.showDatabase) {
            // Tầng database vẽ tường minh: repository chỉ là cửa vào, thứ thật sự bị đọc/ghi là
            // database. BA cần thấy ranh giới này để biết bước nào chạm dữ liệu thật.
            this.out.append("database \"").append(escape(databaseName()))
                    .append("\" as ").append(DATABASE_ALIAS)
                    .append(" <<database>> #FFF2CC\n");
        }
        declareAiOnlyParticipants();
        this.out.append('\n');
    }

    /**
     * Participant chỉ xuất hiện trong đề xuất của AI.
     *
     * <p>Khai báo tường minh với màu và stereotype riêng thay vì để PlantUML tự tạo khi gặp: tự tạo
     * thì cột mọc ra ở rìa phải không có nhãn cảnh báo nào, và người xem không phân biệt được nó với
     * participant do parser khẳng định.
     */
    private void declareAiOnlyParticipants() {
        for (String name : aiOnlyTypes()) {
            this.out.append("participant \"").append(escape(name))
                    .append("\\n(chỉ AI đề xuất)\" as ").append(aiAlias(name))
                    .append(" <<chưa xác nhận>> #FFF9E6\n");
        }
    }

    /** Tên lớp chỉ có trong đề xuất của AI, chưa hề là participant của phân tích tĩnh. */
    private List<String> aiOnlyTypes() {
        Set<String> known = new LinkedHashSet<>();
        this.flow.participants().forEach(participant -> known.add(participant.displayName()));

        Set<String> extra = new LinkedHashSet<>();
        for (FlowComparison.Step step : this.comparison.onlyByAi()) {
            if (!known.contains(step.callerType())) {
                extra.add(step.callerType());
            }
            if (!known.contains(step.calleeType())) {
                extra.add(step.calleeType());
            }
        }
        return List.copyOf(extra);
    }

    /**
     * Các bước chỉ AI tìm ra, vẽ bằng mũi tên nét đứt trong một group riêng.
     *
     * <p>Đánh đổi có ý thức: diagram giờ trộn hai mức độ tin cậy trên cùng một hình. Bù lại bằng ba
     * lớp phân biệt - nằm trong group riêng ở CUỐI luồng (không cắt ngang trình tự thật), mũi tên
     * nét đứt, và nhãn "(?) chưa xác nhận" trên từng mũi tên. Không đủ ba thứ này thì người đọc sẽ
     * tin phỏng đoán của AI ngang với kết luận của parser.
     *
     * <p>Thứ tự các bước KHÔNG phải thứ tự thực thi: bảng đối chiếu so theo tập hợp nên đã mất
     * thông tin thứ tự. Legend nói rõ điều đó.
     */
    private void appendAiOnlySteps() {
        if (this.comparison.onlyByAi().isEmpty()) {
            return;
        }
        this.out.append("group #FFF9E6 Bước chỉ AI tìm ra - CHƯA XÁC NHẬN, không phải kết luận ")
                .append("của phân tích tĩnh\n");
        for (FlowComparison.Step step : this.comparison.onlyByAi()) {
            String from = aliasForSimpleName(step.callerType());
            String to = aliasForSimpleName(step.calleeType());
            this.out.append("  ").append(from).append(" --> ").append(to).append(": (?) ")
                    .append(escape(step.calleeMethod())).append("() [chưa xác nhận]");
            if (step.note() != null && !step.note().isBlank()) {
                this.out.append("\\n").append(escape(step.note()));
            }
            this.out.append('\n');
        }
        this.out.append("end\n\n");
    }

    /**
     * Alias theo tên ngắn - dùng riêng cho bước của AI.
     *
     * <p>AI chỉ đưa ra tên lớp ngắn, không có FQN, nên đây là chỗ duy nhất trong renderer phải tra
     * theo tên ngắn. Trùng tên ngắn giữa hai package thì nối vào cột đầu tiên tìm được; chấp nhận
     * được vì bước này vốn đã mang nhãn "chưa xác nhận".
     */
    private String aliasForSimpleName(String simpleName) {
        return this.flow.participants().stream()
                .filter(participant -> participant.displayName().equals(simpleName))
                .map(ApiFlow.Participant::alias)
                .findFirst()
                .orElseGet(() -> aiAlias(simpleName));
    }

    private static String aiAlias(String simpleName) {
        return "AI_" + simpleName.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private String databaseName() {
        String name = this.flow.databaseName();
        return (name == null || name.isBlank()) ? "Database" : name;
    }

    /** Ghi rõ repo/commit/file nguồn để diagram có thể truy vết lại code. */
    private void appendProvenanceNote() {
        this.out.append("note over ").append(CLIENT_ALIAS).append('\n');
        this.out.append("  Repo: ").append(escape(this.flow.repoUrl())).append('\n');
        this.out.append("  Branch: ").append(escape(this.flow.branch()))
                .append(" @ ").append(shortSha(this.flow.commitSha())).append('\n');
        this.out.append("  Nguồn: ").append(escape(this.flow.endpoint().sourceFile()))
                .append(':').append(this.flow.endpoint().line()).append('\n');
        this.out.append("  Sinh tự động bằng phân tích tĩnh source Java.\n");
        this.out.append("end note\n\n");
    }

    /**
     * Điều kiện phải thoả TRƯỚC khi luồng nghiệp vụ bắt đầu: phân quyền và kiểm tra dữ liệu vào.
     *
     * <p>Những thứ này không phải lời gọi nên không xuất hiện trong cây luồng, nhưng với BA thì
     * chúng là phần đặc tả quan trọng: "ai được gọi" và "dữ liệu nào bị từ chối ngay".
     */
    private void appendPreconditions(String controllerAlias) {
        if (this.spec == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        this.spec.byKind(CodeEvidence.Kind.SECURITY)
                .forEach(item -> lines.add("[phân quyền] " + item.statement()));
        this.spec.byKind(CodeEvidence.Kind.VALIDATION)
                .forEach(item -> lines.add("[kiểm tra dữ liệu] " + item.statement()));

        if (lines.isEmpty()) {
            return;
        }
        this.out.append("note over ").append(controllerAlias).append(" #E8F4FD\n");
        this.out.append("  Điều kiện tiền đề\n");
        lines.stream().limit(12)
                .forEach(line -> this.out.append("  - ").append(escape(line)).append('\n'));
        if (lines.size() > 12) {
            this.out.append("  - ... còn ").append(lines.size() - 12)
                    .append(" điều kiện nữa, xem tài liệu đặc tả\n");
        }
        this.out.append("end note\n\n");
    }

    /** Nhãn mũi tên trả về cho client: kèm kiểu trả về của method controller nếu biết. */
    private String responseLabel() {
        return "HTTP response";
    }

    private static String declare(ApiFlow.Participant participant) {
        String keyword = switch (participant.kind()) {
            case REPOSITORY -> "participant";
            case EXTERNAL -> isQueue(participant.displayName()) ? "queue" : "participant";
            default -> "participant";
        };
        StringBuilder line = new StringBuilder(keyword);
        line.append(" \"").append(escape(participant.displayName()));
        if (participant.note() != null && !participant.note().isBlank()) {
            line.append("\\n").append(escape(participant.note()));
        }
        line.append("\" as ").append(participant.alias());
        line.append(" <<").append(participant.kind().stereotype()).append(">>");
        String color = COLORS.get(participant.kind());
        if (color != null) {
            line.append(' ').append(color);
        }
        return line.toString();
    }

    private static boolean isQueue(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("kafka") || lower.contains("rabbit") || lower.contains("jms")
                || lower.contains("sqs") || lower.contains("sns") || lower.contains("stream")
                || lower.contains("eventpublisher");
    }

    private void renderNodes(List<FlowNode> nodes, String from, int indent) {
        for (FlowNode node : nodes) {
            renderNode(node, from, indent);
        }
    }

    private void renderNode(FlowNode node, String from, int indent) {
        if (node instanceof FlowNode.Call call) {
            renderCall(call, from, indent);
        }
        else if (node instanceof FlowNode.Choice choice) {
            renderChoice(choice, from, indent);
        }
        else if (node instanceof FlowNode.Loop loop) {
            indent(indent).append("loop ").append(escape(loop.label())).append('\n');
            renderNodes(loop.body(), from, indent + 1);
            indent(indent).append("end\n");
        }
        else if (node instanceof FlowNode.Guarded guarded) {
            renderGuarded(guarded, from, indent);
        }
        else if (node instanceof FlowNode.Terminal terminal) {
            renderTerminal(terminal, from, indent);
        }
        else if (node instanceof FlowNode.Unresolved unresolved) {
            indent(indent).append("note over ").append(from)
                    .append(" #FFF3CD: (?) chưa xác định: ")
                    .append(escape(unresolved.expression())).append(" - ")
                    .append(escape(unresolved.reason())).append('\n');
        }
    }

    private void renderCall(FlowNode.Call call, String from, int indent) {
        String to = alias(call.target().typeFqn(), call.target().typeSimpleName());
        indent(indent).append(from).append(" -> ").append(to).append(": ")
                .append(escape(call.label())).append('\n');

        if (call.note() != null && !call.note().isBlank()) {
            indent(indent).append("note right of ").append(to).append(": ")
                    .append(escape(call.note())).append('\n');
        }

        // Repository là cửa vào, không phải đích cuối: vẽ thêm một chặng tới database.
        if (this.showDatabase && call.target().kind() == ParticipantKind.REPOSITORY) {
            renderDatabaseHop(call, from, to, indent);
            return;
        }
        if (!call.expanded()) {
            return;
        }
        indent(indent).append("activate ").append(to).append('\n');
        renderNodes(call.children(), to, indent + 1);
        indent(indent).append(to).append(" --> ").append(from)
                .append(returnLabel(call)).append('\n');
        indent(indent).append("deactivate ").append(to).append('\n');
    }

    /**
     * Chặng repository -&gt; database.
     *
     * <p>Nhãn ưu tiên câu {@code @Query} nếu có, vì đó là thao tác thật chạy trên database. Không
     * có thì suy ra loại thao tác từ tên method (save/find/delete...) - vẫn hữu ích hơn là chỉ
     * nhắc lại tên method.
     */
    private void renderDatabaseHop(FlowNode.Call call, String callerAlias, String repositoryAlias,
            int indent) {

        indent(indent).append("activate ").append(repositoryAlias).append('\n');
        indent(indent + 1).append(repositoryAlias).append(" -> ").append(DATABASE_ALIAS)
                .append(": ").append(escape(databaseOperation(call))).append('\n');
        appendProcedureSignatureNote(call, indent + 1);
        indent(indent + 1).append(DATABASE_ALIAS).append(" --> ").append(repositoryAlias)
                .append(": ").append(escape(dataResultLabel(call))).append('\n');
        indent(indent).append(repositoryAlias).append(" --> ").append(callerAlias)
                .append(returnLabel(call)).append('\n');
        indent(indent).append("deactivate ").append(repositoryAlias).append('\n');
    }

    /**
     * Chữ ký procedure vẽ thành BẢNG trong note, không nhồi vào nhãn mũi tên.
     *
     * <p>Nhồi hết vào nhãn cho ra một dòng như
     * {@code procedure CARDAPP.MSB_CTP.PRC_VALIDATEINFOTOCTP(?1 (cardId): Integer, ?2 (cifNumber):
     * String, ...) -> ?4: REF_CURSOR, ?5: String, ?6: String} - dài, khó dò vị trí nào là vào, vị trí
     * nào là ra, và PlantUML tự bẻ dòng ở chỗ vô nghĩa. Bảng creole tách bốn cột nên mắt đọc theo
     * hàng, và tên package đầy đủ có chỗ riêng thay vì chiếm hai phần ba nhãn.
     */
    private void appendProcedureSignatureNote(FlowNode.Call call, int indent) {
        Optional<StoredProcedureUse> found = procedureFor(call);
        if (found.isEmpty()) {
            return;
        }
        StoredProcedureUse use = found.get();
        indent(indent).append("note right of ").append(DATABASE_ALIAS).append(" #FFF9E6\n");
        indent(indent + 1).append("<b>").append(cell(use.qualifiedName())).append("</b>\n");

        if (use.arguments().isEmpty()) {
            indent(indent + 1).append("chưa đọc được tham số từ phía Java\n");
        }
        else {
            indent(indent + 1).append("|= # |= chiều |= tên |= kiểu |\n");
            for (StoredProcedureUse.Argument argument : use.arguments()) {
                indent(indent + 1).append("| ").append(cellOrDash(argument.position()))
                        .append(" | ").append(cell(argument.direction().title()))
                        .append(" | ").append(cellOrDash(argument.displayName()))
                        .append(" | ").append(cellOrDash(argument.type()))
                        .append(" |\n");
            }
        }
        indent(indent).append("end note\n");
    }

    /**
     * Làm sạch một ô trong bảng creole.
     *
     * <p>Dấu {@code |} là ký tự cấu trúc của bảng nên phải bỏ, còn dấu ngoặc kép và xuống dòng thì
     * phá cú pháp .puml như mọi chỗ khác.
     */
    private static String cell(String value) {
        return value == null ? "" : escape(value).replace("|", "/");
    }

    /** Ô trống hiện dấu gạch ngang: bảng có ô rỗng đọc thành "bị lỗi render". */
    private static String cellOrDash(String value) {
        String cleaned = cell(value);
        return cleaned.isBlank() ? "--" : cleaned;
    }

    /**
     * Loại thao tác trên database, suy từ tên method theo quy ước Spring Data.
     *
     * <p>Chỉ kết luận khi tên method có tiền tố rõ ràng. Không khớp gì thì dùng nhãn trung tính
     * "thao tác dữ liệu" chứ KHÔNG mặc định là đọc: những method như {@code reserveStock} là ghi,
     * gán nhãn đọc cho nó là nói sai chiều tác động - đúng loại sai mà tài liệu này phải tránh.
     */    private String databaseOperation(FlowNode.Call call) {
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

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
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

    private String dataResultLabel(FlowNode.Call call) {
        // Procedure có tham số OUT: đó chính là dữ liệu chảy ngược về. Một tham số thì nêu tên,
        // nhiều thì chỉ nêu số lượng - chi tiết đã có trong bảng ngay phía trên, nhắc lại làm nhãn dài.
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
     * Stored procedure ứng với một lời gọi repository/DAO.
     *
     * <p>Khớp theo {@code TênLớp.tênMethod()} vì đó là thứ {@code StoredProcedureScanner} ghi vào
     * {@code calledIn}. Một method Java gọi nhiều procedure thì lấy cái đầu tiên - nhãn mũi tên chỉ
     * có một dòng, còn danh sách đầy đủ đã nằm trong legend và trong tài liệu.
     */
    private Optional<StoredProcedureUse> procedureFor(FlowNode.Call call) {
        if (this.spec == null) {
            return Optional.empty();
        }
        String where = call.target().typeSimpleName() + "." + call.target().methodName() + "()";
        return this.spec.procedures().stream()
                .filter(use -> use.calledIn().equals(where))
                .findFirst();
    }

    /** Mũi tên trả về kèm kiểu, để đọc sơ đồ không phải mở IDE tra chữ ký method. */
    private String returnLabel(FlowNode.Call call) {
        String returnType = call.target().returnType();
        if (returnType == null || returnType.isBlank()) {
            return "";
        }
        if ("void".equals(returnType)) {
            return ": (void)";
        }
        return ": " + escape(returnType);
    }

    /**
     * Ném lỗi: kèm luôn mã HTTP mà API trả về nếu tra được từ {@link CodeSpec}.
     *
     * <p>Đây là câu BA hỏi nhiều nhất về nhánh lỗi - "client nhận được gì" - và nó không nằm trong
     * cây luồng vì mapping do {@code @ResponseStatus}/{@code @ExceptionHandler} quyết định.
     */
    private void renderTerminal(FlowNode.Terminal terminal, String from, int indent) {
        if (terminal.kind() != FlowNode.Terminal.Kind.THROW) {
            indent(indent).append("note over ").append(from).append(": trả về sớm: ")
                    .append(escape(terminal.detail())).append('\n');
            return;
        }
        StringBuilder text = new StringBuilder("ném lỗi: ").append(escape(terminal.detail()));
        httpStatusFor(terminal.detail()).ifPresent(status ->
                text.append("\\n-> client nhận HTTP ").append(escape(status)));

        indent(indent).append("note over ").append(from).append(" #FFD5D5: ").append(text)
                .append('\n');
    }

    /** Tra mã HTTP của exception được ném, dựa trên dẫn chứng ERROR_MAPPING đã thu tất định. */
    private Optional<String> httpStatusFor(String throwDetail) {
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

    private void renderChoice(FlowNode.Choice choice, String from, int indent) {
        if (choice.alternatives().isEmpty()) {
            return;
        }
        // dùng " là " chứ không phải "->": trong sequence diagram mũi tên đã có nghĩa riêng,
        // nhãn alt chứa "->" rất dễ bị đọc nhầm thành một lời gọi
        String subject = choice.subject().isBlank() ? "" : escape(choice.subject()) + " là ";
        for (int i = 0; i < choice.alternatives().size(); i++) {
            FlowNode.Alternative alternative = choice.alternatives().get(i);
            indent(indent).append(i == 0 ? "alt " : "else ").append(subject)
                    .append(escape(alternative.label())).append('\n');
            renderNodes(alternative.body(), from, indent + 1);
        }
        indent(indent).append("end\n");
    }

    /**
     * try/catch được vẽ thành alt "luồng bình thường" / else "lỗi X". Đây là cách diễn đạt
     * sát nghiệp vụ nhất: BA đọc ra ngay "nếu bước này lỗi thì hệ thống làm gì".
     */
    private void renderGuarded(FlowNode.Guarded guarded, String from, int indent) {
        if (guarded.handlers().isEmpty()) {
            // try-with-resources hoặc try/finally: không có nhánh lỗi nào để vẽ
            renderNodes(guarded.body(), from, indent);
        }
        else {
            indent(indent).append("alt luồng bình thường\n");
            renderNodes(guarded.body(), from, indent + 1);
            for (FlowNode.Alternative handler : guarded.handlers()) {
                indent(indent).append("else ").append(escape(handler.label())).append('\n');
                if (handler.body().isEmpty()) {
                    indent(indent + 1).append("note over ").append(from)
                            .append(" #FFF3CD: bỏ qua lỗi (catch rỗng) - cần xác nhận với dev\n");
                }
                else {
                    renderNodes(handler.body(), from, indent + 1);
                }
            }
            indent(indent).append("end\n");
        }
        if (!guarded.cleanup().isEmpty()) {
            indent(indent).append("group finally\n");
            renderNodes(guarded.cleanup(), from, indent + 1);
            indent(indent).append("end\n");
        }
    }

    private void appendLegend() {
        this.out.append("legend right\n");
        if (this.showDatabase) {
            this.out.append("  Tầng repository được hiểu là cửa vào database ")
                    .append(escape(databaseName())).append(".\n");
        }
        appendProcedureLegend();
        appendAiOnlyLegend();
        if (this.flow.unresolved().isEmpty() && this.flow.warnings().isEmpty()) {
            this.out.append(hasProcedures()
                    ? "  Phân tích tĩnh phủ hết phần code Java của luồng này; phần còn thiếu là "
                            + "thân các procedure nêu ở trên.\n"
                    : "  Phân tích tĩnh không gặp điểm mờ nào trong luồng này.\n");
            this.out.append("end legend\n\n");
            return;
        }
        if (!this.flow.warnings().isEmpty()) {
            this.out.append("  == Giới hạn phân tích ==\n");
            this.flow.warnings().stream().limit(10)
                    .forEach(warning -> this.out.append("  - ").append(escape(warning)).append('\n'));
        }
        if (!this.flow.unresolved().isEmpty()) {
            this.out.append("  == Chưa xác định được (cần người kiểm tra) ==\n");
            this.flow.unresolved().stream().limit(15).forEach(item -> {
                this.out.append("  - ").append(escape(item)).append('\n');
                // Gợi ý của AI đặt ngay dưới điểm nó nói về, thụt sâu hơn một cấp: người đọc thấy
                // liên hệ mà không phải tự đối chiếu với nhóm bước ở cuối diagram.
                UnresolvedLinker.candidatesFor(item, this.comparison).forEach(step ->
                        this.out.append("      -> ").append(escape(UnresolvedLinker.label(step)))
                                .append('\n'));
            });
        }
        this.out.append("end legend\n\n");
    }

    private boolean hasProcedures() {
        return this.spec != null && !this.spec.procedures().isEmpty();
    }

    /**
     * Danh sách procedure/package ngay trong legend của diagram.
     *
     * <p>Người xem sơ đồ thường chỉ mở file .puml chứ không đọc tài liệu markdown kèm theo. Mũi tên
     * vào repository/DAO là chỗ luồng biến mất khỏi code Java, nên tên procedure phải nằm luôn trên
     * hình - nếu không, diagram trông như đã mô tả trọn vẹn nghiệp vụ.
     */
    private void appendProcedureLegend() {
        if (!hasProcedures()) {
            return;
        }
        this.out.append("  == Procedure / package database được gọi ==\n");
        this.spec.procedures().stream()
                .map(use -> use.qualifiedName() + "  -  " + use.inputs().size() + " vào / "
                        + use.outputs().size() + " ra  (từ " + use.calledIn() + ")")
                .distinct()
                .limit(15)
                .forEach(line -> this.out.append("  - ").append(escape(line)).append('\n'));
        this.out.append("  Chi tiết tham số của từng procedure nằm ở bảng cạnh mũi tên tương ứng.\n");
        this.out.append("  Thân procedure KHÔNG đọc được từ source Java - phần logic bên trong "
                + "chưa có trong sơ đồ này.\n");
        this.out.append("  Tham số ở trên là thứ đọc được từ phía Java, không phải chữ ký đã đối "
                + "chiếu với database.\n");
    }

    /** Ghi chú về group bước của AI: người xem phải biết vì sao chúng khác mọi mũi tên còn lại. */
    private void appendAiOnlyLegend() {
        if (this.comparison.onlyByAi().isEmpty()) {
            return;
        }
        this.out.append("  == Bước chỉ AI tìm ra (nét đứt, nhóm cuối) ==\n");
        this.out.append("  ").append(this.comparison.onlyByAi().size())
                .append(" bước do LLM đọc source đề xuất mà phân tích tĩnh KHÔNG tìm ra.\n");
        this.out.append("  Mức đồng thuận giữa hai bên: ").append(this.comparison.agreementPercent())
                .append("%.\n");
        this.out.append("  Chúng CHƯA được xác nhận: có thể là bước thật bị parser bỏ sót (thường do "
                + "thiếu jar dependency), cũng có thể là AI nhìn sai. Thứ tự trong nhóm KHÔNG phải "
                + "thứ tự thực thi.\n");
    }

    private String alias(String fqn, String fallbackSimpleName) {
        String known = this.aliasByFqn.get(fqn);
        if (known != null) {
            return known;
        }
        // không nên xảy ra: builder register participant trước khi tạo node Call
        return fallbackSimpleName.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private StringBuilder indent(int level) {
        return this.out.append("    ".repeat(Math.max(0, level)));
    }

    /** Bỏ ký tự làm hỏng cú pháp .puml: dấu ngoặc kép và ký tự xuống dòng. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\"", "'")
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("\\n", " ")
                .trim();
    }

    private static String shortSha(String sha) {
        if (sha == null) {
            return "n/a";
        }
        return sha.length() > 8 ? sha.substring(0, 8) : sha;
    }
}
