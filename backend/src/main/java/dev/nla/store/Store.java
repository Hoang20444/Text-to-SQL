package dev.nla.store;

import dev.nla.common.ApiException;
import dev.nla.common.Errors;
import dev.nla.common.Json;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Repository;

/** Metadata store: registry documents kept as jsonb plus warehouse-level access grants. */
@Repository
public class Store {
  private final JdbcTemplate jdbc;

  public Store(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public JdbcTemplate jdbc() {
    return jdbc;
  }

  public String actor() {
    var a = SecurityContextHolder.getContext().getAuthentication();
    return a == null ? "anonymous" : a.getName();
  }

  public boolean admin() {
    var a = SecurityContextHolder.getContext().getAuthentication();
    return a != null
        && a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_ADMIN"));
  }

  /** ADMIN sees every warehouse; other roles need a resource_access grant. */
  public void authorize(String warehouse) {
    if (admin()) return;
    if (jdbc.queryForObject(
            "SELECT count(*) FROM resource_access WHERE subject=? AND warehouse_id=?",
            Integer.class,
            actor(),
            warehouse)
        == 0)
      throw new ApiException(
          HttpStatus.FORBIDDEN,
          "FORBIDDEN",
          "Không có quyền truy cập warehouse.",
          Json.obj("warehouseId", warehouse));
  }

  public boolean canAccess(String warehouse) {
    try {
      authorize(warehouse);
      return true;
    } catch (ApiException e) {
      return false;
    }
  }

  public List<Map<String, Object>> all(String table) {
    allowed(table);
    return jdbc.query(
        "SELECT document::text FROM " + table + " ORDER BY id",
        (rs, n) -> Json.read(rs.getString(1)));
  }

  public List<Map<String, Object>> warehouses() {
    return all("warehouse").stream().filter(w -> canAccess(Json.str(w, "warehouseId"))).toList();
  }

  public Map<String, Object> warehouse(String id) {
    authorize(id);
    return get("warehouse", id);
  }

  public Map<String, Object> dataset(String id) {
    var d = get("dataset", id);
    authorize(Json.str(d, "warehouseId"));
    return d;
  }

  public Map<String, Object> get(String table, String id) {
    allowed(table);
    var rows =
        jdbc.query(
            "SELECT document::text FROM " + table + " WHERE id=?",
            (rs, n) -> Json.read(rs.getString(1)),
            id);
    if (rows.isEmpty()) throw ApiException.notFound();
    return rows.getFirst();
  }

  public boolean exists(String table, String id) {
    allowed(table);
    return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id=?", Integer.class, id)
        > 0;
  }

  public Map<String, Object> semantic(String id) {
    dataset(id);
    return Json.read(
        jdbc.queryForObject(
            "SELECT semantic_model::text FROM dataset WHERE id=?", String.class, id));
  }

  public void insertWarehouse(Map<String, Object> doc, String reference) {
    jdbc.update(
        "INSERT INTO warehouse(id,document,credential_reference) VALUES (?,?::jsonb,?)",
        doc.get("warehouseId"),
        Json.write(doc),
        reference);
  }

  public String credential(String id) {
    return jdbc.queryForObject(
        "SELECT credential_reference FROM warehouse WHERE id=?", String.class, id);
  }

  /** reference null keeps the stored credential reference. */
  public void updateWarehouse(Map<String, Object> doc, String reference) {
    jdbc.update(
        "UPDATE warehouse SET"
            + " document=?::jsonb,credential_reference=coalesce(?,credential_reference),updated_at=now()"
            + " WHERE id=?",
        Json.write(doc),
        reference,
        doc.get("warehouseId"));
  }

  public void run(String id, String kind, Map<String, Object> document) {
    jdbc.update(
        "INSERT INTO operation_run(id,kind,document) VALUES (?,?,?::jsonb) ON CONFLICT(id) DO"
            + " UPDATE SET document=excluded.document",
        id,
        kind,
        Json.write(document));
  }

  public Map<String, Object> run(String id, String kind) {
    var list =
        jdbc.query(
            "SELECT document::text FROM operation_run WHERE id=? AND kind=?",
            (rs, n) -> Json.read(rs.getString(1)),
            id,
            kind);
    if (list.isEmpty()) throw ApiException.notFound();
    var doc = list.getFirst();
    if (doc.get("warehouseId") != null) authorize(doc.get("warehouseId").toString());
    return doc;
  }

  public void insertDataset(Map<String, Object> doc, Map<String, Object> semantic) {
    jdbc.update(
        "INSERT INTO dataset(id,warehouse_id,document,semantic_model) VALUES"
            + " (?,?,?::jsonb,?::jsonb)",
        doc.get("datasetId"),
        doc.get("warehouseId"),
        Json.write(doc),
        Json.write(semantic));
  }

  public void updateDataset(Map<String, Object> doc, Map<String, Object> semantic) {
    jdbc.update(
        "UPDATE dataset SET document=?::jsonb,semantic_model=?::jsonb,updated_at=now() WHERE id=?",
        Json.write(doc),
        Json.write(semantic),
        doc.get("datasetId"));
  }

  public void putQuery(Map<String, Object> doc) {
    jdbc.update(
        "INSERT INTO query_history(id,warehouse_id,dataset_id,document) VALUES (?,?,?,?::jsonb) ON"
            + " CONFLICT(id) DO UPDATE SET document=excluded.document",
        doc.get("queryId"),
        doc.get("warehouseId"),
        doc.get("datasetId"),
        Json.write(doc));
  }

  public Map<String, Object> settings() {
    return Json.read(
        jdbc.queryForObject(
            "SELECT document::text FROM platform_settings WHERE id=1", String.class));
  }

  public void settings(Map<String, Object> doc) {
    jdbc.update("UPDATE platform_settings SET document=?::jsonb WHERE id=1", Json.write(doc));
  }

  public List<Map<String, Object>> runs(String kind) {
    return jdbc.query(
        "SELECT document::text FROM operation_run WHERE kind=? ORDER BY created_at DESC",
        (rs, n) -> Json.read(rs.getString(1)),
        kind);
  }

  public void audit(String action, String id) {
    jdbc.update(
        "INSERT INTO audit_event(actor,action,resource_id,correlation_id) VALUES (?,?,?,?)",
        actor(),
        action,
        id,
        Errors.correlation());
  }

  public void audit(String action, String id, Map<String, Object> details) {
    jdbc.update(
        "INSERT INTO audit_event(actor,action,resource_id,correlation_id,details)"
            + " VALUES (?,?,?,?,?::jsonb)",
        actor(),
        action,
        id,
        Errors.correlation(),
        Json.write(details));
  }

  public List<String> accessibleWarehouseIds() {
    if (admin()) {
      return jdbc.queryForList("SELECT id FROM warehouse ORDER BY id", String.class);
    }
    return jdbc.queryForList(
        "SELECT warehouse_id FROM resource_access WHERE subject=? ORDER BY warehouse_id",
        String.class,
        actor());
  }

  public void grantWarehouseAccess(String warehouseId, String subject) {
    jdbc.update(
        "INSERT INTO resource_access(subject,warehouse_id) VALUES (?,?)"
            + " ON CONFLICT(subject,warehouse_id) DO NOTHING",
        subject,
        warehouseId);
  }

  private static void allowed(String table) {
    if (!Set.of("warehouse", "dataset", "query_history").contains(table))
      throw new IllegalArgumentException("Invalid metadata table");
  }
}
