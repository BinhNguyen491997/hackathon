package com.example.aihackathon.codeanalysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.FlowNode;
import com.example.aihackathon.codeanalysis.model.ParticipantKind;

/**
 * Diễn giải cây luồng thành văn bản.
 *
 * <p>Có hai người đọc: BA (cần đọc hiểu mà không cần mở PlantUML) và LLM (cần bản gọn để
 * giải thích lại bằng ngôn ngữ nghiệp vụ). Vì thế bản này ưu tiên ngắn và có cấu trúc, thay vì
 * lặp lại toàn bộ nội dung file .puml - đưa cả .puml vào prompt là cách nhanh nhất để nổ token.
 *
 * <p>Vẫn là bước tất định: không gọi model, chỉ đọc lại cây đã dựng.
 */
final class FlowSummarizer {

    private FlowSummarizer() {
    }

    /**
     * Rút dữ kiện + phần trình tự dạng cây ra riêng, để markdown và HTML dùng lại đúng kết quả
     * mà bản text đang dùng. Ba renderer đọc cùng một nguồn nên không thể lệch nhau.
     */
    static MarkdownReportRenderer.FlowFacts extract(ApiFlow flow) {
        Facts facts = new Facts();
        collect(flow.nodes(), facts);

        StringBuilder outline = new StringBuilder(1024);
        appendOutline(flow.nodes(), outline, 0);

        return new MarkdownReportRenderer.FlowFacts(
                List.copyOf(facts.dataAccess),
                List.copyOf(facts.externals),
                List.copyOf(facts.conditions),
                List.copyOf(facts.errors),
                outline.toString());
    }

    static String summarize(ApiFlow flow) {
        Facts facts = new Facts();
        collect(flow.nodes(), facts);

        StringBuilder out = new StringBuilder(2048);
        out.append("ENDPOINT: ").append(flow.endpoint().label()).append('\n');
        if (flow.endpoint().summary() != null && !flow.endpoint().summary().isBlank()) {
            out.append("Mô tả (@Operation): ").append(flow.endpoint().summary()).append('\n');
        }
        out.append("Vào ở: ").append(flow.endpoint().controllerSimpleName()).append('.')
                .append(flow.endpoint().methodName()).append("()  (")
                .append(flow.endpoint().sourceFile()).append(':').append(flow.endpoint().line())
                .append(")\n");
        out.append("Commit: ").append(flow.branch()).append(" @ ")
                .append(flow.commitSha() == null ? "n/a" : flow.commitSha()).append('\n');

        out.append("\n== CÁC LỚP THAM GIA ==\n");
        for (ApiFlow.Participant participant : flow.participants()) {
            out.append("- ").append(participant.displayName())
                    .append(" [").append(participant.kind().stereotype()).append(']');
            if (participant.note() != null && !participant.note().isBlank()) {
                out.append(" - ").append(participant.note());
            }
            out.append('\n');
        }

        out.append("\n== TRÌNH TỰ ==\n");
        appendOutline(flow.nodes(), out, 0);

        appendSection(out, "DỮ LIỆU BỊ TÁC ĐỘNG - gọi vào database "
                + (flow.databaseName() == null || flow.databaseName().isBlank()
                        ? "(chưa khai báo tên)" : flow.databaseName()), facts.dataAccess);
        appendSection(out, "GỌI RA NGOÀI HỆ THỐNG", facts.externals);
        appendSection(out, "ĐIỀU KIỆN RẼ NHÁNH", facts.conditions);
        appendSection(out, "NHÁNH LỖI", facts.errors);
        appendSection(out, "Chưa xác định được - cần người kiểm tra (Chưa có AI)", flow.unresolved());
        appendSection(out, "GIỚI HẠN PHÂN TÍCH", flow.warnings());

        if (flow.unresolved().isEmpty() && flow.warnings().isEmpty()) {
            out.append("\nPhân tích tĩnh phủ hết luồng này, không có điểm mờ.\n");
        }
        return out.toString();
    }

    private static void appendSection(StringBuilder out, String title, Collection<String> items) {
        if (items.isEmpty()) {
            return;
        }
        out.append("\n== ").append(title).append(" ==\n");
        items.forEach(item -> out.append("- ").append(item).append('\n'));
    }

