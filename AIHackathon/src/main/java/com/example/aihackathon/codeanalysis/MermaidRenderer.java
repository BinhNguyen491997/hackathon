package com.example.aihackathon.codeanalysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.FlowNode;

/**
 * Sinh sequence diagram dạng Mermaid từ cùng cây {@link FlowNode} mà PlantUmlRenderer dùng.
 *
 * <p>Lý do cần format này: GitLab render Mermaid ngay trong markdown (wiki, mô tả MR, issue),
 * nên BA xem được sơ đồ mà không cài gì, và code không phải đi ra ngoài hạ tầng công ty.
 *
 * <p>Cố tình KHÔNG dùng các cú pháp mới của Mermaid ({@code box} để nhóm, JSON config để đổi
 * hình participant thành database/queue). Điểm mạnh duy nhất của format này là "chạy ở mọi nơi",
 * nên nó phải chạy được với cả bản Mermaid cũ mà GitLab/VS Code đang nhúng. Vai của lớp được
 * ghi vào nhãn thay vì thể hiện bằng hình.
 */
final class MermaidRenderer {

    private static final String CLIENT_ALIAS = "CLIENT";

    private final ApiFlow flow;

    private final Map<String, String> aliasByFqn = new LinkedHashMap<>();

    private final StringBuilder out = new StringBuilder(4096);

    private MermaidRenderer(ApiFlow flow) {
        this.flow = flow;
        flow.participants().forEach(participant ->
                this.aliasByFqn.put(participant.typeFqn(), participant.alias()));
    }

    static String render(ApiFlow flow) {
        return new MermaidRenderer(flow).build();
    }

    private String build() {
        String controllerAlias = this.flow.participants().isEmpty()
                ? CLIENT_ALIAS
                : this.flow.participants().get(0).alias();

        this.out.append("sequenceDiagram\n");
        this.out.append("    autonumber\n");
        this.out.append("    actor ").append(CLIENT_ALIAS).append(" as Client\n");

        for (ApiFlow.Participant participant : this.flow.participants()) {
            this.out.append("    participant ").append(participant.alias()).append(" as ")
                    .append(label(participant)).append('\n');
        }

        this.out.append("    Note over ").append(CLIENT_ALIAS).append(": ")
                .append(provenance()).append('\n');

        message(CLIENT_ALIAS, controllerAlias, escape(this.flow.endpoint().label()), true, 1);
        this.out.append("    activate ").append(controllerAlias).append('\n');

        renderNodes(this.flow.nodes(), controllerAlias, 1);

        message(controllerAlias, CLIENT_ALIAS, "HTTP response", false, 1);
        this.out.append("    deactivate ").append(controllerAlias).append('\n');

        appendLimitations();
        return this.out.toString();
    }

    /**
     * Nhãn participant trên MỘT dòng, không dùng thẻ {@code <br/>}.
     *
     * <p>Muốn xuống dòng trong nhãn thì phải hạ {@code securityLevel} của Mermaid xuống 'loose'
     * để nó cho phép HTML trong nhãn. Nhưng nhãn ở đây sinh từ source của repo lạ, nên cho phép
     * HTML là mở luôn đường XSS. Đổi xuống dòng lấy an toàn là đánh đổi đúng.
     */
    private static String label(ApiFlow.Participant participant) {
        StringBuilder label = new StringBuilder(escape(participant.displayName()));
        label.append(" [").append(participant.kind().stereotype()).append(']');
        if (participant.note() != null && !participant.note().isBlank()) {
            label.append(" - ").append(escape(participant.note()));
        }
        return label.toString();
    }

