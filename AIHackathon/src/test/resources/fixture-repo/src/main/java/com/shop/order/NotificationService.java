package com.shop.order;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public NotificationService(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void notifyCreated(Order order) {
        try {
            this.kafkaTemplate.send("order-created", String.valueOf(order.getId()));
        }
        catch (RuntimeException ex) {
            // gửi thông báo lỗi thì bỏ qua, không làm fail đơn hàng
        }
    }

    public void notifyHighValue(Order order) {
        this.kafkaTemplate.send("order-high-value", String.valueOf(order.getId()));
    }

    public void notifyPaymentFailed(Order order) {
        this.kafkaTemplate.send("order-payment-failed", String.valueOf(order.getId()));
    }
}
