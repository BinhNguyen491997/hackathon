package com.shop.order;

import java.util.List;

import com.shop.customer.Customer;
import com.shop.customer.CustomerRepository;
import com.shop.inventory.InventoryService;
import com.shop.payment.PaymentClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;

    private final CustomerRepository customerRepository;

    private final OrderValidator orderValidator;

    private final OrderMapper orderMapper;

    private final PaymentClient paymentClient;

    private final InventoryService inventoryService;

    private final NotificationService notificationService;

    public OrderServiceImpl(OrderRepository orderRepository, CustomerRepository customerRepository,
            OrderValidator orderValidator, OrderMapper orderMapper, PaymentClient paymentClient,
            InventoryService inventoryService, NotificationService notificationService) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
        this.orderValidator = orderValidator;
        this.orderMapper = orderMapper;
        this.paymentClient = paymentClient;
        this.inventoryService = inventoryService;
        this.notificationService = notificationService;
    }

    @Override
    @Transactional
    public OrderResponse create(OrderRequest request) {
        this.orderValidator.validate(request);

        Customer customer = this.customerRepository.findByCode(request.customerCode());
        if (customer == null) {
            throw new OrderNotFoundException("Không tìm thấy khách " + request.customerCode());
        }

        Order order = this.orderMapper.toEntity(request, customer);

        if (request.total() > 10_000_000L) {
            this.notificationService.notifyHighValue(order);
        }
        else {
            this.notificationService.notifyCreated(order);
        }

        for (OrderLine line : order.getLines()) {
            this.inventoryService.reserve(line);
        }

        try {
            this.paymentClient.charge(customer.getId(), request.total());
        }
        catch (RuntimeException ex) {
            this.notificationService.notifyPaymentFailed(order);
            throw new OrderNotFoundException("Thanh toán thất bại");
        }

        Order saved = this.orderRepository.save(order);
        return this.orderMapper.toResponse(saved);
    }

    @Override
    public Order findById(Long id) {
        List<Order> found = this.orderRepository.findActiveById(id);
        if (found.isEmpty()) {
            throw new OrderNotFoundException("Không có đơn " + id);
        }
        return found.get(0);
    }
}
