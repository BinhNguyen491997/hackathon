package com.example.aihackathon.agent;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.example.aihackathon.tools.WorkTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

/**
 * Lớp nghiệp vụ của agent: gắn conversationId vào bộ nhớ và vào ToolContext.
 *
 * <p>Đây cũng là nơi log chi phí của mỗi lượt (thời gian + token), vì chỉ ở đây mới nhìn thấy
 * {@link ChatResponse} đã cộng dồn usage của tất cả các vòng tool calling.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final ChatClient chatClient;

    public AgentService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** Hỏi đáp một lượt, chờ câu trả lời đầy đủ. */
    public String ask(String conversationId, String message) {
        long startNanos = System.nanoTime();
        log.info("[{}] ask: {} ký tự", conversationId, message.length());

        ChatResponse response;
        try {
            response = request(conversationId, message).call().chatResponse();
        }
        catch (RuntimeException ex) {
            log.error("[{}] ask THẤT BẠI sau {} ms: {}", conversationId, elapsedMillis(startNanos),
                    ex.toString(), ex);
            throw ex;
        }

        if (response == null || response.getResult() == null) {
            log.warn("[{}] ask: model không trả về generation nào sau {} ms", conversationId,
                    elapsedMillis(startNanos));
            return "";
        }

        String reply = response.getResult().getOutput().getText();
        reply = (reply == null) ? "" : reply;
        log.info("[{}] ask xong sau {} ms | finishReason={} | reply {} ký tự | {}", conversationId,
                elapsedMillis(startNanos), response.getResult().getMetadata().getFinishReason(),
                reply.length(), describeUsage(response));
        return reply;
    }

    /** Hỏi đáp dạng streaming để client in ra từng chunk. */
    public Flux<String> askStream(String conversationId, String message) {
        long startNanos = System.nanoTime();
        AtomicInteger chunks = new AtomicInteger();
        AtomicLong chars = new AtomicLong();

        return request(conversationId, message).stream().content()
                .doOnSubscribe(subscription -> log.info("[{}] stream: {} ký tự", conversationId,
                        message.length()))
                .doOnNext(chunk -> {
                    chars.addAndGet(chunk.length());
                    if (chunks.incrementAndGet() == 1) {
                        log.info("[{}] stream chunk đầu sau {} ms", conversationId, elapsedMillis(startNanos));
                    }
                })
                // log cả stack trace: lỗi streaming của provider hay bị controller che thành 500
                .doOnError(error -> log.error("[{}] stream THẤT BẠI sau {} ms (đã gửi {} chunk): {}",
                        conversationId, elapsedMillis(startNanos), chunks.get(), error.toString(), error))
                .doOnComplete(() -> log.info("[{}] stream xong sau {} ms | {} chunk | {} ký tự",
                        conversationId, elapsedMillis(startNanos), chunks.get(), chars.get()));
    }

    private ChatClient.ChatClientRequestSpec request(String conversationId, String message) {
        return this.chatClient.prompt()
                .user(message)
                // history được tách riêng theo conversationId
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                // owner đi qua ToolContext, model không nhìn thấy và không sửa được
                .toolContext(Map.of(WorkTools.OWNER_KEY, conversationId));
    }

    /** Token của cả lượt (đã cộng dồn qua các vòng tool calling) - dùng để theo dõi chi phí. */
    private static String describeUsage(ChatResponse response) {
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return "usage=n/a";
        }
        return "token: prompt=%s completion=%s total=%s".formatted(usage.getPromptTokens(),
                usage.getCompletionTokens(), usage.getTotalTokens());
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
