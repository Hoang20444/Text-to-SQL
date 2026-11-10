package com.fsa.textsql.security;

/** The three application roles from backlog US-1.2. */
public enum AppRole {
  /** Full administration. */
  ADMIN,
  /** Data analysis (queries). */
  ANALYST,
  /** Read-only access to granted warehouses. */
  VIEWER;

  public String authority() {
    return "ROLE_" + name();
  }
}
