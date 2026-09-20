package com.example.aihackathon.codeanalysis.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Một endpoint HTTP tìm thấy trong repo.
 *
 * @param httpMethod GET/POST/... hoặc ANY khi {@code @RequestMapping} không khai báo method
 * @param path       path đã ghép prefix ở class với path ở method
 * @param summary    lấy từ {@code @Operation(summary = "...")}, null nếu không có
 */
public record ApiEndpoint(
        String httpMethod,
        String path,
        String controllerFqn,
        String controllerSimpleName,
        String methodName,
        int argCount,
        String summary,
        String sourceFile,
        int line) {

    public String label() {
        return this.httpMethod + " " + this.path;
    }

    public String describe() {
        String base = label() + "  ->  " + this.controllerSimpleName + "." + this.methodName + "()";
        return (this.summary == null || this.summary.isBlank()) ? base : base + "  // " + this.summary;
    }

    /**
     * So khớp với input của người dùng. Chấp nhận cả path có biến giữ nguyên tên
     * ({@code /api/orders/{id}}) và path đã điền giá trị thật ({@code /api/orders/42}),
     * vì BA thường copy URL từ Postman.
     */
    public boolean matches(String requestedMethod, String requestedPath) {
        if (requestedMethod != null && !requestedMethod.isBlank()
                && !"ANY".equals(this.httpMethod)
                && !this.httpMethod.equalsIgnoreCase(requestedMethod.trim())) {
            return false;
        }
        return pathMatches(requestedPath);
    }

    private boolean pathMatches(String requestedPath) {
        List<String> mine = segments(this.path);
        List<String> theirs = segments(requestedPath);
        if (mine.size() != theirs.size()) {
            return false;
        }
        for (int i = 0; i < mine.size(); i++) {
            String a = mine.get(i);
            String b = theirs.get(i);
            boolean aVar = a.startsWith("{");
            boolean bVar = b.startsWith("{");
            if (aVar || bVar) {
                // một bên là biến -> coi như khớp, không cần trùng tên biến
                continue;
            }
            if (!a.equalsIgnoreCase(b)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> segments(String path) {
        if (path == null) {
            return List.of();
        }
        String cleaned = path.trim();
        int query = cleaned.indexOf('?');
        if (query >= 0) {
            cleaned = cleaned.substring(0, query);
        }
        return Arrays.stream(cleaned.toLowerCase(Locale.ROOT).split("/"))
                .filter(segment -> !segment.isBlank())
                .toList();
    }
}
