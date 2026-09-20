package com.example.aihackathon.config;

import com.example.aihackathon.memory.ChatModelSummarizer;
import com.example.aihackathon.memory.SummarizingChatMemory;
import com.example.aihackathon.tools.CodeAnalysisTools;
import com.example.aihackathon.tools.WorkTools;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Nơi "lắp" agent: model + system prompt + bộ nhớ hội thoại + tool.
 */
@Configuration
public class AgentConfig {

    /**
     * System prompt là phần quyết định chất lượng agent nhiều nhất.
     * Nêu rõ: vai trò, khi nào dùng tool, giới hạn, và định dạng trả lời.
     */
    private static final String SYSTEM_PROMPT = """
            Bạn là "Work Agent" - trợ lý công việc cho người dùng Việt Nam.

            Nhiệm vụ: giúp người dùng quản lý công việc, deadline, lập kế hoạch,
            tóm tắt và soạn nội dung công việc (email, ghi chú họp, checklist).
            Ngoài ra, bạn giúp BA và developer đọc hiểu luồng xử lý của API trong repo Java/Spring.

            Quy tắc:
            - Trả lời bằng tiếng Việt, ngắn gọn, đi thẳng vào việc.
            - Mọi câu hỏi liên quan tới thời gian ("hôm nay", "còn mấy ngày", "tuần này")
              phải gọi tool lấy ngày giờ hiện tại trước, không được tự suy đoán.
            - Khi người dùng nhắc tới việc cần làm, hãy dùng tool để tạo/liệt kê/hoàn thành task
              thay vì chỉ nói suông. Sau khi gọi tool, xác nhận lại kết quả thực tế.
            - Không bịa số liệu, tên người, hay nội dung tài liệu. Nếu thiếu thông tin thì hỏi lại
              đúng một câu hỏi cụ thể.
            - Khi được yêu cầu soạn nội dung, đưa ra bản nháp hoàn chỉnh có thể dùng ngay.

            Quy tắc riêng khi phân tích code (QUAN TRỌNG):
            - Luồng gọi giữa các lớp CHỈ được lấy từ tool phân tích code. Tuyệt đối không tự suy
              ra tên class, tên method, hay thứ tự gọi từ kiến thức chung về Spring - đó là cách
              nhanh nhất để tạo ra tài liệu sai mà BA lại tin.
            - Cần link repo GitLab và path API (hoặc mô tả chức năng). Thiếu link repo thì hỏi,
              không đoán.
            - Chưa biết path chính xác thì gọi tool liệt kê endpoint trước, rồi mới phân tích.
            - Khi tool báo không tìm thấy, hãy nói đúng điều đó và đưa lại danh sách gợi ý của
              tool. Không được tự dựng một luồng "hợp lý" để lấp chỗ trống.
            - Kết quả tool có phần "CHƯA XÁC ĐỊNH ĐƯỢC" và "GIỚI HẠN PHÂN TÍCH": phải nhắc lại
              cho người dùng, vì đó là những chỗ diagram còn thiếu và cần người kiểm tra.

            Quy tắc khi viết mô tả chức năng / tài liệu đặc tả (BẮT BUỘC theo đúng thứ tự):
            1. Gọi collectCodeEvidence TRƯỚC. Không được viết một câu nào về hành vi của API
               trước khi có dữ kiện từ tool này.
            2. Mỗi phát biểu về hành vi hệ thống phải kèm mã dẫn chứng, dạng [E3] hoặc [E3, E7].
               Chỉ được dùng đúng những mã mà tool đã trả về.
            3. TUYỆT ĐỐI không tự viết tên file kèm số dòng (dạng Abc.java:42). Vị trí do hệ
               thống quản lý; tự gõ ra là bịa, và tool sẽ từ chối lưu.
            4. Phân biệt rõ ba loại nội dung, không được trộn:
               - Dữ kiện: có mã dẫn chứng. Viết ở thể khẳng định.
               - Suy luận: không có dẫn chứng trực tiếp. Phải đặt trong mục riêng "Suy luận -
                 cần xác nhận với dev" và nói rõ mình suy ra từ đâu.
               - Điều không biết: nói thẳng là không biết.
            5. Danh sách "điểm cần xác nhận với dev" mà tool trả về phải được nhắc lại đầy đủ cho
               người dùng, không được lược bớt cho gọn. Mục nào ở mức BLOCKING thì phải nói rõ là
               tài liệu chưa dùng được trước khi hỏi dev.
            6. Viết xong thì gọi writeFunctionalSpec để lưu. Nếu tool báo lỗi truy vết thì sửa
               đúng chỗ nó chỉ rồi gọi lại, không được bỏ qua.
            7. Không suy ra "người dùng cần gì" từ code. Code chỉ cho biết hệ thống ĐANG làm gì.
               Muốn nói về mục đích nghiệp vụ thì phải xếp vào phần suy luận.
            """;

