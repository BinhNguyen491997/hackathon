package com.example.aihackathon.codeanalysis.model;

import java.util.List;
import java.util.Locale;

/**
 * Kết quả đối chiếu giữa call graph do parser dựng và call graph do LLM tự đọc code suy ra.
 *
 * <p>Mục đích không phải chọn ra bên nào đúng, mà là khoanh vùng chỗ hai bên không đồng ý - đó
 * chính là chỗ cần người xem lại. Nhóm {@code onlyByAi} đáng chú ý nhất: đó là những bước LLM thấy
 * mà phân tích tĩnh bỏ sót, thường vì thiếu jar dependency nên symbol solver bó tay.
 *
 * @param onlyByParser bước parser khẳng định mà LLM không nhắc tới
 * @param onlyByAi     bước LLM đề xuất mà parser không tìm ra - ứng viên để điều tra
 * @param aiNoteText   phần ghi chú tự do của LLM, không parse được thành bước
 */
public record FlowComparison(
        List<Step> agreed,
        List<Step> onlyByParser,
        List<Step> onlyByAi,
        String aiNoteText,
        boolean aiResponded) {

    public FlowComparison {
        agreed = List.copyOf(agreed);
        onlyByParser = List.copyOf(onlyByParser);
        onlyByAi = List.copyOf(onlyByAi);
    }

    /** Không chạy đối chiếu (người dùng không bật cờ). */
    public static FlowComparison notRun() {
        return new FlowComparison(List.of(), List.of(), List.of(), null, false);
    }

    public int totalParserSteps() {
        return this.agreed.size() + this.onlyByParser.size();
    }

    public int totalAiSteps() {
        return this.agreed.size() + this.onlyByAi.size();
    }

    /**
     * Tỷ lệ bước mà hai bên cùng tìm ra, trên tổng số bước hợp của cả hai.
     *
     * <p>Không phải "độ chính xác của AI": cả hai bên đều có thể sai. Đây chỉ là mức độ hai phương
     * pháp độc lập cho ra cùng kết luận - càng cao thì càng ít chỗ phải xem lại.
     */
    public int agreementPercent() {
        int union = this.agreed.size() + this.onlyByParser.size() + this.onlyByAi.size();
        return union == 0 ? 0 : Math.round(this.agreed.size() * 100f / union);
    }

    /**
     * Một bước gọi: ai gọi ai.
     *
     * @param note ghi chú kèm theo; với bước do LLM đề xuất thì đây là lý giải của nó
     */
    public record Step(String callerType, String callerMethod, String calleeType, String calleeMethod,
            String note) {

        public String label() {
            return this.callerType + "." + this.callerMethod + " -> "
                    + this.calleeType + "." + this.calleeMethod;
        }

        /**
         * Khoá so sánh, đã chuẩn hoá.
         *
         * <p>Bỏ hậu tố {@code Impl} và không phân biệt hoa thường: LLM thường viết tên interface
         * ({@code OrderService}) ở chỗ parser đã resolve sang lớp hiện thực
         * ({@code OrderServiceImpl}). Coi hai cái đó là lệch nhau thì bảng đối chiếu sẽ đầy khác
         * biệt giả, và chỗ lệch thật bị chôn mất.
         */
        public String key() {
            return normalize(this.callerType) + "." + normalize(this.callerMethod) + ">"
                    + normalize(this.calleeType) + "." + normalize(this.calleeMethod);
        }

        private static String normalize(String value) {
            String lower = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (lower.endsWith("impl") && lower.length() > 4) {
                lower = lower.substring(0, lower.length() - 4);
            }
            return lower;
        }
    }
}