    /**
     * Chỉ một dòng ngắn để truy vết. Bản đầy đủ (repo, branch, commit, file:line) nằm trong bảng
     * của báo cáo HTML/Markdown, không cần nhồi hết vào hình.
     */
    private String provenance() {
        return escape(this.flow.branch()) + " @ " + shortSha(this.flow.commitSha())
                + " - " + escape(this.flow.endpoint().sourceFile())
                + ":" + this.flow.endpoint().line();
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
            String prefix = terminal.kind() == FlowNode.Terminal.Kind.THROW
                    ? "ném lỗi: "
                    : "trả về sớm: ";
            note(indent, "over", from, prefix + escape(terminal.detail()));
        }
        else if (node instanceof FlowNode.Unresolved unresolved) {
            note(indent, "over", from, "(?) chưa xác định: " + escape(unresolved.expression())
                    + " - " + escape(unresolved.reason()));
        }
    }

    private void renderCall(FlowNode.Call call, String from, int indent) {
        String to = alias(call.target().typeFqn(), call.target().typeSimpleName());
        message(from, to, escape(call.label()), true, indent);

        if (call.note() != null && !call.note().isBlank()) {
            note(indent, "right of", to, escape(call.note()));
        }
        if (!call.expanded()) {
            return;
        }
        indent(indent).append("activate ").append(to).append('\n');
        renderNodes(call.children(), to, indent + 1);
        message(to, from, "kết quả", false, indent);
        indent(indent).append("deactivate ").append(to).append('\n');
    }

    private void renderChoice(FlowNode.Choice choice, String from, int indent) {
        if (choice.alternatives().isEmpty()) {
            return;
        }
        String subject = choice.subject().isBlank() ? "" : escape(choice.subject()) + " là ";
        for (int i = 0; i < choice.alternatives().size(); i++) {
            FlowNode.Alternative alternative = choice.alternatives().get(i);
            indent(indent).append(i == 0 ? "alt " : "else ").append(subject)
                    .append(escape(alternative.label())).append('\n');
            renderNodes(alternative.body(), from, indent + 1);
        }
        indent(indent).append("end\n");
    }

    private void renderGuarded(FlowNode.Guarded guarded, String from, int indent) {
        if (guarded.handlers().isEmpty()) {
            renderNodes(guarded.body(), from, indent);
        }
        else {
            indent(indent).append("alt luồng bình thường\n");
            renderNodes(guarded.body(), from, indent + 1);
            for (FlowNode.Alternative handler : guarded.handlers()) {
                indent(indent).append("else ").append(escape(handler.label())).append('\n');
                if (handler.body().isEmpty()) {
                    note(indent + 1, "over", from, "bỏ qua lỗi (catch rỗng)");
                }
                else {
                    renderNodes(handler.body(), from, indent + 1);
                }
            }
            indent(indent).append("end\n");
        }
        if (!guarded.cleanup().isEmpty()) {
            indent(indent).append("rect rgb(240, 240, 240)\n");
            note(indent + 1, "over", from, "finally");
            renderNodes(guarded.cleanup(), from, indent + 1);
            indent(indent).append("end\n");
        }
    }

    /**
     * Mermaid không có "legend". Ghi một dòng ngắn cho biết còn bao nhiêu điểm mờ; danh sách đầy
     * đủ nằm trong phần ghi chú của báo cáo HTML/Markdown.
     *
     * <p>Vẫn phải có dòng này: nếu ai đó copy riêng khối mermaid đi dán chỗ khác, họ vẫn phải
     * biết diagram chưa phủ hết luồng.
     */
    private void appendLimitations() {
        int unresolved = this.flow.unresolved().size();
        int warnings = this.flow.warnings().size();
        if (unresolved == 0 && warnings == 0) {
            note(1, "over", CLIENT_ALIAS, "Phân tích tĩnh phủ hết luồng này, không có điểm mờ.");
            return;
        }
        note(1, "over", CLIENT_ALIAS, "Lưu ý: " + unresolved + " điểm chưa xác định, "
                + warnings + " giới hạn phân tích - xem phần ghi chú của báo cáo.");
    }

    private void message(String from, String to, String text, boolean solid, int indent) {
        indent(indent).append(from).append(solid ? "->>" : "-->>").append(to).append(": ")
                .append(text.isBlank() ? "..." : text).append('\n');
    }

    private void note(int indent, String position, String target, String text) {
        indent(indent).append("Note ").append(position).append(' ').append(target)
                .append(": ").append(text).append('\n');
    }

    private String alias(String fqn, String fallbackSimpleName) {
        String known = this.aliasByFqn.get(fqn);
        return known != null ? known : fallbackSimpleName.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private StringBuilder indent(int level) {
        return this.out.append("    ".repeat(Math.max(1, level)));
    }

    /**
     * Escape nhãn cho Mermaid.
     *
     * <p>Nhãn ở đây là code thật lấy từ repo (điều kiện if, biểu thức, tên exception), nên nó
     * hoàn toàn có thể chứa những ký tự phá cú pháp Mermaid. Bốn cái bẫy:
     * <ul>
     *   <li>{@code #} là ký tự mở comment/entity -&gt; phải thành {@code #35;}</li>
     *   <li>{@code ;} có thể được hiểu là dấu kết câu lệnh -&gt; {@code #59;}</li>
     *   <li>{@code <} {@code >} bị hiểu là thẻ HTML (rất hay gặp: {@code total > 1000})</li>
     *   <li>backtick gây lỗi "Unsupported markdown: codespan" từ Mermaid 11</li>
     * </ul>
     *
     * <p>Thay thế theo TỪNG KÝ TỰ trong một lượt duy nhất, không dùng chuỗi replace() liên tiếp:
     * replace('#') sinh ra ';' và replace(';') sinh ra '#', nên chạy nối tiếp sẽ tự phá kết quả
     * của nhau.
     */
    static String escape(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '#' -> escaped.append("#35;");
                case ';' -> escaped.append("#59;");
                case '<' -> escaped.append("#60;");
                case '>' -> escaped.append("#62;");
                case '"' -> escaped.append("#34;");
                case '`' -> escaped.append('\'');
                case '\n', '\r', '\t' -> escaped.append(' ');
                // KHÔNG escape ':' - Mermaid chỉ tách ở dấu ':' ĐẦU TIÊN (dấu do renderer tự
                // đặt), mọi dấu ':' sau đó là ký tự thường. Escape nó chỉ làm câu @Query và
                // ghi chú "bảng: orders" trở nên khó đọc khi xem mã nguồn sơ đồ.
                default -> escaped.append(character);
            }
        }
        // Token "end" làm Mermaid tưởng là đóng khối và vỡ cả diagram -> bọc trong ngoặc.
        // Giữ nguyên chữ hoa/thường của bản gốc vì đây là code, không phải văn bản tự do.
        return escaped.toString()
                .replaceAll("(?i)(?<![A-Za-z0-9_])(end)(?![A-Za-z0-9_])", "($1)");
    }

    private static String shortSha(String sha) {
        if (sha == null) {
            return "n/a";
        }
        return sha.length() > 8 ? sha.substring(0, 8) : sha;
    }
}