    /**
     * Timeout dài hơn mặc định của OkHttp cho lời gọi tới LLM.
     *
     * <p>Cần vì hai lý do:
     * <ul>
     *   <li>GLM là model reasoning: token suy luận cũng tính vào completion, nên một câu trả lời
     *       bình thường có thể mất lâu hơn chat model thường.</li>
     *   <li>Tool phân tích code gửi nguyên văn source code cho model (xem
     *       {@code CodeAnalysisTools}, {@code SpecNarrator}, {@code LlmFlowProposer}) - request
     *       này nặng hơn nhiều so với một câu chat thường.</li>
     * </ul>
     *
     * <p>Không chỉnh được qua {@code spring.ai.openai.*}: Spring AI 2.x không expose property
     * timeout, phải cấu hình qua {@link OpenAiHttpClientBuilderCustomizer} - cơ chế chính thức để
     * tuỳ biến OkHttp client bên dưới OpenAI Java SDK mà không phải override cả bean ChatModel.
     *
     * <p>Nếu gặp {@code OpenAIIoException: Request failed} mà tăng timeout không hết, đó không
     * phải do timeout - kiểm tra DNS/proxy/firewall tới host LLM trước.
     */
    @Bean
    OpenAiHttpClientBuilderCustomizer llmTimeoutCustomizer(
            @Value("${agent.llm.timeout-seconds:180}") long timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        return builder -> builder.timeout(timeout);
    }

    @Bean
    ChatMemory chatMemory(ChatModel chatModel,
            @Value("${agent.memory.window:40}") int window,
            @Value("${agent.memory.keep-recent:12}") int keepRecent,
            @Value("${agent.memory.summarize:true}") boolean summarize,
            @Value("${agent.memory.summary-max-words:200}") int summaryMaxWords,
            @Value("${agent.memory.strip-reasoning:true}") boolean stripReasoning) {

        return SummarizingChatMemory.builder()
                .maxMessages(window)
                .keepRecent(keepRecent)
                // GLM trả reasoning_content: không strip là mỗi lượt gửi lại vài trăm token vô ích
                .stripReasoning(stripReasoning)
                // null = chỉ cắt cửa sổ như MessageWindowChatMemory
                .summarizer(summarize ? new ChatModelSummarizer(chatModel, summaryMaxWords) : null)
                .build();
    }

    /**
     * Model/temperature/max-tokens lấy từ {@code spring.ai.openai.chat.*} (xem application.yml).
     * Spring AI 2.x tự dựng {@code OpenAiChatOptions} từ các property đó nên ở đây không set lại,
     * tránh hai nơi cùng khai báo một giá trị.
     *
     * <p>{@code SimpleLoggerAdvisor} log toàn bộ request/response với model ở mức DEBUG. Bật khi
     * cần soi prompt thật:
     * {@code logging.level.org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor=DEBUG}.
     */
    @Bean
    ChatClient agentChatClient(ChatModel chatModel, ChatMemory chatMemory, WorkTools workTools,
            CodeAnalysisTools codeAnalysisTools) {
        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                // memory advisor: tự nạp lại history theo conversationId ở mỗi lượt
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor())
                // tool luôn khả dụng; tool có tính phá huỷ nên truyền theo từng request
                .defaultTools(workTools, codeAnalysisTools)
                .build();
    }
}
