package com.bank.settlement;

import jakarta.persistence.EntityManager;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.StoredProcedureQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

/**
 * Gọi procedure bằng API JPA {@code StoredProcedureQuery} - cách phổ biến nhất ở core banking, và
 * là chỗ duy nhất khai báo chiều IN/OUT/INOUT/REF_CURSOR tường minh.
 */
@Repository
@Slf4j
public class EventDao {

    protected EntityManager entityManager;

    public ProcessEventRequest postEvent(ProcessEventRequest processEventRequest) {
        // Câu log bắt đầu bằng chữ "Call " - KHÔNG được đọc thành câu lệnh SQL.
        log.info("Call method postEvent contractNo: {} eventCode: {} ",
                processEventRequest.getContractNumber(), processEventRequest.getEventCode());

        StoredProcedureQuery storedProcedure = this.entityManager
                .createStoredProcedureQuery("CARDAPP.pkg_msb_card_api.prc_process_event_one");

        storedProcedure.registerStoredProcedureParameter(1, String.class, ParameterMode.INOUT);
        storedProcedure.registerStoredProcedureParameter(2, String.class, ParameterMode.IN);
        storedProcedure.registerStoredProcedureParameter(3, String.class, ParameterMode.IN);
        storedProcedure.registerStoredProcedureParameter(4, void.class, ParameterMode.REF_CURSOR);
        storedProcedure.registerStoredProcedureParameter(5, String.class, ParameterMode.OUT);

        storedProcedure.setParameter(2, processEventRequest.contractNumber);
        storedProcedure.setParameter(3, processEventRequest.eventCode);
        storedProcedure.execute();

        processEventRequest.errorMsg = (String) storedProcedure.getOutputParameterValue(1);
        processEventRequest.errorCode = (String) storedProcedure.getOutputParameterValue(5);
        return processEventRequest;
    }
}
