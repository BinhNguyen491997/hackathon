package com.shop.order;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    private final OrderMapper orderMapper;

    public OrderController(OrderService orderService, OrderMapper orderMapper) {
        this.orderService = orderService;
        this.orderMapper = orderMapper;
    }

    @PostMapping
    @Operation(summary = "Tạo đơn hàng mới cho khách")
    @PreAuthorize("hasRole('ORDER_CREATE')")
    public OrderResponse create(@Valid @RequestBody OrderRequest request) {
        return this.orderService.create(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Xem chi tiết đơn hàng")
    public OrderResponse findOne(@PathVariable Long id) {
        Order order = this.orderService.findById(id);
        return this.orderMapper.toResponse(order);
    }
}
