package com.example.aihackathon.memory;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChatModelSummarizerTests {

    private final ChatModel chatModel = mock(ChatModel.class);

    @Test
    void goiModelVoiTranscriptVaTraVeTomTat() {
        given(this.chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .willReturn(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("- Người dùng cần slide trước 12/09"),
                                ChatGenerationMetadata.NULL))));

        String summary = new ChatModelSummarizer(this.chatModel, 200).summarize(List.of(
                new UserMessage("tạo task viết slide, hạn 2026-09-12"),
                new AssistantMessage("Đã tạo task VIET_SLIDE.")));

        assertThat(summary).isEqualTo("- Người dùng cần slide trước 12/09");

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(this.chatModel).call(prompt.capture());
        List<Message> messages = prompt.getValue().getInstructions();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(messages.get(0).getText()).contains("tối đa 200 từ");
        assertThat(messages.get(1).getText())
                .contains("Người dùng: tạo task viết slide, hạn 2026-09-12")
                .contains("Trợ lý: Đã tạo task VIET_SLIDE.");
    }

    @Test
    void transcriptRongThiKhongGoiModel() {
        String summary = new ChatModelSummarizer(this.chatModel, 200).summarize(List.of(new AssistantMessage("")));

        assertThat(summary).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(this.chatModel);
    }

    @Test
    void assistantChiGoiToolThiGhiNhanLaGoiTool() {
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("1", "function", "createTask", "{}")))
                .build();

        assertThat(ChatModelSummarizer.render(List.of(new UserMessage("tạo task"), toolCall)))
                .contains("Người dùng: tạo task")
                .contains("Trợ lý: (gọi tool: [createTask])");
    }
}
