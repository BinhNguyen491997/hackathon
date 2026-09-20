package com.example.aihackathon.memory;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.Assert;

/**
 * Tóm tắt hội thoại bằng chính model đang dùng (GreenNode MaaS).
 *
 * <p>Gọi thẳng {@link ChatModel} chứ không dùng {@code ChatClient} của agent: ChatClient đó đã gắn
 * tool và memory advisor, tóm tắt qua đó sẽ đệ quy vào chính bộ nhớ đang nén.
 */
public class ChatModelSummarizer implements SummarizingChatMemory.Summarizer {

    private static final Logger log = LoggerFactory.getLogger(ChatModelSummarizer.class);

    private static final String SUMMARY_PROMPT = """
            Bạn là bộ nén ngữ cảnh. Nhiệm vụ: tóm tắt đoạn hội thoại dưới đây để một trợ lý khác
            đọc vào là tiếp tục được cuộc trò chuyện.

            Quy tắc:
            - Giữ: sự việc, quyết định, con số, ngày tháng, tên task, yêu cầu còn dở của người dùng.
            - Bỏ: lời chào, diễn giải dài, câu đã được thay thế bởi thông tin mới hơn.
            - Viết tiếng Việt, dạng gạch đầu dòng, tối đa %d từ.
            - Chỉ xuất bản tóm tắt, không thêm lời dẫn.
            """;

    private final ChatModel chatModel;

    private final int maxWords;

    public ChatModelSummarizer(ChatModel chatModel, int maxWords) {
        Assert.notNull(chatModel, "chatModel cannot be null");
        Assert.isTrue(maxWords > 0, "maxWords must be greater than 0");
        this.chatModel = chatModel;
        this.maxWords = maxWords;
    }

    @Override
    public String summarize(List<Message> messages) {
        String transcript = render(messages);
        if (transcript.isBlank()) {
            return "";
        }
        long startNanos = System.nanoTime();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SUMMARY_PROMPT.formatted(this.maxWords)),
                new UserMessage(transcript)));
        String summary = this.chatModel.call(prompt).getResult().getOutput().getText();
        summary = (summary == null) ? "" : summary;
        log.info("tóm tắt {} message ({} ký tự) -> {} ký tự trong {} ms", messages.size(), transcript.length(),
                summary.length(), (System.nanoTime() - startNanos) / 1_000_000);
        return summary;
    }

    /** Dựng transcript dạng text để model tóm tắt; tool call chỉ ghi tên, không ghi payload. */
    static String render(List<Message> messages) {
        StringBuilder text = new StringBuilder();
        for (Message message : messages) {
            String label = switch (message.getMessageType()) {
                case USER -> "Người dùng";
                case ASSISTANT -> "Trợ lý";
                case TOOL -> "Kết quả tool";
                case SYSTEM -> "Hệ thống";
            };
            String content = (message.getText() == null) ? "" : message.getText().strip();
            if (content.isEmpty() && message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                content = "(gọi tool: "
                        + assistant.getToolCalls().stream().map(AssistantMessage.ToolCall::name).toList() + ")";
            }
            if (content.isEmpty()) {
                continue;
            }
            text.append(label).append(": ").append(content).append(System.lineSeparator());
        }
        return text.toString().strip();
    }
}
