package com.example.aihackathon.codeanalysis.model;

import java.util.List;

/**
 * Một bước trong luồng thực thi đã trích ra từ source.
 *
 * <p>Cây node này giữ lại cấu trúc điều khiển (if/switch/loop/try) thay vì chỉ là danh sách
 * lời gọi phẳng, nhờ vậy sequence diagram mới có alt/else/loop - đúng thứ BA cần để thấy
 * "trường hợp nào thì đi nhánh nào".
 *
 * <p>Mỗi node mang theo {@link Source} - vị trí thật trong repo. Nhờ đó tài liệu đặc tả dẫn được
 * {@code file:line} cho từng phát biểu mà không phải resolve lại lần hai, và model ngôn ngữ không
 * bao giờ phải (và không bao giờ được) tự viết số dòng.
 */
public sealed interface FlowNode {

    /** Vị trí trong repo của đoạn code sinh ra node này. */
    record Source(String file, int line) {

        public static final Source UNKNOWN = new Source("", 0);

        public boolean known() {
            return !this.file.isEmpty() && this.line > 0;
        }

        @Override
        public String toString() {
            return known() ? this.file + ":" + this.line : "(không rõ vị trí)";
        }
    }

    /** Vị trí của node này trong source. */
    Source source();

    /**
     * Một lời gọi method sang lớp khác (hoặc sang chính nó).
     *
     * @param target   method được gọi
     * @param label    nhãn hiện trên mũi tên
     * @param note     ghi chú thêm: bảng DB, đệ quy, đã mô tả ở trên... có thể null
     * @param children luồng bên trong method đó; rỗng nếu không đi sâu
     */
    record Call(MethodRef target, String label, String note, Source source, List<FlowNode> children)
            implements FlowNode {

        public Call {
            children = List.copyOf(children);
        }

        public boolean expanded() {
            return !this.children.isEmpty();
        }
    }

    /**
     * Rẽ nhánh: dùng cho cả {@code if/else} và {@code switch}.
     *
     * @param subject      biểu thức được xét; rỗng với if thường
     * @param alternatives các nhánh, theo thứ tự xuất hiện trong code
     */
    record Choice(String subject, Source source, List<Alternative> alternatives) implements FlowNode {

        public Choice {
            alternatives = List.copyOf(alternatives);
        }
    }

    /** Vòng lặp for/while/do-while/forEach. */
    record Loop(String label, Source source, List<FlowNode> body) implements FlowNode {

        public Loop {
            body = List.copyOf(body);
        }
    }

    /**
     * try/catch/finally. Phần {@code handlers} là các catch, dùng để vẽ nhánh lỗi -
     * thông tin mà BA thường hỏi nhất và thường bị thiếu trong tài liệu.
     */
    record Guarded(Source source, List<FlowNode> body, List<Alternative> handlers,
            List<FlowNode> cleanup) implements FlowNode {

        public Guarded {
            body = List.copyOf(body);
            handlers = List.copyOf(handlers);
            cleanup = List.copyOf(cleanup);
        }
    }

    /** Kết thúc sớm: return giữa luồng hoặc throw. */
    record Terminal(Kind kind, String detail, Source source) implements FlowNode {

        public enum Kind {
            RETURN, THROW
        }
    }

    /**
     * Chỗ static analysis không kết luận được (dynamic dispatch, reflection, nhiều
     * implementation, lớp nằm ngoài repo). Bắt buộc phải hiện lên diagram thay vì bỏ qua,
     * vì một diagram liền mạch nhưng sai còn tệ hơn một diagram có lỗ hổng được ghi rõ.
     */
    record Unresolved(String expression, String reason, Source source) implements FlowNode {
    }

    /**
     * Một nhánh của Choice hoặc một catch của Guarded.
     *
     * @param source vị trí của điều kiện/catch, để dẫn chứng được từng nhánh riêng
     */
    record Alternative(String label, Source source, List<FlowNode> body) {

        public Alternative {
            body = List.copyOf(body);
        }
    }
}