    private static void appendOutline(List<FlowNode> nodes, StringBuilder out, int level) {
        String pad = "  ".repeat(level);
        for (FlowNode node : nodes) {
            if (node instanceof FlowNode.Call call) {
                out.append(pad).append("-> ").append(call.target().typeSimpleName()).append('.')
                        .append(call.label()).append("  [").append(call.target().kind().stereotype())
                        .append(']');
                if (call.note() != null && !call.note().isBlank()) {
                    out.append("  (").append(call.note()).append(')');
                }
                out.append('\n');
                appendOutline(call.children(), out, level + 1);
            }
            else if (node instanceof FlowNode.Choice choice) {
                for (int i = 0; i < choice.alternatives().size(); i++) {
                    FlowNode.Alternative alternative = choice.alternatives().get(i);
                    out.append(pad).append(i == 0 ? "NẾU " : "NGƯỢC LẠI NẾU ");
                    if (!choice.subject().isBlank()) {
                        out.append(choice.subject()).append(" là ");
                    }
                    out.append(alternative.label()).append(":\n");
                    appendOutline(alternative.body(), out, level + 1);
                }
            }
            else if (node instanceof FlowNode.Loop loop) {
                out.append(pad).append("LẶP ").append(loop.label()).append(":\n");
                appendOutline(loop.body(), out, level + 1);
            }
            else if (node instanceof FlowNode.Guarded guarded) {
                appendOutline(guarded.body(), out, level);
                for (FlowNode.Alternative handler : guarded.handlers()) {
                    out.append(pad).append("KHI ").append(handler.label()).append(":\n");
                    appendOutline(handler.body(), out, level + 1);
                }
                if (!guarded.cleanup().isEmpty()) {
                    out.append(pad).append("CUỐI CÙNG (finally):\n");
                    appendOutline(guarded.cleanup(), out, level + 1);
                }
            }
            else if (node instanceof FlowNode.Terminal terminal) {
                out.append(pad)
                        .append(terminal.kind() == FlowNode.Terminal.Kind.THROW ? "NÉM LỖI: " : "TRẢ VỀ: ")
                        .append(terminal.detail()).append('\n');
            }
            else if (node instanceof FlowNode.Unresolved unresolved) {
                out.append(pad).append("(?) ").append(unresolved.expression())
                        .append(" - ").append(unresolved.reason()).append('\n');
            }
        }
    }

    private static void collect(List<FlowNode> nodes, Facts facts) {
        for (FlowNode node : nodes) {
            if (node instanceof FlowNode.Call call) {
                String entry = call.target().typeSimpleName() + "." + call.label()
                        + (call.note() == null ? "" : "  (" + call.note() + ")");
                if (call.target().kind() == ParticipantKind.REPOSITORY) {
                    facts.dataAccess.add(entry);
                }
                if (call.target().kind() == ParticipantKind.EXTERNAL) {
                    facts.externals.add(entry);
                }
                collect(call.children(), facts);
            }
            else if (node instanceof FlowNode.Choice choice) {
                choice.alternatives().forEach(alternative -> {
                    if (!"ngược lại".equals(alternative.label())) {
                        facts.conditions.add(choice.subject().isBlank()
                                ? alternative.label()
                                : choice.subject() + " là " + alternative.label());
                    }
                    collect(alternative.body(), facts);
                });
            }
            else if (node instanceof FlowNode.Loop loop) {
                collect(loop.body(), facts);
            }
            else if (node instanceof FlowNode.Guarded guarded) {
                collect(guarded.body(), facts);
                guarded.handlers().forEach(handler -> {
                    facts.errors.add(handler.label());
                    collect(handler.body(), facts);
                });
                collect(guarded.cleanup(), facts);
            }
            else if (node instanceof FlowNode.Terminal terminal
                    && terminal.kind() == FlowNode.Terminal.Kind.THROW) {
                facts.errors.add("ném " + terminal.detail());
            }
        }
    }

    /** Các dữ kiện rút ra khi đi qua cây, dùng cho phần tổng hợp cuối bản mô tả. */
    private static final class Facts {

        private final Set<String> dataAccess = new LinkedHashSet<>();

        private final Set<String> externals = new LinkedHashSet<>();

        private final Set<String> conditions = new LinkedHashSet<>();

        private final Set<String> errors = new LinkedHashSet<>();
    }

    /** Danh sách endpoint dạng text, dùng khi người dùng nhập sai path và cần gợi ý. */
    static String listEndpoints(List<ApiEndpoint> endpoints, int limit) {
        if (endpoints.isEmpty()) {
            return "Không tìm thấy endpoint nào trong repo (không có @RestController/@Controller).";
        }
        List<String> lines = new ArrayList<>();
        endpoints.stream().limit(limit).forEach(endpoint -> lines.add("- " + endpoint.describe()));
        String result = String.join("\n", lines);
        if (endpoints.size() > limit) {
            result += "\n... còn " + (endpoints.size() - limit) + " endpoint nữa.";
        }
        return result;
    }
}
