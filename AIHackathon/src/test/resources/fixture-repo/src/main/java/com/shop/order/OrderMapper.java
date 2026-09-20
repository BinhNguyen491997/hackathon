package com.shop.order;

import com.shop.customer.Customer;
import org.springframework.stereotype.Component;

@Component
public class OrderMapper {

    public Order toEntity(OrderRequest request, Customer customer) {
        return new Order();
    }

    public OrderResponse toResponse(Order order) {
        return new OrderResponse(order.getId(), order.getStatus());
    }
}
