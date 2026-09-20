package com.example.aihackathon.agent;

import java.util.ArrayList;
import java.util.List;

import com.example.aihackathon.codeanalysis.NarrativeWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

/**
 * Nhờ LLM diễn giải bảng dẫn chứng thành văn xuôi nghiệp vụ.
 *
 * <p>Gọi thẳng {@link ChatModel}, không qua {@code ChatClient} của agent: ở đây không cần bộ nhớ
 * hội thoại và cũng không nên có tool nào, vì việc duy nhất của model là viết - mọi dữ kiện đã
 * được đưa sẵn.
 *
 * <p>Model KHÔNG hề nhìn thấy source code, cũng không thấy {@code file:line}. Nó chỉ nhận danh sách
 * dữ kiện đã được rút tất định kèm mã {@code [En]}. Giới hạn đầu vào như vậy là cách rẻ nhất để
 * giảm bịa: không có gì để bịa ngoài những gì được đưa.
 */
@Service
public class SpecNarrator implements NarrativeWriter {

    private static final Logger log = LoggerFactory.getLogger(SpecNarrator.class);

    private static final String SYSTEM_PROMPT = """
            Bạn là chuyên gia phân tích nghiệp vụ, viết tài liệu cho BA người Việt đọc.

            Đầu vào là danh sách DỮ KIỆN đã được rút tự động từ source code, mỗi dữ kiện có một mã
            dạng E1, E2. Việc của bạn là diễn giải chúng thành văn xuôi nghiệp vụ dễ hiểu.

            RÀNG BUỘC BẮT BUỘC:
            - Chỉ được dùng thông tin trong danh sách dữ kiện. Không thêm bước, tên class, tên
              method, hay quy tắc nào không có trong đó.
            - Mỗi câu phát biểu về hành vi hệ thống phải kèm mã dẫn chứng: [E3] hoặc [E3, E7].
            - TUYỆT ĐỐI không viết tên file kèm số dòng (dạng Abc.java:42). Bạn không có thông tin
              đó, và hệ thống sẽ từ chối bản viết nếu phát hiện.
            - Không suy ra "người dùng cần gì". Chỉ mô tả hệ thống ĐANG làm gì.

            CẤU TRÚC BẮT BUỘC, đúng hai mục, dùng markdown:

            ### Hệ thống đang làm gì
            Văn xuôi hoặc gạch đầu dòng, theo trình tự nghiệp vụ. Mỗi ý kèm mã dẫn chứng.
            Ưu tiên ngôn ngữ nghiệp vụ: nói "không cho tạo đơn khi tổng tiền bằng 0" thay vì
            "gọi validate() rồi throw IllegalArgumentException".

            ### Suy luận - cần xác nhận với dev
            Những điều bạn SUY RA chứ không đọc thẳng được từ dữ kiện. Mỗi ý phải nói rõ suy ra từ
            dẫn chứng nào và vì sao chưa chắc. Nếu không có gì đáng suy luận thì ghi "Không có".
            Ví dụ đúng: "Ngưỡng ở [E13] có thể là mốc phân loại đơn giá trị cao, nhưng code không
            nói rõ căn cứ - cần dev xác nhận."

            Viết ngắn gọn, không lặp lại nguyên văn dữ kiện, không mở đầu bằng câu dẫn nhập.
            """;

    private final ChatModel chatModel;

    public SpecNarrator(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String write(String promptText, String feedback) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.add(new UserMessage(promptText));

        if (feedback != null && !feedback.isBlank()) {
            // Đưa đúng lỗi của lần trước vào để model sửa, thay vì nhắc lại quy tắc chung chung
            messages.add(new UserMessage("""
                    Bản viết trước của bạn KHÔNG ĐẠT kiểm tra truy vết:
                    %s

                    Viết lại toàn bộ, chỉ dùng những mã dẫn chứng có thật trong danh sách phía trên,
                    và không viết tên file kèm số dòng.""".formatted(feedback)));
        }

        long startNanos = System.nanoTime();
        String narrative = this.chatModel.call(new Prompt(messages))
                .getResult().getOutput().getText();

        log.info("viết mô tả đặc tả xong sau {} ms | {} ký tự | lần thử lại: {}",
                (System.nanoTime() - startNanos) / 1_000_000,
                narrative == null ? 0 : narrative.length(), feedback != null);
        return narrative;
    }
}
