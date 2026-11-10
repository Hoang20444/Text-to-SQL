package com.fsa.textsql.warehouse;

/** The only engine/dialect values allowed across FE, BE and AI (docs/contracts/domain.md). */
public enum Engine {
  POSTGRESQL,
  MYSQL,
  SQL_SERVER
}
