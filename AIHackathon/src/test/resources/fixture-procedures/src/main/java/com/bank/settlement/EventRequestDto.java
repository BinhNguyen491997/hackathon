package com.bank.settlement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class EventRequestDto {

    @NotBlank
    @Pattern(regexp = "\\d+", message = "contractNumber must be number")
    private String contractNumber;

    @NotBlank(message = "eventCode is required")
    private String eventCode;

    public String getContractNumber() {
        return this.contractNumber;
    }

    public String getEventCode() {
        return this.eventCode;
    }
}
