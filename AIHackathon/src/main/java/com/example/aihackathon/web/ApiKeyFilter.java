package com.example.aihackathon.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Xác thực đơn giản bằng shared secret trong header {@code X-API-Key}.
 *
 * <p>Nếu {@code agent.api-key} để trống, filter cho qua tất cả - chỉ an toàn khi chạy
 * localhost. Khi deploy ra internet, BẮT BUỘC đặt AGENT_API_KEY, nếu không bất kỳ ai
 * biết URL đều có thể tiêu tốn quota LLM của bạn.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private static final String HEADER = "X-API-Key";

    private final byte[] expectedKey;

    public ApiKeyFilter(@Value("${agent.api-key:}") String apiKey) {
        this.expectedKey = apiKey.isBlank() ? null : apiKey.getBytes(StandardCharsets.UTF_8);
        if (this.expectedKey == null) {
            log.warn("agent.api-key trong so - API dang MO cho moi ket noi. "
                    + "Chi dung khi chay localhost.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return this.expectedKey == null || !path.startsWith("/api/") || path.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(this.expectedKey,
                provided.getBytes(StandardCharsets.UTF_8))) {
            log.warn("tu choi request tu {} - thieu hoac sai {}", request.getRemoteAddr(), HEADER);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"missing or invalid " + HEADER + "\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
