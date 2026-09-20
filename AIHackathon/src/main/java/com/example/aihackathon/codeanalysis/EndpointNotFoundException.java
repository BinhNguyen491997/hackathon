package com.example.aihackathon.codeanalysis;

import java.util.List;

/**
 * Không tìm được endpoint nào khớp với input.
 *
 * <p>Mang theo danh sách gợi ý để người dùng biết mình gõ sai path hay repo thật sự không có
 * API đó - hai tình huống rất khác nhau nhưng dễ bị gộp thành một câu báo lỗi vô nghĩa.
 */
public class EndpointNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> suggestions;

    public EndpointNotFoundException(String message, List<String> suggestions) {
        super(message);
        this.suggestions = List.copyOf(suggestions);
    }

    public List<String> suggestions() {
        return this.suggestions;
    }
}
