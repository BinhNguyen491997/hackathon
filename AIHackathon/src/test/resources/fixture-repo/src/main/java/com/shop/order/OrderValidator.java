package com.shop.order;

import org.springframework.stereotype.Component;

@Component
public class OrderValidator {

    public void validate(OrderRequest request) {
        if (request.total() <= 0) {
            throw new IllegalArgumentException("Tổng tiền phải lớn hơn 0");
        }
    }
}
