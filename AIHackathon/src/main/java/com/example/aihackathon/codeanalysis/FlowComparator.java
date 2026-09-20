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

import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.FlowComparison;
import com.example.aihackathon.codeanalysis.model.FlowNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Đối chiếu call graph của parser với call graph do LLM tự đọc code đề xuất.
 *
 * <p>Dùng định dạng dòng {@code STEP A.m -> B.n | ghi chú} thay vì JSON. Lý do thực dụng: LLM rất
 * hay làm sai cấu trúc JSON (thiếu dấu phẩy, bọc trong markdown fence, thêm lời dẫn), và một ký tự
 * sai là mất toàn bộ kết quả. Với định dạng dòng, dòng nào hỏng thì bỏ đúng dòng đó.
 */
final class FlowComparator {

    private static final Logger log = LoggerFactory.getLogger(FlowComparator.class);

    /** STEP Caller.method -> Callee.method | ghi chú (phần ghi chú không bắt buộc). */
    private static final Pattern STEP = Pattern.compile(
            "^\\s*STEP\\s+([\\w$]+)\\s*\\.\\s*([\\w$]+)\\s*(?:->|→|=>)\\s*([\\w$]+)\\s*\\.\\s*([\\w$]+)"
                    + "\\s*(?:\\|\\s*(.*))?$",
            Pattern.CASE_INSENSITIVE);

    private FlowComparator() {
    }

    static FlowComparison compare(ApiFlow flow, String proposal) {
        List<FlowComparison.Step> parserSteps = parserSteps(flow);

        if (proposal == null || proposal.isBlank()) {
            return new FlowComparison(List.of(), parserSteps, List.of(), null, false);
        }

        List<FlowComparison.Step> aiSteps = parse(proposal);
        String notes = freeText(proposal);

        // Bản đồ interface -> lớp hiện thực, lấy từ chính kết quả resolve của parser.
        Map<String, String> aliases = implementationAliases(flow);

        Map<String, FlowComparison.Step> aiByKey = new LinkedHashMap<>();
        aiSteps.forEach(step -> aiByKey.putIfAbsent(key(step, aliases), step));

        List<FlowComparison.Step> agreed = new ArrayList<>();
        List<FlowComparison.Step> onlyParser = new ArrayList<>();
        Set<String> matchedAiKeys = new LinkedHashSet<>();

        for (FlowComparison.Step step : parserSteps) {
            String key = key(step, aliases);
            if (aiByKey.containsKey(key)) {
                agreed.add(step);
                matchedAiKeys.add(key);
            }
            else {
                onlyParser.add(step);
            }
        }

        List<FlowComparison.Step> onlyAi = new ArrayList<>();
        aiByKey.forEach((key, step) -> {
            if (!matchedAiKeys.contains(key)) {
                onlyAi.add(step);
            }
        });

        log.info("đối chiếu call graph: {} bước khớp, {} chỉ parser, {} chỉ AI (AI đề xuất {} bước, "
                        + "{} alias interface->impl)",
                agreed.size(), onlyParser.size(), onlyAi.size(), aiSteps.size(), aliases.size());

        return new FlowComparison(agreed, onlyParser, List.copyOf(onlyAi), notes, true);
    }

    /**
     * Khoá so sánh, sau khi đổi tên interface thành lớp hiện thực mà parser đã xác định.
     *
     * <p>{@link FlowComparison.Step#key()} tự nó chỉ bỏ được hậu tố {@code Impl}, nên nó chỉ đúng
     * khi lớp hiện thực được đặt tên đúng khuôn {@code <Interface>Impl}. Code thật hay chèn thêm chữ
     * vào giữa: {@code EnrollC2PMasterCardServiceImpl} hiện thực {@code EnrollC2PService}. Khi đó
     * LLM ghi tên interface, parser ghi tên impl, và hai bên bị coi là bất đồng - một lệch GIẢ đẩy
     * cùng một bước vào cả hai bảng "chỉ AI" và "chỉ parser", rồi kéo mức đồng thuận xuống.
     *
     * <p>Sửa bằng cách dùng bản đồ thật của parser thay vì đoán theo tên. Đây KHÔNG phải rò rỉ kết
     * quả parser sang LLM: bản đồ chỉ được dùng lúc so, sau khi LLM đã trả lời xong.
     */
    private static String key(FlowComparison.Step step, Map<String, String> aliases) {
        return new FlowComparison.Step(canonical(step.callerType(), aliases), step.callerMethod(),
                canonical(step.calleeType(), aliases), step.calleeMethod(), step.note()).key();
    }

    private static String canonical(String typeSimpleName, Map<String, String> aliases) {
        if (typeSimpleName == null) {
            return null;
        }
        return aliases.getOrDefault(typeSimpleName.toLowerCase(Locale.ROOT), typeSimpleName);
    }

