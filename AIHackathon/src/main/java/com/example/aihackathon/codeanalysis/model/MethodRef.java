package com.example.aihackathon.codeanalysis.model;

/**
 * Một method cụ thể trong repo được phân tích.
 *
 * @param typeFqn        tên đầy đủ của lớp khai báo, ví dụ com.shop.order.OrderServiceImpl
 * @param typeSimpleName tên ngắn dùng để hiển thị
 * @param methodName     tên method
 * @param argCount       số tham số ở chỗ gọi - dùng để phân biệt overload ở mức thô
 * @param kind           vai của lớp khai báo
 * @param returnType     kiểu trả về khai báo trong code; null khi không đọc được (lớp ngoài repo).
 *                       Dùng để ghi lên mũi tên trả về, giúp đọc sequence diagram không phải mở IDE
 */
public record MethodRef(
        String typeFqn,
        String typeSimpleName,
        String methodName,
        int argCount,
        ParticipantKind kind,
        String returnType) {

    /** Khoá để chống lặp vô hạn và chống mở rộng trùng. */
    public String key() {
        return this.typeFqn + "#" + this.methodName + "/" + this.argCount;
    }

    public String label() {
        return this.methodName + "()";
    }

    @Override
    public String toString() {
        return this.typeSimpleName + "." + this.methodName + "/" + this.argCount;
    }
}
