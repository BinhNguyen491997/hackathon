package com.example.aihackathon.agent;

import java.util.List;

import com.example.aihackathon.codeanalysis.FlowProposer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

/**
 * Nhờ LLM tự đọc source code và dựng call graph, độc lập với parser.
 *
 * <p>Đây là bên thứ hai trong bước đối chiếu. Nó cố tình KHÔNG được xem kết quả của parser: nếu
 * thấy trước, nó sẽ có xu hướng xác nhận lại parser và bước đối chiếu mất hết giá trị. Hai bên phải
 * làm việc độc lập rồi mới so.
 *
 * <p><b>Lưu ý bảo mật:</b> lớp này gửi nguyên văn source code tới nhà cung cấp model. Đó là lý do
 * nó chỉ chạy khi người dùng bật cờ tường minh, và mỗi lần chạy đều ghi log cảnh báo.
 */
@Service
public class LlmFlowProposer implements FlowProposer {

    private static final Logger log = LoggerFactory.getLogger(LlmFlowProposer.class);

    private static final String SYSTEM_PROMPT = """
            Bạn là chuyên gia đọc code Java/Spring. Nhiệm vụ: từ source được cung cấp, xác định
            trình tự các lời gọi method giữa các lớp, bắt đầu từ method của controller.

            ĐỊNH DẠNG BẮT BUỘC - mỗi bước một dòng, đúng khuôn sau:
            STEP LopGoi.tenMethod -> LopBiGoi.tenMethod | ghi chú ngắn

            Ví dụ:
            STEP OrderController.create -> OrderService.create | chuyển sang tầng nghiệp vụ
            STEP OrderService.create -> OrderRepository.save | lưu đơn hàng

            Quy tắc:
            - Chỉ dùng tên lớp và tên method CÓ THẬT trong source được cung cấp.
            - Bỏ qua getter/setter của DTO/entity, và các lời gọi vào thư viện chuẩn Java
              (List, Optional, String, stream...). Chỉ quan tâm lời gọi giữa các lớp của dự án,
              và lời gọi ra hạ tầng (repository, HTTP client, Kafka...).
            - Nếu một lời gọi đi qua interface mà bạn không chắc lớp hiện thực nào chạy, cứ ghi tên
              interface và nói rõ trong ghi chú là chưa chắc.
            - Liệt kê theo thứ tự thực thi. Lời gọi trong nhánh if/else/catch cũng phải liệt kê.
            - Sau danh sách STEP, nếu có chỗ bạn không chắc thì viết thêm vài dòng văn xuôi giải
              thích. Đừng bọc kết quả trong khối code markdown.
            - Không bịa. Không chắc thì nói không chắc, đừng đoán cho đủ.
            """;

    private final ChatModel chatModel;

    public LlmFlowProposer(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String propose(String sourceBundle, String endpointLabel) {
        log.warn("GỬI SOURCE CODE tới nhà cung cấp model để đối chiếu call graph: {} ({} ký tự). "
                + "Chỉ chạy khi người dùng bật cờ crossCheckWithAi.", endpointLabel,
                sourceBundle == null ? 0 : sourceBundle.length());

        List<Message> messages = List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage("Endpoint cần phân tích: " + endpointLabel
                        + "\n\nSource code:\n" + sourceBundle));

        long startNanos = System.nanoTime();
        String proposal = this.chatModel.call(new Prompt(messages))
                .getResult().getOutput().getText();

        log.info("LLM đề xuất call graph cho {} sau {} ms | {} ký tự", endpointLabel,
                (System.nanoTime() - startNanos) / 1_000_000,
                proposal == null ? 0 : proposal.length());
        return proposal;
    }
}