    /**
     * Interface (chữ thường) -&gt; tên lớp hiện thực, đọc từ ghi chú mà parser đã gắn lên node Call.
     *
     * <p>Nguồn là chính kết luận của {@code CallFlowBuilder.resolveImplementation()}: nó chỉ gắn ghi
     * chú này khi tìm thấy <b>đúng một</b> lớp hiện thực khai báo method đó. Nhiều implementation thì
     * parser không kết luận, nên ở đây cũng không có alias - đúng như vậy, vì lúc đó bất đồng giữa
     * hai bên là bất đồng THẬT và cần người xem.
     */
    private static Map<String, String> implementationAliases(ApiFlow flow) {
        Map<String, String> aliases = new LinkedHashMap<>();
        collectAliases(flow.nodes(), aliases);
        return aliases;
    }

    private static void collectAliases(List<FlowNode> nodes, Map<String, String> sink) {
        for (FlowNode node : nodes) {
            if (node instanceof FlowNode.Call call) {
                interfaceIn(call.note()).ifPresent(declared ->
                        sink.putIfAbsent(declared.toLowerCase(Locale.ROOT),
                                call.target().typeSimpleName()));
                collectAliases(call.children(), sink);
            }
            else if (node instanceof FlowNode.Choice choice) {
                choice.alternatives().forEach(alternative -> collectAliases(alternative.body(), sink));
            }
            else if (node instanceof FlowNode.Loop loop) {
                collectAliases(loop.body(), sink);
            }
            else if (node instanceof FlowNode.Guarded guarded) {
                collectAliases(guarded.body(), sink);
                guarded.handlers().forEach(handler -> collectAliases(handler.body(), sink));
                collectAliases(guarded.cleanup(), sink);
            }
        }
    }

    /** Tên interface trong ghi chú dạng {@code "hiện thực của EnrollC2PService | @Transactional"}. */
    private static Optional<String> interfaceIn(String note) {
        if (note == null || !note.contains(CallFlowBuilder.IMPLEMENTATION_NOTE)) {
            return Optional.empty();
        }
        Matcher matcher = IMPLEMENTATION_OF.matcher(note);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static final Pattern IMPLEMENTATION_OF = Pattern.compile(
            Pattern.quote(CallFlowBuilder.IMPLEMENTATION_NOTE) + "([A-Za-z_$][\\w$]*)");

    /** Các cặp (người gọi -> người bị gọi) mà parser khẳng định. */
    private static List<FlowComparison.Step> parserSteps(ApiFlow flow) {
        List<FlowComparison.Step> steps = new ArrayList<>();
        String entry = flow.endpoint().controllerSimpleName();
        String entryMethod = flow.endpoint().methodName();
        walk(flow.nodes(), entry, entryMethod, steps);

        // gộp trùng: cùng một cặp gọi xuất hiện ở nhiều nhánh vẫn chỉ là một bước
        Map<String, FlowComparison.Step> unique = new LinkedHashMap<>();
        steps.forEach(step -> unique.putIfAbsent(step.key(), step));
        return List.copyOf(unique.values());
    }

    private static void walk(List<FlowNode> nodes, String callerType, String callerMethod,
            List<FlowComparison.Step> sink) {

        for (FlowNode node : nodes) {
            if (node instanceof FlowNode.Call call) {
                sink.add(new FlowComparison.Step(callerType, callerMethod,
                        call.target().typeSimpleName(), call.target().methodName(), call.note()));
                walk(call.children(), call.target().typeSimpleName(), call.target().methodName(), sink);
            }
            else if (node instanceof FlowNode.Choice choice) {
                choice.alternatives().forEach(alt -> walk(alt.body(), callerType, callerMethod, sink));
            }
            else if (node instanceof FlowNode.Loop loop) {
                walk(loop.body(), callerType, callerMethod, sink);
            }
            else if (node instanceof FlowNode.Guarded guarded) {
                walk(guarded.body(), callerType, callerMethod, sink);
                guarded.handlers().forEach(handler ->
                        walk(handler.body(), callerType, callerMethod, sink));
                walk(guarded.cleanup(), callerType, callerMethod, sink);
            }
        }
    }

    static List<FlowComparison.Step> parse(String proposal) {
        List<FlowComparison.Step> steps = new ArrayList<>();
        int malformed = 0;

        for (String line : proposal.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher matcher = STEP.matcher(trimmed);
            if (matcher.matches()) {
                String note = matcher.group(5) == null ? null : matcher.group(5).trim();
                steps.add(new FlowComparison.Step(matcher.group(1), matcher.group(2),
                        matcher.group(3), matcher.group(4),
                        note == null || note.isEmpty() ? null : note));
            }
            else if (trimmed.toUpperCase(java.util.Locale.ROOT).startsWith("STEP")) {
                // co y dinh la mot buoc nhung sai cu phap -> dem lai de biet chat luong output
                malformed++;
            }
        }
        if (malformed > 0) {
            log.warn("bỏ qua {} dòng STEP sai cú pháp trong đề xuất của LLM", malformed);
        }
        return steps;
    }

    /** Phần văn bản không phải bước: giữ lại vì LLM hay giải thích chỗ nó không chắc. */
    private static String freeText(String proposal) {
        List<String> lines = new ArrayList<>();
        for (String line : proposal.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || STEP.matcher(trimmed).matches()) {
                continue;
            }
            if (trimmed.toUpperCase(java.util.Locale.ROOT).startsWith("STEP")) {
                continue;
            }
            lines.add(trimmed);
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }
}
