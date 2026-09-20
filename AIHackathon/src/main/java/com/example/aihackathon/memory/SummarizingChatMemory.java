package com.example.aihackathon.memory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Bộ nhớ hội thoại tiết kiệm token, thay cho {@code MessageWindowChatMemory}. Hai việc nó làm:
 *
 * <ol>
 * <li><b>Bỏ chuỗi suy luận.</b> GLM (và các model reasoning khác trên GreenNode MaaS) trả thêm
 * {@code reasoning_content}; Spring AI lưu vào metadata {@code reasoningContent} và
 * <em>gửi lại</em> ở các lượt sau ({@code OpenAiChatModel} đọc metadata này rồi set lại field
 * {@code reasoning_content} trong request). Đó là hàng trăm token vô ích mỗi lượt, nên ở đây
 * strip trước khi lưu.</li>
 * <li><b>Tóm tắt phần cũ.</b> Khi vượt {@code maxMessages}, các message cũ được nén thành một
 * message tóm tắt duy nhất, chỉ giữ nguyên văn {@code keepRecent} message gần nhất. Không có
 * summarizer thì hành vi rút về đúng cửa sổ trượt như cũ.</li>
 * </ol>
 *
 * <p>Tóm tắt được lưu dưới dạng {@link UserMessage} (không phải {@code SystemMessage}) để system
 * prompt của agent vẫn nằm đầu prompt — vừa đúng thứ tự, vừa giữ prefix ổn định cho prompt cache.
 */
public class SummarizingChatMemory implements ChatMemory {

    /** Metadata key Spring AI dùng cho chuỗi suy luận của model. */
    public static final String REASONING_METADATA_KEY = "reasoningContent";

    /** Nhãn đứng đầu message tóm tắt, để người đọc log phân biệt được với message thật. */
    public static final String SUMMARY_MARKER = "[Bối cảnh đã tóm tắt từ các lượt trước]";

    private static final Logger log = LoggerFactory.getLogger(SummarizingChatMemory.class);

    /** Nén danh sách message cũ thành một đoạn văn bản ngắn. */
    public interface Summarizer {

        String summarize(List<Message> messages);

    }

    private final ChatMemoryRepository repository;

    private final int maxMessages;

    private final int keepRecent;

    private final boolean stripReasoning;

    private final Summarizer summarizer;

    private SummarizingChatMemory(ChatMemoryRepository repository, int maxMessages, int keepRecent,
            boolean stripReasoning, Summarizer summarizer) {
        Assert.notNull(repository, "repository cannot be null");
        Assert.isTrue(maxMessages > 0, "maxMessages must be greater than 0");
        Assert.isTrue(keepRecent > 0, "keepRecent must be greater than 0");
        Assert.isTrue(keepRecent < maxMessages, "keepRecent must be smaller than maxMessages");
        this.repository = repository;
        this.maxMessages = maxMessages;
        this.keepRecent = keepRecent;
        this.stripReasoning = stripReasoning;
        this.summarizer = summarizer;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        Assert.noNullElements(messages, "messages cannot contain null elements");

        List<Message> all = new ArrayList<>(this.repository.findByConversationId(conversationId));
        int before = all.size();
        int stripped = 0;
        for (Message message : messages) {
            Message toStore = this.stripReasoning ? stripReasoning(message) : message;
            if (toStore != message) {
                stripped++;
            }
            all.add(toStore);
        }
        if (stripped > 0) {
            log.debug("[{}] bỏ reasoning_content của {} assistant message", conversationId, stripped);
        }

        List<Message> compacted = compact(conversationId, all);
        this.repository.saveAll(conversationId, compacted);
        log.debug("[{}] memory: {} + {} message -> giữ {}", conversationId, before, messages.size(),
                compacted.size());
    }

