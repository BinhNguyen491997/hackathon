package com.shop.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record OrderRequest(

        @NotBlank(message = "Mã khách không được để trống")
        @Size(max = 20)
        String customerCode,

        @Positive(message = "Tổng tiền phải lớn hơn 0")
        long total) {
}
