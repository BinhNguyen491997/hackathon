package com.example.aihackathon.codeanalysis;

import java.util.List;
import java.util.Locale;

import com.example.aihackathon.codeanalysis.model.FlowComparison;

/**
 * Nối mục "Chưa xác định được" của phân tích tĩnh với các bước chỉ AI tìm ra.
 *
 * <p>Hai mục này nói về cùng một chỗ mà trước đây nằm rời nhau trong tài liệu. Chúng có cùng nguyên
 * nhân gốc: bản clone shallow không có jar dependency nên symbol solver không resolve được lớp đích
 * (ghi vào {@code flow.unresolved()}), còn LLM đọc source thì vẫn đoán ra được lớp nào (ghi vào
 * {@code comparison.onlyByAi()}). Người đọc phải tự đối chiếu nguyên văn lời gọi với nhãn bước của
 * AI mới nhận ra chúng là một - việc mà không ai làm.
 *
 * <p><b>Ghép theo tên method</b>, vì đó là thông tin duy nhất có ở cả hai phía: mục unresolved chỉ
 * lưu nguyên văn biểu thức gọi (không lưu lớp gọi), còn AI đưa ra cặp lớp-method. Tên lớp đích, nếu
 * cũng xuất hiện trong biểu thức, được dùng làm tín hiệu tăng độ tin cậy chứ không phải điều kiện
 * bắt buộc - phần lớn lời gọi không resolve được là gọi qua biến nên tên lớp không có trong text.
 *
 * <p>Kết quả luôn được gắn nhãn "ứng viên, chưa xác nhận". Ghép theo tên method có thể sai khi hai
 * lớp khác nhau có method cùng tên, nên đây là <b>gợi ý chỗ cần kiểm tra</b>, không phải câu trả lời.
 */
final class UnresolvedLinker {

    /**
     * Tên method quá ngắn không dùng để ghép: {@code of}, {@code to}, {@code id} khớp vào quá nhiều
     * biểu thức và biến gợi ý thành nhiễu.
     */
    private static final int MIN_METHOD_NAME_LENGTH = 3;

    /** Một điểm unresolved thật sự chỉ có vài ứng viên đáng xem; nhiều hơn là dấu hiệu ghép bừa. */
    private static final int MAX_CANDIDATES = 3;

    private UnresolvedLinker() {
    }

    /**
     * Các bước AI đề xuất có thể là câu trả lời cho một điểm unresolved.
     *
     * @param unresolved nguyên văn dòng trong {@code flow.unresolved()}
     */
    static List<FlowComparison.Step> candidatesFor(String unresolved, FlowComparison comparison) {
        if (unresolved == null || unresolved.isBlank() || comparison == null
                || comparison.onlyByAi().isEmpty()) {
            return List.of();
        }
        return comparison.onlyByAi().stream()
                .filter(step -> mentions(unresolved, step))
                .limit(MAX_CANDIDATES)
                .toList();
    }

    private static boolean mentions(String unresolved, FlowComparison.Step step) {
        String method = step.calleeMethod();
        if (method == null || method.length() < MIN_METHOD_NAME_LENGTH) {
            return false;
        }
        // Có dấu "." và "(" quanh tên method: đủ để phân biệt lời gọi thật với việc tên method tình
        // cờ là một phần của tên biến (vd. "verifyResult" không phải lời gọi "verify").
        boolean calledOnSomething = unresolved.contains("." + method + "(");
        boolean calledAndTypeNamed = unresolved.contains(method + "(")
                && unresolved.toLowerCase(Locale.ROOT)
                        .contains(step.calleeType().toLowerCase(Locale.ROOT));
        return calledOnSomething || calledAndTypeNamed;
    }

    /** Nhãn một dòng cho gợi ý, dùng chung cho mọi định dạng để không lệch cách diễn đạt. */
    static String label(FlowComparison.Step step) {
        String note = step.note() == null || step.note().isBlank() ? "" : " - " + step.note();
        return "AI đề xuất (CHƯA XÁC NHẬN): " + step.label() + note;
    }
}
