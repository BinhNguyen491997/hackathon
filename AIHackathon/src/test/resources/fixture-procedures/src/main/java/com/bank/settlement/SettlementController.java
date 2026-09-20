package com.bank.settlement;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @Operation(summary = "Hạch toán bù trừ cuối ngày")
    @PostMapping("/{id}/post")
    public String post(@PathVariable Long id) {
        return this.settlementService.post(id);
    }
}
