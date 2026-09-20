package com.example.aihackathon.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra agent gọi đúng "phương ngữ" API của GreenNode MaaS: dựng một server giả trả về đúng
 * mẫu response thật của MaaS (có cả {@code reasoning_content} mà OpenAI không có), rồi soi lại
 * request mà Spring AI đã gửi.
 */
@SpringBootTest
class GreenNodeApiCompatibilityTests {

    private static final String REPLY = "AI la nganh khoa hoc may tinh tao ra he thong lam duoc viec can tri tue con nguoi.";

    private static final String GREENNODE_RESPONSE = """
            {
              "id": "9a9cc05611534e46812269852a57d908",
              "object": "chat.completion",
              "created": 1789178243,
              "model": "glm-5.2",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "reasoning_content": "1. Analyze the request. 2. Draft the answer.",
                    "content": "%s"
                  },
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 31,
                "total_tokens": 1376,
                "completion_tokens": 1345,
                "prompt_tokens_details": { "cached_tokens": 0 },
                "completion_tokens_details": { "reasoning_tokens": 699 }
              },
              "service_tier": "default"
            }
            """.formatted(REPLY);

    private record CapturedRequest(String path, String authorization, String body) {
    }

    private static final List<CapturedRequest> REQUESTS = new CopyOnWriteArrayList<>();

    private static final HttpServer SERVER = startServer();

    @Autowired
    private AgentService agentService;

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                REQUESTS.add(new CapturedRequest(exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));

                byte[] body = GREENNODE_RESPONSE.getBytes(StandardCharsets.UTF_8);
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
    static void greenNodeProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1");
        registry.add("spring.ai.openai.api-key", () -> "greennode-test-key");
        registry.add("spring.ai.openai.chat.model", () -> "z-ai/glm-5.2-hackathon");
        registry.add("spring.ai.openai.chat.temperature", () -> "0.3");
        registry.add("spring.ai.openai.chat.top-p", () -> "0.95");
        registry.add("spring.ai.openai.chat.max-tokens", () -> "4096");
        // ngưỡng cao để test này không kích hoạt nén, tránh gọi model tóm tắt
        registry.add("agent.memory.window", () -> "40");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void goiDungEndpointVaDocDuocResponseCuaGreenNode() {
        REQUESTS.clear();

        String reply = this.agentService.ask("conv-greennode", "What is AI?");

        // response của MaaS (kèm reasoning_content, service_tier) parse được, lấy đúng content
        assertThat(reply).contains("AI la nganh khoa hoc may tinh");

        CapturedRequest request = REQUESTS.get(0);
        // đúng path chuẩn OpenAI: base-url (đã có /v1) + chat/completions
        assertThat(request.path()).isEqualTo("/v1/chat/completions");
        assertThat(request.authorization()).isEqualTo("Bearer greennode-test-key");

        // các option trong application.yml thực sự đi vào request
        assertThat(request.body())
                .contains("\"model\":\"z-ai/glm-5.2-hackathon\"")
                .contains("\"max_tokens\":4096")
                .contains("\"top_p\":0.95")
                .contains("\"temperature\":0.3")
                .contains("\"role\":\"system\"")
                .contains("\"role\":\"user\"")
                // tool calling: agent khai báo tool cho model
                .contains("\"tools\":[")
                .contains("createTask");
    }

    @Test
    void luotSauMangTheoContextNhungKhongMangTheoReasoning() {
        REQUESTS.clear();

        this.agentService.ask("conv-context", "What is AI?");
        this.agentService.ask("conv-context", "Cho ví dụ ở Việt Nam");

        assertThat(REQUESTS).hasSize(2);
        String secondBody = REQUESTS.get(1).body();

        // context của lượt 1 được gửi lại: câu hỏi cũ + câu trả lời cũ
        assertThat(secondBody)
                .contains("What is AI?")
                .contains("AI la nganh khoa hoc may tinh")
                .contains("Cho ví dụ ở Việt Nam");

        // nhưng chuỗi suy luận của GLM thì bị SummarizingChatMemory strip, không gửi lại
        assertThat(secondBody).doesNotContain("reasoning_content");
        assertThat(secondBody).doesNotContain("Analyze the request");
    }
}
