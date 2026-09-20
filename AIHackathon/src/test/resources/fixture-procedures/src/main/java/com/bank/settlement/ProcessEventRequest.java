package com.bank.settlement;

import lombok.Builder;
import lombok.Data;

/** Lombok {@code @Builder}: lớp builder sinh lúc compile, source không có nên không resolve được. */
@Data
@Builder
public class ProcessEventRequest {

    public String contractNumber;

    public String eventCode;

    public String errorCode;

    public String errorMsg;
}
