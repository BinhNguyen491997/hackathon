package com.example.aihackathon.web;

import java.util.Map;
import java.util.UUID;

import com.example.aihackathon.agent.AgentService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP API để client (máy cá nhân, Postman, CLI, web UI) gọi agent.
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentService agentService;

    public ChatController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /**
     * Health check cho AgentBase Runtime.
     *
     * <p>Platform bắt buộc {@code GET /health} trả 200 để đánh dấu runtime ACTIVE - đường dẫn cố
     * định, không đổi được. Giữ {@code /api/health} riêng cho client sẵn có.
     *
     * <p>Path không bắt đầu bằng {@code /api/} nên {@link ApiKeyFilter} tự động cho qua, đúng ý vì
     * probe của platform không gửi được header {@code X-API-Key}.
     */
    @GetMapping("/health")
    public Map<String, String> platformHealth() {
        return Map.of("status", "UP");
    }

    /** Hỏi đáp một lượt, trả JSON. */
    @PostMapping(path = "/api/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatReply chat(@Valid @RequestBody ChatRequest request) {
        String conversationId = conversationId(request);
        long startNanos = System.nanoTime();
        log.info("--> POST /api/chat conversationId={} chars={}", conversationId, request.message().length());

        String reply = this.agentService.ask(conversationId, request.message());

        log.info("<-- POST /api/chat conversationId={} {} ms reply={} chars", conversationId,
                (System.nanoTime() - startNanos) / 1_000_000, reply.length());
        return new ChatReply(conversationId, reply);
    }

    /** Hỏi đáp dạng streaming (SSE) để hiện chữ chạy dần như ChatGPT. */
    @PostMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@Valid @RequestBody ChatRequest request) {
        String conversationId = conversationId(request);
        log.info("--> POST /api/chat/stream conversationId={} chars={}", conversationId,
                request.message().length());
        return this.agentService.askStream(conversationId, request.message());
    }

    private static String conversationId(ChatRequest request) {
        String id = request.conversationId();
        return (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id.trim();
    }

    /** Lỗi từ tool/model được trả về dạng JSON thay vì stacktrace HTML. */
    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, String> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("bad request: {}", ex.getMessage());
        return Map.of("error", String.valueOf(ex.getMessage()));
    }
}
