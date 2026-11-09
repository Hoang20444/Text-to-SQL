package com.fsa.textsql.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PageQueryTest {

  private static final Map<String, String> SORTS =
      Map.of("createdAt", "created_at", "name", "name");
  private static final Set<String> FILTERS = Set.of("search");

  @Test
  void usesContractDefaults() {
    PageQuery q = PageQuery.parse(Map.of(), SORTS, "createdAt", FILTERS);

    assertThat(q.page()).isEqualTo(1);
    assertThat(q.pageSize()).isEqualTo(20);
    assertThat(q.sortColumn()).isEqualTo("created_at");
    assertThat(q.direction()).isEqualTo("DESC");
    assertThat(q.offset()).isZero();
  }

  @Test
  void mapsSortThroughAllowlistAndComputesOffset() {
    PageQuery q =
        PageQuery.parse(
            Map.of("page", "3", "pageSize", "10", "sort", "name", "direction", "asc"),
            SORTS,
            "createdAt",
            FILTERS);

    assertThat(q.sortColumn()).isEqualTo("name");
    assertThat(q.direction()).isEqualTo("ASC");
    assertThat(q.offset()).isEqualTo(20);
  }

  @Test
  void rejectsSortOutsideAllowlist() {
    assertThatThrownBy(
            () ->
                PageQuery.parse(
                    Map.of("sort", "name; DROP TABLE warehouses"), SORTS, "createdAt", FILTERS))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("INVALID_REQUEST");
  }

  @Test
  void rejectsPageSizeAboveMaximumAndNonPositiveNumbers() {
    assertThatThrownBy(
            () -> PageQuery.parse(Map.of("pageSize", "101"), SORTS, "createdAt", FILTERS))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> PageQuery.parse(Map.of("page", "0"), SORTS, "createdAt", FILTERS))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> PageQuery.parse(Map.of("page", "abc"), SORTS, "createdAt", FILTERS))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void rejectsUnknownParameters() {
    assertThatThrownBy(() -> PageQuery.parse(Map.of("foo", "1"), SORTS, "createdAt", FILTERS))
        .isInstanceOf(ApiException.class)
        .extracting("details")
        .isEqualTo(Map.of("parameters", java.util.List.of("foo")));
  }

  @Test
  void escapesLikeWildcards() {
    assertThat(PageQuery.likePattern("50%_off\\")).isEqualTo("%50\\%\\_off\\\\%");
  }
}
