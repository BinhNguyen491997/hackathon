package com.example.aihackathon.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.example.aihackathon.memory.SummarizingChatMemory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra end-to-end việc nén bộ nhớ: hạ ngưỡng xuống rất thấp, hỏi 3 lượt, rồi soi các request
 * thật gửi lên endpoint giả để thấy (1) có một lần gọi model để tóm tắt và (2) lượt sau chỉ mang
 * theo bản tóm tắt thay vì toàn bộ lịch sử.
 */
@SpringBootTest
class MemoryCompactionIntegrationTests {

    private static final String REPLY = "OK-REPLY";

    private static final String RESPONSE = """
            {
              "id": "cmpl-1",
              "object": "chat.completion",
              "created": 1789178243,
              "model": "glm-5.2",
              "choices": [
                { "index": 0, "message": { "role": "assistant", "content": "%s" }, "finish_reason": "stop" }
              ],
              "usage": { "prompt_tokens": 10, "total_tokens": 20, "completion_tokens": 10 }
            }
            """.formatted(REPLY);

    private static final List<String> BODIES = new CopyOnWriteArrayList<>();

    private static final HttpServer SERVER = startServer();

    @Autowired
    private AgentService agentService;

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = RESPONSE.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1");
        registry.add("spring.ai.openai.api-key", () -> "greennode-test-key");
        registry.add("spring.ai.openai.chat.model", () -> "z-ai/glm-5.2-hackathon");
        registry.add("agent.memory.window", () -> "4");
        registry.add("agent.memory.keep-recent", () -> "2");
        registry.add("agent.memory.summarize", () -> "true");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void nenLichSuThanhTomTatKhiVuotNguong() {
        BODIES.clear();

        this.agentService.ask("conv-compact", "lượt một: tạo task viết slide");
        this.agentService.ask("conv-compact", "lượt hai: hạn là 12/09");
        this.agentService.ask("conv-compact", "lượt ba: còn mấy ngày nữa");
        this.agentService.ask("conv-compact", "lượt bốn: nhắc lại giúp tôi");

        List<String> summaryCalls = BODIES.stream().filter(body -> body.contains("bộ nén ngữ cảnh")).toList();
        List<String> chatCalls = BODIES.stream().filter(body -> body.contains("\"tools\"")).toList();

        // 4 lượt hỏi đều là request có tool; nén xảy ra khi lịch sử vượt window
        assertThat(chatCalls).hasSize(4);
        assertThat(summaryCalls).isNotEmpty();

        // request tóm tắt là prompt riêng, không kèm tool, và đọc đúng lịch sử cũ
        assertThat(summaryCalls.get(0))
                .doesNotContain("\"tools\"")
                .contains("tạo task viết slide");

        // lượt cuối chỉ mang bản tóm tắt + lượt gần nhất, không còn nguyên văn lượt một
        String lastChat = chatCalls.get(chatCalls.size() - 1);
        assertThat(lastChat)
                .contains(SummarizingChatMemory.SUMMARY_MARKER)
                .contains("lượt ba")
                .doesNotContain("lượt một: tạo task viết slide");
    }
}
