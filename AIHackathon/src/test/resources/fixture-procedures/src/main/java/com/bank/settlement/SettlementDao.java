package com.bank.settlement;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Types;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.stereotype.Repository;

/**
 * DAO gọi thẳng package PL/SQL - đúng kiểu hay gặp ở core banking chạy trên Oracle.
 */
@Repository
public class SettlementDao {

    /** Câu lệnh gom vào hằng số: literal không nằm tại chỗ gọi. */
    private static final String CALL_REVERSE = "{call PKG_SETTLEMENT.REVERSE_ENTRY(?, ?)}";

    private final JdbcTemplate jdbcTemplate;

    public SettlementDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * SimpleJdbcCall: withCatalogName chính là tên package của Oracle, và declareParameters là
     * chỗ duy nhất trong API này nói rõ tham số nào là OUT.
     */
    public void postEntry(Long id) {
        SimpleJdbcCall call = new SimpleJdbcCall(this.jdbcTemplate)
                .withCatalogName("PKG_SETTLEMENT")
                .withProcedureName("POST_ENTRY")
                .declareParameters(
                        new SqlParameter("p_id", Types.NUMERIC),
                        new SqlOutParameter("p_result_code", Types.VARCHAR));
        call.execute(new MapSqlParameterSource("p_id", id));
    }

    /** CallableStatement: câu lệnh lấy từ hằng số, tham số OUT đăng ký theo vị trí. */
    public void reverseIfNeeded(Long id) {
        this.jdbcTemplate.execute((Connection connection) -> {
            CallableStatement statement = connection.prepareCall(CALL_REVERSE);
            statement.setLong(1, id);
            statement.registerOutParameter(2, Types.NUMERIC);
            statement.execute();
            return statement.getLong(2);
        });
    }

    /** Khối PL/SQL vô danh gửi thẳng xuống database, tham số theo vị trí kiểu Oracle. */
    public void logAudit(Long id) {
        this.jdbcTemplate.execute("BEGIN PKG_AUDIT.LOG_EVENT(:1); END;");
    }

    /** Procedure gọi bằng tên trần: không biết package, cũng không khai báo tham số nào. */
    public void syncLegacy() {
        new SimpleJdbcCall(this.jdbcTemplate).withProcedureName("SP_LEGACY_SYNC").execute();
    }
}
