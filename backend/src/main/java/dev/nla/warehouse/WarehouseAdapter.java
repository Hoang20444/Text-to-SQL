package dev.nla.warehouse;

import java.sql.*;
import java.util.*;

public interface WarehouseAdapter {
  String engine();

  SqlDialect getSqlDialect();

  Map<String, Boolean> getCapabilities();

  Connection connect(Map<String, Object> warehouse, String password) throws SQLException;

  boolean testConnection(Map<String, Object> warehouse, String password) throws SQLException;

  List<String> discoverSchemas(Connection connection) throws SQLException;

  List<Map<String, Object>> discoverTables(Connection connection, Set<String> schemas)
      throws SQLException;

  List<Map<String, Object>> discoverColumns(Connection connection, String schema, String table)
      throws SQLException;

  List<Map<String, Object>> discoverRelationships(
      Connection connection, String schema, String table) throws SQLException;

  String quoteIdentifier(String identifier);

  String normalizeIdentifier(Connection connection, String identifier, boolean quoted)
      throws SQLException;
}
