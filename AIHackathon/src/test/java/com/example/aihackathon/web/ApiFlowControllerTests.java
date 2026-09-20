package com.example.aihackathon.web;

import java.nio.charset.StandardCharsets;

import com.example.aihackathon.codeanalysis.FixtureRepo;
import com.example.aihackathon.codeanalysis.FlowProposer;
import com.example.aihackathon.codeanalysis.NarrativeWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chạy qua HTTP thật với analyzer nối vào repo mẫu, nên test này phủ luôn cả hợp đồng JSON
 * chứ không chỉ logic phân tích.
 */
class ApiFlowControllerTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // NarrativeWriter.NONE + FlowProposer.NONE = che do khong dung AI, nen test web khong can key
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new ApiFlowController(FixtureRepo.analyzer(), NarrativeWriter.NONE,
                        FlowProposer.NONE))
                .build();
    }

    @Test
    void traVeDiagramVaThongTinTruyVet() throws Exception {
        this.mockMvc.perform(post("/api/analyze/api-flow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"repoUrl":"%s","branch":"master","httpMethod":"POST","path":"/api/orders"}
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.httpMethod").value("POST"))
                .andExpect(jsonPath("$.path").value("/api/orders"))
                .andExpect(jsonPath("$.operationSummary").value("Tạo đơn hàng mới cho khách"))
                .andExpect(jsonPath("$.controllerMethod").value("create"))
                .andExpect(jsonPath("$.commitSha").value(FixtureRepo.COMMIT_SHA))
                .andExpect(jsonPath("$.sourceFile").value(
                        "src/main/java/com/shop/order/OrderController.java"))
                .andExpect(jsonPath("$.formats.puml.content").value(
                        org.hamcrest.Matchers.containsString("@startuml")))
                .andExpect(jsonPath("$.formats.html.content").value(
                        org.hamcrest.Matchers.containsString("<!DOCTYPE html>")))
                .andExpect(jsonPath("$.formats.markdown.content").value(
                        org.hamcrest.Matchers.containsString("```mermaid")))
                .andExpect(jsonPath("$.formats.mermaid.content").value(
                        org.hamcrest.Matchers.containsString("sequenceDiagram")))
                .andExpect(jsonPath("$.formats.html.file").value(
                        org.hamcrest.Matchers.containsString(".html")))
                .andExpect(jsonPath("$.summary").value(
                        org.hamcrest.Matchers.containsString("== TRÌNH TỰ ==")))
                .andExpect(jsonPath("$.participants[0].name").value("OrderController"))
                .andExpect(jsonPath("$.participants[0].kind").value("controller"));
    }

    @Test
    void timTheoOperationSummaryKhiKhongCoPath() throws Exception {
        this.mockMvc.perform(post("/api/analyze/api-flow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"repoUrl":"%s","summary":"chi tiết đơn hàng"}
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("/api/orders/{id}"));
    }

    @Test
    void traVe404KemGoiYKhiKhongCoEndpointKhop() throws Exception {
        this.mockMvc.perform(post("/api/analyze/api-flow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"repoUrl":"%s","httpMethod":"POST","path":"/api/invoices"}
                                """)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("Không có thông tin cần tìm")))
                .andExpect(jsonPath("$.suggestions").isNotEmpty());
    }

    @Test
    void traVe400KhiThieuCaPathVaSummary() throws Exception {
        this.mockMvc.perform(post("/api/analyze/api-flow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"repoUrl":"%s"}
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("path hoặc summary")));
    }

    @Test
    void traVe400KhiThieuRepoUrl() throws Exception {
        this.mockMvc.perform(post("/api/analyze/api-flow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/api/orders\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void macDinhKhongDungAi() throws Exception {
        this.mockMvc.perform(post("/api/analyze/spec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"repoUrl":"%s","httpMethod":"POST","path":"/api/orders"}
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiUsed").value(false))
                .andExpect(jsonPath("$.aiAttempts").value(0))
                .andExpect(jsonPath("$.evidenceCount").value(
                        org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void banTatDinhVanDayDuDanChungVaCauHoi() throws Exception {
        this.mockMvc.perform(post("/api/analyze/spec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"repoUrl":"%s","httpMethod":"POST","path":"/api/orders","useAi":false}
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionCount").value(
                        org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.evidence[0].sourceFile").value(
                        org.hamcrest.Matchers.containsString(".java")))
                .andExpect(jsonPath("$.evidence[0].line").value(
                        org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void traVeFilePumlChoPostApiFlow() throws Exception {
        this.mockMvc.perform(post("/api/analyze/api-flow.puml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"repoUrl":"%s","branch":"master","httpMethod":"POST","path":"/api/orders"}
                                """)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("attachment"),
                                org.hamcrest.Matchers.containsString(".puml"))))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("@startuml")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("OrderController")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("@enduml")));
    }

    @Test
    void pumlTraVe404KhiKhongCoEndpointKhop() throws Exception {
        this.mockMvc.perform(post("/api/analyze/api-flow.puml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"repoUrl":"%s","httpMethod":"POST","path":"/api/invoices"}
                                """)))
                .andExpect(status().isNotFound());
    }

    @Test
    void pumlTraVe400KhiThieuCaPathVaSummary() throws Exception {
        this.mockMvc.perform(post("/api/analyze/api-flow.puml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"repoUrl":"%s"}
                                """)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lietKeEndpoint() throws Exception {
        this.mockMvc.perform(post("/api/analyze/endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"repoUrl":"%s","branch":"master"}
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.path == '/api/orders')].httpMethod").value("POST"));
    }

    private static byte[] body(String template) {
        return template.formatted(FixtureRepo.REPO_URL).getBytes(StandardCharsets.UTF_8);
    }
}
