package com.fsa.textsql.query;

import java.sql.Types;

/** Maps JDBC types to the canonical dataType values of docs/contracts/domain.md. */
public final class SqlTypes {

  private SqlTypes() {}

  public static String canonical(int jdbcType) {
    return switch (jdbcType) {
      case Types.BIT, Types.BOOLEAN -> "BOOLEAN";
      case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> "INTEGER";
      case Types.BIGINT -> "BIGINT";
      case Types.NUMERIC, Types.DECIMAL -> "DECIMAL";
      case Types.REAL, Types.FLOAT, Types.DOUBLE -> "FLOAT";
      case Types.DATE -> "DATE";
      case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP";
      default -> "STRING";
    };
  }
}
