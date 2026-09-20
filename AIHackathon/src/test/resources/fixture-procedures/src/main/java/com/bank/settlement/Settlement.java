package com.bank.settlement;

import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedStoredProcedureQuery;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.StoredProcedureParameter;
import jakarta.persistence.Table;

@Entity
@Table(name = "SETTLEMENT")
@NamedStoredProcedureQuery(
        name = "Settlement.archive",
        procedureName = "PKG_ARCHIVE.MOVE_OLD",
        parameters = {
                @StoredProcedureParameter(mode = ParameterMode.IN, name = "p_cutoff_date",
                        type = LocalDate.class),
                @StoredProcedureParameter(mode = ParameterMode.OUT, name = "p_moved_rows",
                        type = Long.class)
        })
public class Settlement {

    @Id
    private Long id;

    private String status;

    public Long getId() {
        return this.id;
    }

    public String getStatus() {
        return this.status;
    }
}
