package com.fsa.textsql.access;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WarehouseAccessRepository {

  private final JdbcClient jdbc;

  public WarehouseAccessRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Grants access unless it already exists. Returns true when a new grant was stored. */
  public boolean grantIfAbsent(String subject, String warehouseId) {
    return jdbc.sql(
                """
                INSERT INTO warehouse_access (subject, warehouse_id)
                VALUES (:subject, :warehouseId)
                ON CONFLICT DO NOTHING
                """)
            .param("subject", subject)
            .param("warehouseId", warehouseId)
            .update()
        == 1;
  }

  public boolean hasAccess(String subject, String warehouseId) {
    return jdbc.sql(
                "SELECT count(*) FROM warehouse_access"
                    + " WHERE subject = :subject AND warehouse_id = :warehouseId")
            .param("subject", subject)
            .param("warehouseId", warehouseId)
            .query(Long.class)
            .single()
        > 0;
  }

  public List<String> warehouseIdsFor(String subject) {
    return jdbc.sql(
            "SELECT warehouse_id FROM warehouse_access WHERE subject = :subject"
                + " ORDER BY warehouse_id")
        .param("subject", subject)
        .query(String.class)
        .list();
  }
}
