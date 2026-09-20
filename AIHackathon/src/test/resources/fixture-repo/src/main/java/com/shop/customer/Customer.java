package com.shop.customer;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Customer {

    @Id
    private Long id;

    private String code;

    public Long getId() {
        return this.id;
    }

    public String getCode() {
        return this.code;
    }
}
