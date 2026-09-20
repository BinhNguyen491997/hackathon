package com.bank.settlement;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Mô phỏng đúng hai nguồn nhiễu Lombok gặp trên repo thật: field {@code log} do {@code @Slf4j} sinh,
 * và lớp builder do {@code @Builder} sinh. Cả hai không tồn tại trong source nên symbol solver chắc
 * chắn thất bại - nhưng chúng không phải bước nghiệp vụ, không được báo là "chưa xác định được".
 */
@Service
@Slf4j
public class EventService {

    private final EventDao eventDao;

    public EventService(EventDao eventDao) {
        this.eventDao = eventDao;
    }

    public String post(Long id) {
        ProcessEventRequest request = ProcessEventRequest.builder()
                .contractNumber(String.valueOf(id))
                .eventCode("C2P_ENROLL")
                .build();

        ProcessEventRequest result = this.eventDao.postEvent(request);
        log.info("Post event {} return {}", id, result.getErrorMsg());

        if (!"00".equals(result.getErrorCode())) {
            throw new BaseException(new Object[] { "prc_process_event_one", result.getErrorMsg() },
                    CommonErrorCode.CALL_EVENT_PROCESS_ONE);
        }
        return result.getErrorCode();
    }
}
