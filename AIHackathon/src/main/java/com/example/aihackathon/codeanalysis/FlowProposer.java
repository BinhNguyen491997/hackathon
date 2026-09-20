package com.example.aihackathon.codeanalysis;

/**
 * Bên tự đọc source code và đề xuất call graph, độc lập với parser.
 *
 * <p>Interface thuần, không import Spring AI, để package {@code codeanalysis} vẫn test được mà
 * không cần API key. Bản hiện thực dùng LLM nằm ở package {@code agent}.
 *
 * <p>Lưu ý bảo mật: bản hiện thực dùng LLM sẽ GỬI SOURCE CODE ra ngoài tới nhà cung cấp model.
 * Vì vậy nó phải là lựa chọn bật tường minh, không bao giờ mặc định.
 */
public interface FlowProposer {

    /** Không chạy đối chiếu. */
    FlowProposer NONE = (sourceBundle, endpointLabel) -> null;

    /**
     * @param sourceBundle nguyên văn source của các method trong luồng
     * @param endpointLabel ví dụ "POST /api/orders", để LLM biết điểm bắt đầu
     * @return văn bản theo định dạng {@code STEP A.m -> B.n | ghi chú}, hoặc null nếu không gọi được
     */
    String propose(String sourceBundle, String endpointLabel);
}
