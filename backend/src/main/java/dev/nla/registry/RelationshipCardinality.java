package dev.nla.registry;

import dev.nla.common.ApiException;
import dev.nla.common.Json;
import dev.nla.store.Store;
import dev.nla.warehouse.Adapters;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Proves the "one" side of every declared relationship on real data (T-3.3.1, US-3.3 AC1).
 *
 * <p>MANY_TO_ONE needs a unique {@code to} column, ONE_TO_MANY a unique {@code from} column and
 * ONE_TO_ONE both. A single-column primary key already proves it; otherwise the warehouse is asked
 * for one duplicated non-null value with {@code GROUP BY col HAVING COUNT(*) > 1}. A duplicate
 * means joins through that edge would repeat rows and multiply totals, so the model is rejected.
 *
 * <p>The checks share one 5 second budget and at most 8 scans. When uniqueness cannot be proven in
 * that budget the declaration is rejected instead of trusted: add a key in the database or remove
 * the relationship.
 */
@Component
public class RelationshipCardinality {
  static final int MAX_SCANS = 8;
  static final long BUDGET_SECONDS = 5;

  private final Store store;
  private final Adapters adapters;

  RelationshipCardinality(Store store, Adapters adapters) {
    this.store = store;
    this.adapters = adapters;
  }

  public void verify(
      Map<String, Object> warehouse,
      List<Map<String, Object>> tables,
      List<Map<String, Object>> relationships) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BUDGET_SECONDS);
    Set<String> proven = new HashSet<>();
    int scans = 0;
    for (int i = 0; i < relationships.size(); i++) {
      var relation = relationships.get(i);
      for (String side : uniqueSides(Json.str(relation, "cardinality"))) {
        var endpoint = Json.map(relation.get(side));
        var table = Json.map(endpoint.get("table"));
        String column = Json.str(endpoint, "column");
        String identity = CatalogService.key(table) + "." + column;
        if (proven.contains(identity) || primaryKey(tables, table, column)) {
          proven.add(identity);
          continue;
        }
        String path = "relationships[" + i + "]." + side + ".column";
        long remaining = TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime());
        if (++scans > MAX_SCANS || remaining < 1)
          throw invalid(
              "Không chứng minh được cột '"
                  + column
                  + "' là duy nhất trong giới hạn kiểm tra; thêm khóa chính/unique trong CSDL"
                  + " hoặc bỏ bớt quan hệ chưa có ràng buộc.",
              path);
        if (hasDuplicate(warehouse, table, column, (int) Math.min(3, remaining)))
          throw invalid(
              "Cột '"
                  + CatalogService.key(table)
                  + "."
                  + column
                  + "' có giá trị trùng nên không thể là phía \"một\" của quan hệ "
                  + Json.str(relation, "cardinality")
                  + "; nối theo quan hệ này sẽ nhân bản số liệu.",
              path);
        proven.add(identity);
      }
    }
  }

  static List<String> uniqueSides(String cardinality) {
    return switch (cardinality) {
      case "MANY_TO_ONE" -> List.of("to");
      case "ONE_TO_MANY" -> List.of("from");
      case "ONE_TO_ONE" -> List.of("from", "to");
      default -> List.of();
    };
  }

  private static boolean primaryKey(
      List<Map<String, Object>> tables, Map<String, Object> table, String column) {
    return tables.stream()
        .filter(t -> CatalogService.key(t).equals(CatalogService.key(table)))
        .anyMatch(t -> List.of(column).equals(t.get("primaryKey")));
  }

  private boolean hasDuplicate(
      Map<String, Object> warehouse, Map<String, Object> table, String column, int seconds) {
    var adapter = adapters.get(warehouse);
    String col = adapter.quoteIdentifier(column);
    String sql =
        "SELECT "
            + col
            + " FROM "
            + adapter.quoteIdentifier(Json.str(table, "schema"))
            + "."
            + adapter.quoteIdentifier(Json.str(table, "name"))
            + " WHERE "
            + col
            + " IS NOT NULL GROUP BY "
            + col
            + " HAVING COUNT(*) > 1";
    try {
      var result =
          adapter.executeReadOnlyQuery(
              warehouse,
              adapters.password(store.credential(Json.str(warehouse, "warehouseId"))),
              sql,
              1,
              4096,
              Math.max(1, seconds));
      return !Json.objects(result.get("rows")).isEmpty();
    } catch (SQLException e) {
      org.slf4j.LoggerFactory.getLogger(RelationshipCardinality.class)
          .warn(
              "Cardinality check failed engine={} SQLState={} vendorCode={}",
              warehouse.get("engine"),
              e.getSQLState(),
              e.getErrorCode());
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "WAREHOUSE_CONNECTION_ERROR",
          "Không kiểm tra được dữ liệu quan hệ trên warehouse.");
    }
  }

  private static ApiException invalid(String message, String path) {
    return new ApiException(
        HttpStatus.CONFLICT, "SEMANTIC_MODEL_INVALID", message, Json.obj("path", path));
  }
}
