package com.example.aihackathon.web;

import com.example.aihackathon.agent.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTests {

    private final AgentService agentService = mock(AgentService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(new ChatController(this.agentService)).build();
    }

    @Test
    void returnsReplyAndEchoesConversationId() throws Exception {
        given(this.agentService.ask(eq("conv-1"), eq("Hôm nay tôi có việc gì?")))
                .willReturn("Bạn có 2 việc.");

        this.mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"conv-1","message":"Hôm nay tôi có việc gì?"}
                                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.reply").value("Bạn có 2 việc."));
    }

    @Test
    void generatesConversationIdWhenMissing() throws Exception {
        given(this.agentService.ask(anyString(), eq("xin chào"))).willReturn("Chào bạn.");

        this.mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"xin chào\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").isNotEmpty())
                .andExpect(jsonPath("$.reply").value("Chào bạn."));
    }

    @Test
    void rejectsEmptyMessage() throws Exception {
        this.mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void healthEndpointIsOpen() throws Exception {
        this.mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
