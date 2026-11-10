package com.fsa.textsql.common;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validated list parameters (page, pageSize, sort, direction) plus endpoint-specific filters.
 *
 * <p>Sort fields are mapped through an allowlist to fixed SQL columns, so user input is never
 * concatenated into SQL. Unknown query parameters are rejected to surface client/contract drift.
 */
public record PageQuery(
    int page, int pageSize, String sortColumn, String direction, Map<String, String> filters) {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;
  private static final Set<String> PAGING_KEYS = Set.of("page", "pageSize", "sort", "direction");

  /**
   * @param params raw query parameters
   * @param sortColumns allowed API sort names mapped to SQL column names
   * @param defaultSort API sort name used when none is given
   * @param filterKeys filter parameter names accepted by the endpoint
   */
  public static PageQuery parse(
      Map<String, String> params,
      Map<String, String> sortColumns,
      String defaultSort,
      Set<String> filterKeys) {
    Set<String> unknown = new HashSet<>(params.keySet());
    unknown.removeAll(PAGING_KEYS);
    unknown.removeAll(filterKeys);
    if (!unknown.isEmpty()) {
      throw ApiException.invalidRequest(
          "Tham số không được hỗ trợ.", Map.of("parameters", List.copyOf(unknown)));
    }

    int page = positiveInt(params.get("page"), 1, "page");
    int pageSize = positiveInt(params.get("pageSize"), DEFAULT_PAGE_SIZE, "pageSize");
    if (pageSize > MAX_PAGE_SIZE) {
      throw ApiException.invalidRequest(
          "pageSize tối đa " + MAX_PAGE_SIZE + ".", Map.of("parameter", "pageSize"));
    }

    String sort = params.getOrDefault("sort", defaultSort);
    String column = sortColumns.get(sort);
    if (column == null) {
      throw ApiException.invalidRequest(
          "Không hỗ trợ sắp xếp theo trường này.",
          Map.of("parameter", "sort", "allowed", List.copyOf(sortColumns.keySet())));
    }

    String direction = params.getOrDefault("direction", "DESC").toUpperCase();
    if (!direction.equals("ASC") && !direction.equals("DESC")) {
      throw ApiException.invalidRequest(
          "direction chỉ nhận ASC hoặc DESC.", Map.of("parameter", "direction"));
    }

    Map<String, String> filters = new java.util.LinkedHashMap<>();
    for (String key : filterKeys) {
      String value = params.get(key);
      if (value != null && !value.isBlank()) {
        filters.put(key, value.strip());
      }
    }
    return new PageQuery(page, pageSize, column, direction, Map.copyOf(filters));
  }

  public int offset() {
    return (page - 1) * pageSize;
  }

  /** Rejects a filter value that is not one of the enum constants (400 INVALID_REQUEST). */
  public <E extends Enum<E>> void requireEnum(String key, Class<E> type) {
    String value = filters.get(key);
    if (value == null) {
      return;
    }
    List<String> allowed = Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    if (!allowed.contains(value)) {
      throw ApiException.invalidRequest(
          key + " không hợp lệ.", Map.of("parameter", key, "allowed", allowed));
    }
  }

  /** Escapes LIKE wildcards so a search term is matched literally (used with ESCAPE '\'). */
  public static String likePattern(String term) {
    String escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  private static int positiveInt(String raw, int fallback, String name) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      int value = Integer.parseInt(raw.strip());
      if (value < 1) {
        throw new NumberFormatException();
      }
      return value;
    } catch (NumberFormatException e) {
      throw ApiException.invalidRequest(
          name + " phải là số nguyên dương.", Map.of("parameter", name));
    }
  }
}
