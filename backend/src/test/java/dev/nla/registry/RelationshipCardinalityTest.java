package dev.nla.registry;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.nla.common.ApiException;
import dev.nla.common.Json;
import dev.nla.store.Store;
import dev.nla.warehouse.Adapters;
import dev.nla.warehouse.WarehouseAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T-3.3.1: primary keys skip the scan, and the scan budget fails closed after 8 scans. */
class RelationshipCardinalityTest {
  private final Map<String, Object> warehouse = Json.obj("warehouseId", "wh-1", "engine", "X");
  private WarehouseAdapter adapter;
  private RelationshipCardinality cardinality;

  @BeforeEach
  void setUp() throws Exception {
    var store = mock(Store.class);
    var adapters = mock(Adapters.class);
    adapter = mock(WarehouseAdapter.class);
    when(adapters.get(warehouse)).thenReturn(adapter);
    when(adapters.password(any())).thenReturn("secret");
    when(store.credential("wh-1")).thenReturn("env:TEST");
    when(adapter.quoteIdentifier(anyString())).thenAnswer(call -> call.getArgument(0));
    // Every scan finds no duplicated value.
    when(adapter.executeReadOnlyQuery(
            eq(warehouse), any(), anyString(), anyInt(), anyInt(), anyInt()))
        .thenReturn(Json.obj("rows", List.of()));
    cardinality = new RelationshipCardinality(store, adapters);
  }

  private static <T> T any() {
    return org.mockito.ArgumentMatchers.any();
  }

  private static Map<String, Object> table(String name, List<String> primaryKey) {
    return Json.obj("schema", "s", "name", name, "primaryKey", primaryKey);
  }

  private static Map<String, Object> manyToOne(String toTable, String toColumn) {
    return Json.obj(
        "from",
        Json.obj("table", Json.obj("schema", "s", "name", "fact"), "column", "k"),
        "to",
        Json.obj("table", Json.obj("schema", "s", "name", toTable), "column", toColumn),
        "cardinality",
        "MANY_TO_ONE");
  }

  @Test
  void primaryKeyNeedsNoScan() throws Exception {
    var tables = List.of(table("fact", List.of()), table("dim", List.of("id")));

    cardinality.verify(warehouse, tables, List.of(manyToOne("dim", "id")));

    verify(adapter, never())
        .executeReadOnlyQuery(any(), any(), anyString(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void manyToManyIsNeverScanned() throws Exception {
    var relation = new java.util.LinkedHashMap<>(manyToOne("dim", "code"));
    relation.put("cardinality", "MANY_TO_MANY");

    cardinality.verify(warehouse, List.of(table("dim", List.of())), List.of(relation));

    verify(adapter, never())
        .executeReadOnlyQuery(any(), any(), anyString(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void eightScansAreAllowedAndTheNinthFailsClosed() throws Exception {
    List<Map<String, Object>> tables = new ArrayList<>();
    List<Map<String, Object>> relationships = new ArrayList<>();
    for (int i = 0; i < RelationshipCardinality.MAX_SCANS + 1; i++) {
      tables.add(table("dim" + i, List.of()));
      relationships.add(manyToOne("dim" + i, "code"));
    }

    assertThatCode(() -> cardinality.verify(warehouse, tables, relationships.subList(0, 8)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> cardinality.verify(warehouse, tables, relationships))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Không chứng minh được");
    // 8 scans for the first call, 8 more before the second call gives up on the ninth.
    verify(adapter, times(16))
        .executeReadOnlyQuery(any(), any(), anyString(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void theSameColumnIsScannedOnce() throws Exception {
    var tables = List.of(table("dim", List.of()));

    cardinality.verify(
        warehouse, tables, List.of(manyToOne("dim", "code"), manyToOne("dim", "code")));

    verify(adapter, times(1))
        .executeReadOnlyQuery(any(), any(), anyString(), anyInt(), anyInt(), anyInt());
  }
}
