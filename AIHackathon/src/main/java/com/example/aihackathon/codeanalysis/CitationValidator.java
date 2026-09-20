package com.example.aihackathon.codeanalysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.aihackathon.codeanalysis.model.CodeEvidence;

/**
 * Kiểm tra phần văn bản do model ngôn ngữ viết: mọi trích dẫn phải trỏ tới dẫn chứng thật.
 *
 * <p>Nhắc trong system prompt là chưa đủ. Model vẫn sẽ bịa - và bịa citation là dạng bịa nguy hiểm
 * nhất, vì nó làm câu văn trông có căn cứ. Lớp này biến yêu cầu "phải dẫn nguồn" từ lời khuyên
 * thành một phép kiểm tra chạy được: đối chiếu từng mã {@code [En]} với tập dẫn chứng đã thu, và
 * bắt cả trường hợp model tự gõ {@code file:line} thay vì dùng mã.
 */
public final class CitationValidator {

    /** Trích dẫn hợp lệ: [E1], [E12]. Cho phép nhiều mã trong một cặp ngoặc: [E1, E2]. */
    private static final Pattern CITATION = Pattern.compile("\\[(E\\d+(?:\\s*,\\s*E\\d+)*)]");

    /** Model tự gõ đường dẫn kèm số dòng - đúng thứ bị cấm vì số dòng đó không kiểm chứng được. */
    private static final Pattern HAND_WRITTEN_SOURCE =
            Pattern.compile("[\\w/\\\\.-]+\\.java:\\d+");

    private CitationValidator() {
    }

    /**
     * @param citedIds     các mã dẫn chứng model có dẫn, và chúng tồn tại thật
     * @param invalidIds   mã model dẫn nhưng không có trong tập dẫn chứng - dấu hiệu bịa
     * @param handWritten  các chuỗi file:line model tự gõ ra thay vì dùng mã
     * @param uncitedKinds loại dẫn chứng quan trọng mà model không hề dẫn tới
     */
    public record Result(
            List<String> citedIds,
            List<String> invalidIds,
            List<String> handWritten,
            List<CodeEvidence.Kind> uncitedKinds) {

        public Result {
            citedIds = List.copyOf(citedIds);
            invalidIds = List.copyOf(invalidIds);
            handWritten = List.copyOf(handWritten);
            uncitedKinds = List.copyOf(uncitedKinds);
        }

        public boolean trustworthy() {
            return this.invalidIds.isEmpty() && this.handWritten.isEmpty();
        }

        /** Thông báo để đưa lại cho model sửa, hoặc để cảnh báo người đọc. */
        public String describe() {
            if (trustworthy()) {
                return "Mọi trích dẫn đều trỏ tới dẫn chứng thật (" + this.citedIds.size() + " trích dẫn).";
            }
            StringBuilder message = new StringBuilder("Văn bản có vấn đề về truy vết:");
            if (!this.invalidIds.isEmpty()) {
                message.append("\n- Dẫn tới mã không tồn tại: ").append(String.join(", ", this.invalidIds))
                        .append(". Đây là dẫn chứng bịa, phải bỏ hoặc thay bằng mã thật.");
            }
            if (!this.handWritten.isEmpty()) {
                message.append("\n- Tự gõ đường dẫn kèm số dòng: ")
                        .append(String.join(", ", this.handWritten))
                        .append(". Không được tự viết file:line, chỉ dùng mã [En].");
            }
            return message.toString();
        }
    }

    public static Result validate(String text, List<CodeEvidence> evidence) {
        Set<String> known = new LinkedHashSet<>();
        evidence.forEach(item -> known.add(item.id()));

        List<String> cited = new ArrayList<>();
        List<String> invalid = new ArrayList<>();

        if (text != null) {
            Matcher matcher = CITATION.matcher(text);
            while (matcher.find()) {
                for (String id : matcher.group(1).split("\\s*,\\s*")) {
                    if (known.contains(id)) {
                        if (!cited.contains(id)) {
                            cited.add(id);
                        }
                    }
                    else if (!invalid.contains(id)) {
                        invalid.add(id);
                    }
                }
            }
        }

        List<String> handWritten = new ArrayList<>();
        if (text != null) {
            Matcher matcher = HAND_WRITTEN_SOURCE.matcher(text);
            while (matcher.find() && handWritten.size() < 10) {
                if (!handWritten.contains(matcher.group())) {
                    handWritten.add(matcher.group());
                }
            }
        }

        return new Result(cited, invalid, handWritten, uncitedKinds(cited, evidence));
    }

    /**
     * Loại dẫn chứng có dữ liệu nhưng không câu nào trong tài liệu dẫn tới.
     *
     * <p>Không phải lỗi, nhưng là dấu hiệu tài liệu bỏ sót một mặt của đặc tả - ví dụ có quy tắc
     * validation trong code mà phần mô tả không nhắc gì.
     */
    private static List<CodeEvidence.Kind> uncitedKinds(List<String> cited, List<CodeEvidence> evidence) {
        Set<CodeEvidence.Kind> available = new LinkedHashSet<>();
        Set<CodeEvidence.Kind> used = new LinkedHashSet<>();
        for (CodeEvidence item : evidence) {
            available.add(item.kind());
            if (cited.contains(item.id())) {
                used.add(item.kind());
            }
        }
        available.removeAll(used);
        return List.copyOf(available);
    }
}
