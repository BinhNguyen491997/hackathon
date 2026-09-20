package com.shop.inventory;

import com.shop.order.OrderLine;
import org.springframework.stereotype.Service;

@Service
public class InventoryService {

    private final StockRepository stockRepository;

    public InventoryService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    public void reserve(OrderLine line) {
        switch (line.getSku()) {
            case "GIFT" -> this.stockRepository.reserveGift(line.getQuantity());
            case "NORMAL" -> this.stockRepository.reserveNormal(line.getQuantity());
            default -> this.stockRepository.reserveDefault(line.getSku(), line.getQuantity());
        }
    }
}
