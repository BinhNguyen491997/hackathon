package com.shop.order;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private Long id;

    private String status;

    private final List<OrderLine> lines = new ArrayList<>();

    public Long getId() {
        return this.id;
    }

    public String getStatus() {
        return this.status;
    }

    public List<OrderLine> getLines() {
        return this.lines;
    }
}
