package com.bank.settlement;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementService {

    private final SettlementDao settlementDao;

    private final SettlementRepository settlementRepository;

    public SettlementService(SettlementDao settlementDao, SettlementRepository settlementRepository) {
        this.settlementDao = settlementDao;
        this.settlementRepository = settlementRepository;
    }

    @Transactional
    public String post(Long id) {
        this.settlementDao.postEntry(id);
        this.settlementRepository.recalcBalance(id);
        this.settlementRepository.syncStatus(id);
        this.settlementRepository.archive();
        this.settlementDao.reverseIfNeeded(id);
        this.settlementDao.logAudit(id);
        this.settlementDao.syncLegacy();
        return "POSTED";
    }
}