    @Override
    public List<Message> get(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        return this.repository.findByConversationId(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        this.repository.deleteByConversationId(conversationId);
    }

    /**
     * Bỏ metadata {@code reasoningContent} khỏi assistant message để nó không bị gửi lại.
     */
    static Message stripReasoning(Message message) {
        if (!(message instanceof AssistantMessage assistantMessage)
                || !assistantMessage.getMetadata().containsKey(REASONING_METADATA_KEY)) {
            return message;
        }
        Map<String, Object> metadata = new HashMap<>(assistantMessage.getMetadata());
        metadata.remove(REASONING_METADATA_KEY);
        return assistantMessage.mutate().properties(metadata).build();
    }

    private List<Message> compact(String conversationId, List<Message> messages) {
        if (messages.size() <= this.maxMessages) {
            return messages;
        }

        int cut = snapToUserBoundary(messages, messages.size() - this.keepRecent);
        if (cut <= 0) {
            return messages;
        }

        List<Message> older = List.copyOf(messages.subList(0, cut));
        List<Message> recent = new ArrayList<>(messages.subList(cut, messages.size()));

        if (this.summarizer == null) {
            log.info("[{}] memory vượt {} message -> cắt cửa sổ, bỏ {} message cũ", conversationId,
                    this.maxMessages, older.size());
            return recent;
        }

        log.info("[{}] memory vượt {} message -> nén {} message cũ thành 1 tóm tắt, giữ {} message",
                conversationId, this.maxMessages, older.size(), recent.size());

        String summary;
        try {
            summary = this.summarizer.summarize(older);
        }
        catch (RuntimeException ex) {
            // Tóm tắt lỗi (mạng, quota...) thì vẫn phải cắt bớt, không được làm gãy lượt hỏi đáp.
            log.warn("[{}] tóm tắt hội thoại thất bại, rơi về cắt cửa sổ: {}", conversationId, ex.toString());
            return recent;
        }

        if (!StringUtils.hasText(summary)) {
            log.warn("[{}] summarizer trả về rỗng, rơi về cắt cửa sổ", conversationId);
            return recent;
        }

        List<Message> compacted = new ArrayList<>(recent.size() + 1);
        compacted.add(new UserMessage(SUMMARY_MARKER + System.lineSeparator() + summary.strip()));
        compacted.addAll(recent);
        return compacted;
    }

    /**
     * Đẩy điểm cắt tới message USER gần nhất, để cửa sổ giữ lại luôn bắt đầu bằng một lượt trọn
     * vẹn — tránh giữ assistant reply hoặc tool result mà mất câu hỏi sinh ra nó.
     */
    private static int snapToUserBoundary(List<Message> messages, int cut) {
        int snapped = Math.max(cut, 0);
        while (snapped < messages.size() && messages.get(snapped).getMessageType() != MessageType.USER) {
            snapped++;
        }
        // Không tìm được ranh giới lượt nào thì dùng điểm cắt thô, đừng xoá sạch bộ nhớ.
        return (snapped >= messages.size()) ? Math.max(cut, 0) : snapped;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private ChatMemoryRepository repository = new InMemoryChatMemoryRepository();

        private int maxMessages = 40;

        private int keepRecent = 12;

        private boolean stripReasoning = true;

        private Summarizer summarizer;

        private Builder() {
        }

        public Builder repository(ChatMemoryRepository repository) {
            this.repository = repository;
            return this;
        }

        /** Số message tối đa trước khi nén. */
        public Builder maxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
            return this;
        }

        /** Số message gần nhất giữ nguyên văn sau khi nén. */
        public Builder keepRecent(int keepRecent) {
            this.keepRecent = keepRecent;
            return this;
        }

        public Builder stripReasoning(boolean stripReasoning) {
            this.stripReasoning = stripReasoning;
            return this;
        }

        /** Bỏ trống (null) = không tóm tắt, chỉ cắt cửa sổ. */
        public Builder summarizer(Summarizer summarizer) {
            this.summarizer = summarizer;
            return this;
        }

        public SummarizingChatMemory build() {
            return new SummarizingChatMemory(this.repository, this.maxMessages, this.keepRecent,
                    this.stripReasoning, this.summarizer);
        }

    }
}
