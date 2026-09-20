package com.example.aihackathon.codeanalysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.aihackathon.codeanalysis.JavaSourceIndex.IndexedType;
import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.FlowNode;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gom nguyên văn source của các method trong luồng để LLM tự đọc và dựng call graph riêng.
 *
 * <p>Đưa cả thân method, không phải bản đã rút gọn: mục đích của bước đối chiếu là xem LLM có tìm
 * ra thứ parser bỏ sót hay không. Nếu chỉ đưa những gì parser đã hiểu thì LLM chỉ xác nhận lại
 * parser, và bước đối chiếu thành vô nghĩa.
 *
 * <p>Kèm cả khai báo field của lớp, vì chỗ 39 điểm unresolved của repo thật thường là lời gọi trên
 * field mà symbol solver không resolve được kiểu.
 */
final class SourceBundleBuilder {

    private static final Logger log = LoggerFactory.getLogger(SourceBundleBuilder.class);

    private SourceBundleBuilder() {
    }

    /**
     * @param maxChars hạn mức ký tự; vượt thì cắt và ghi rõ đã cắt, để LLM không tưởng là đã thấy hết
     */
    static String build(JavaSourceIndex index, ApiFlow flow, int maxChars) {
        Map<String, String> methodsByKey = new LinkedHashMap<>();

        // method vào của controller luôn phải có, đây là điểm bắt đầu của luồng
        index.type(flow.endpoint().controllerFqn()).ifPresent(controller ->
                controller.declaration().getMethodsByName(flow.endpoint().methodName()).stream()
                        .findFirst()
                        .ifPresent(method -> methodsByKey.put(
                                flow.endpoint().controllerSimpleName() + "." + method.getNameAsString(),
                                render(controller, method))));

        Set<String> wanted = new LinkedHashSet<>();
        collectExpandedTargets(flow.nodes(), wanted);

        for (String target : wanted) {
            int dot = target.lastIndexOf('#');
            if (dot < 0) {
                continue;
            }
            String fqn = target.substring(0, dot);
            String methodName = target.substring(dot + 1);
            Optional<IndexedType> type = index.type(fqn);
            if (type.isEmpty()) {
                continue;
            }
            for (MethodDeclaration method : type.get().declaration().getMethodsByName(methodName)) {
                if (method.getBody().isPresent()) {
                    methodsByKey.putIfAbsent(type.get().simpleName() + "." + methodName,
                            render(type.get(), method));
                    break;
                }
            }
        }

        StringBuilder out = new StringBuilder(Math.min(maxChars, 8192));
        out.append("ENDPOINT: ").append(flow.endpoint().label()).append('\n');
        out.append("Điểm vào: ").append(flow.endpoint().controllerSimpleName()).append('.')
                .append(flow.endpoint().methodName()).append("()\n");

        int truncated = 0;
        for (Map.Entry<String, String> entry : methodsByKey.entrySet()) {
            if (out.length() + entry.getValue().length() > maxChars) {
                truncated++;
                continue;
            }
            out.append(entry.getValue());
        }
        if (truncated > 0) {
            out.append("\n[ĐÃ CẮT ").append(truncated).append(" method vì vượt hạn mức ")
                    .append(maxChars).append(" ký tự - phần luồng trong các method đó không có ở đây]\n");
        }

        log.info("gói source cho AI: {} method, {} ký tự{}", methodsByKey.size() - truncated,
                out.length(), truncated > 0 ? " (cắt " + truncated + " method)" : "");
        return out.toString();
    }

    /** Chỉ lấy những method đã được mở rộng: đó là các method mà luồng thật sự đi vào. */
    private static void collectExpandedTargets(List<FlowNode> nodes, Set<String> sink) {
        for (FlowNode node : nodes) {
            if (node instanceof FlowNode.Call call) {
                if (call.expanded()) {
                    sink.add(call.target().typeFqn() + "#" + call.target().methodName());
                }
                collectExpandedTargets(call.children(), sink);
            }
            else if (node instanceof FlowNode.Choice choice) {
                choice.alternatives().forEach(alt -> collectExpandedTargets(alt.body(), sink));
            }
            else if (node instanceof FlowNode.Loop loop) {
                collectExpandedTargets(loop.body(), sink);
            }
            else if (node instanceof FlowNode.Guarded guarded) {
                collectExpandedTargets(guarded.body(), sink);
                guarded.handlers().forEach(handler -> collectExpandedTargets(handler.body(), sink));
                collectExpandedTargets(guarded.cleanup(), sink);
            }
        }
    }

    private static String render(IndexedType type, MethodDeclaration method) {
        StringBuilder out = new StringBuilder(1024);
        out.append("\n===== ").append(type.simpleName()).append('.')
                .append(method.getNameAsString()).append(" =====\n");
        out.append("// file: ").append(type.relativePath()).append('\n');

        List<String> fields = new ArrayList<>();
        for (FieldDeclaration field : type.declaration().getFields()) {
            fields.add(field.toString().replaceAll("\\s+", " ").trim());
        }
        if (!fields.isEmpty()) {
            out.append("// field của ").append(type.simpleName()).append(":\n");
            fields.forEach(field -> out.append("//   ").append(field).append('\n'));
        }
        out.append(method).append('\n');
        return out.toString();
    }
}
