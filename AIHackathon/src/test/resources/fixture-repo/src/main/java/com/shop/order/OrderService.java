package com.shop.order;

public interface OrderService {

    OrderResponse create(OrderRequest request);

    Order findById(Long id);
}
