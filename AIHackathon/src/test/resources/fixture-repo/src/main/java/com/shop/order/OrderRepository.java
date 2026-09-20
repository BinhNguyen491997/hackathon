package com.shop.order;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("select o from Order o where o.id = :id and o.status <> 'CANCELLED'")
    List<Order> findActiveById(Long id);
}
