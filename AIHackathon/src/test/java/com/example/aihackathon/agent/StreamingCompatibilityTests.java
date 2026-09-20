package com.example.aihackathon.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra đường streaming (/api/chat/stream) với SSE giống GLM trên GreenNode MaaS: delta có
 * {@code reasoning_content} trước khi có {@code content}, chunk cuối kèm {@code usage}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamingCompatibilityTests {

    private static final List<String> CHUNKS = List.of(
            """
            {"id":"c1","object":"chat.completion.chunk","created":1,"model":"glm-5.2",\
            "choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
            """
            {"id":"c1","object":"chat.completion.chunk","created":1,"model":"glm-5.2",\
            "choices":[{"index":0,"delta":{"reasoning_content":"Người dùng hỏi danh sách task."}}]}""",
            """
            {"id":"c1","object":"chat.completion.chunk","created":1,"model":"glm-5.2",\
            "choices":[{"index":0,"delta":{"content":"Bạn có "}}]}""",
            """
            {"id":"c1","object":"chat.completion.chunk","created":1,"model":"glm-5.2",\
            "choices":[{"index":0,"delta":{"content":"2 việc."}}]}""",
            """
            {"id":"c1","object":"chat.completion.chunk","created":1,"model":"glm-5.2",\
            "choices":[{"index":0,"delta":{},"finish_reason":"stop"}],\
            "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""");

    /** Stream khi model quyết định gọi tool trước, rồi mới trả text ở vòng sau. */
    private static final List<String> TOOL_CALL_CHUNKS = List.of(
            """
            {"id":"c0","object":"chat.completion.chunk","created":1,"model":"glm-5.2",\
            "choices":[{"index":0,"delta":{"role":"assistant","content":"",\
            "tool_calls":[{"index":0,"id":"call_1","type":"function",\
            "function":{"name":"listTasks","arguments":""}}]}}]}""",
            """
            {"id":"c0","object":"chat.completion.chunk","created":1,"model":"glm-5.2",\
            "choices":[{"index":0,"delta":{"tool_calls":[{"index":0,\
            "function":{"arguments":"{}"}}]}}]}""",
            """
            {"id":"c0","object":"chat.completion.chunk","created":1,"model":"glm-5.2",\
            "choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""");

    private static final HttpServer SERVER = startServer();

    @Autowired
    private AgentService agentService;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                // Vòng đầu của câu hỏi "task": trả tool_calls. Khi đã có kết quả tool thì trả text.
                boolean afterTool = body.contains("\"role\":\"tool\"");
                boolean wantsToolCall = body.contains("goi tool di") && !afterTool;
                List<String> chunks = wantsToolCall ? TOOL_CALL_CHUNKS : CHUNKS;

                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (String chunk : chunks) {
                        out.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                }
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
        registry.add("spring.ai.openai.api-key", () -> "test-key");
        registry.add("spring.ai.openai.chat.model", () -> "z-ai/glm-5.2-hackathon");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void streamTraVeTungChunkVanBan() {
        List<String> chunks = this.agentService.askStream("conv-stream", "Liệt kê task của tôi")
                .collectList()
                .block();

        assertThat(chunks).isNotNull();
        assertThat(String.join("", chunks)).contains("Bạn có 2 việc.");
    }

    @Test
    void endpointStreamTraVeSseQuaHttp() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + this.port + "/api/chat/stream"))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"conversationId\":\"conv-http\",\"message\":\"Liệt kê task của tôi\"}",
                        StandardCharsets.UTF_8))
                .build();

        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Bạn có ", "2 việc.");
    }

    @Test
    void streamVanChayKhiModelGoiToolGiuaDuong() {
        List<String> chunks = this.agentService.askStream("conv-tool-stream", "goi tool di")
                .collectList()
                .block();

        assertThat(chunks).isNotNull();
        assertThat(String.join("", chunks)).contains("2 việc.");
    }
}
