package com.shop.payment;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

@FeignClient(name = "payment-service")
public interface PaymentClient {

    @PostMapping("/payments")
    void charge(Long customerId, long amount);
}
