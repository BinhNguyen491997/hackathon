package com.bank.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.query.Procedure;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    /** Tên thật trong database, khai báo tường minh. */
    @Procedure(procedureName = "PKG_SETTLEMENT.RECALC_BALANCE")
    void recalcBalance(Long id);

    /** Procedure gọi qua @Query nativeQuery. */
    @Query(value = "{ call PKG_SETTLEMENT.SYNC_STATUS(:id) }", nativeQuery = true)
    void syncStatus(Long id);

    /** Trỏ tới @NamedStoredProcedureQuery trên entity, phải tra tiếp mới ra tên thật. */
    @Procedure(name = "Settlement.archive")
    void archive();
}
