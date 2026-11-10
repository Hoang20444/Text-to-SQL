package com.fsa.textsql.dataset;

/** Fully qualified table reference. For MySQL, {@code schema} is the database/catalog. */
public record TableRef(String schema, String name) {}
