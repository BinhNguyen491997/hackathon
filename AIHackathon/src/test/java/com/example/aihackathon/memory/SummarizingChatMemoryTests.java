package com.example.aihackathon.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class SummarizingChatMemoryTests {

    private static final String CONV = "conv-1";

    private static AssistantMessage assistantWithReasoning(String content, String reasoning) {
        return AssistantMessage.builder()
                .content(content)
                .properties(Map.of(SummarizingChatMemory.REASONING_METADATA_KEY, reasoning, "id", "abc"))
                .build();
    }

    @Test
    void boReasoningContentTruocKhiLuu() {
        SummarizingChatMemory memory = SummarizingChatMemory.builder().build();

        memory.add(CONV, List.of(
                new UserMessage("What is AI?"),
                assistantWithReasoning("AI là ngành khoa học máy tính.", "1. Phân tích câu hỏi 2. Soạn câu trả lời")));

        List<Message> stored = memory.get(CONV);
        assertThat(stored).hasSize(2);
        AssistantMessage assistant = (AssistantMessage) stored.get(1);
        assertThat(assistant.getMetadata()).doesNotContainKey(SummarizingChatMemory.REASONING_METADATA_KEY);
        // nội dung và metadata khác vẫn nguyên
        assertThat(assistant.getText()).isEqualTo("AI là ngành khoa học máy tính.");
        assertThat(assistant.getMetadata()).containsEntry("id", "abc");
    }

    @Test
    void giuLaiReasoningKhiTatStrip() {
        SummarizingChatMemory memory = SummarizingChatMemory.builder().stripReasoning(false).build();

        memory.add(CONV, List.of(assistantWithReasoning("xong", "suy luận dài")));

        assertThat(memory.get(CONV).get(0).getMetadata())
                .containsEntry(SummarizingChatMemory.REASONING_METADATA_KEY, "suy luận dài");
    }

    @Test
    void nenPhanCuThanhMotMessageTomTat() {
        AtomicReference<List<Message>> summarized = new AtomicReference<>();
        SummarizingChatMemory memory = SummarizingChatMemory.builder()
                .maxMessages(6)
                .keepRecent(2)
                .summarizer(messages -> {
                    summarized.set(List.copyOf(messages));
                    return "- Đã bàn về task viết slide, hạn 12/09";
                })
                .build();

        for (int i = 1; i <= 4; i++) {
            memory.add(CONV, List.of(new UserMessage("câu hỏi " + i), new AssistantMessage("trả lời " + i)));
        }

        List<Message> stored = memory.get(CONV);
        // 1 tóm tắt + cửa sổ giữ lại (snap tới lượt USER nên là user 4 + assistant 4)
        assertThat(stored).hasSize(3);
        assertThat(stored.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(stored.get(0).getText())
                .startsWith(SummarizingChatMemory.SUMMARY_MARKER)
                .contains("task viết slide");
        assertThat(stored.get(1).getText()).isEqualTo("câu hỏi 4");
        assertThat(stored.get(2).getText()).isEqualTo("trả lời 4");

        // summarizer nhận đúng phần bị nén, không nhận phần giữ lại
        assertThat(summarized.get()).extracting(Message::getText)
                .contains("câu hỏi 1", "trả lời 1", "câu hỏi 3", "trả lời 3")
                .doesNotContain("câu hỏi 4");
    }

    @Test
    void tomTatDonTichLuyQuaNhieuLanNen() {
        List<List<String>> summarizerInputs = new ArrayList<>();
        SummarizingChatMemory memory = SummarizingChatMemory.builder()
                .maxMessages(4)
                .keepRecent(2)
                .summarizer(messages -> {
                    summarizerInputs.add(messages.stream().map(Message::getText).toList());
                    return "tóm tắt lần " + summarizerInputs.size();
                })
                .build();

        for (int i = 1; i <= 5; i++) {
            memory.add(CONV, List.of(new UserMessage("q" + i), new AssistantMessage("a" + i)));
        }

        assertThat(summarizerInputs).hasSizeGreaterThanOrEqualTo(2);
        // lần nén sau đọc lại tóm tắt của lần trước -> ngữ cảnh cũ không bị mất hẳn
        assertThat(summarizerInputs.get(1))
                .anySatisfy(text -> assertThat(text).contains("tóm tắt lần 1"));
        assertThat(memory.get(CONV).get(0).getText()).contains("tóm tắt lần " + summarizerInputs.size());
    }

    @Test
    void summarizerLoiThiRoiVeCatCuaSo() {
        SummarizingChatMemory memory = SummarizingChatMemory.builder()
                .maxMessages(4)
                .keepRecent(2)
                .summarizer(messages -> {
                    throw new IllegalStateException("MaaS 503");
                })
                .build();

        for (int i = 1; i <= 3; i++) {
            memory.add(CONV, List.of(new UserMessage("q" + i), new AssistantMessage("a" + i)));
        }

        List<Message> stored = memory.get(CONV);
        assertThat(stored).hasSize(2);
        assertThat(stored).extracting(Message::getText).containsExactly("q3", "a3");
    }

    @Test
    void khongCoSummarizerThiHoatDongNhuCuaSoTruot() {
        SummarizingChatMemory memory = SummarizingChatMemory.builder()
                .maxMessages(4)
                .keepRecent(2)
                .summarizer(null)
                .build();

        for (int i = 1; i <= 3; i++) {
            memory.add(CONV, List.of(new UserMessage("q" + i), new AssistantMessage("a" + i)));
        }

        assertThat(memory.get(CONV)).extracting(Message::getText).containsExactly("q3", "a3");
    }

    @Test
    void khongNenKhiChuaVuotNguong() {
        SummarizingChatMemory memory = SummarizingChatMemory.builder()
                .maxMessages(10)
                .keepRecent(2)
                .summarizer(messages -> "không nên được gọi")
                .build();

        memory.add(CONV, List.of(new UserMessage("q1"), new AssistantMessage("a1")));

        assertThat(memory.get(CONV)).hasSize(2);
        assertThat(memory.get(CONV).get(0).getText()).isEqualTo("q1");
    }

    @Test
    void clearXoaHoiThoai() {
        SummarizingChatMemory memory = SummarizingChatMemory.builder().build();
        memory.add(CONV, List.of(new UserMessage("q1")));
        memory.add("conv-2", List.of(new UserMessage("khác")));

        memory.clear(CONV);

        assertThat(memory.get(CONV)).isEmpty();
        assertThat(memory.get("conv-2")).hasSize(1);
    }
}
