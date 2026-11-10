package com.fsa.textsql.catalog;

import com.fsa.textsql.dataset.TableRef;

/**
 * Link between two tables. {@code source} is FOREIGN_KEY (discovered) or SEMANTIC (declared in the
 * semantic model); {@code cardinality} is null when not declared.
 */
public record Relationship(Endpoint from, Endpoint to, String cardinality, String source) {

  public record Endpoint(TableRef table, String column) {}
}
