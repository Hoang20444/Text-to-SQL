package com.fsa.textsql.common;

import java.util.List;

/** List envelope from docs/contracts/domain.md (List convention). */
public record PageResponse<T>(
    List<T> items, int page, int pageSize, long totalItems, long totalPages) {

  public static <T> PageResponse<T> of(List<T> items, PageQuery query, long totalItems) {
    long totalPages = totalItems == 0 ? 0 : (totalItems + query.pageSize() - 1) / query.pageSize();
    return new PageResponse<>(items, query.page(), query.pageSize(), totalItems, totalPages);
  }
}
