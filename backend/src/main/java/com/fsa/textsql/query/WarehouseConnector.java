package com.fsa.textsql.query;

import com.fsa.textsql.common.ApiException;
import com.fsa.textsql.warehouse.WarehouseConnection;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Opens read-only JDBC connections to the three supported engines with the warehouse's reader
 * account. Read-only is enforced at the driver/session level here and, independently, by the
 * database grants of the reader account.
 */
@Component
public class WarehouseConnector {

  private static final Logger log = LoggerFactory.getLogger(WarehouseConnector.class);

  private final CredentialResolver credentials;
  private final QueryProperties properties;

  public WarehouseConnector(CredentialResolver credentials, QueryProperties properties) {
    this.credentials = credentials;
    this.properties = properties;
  }

  public Connection openReadOnly(WarehouseConnection w) {
    if (!properties.allowedHostSet().isEmpty() && !properties.allowedHostSet().contains(w.host())) {
      throw connectionError();
    }
    Properties props = new Properties();
    props.setProperty("user", w.username());
    props.setProperty("password", credentials.resolve(w.credentialReference()));
    if (w.engine() == com.fsa.textsql.warehouse.Engine.MYSQL) {
      // Passed as a driver property, not in the URL: Windows paths contain '\' and ':'.
      String rsa = properties.mysqlRsaPublicKey();
      if (rsa != null
          && !rsa.isBlank()
          && java.nio.file.Files.isReadable(java.nio.file.Path.of(rsa))) {
        props.setProperty("serverRSAPublicKeyFile", rsa);
      } else {
        props.setProperty("allowPublicKeyRetrieval", "true");
      }
    }
    try {
      Connection connection = DriverManager.getConnection(jdbcUrl(w), props);
      connection.setReadOnly(true);
      return connection;
    } catch (SQLException e) {
      // SQLState/vendor code only: messages can contain host names or user names.
      log.warn(
          "Warehouse connection failed warehouse={} engine={} sqlState={} vendorCode={}",
          w.id(),
          w.engine(),
          e.getSQLState(),
          e.getErrorCode());
      throw connectionError();
    }
  }

  String jdbcUrl(WarehouseConnection w) {
    int login = properties.loginTimeoutSeconds();
    return switch (w.engine()) {
      case POSTGRESQL ->
          "jdbc:postgresql://"
              + w.host()
              + ":"
              + w.port()
              + "/"
              + w.database()
              // readOnlyMode=always makes every transaction read-only, also in auto-commit.
              + "?readOnly=true&readOnlyMode=always&ApplicationName=text-to-sql"
              + "&connectTimeout="
              + login
              + "&loginTimeout="
              + login;
      case MYSQL ->
          "jdbc:mysql://"
              + w.host()
              + ":"
              + w.port()
              + "/"
              + w.database()
              + "?sslMode=PREFERRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=false"
              + "&connectTimeout="
              + (login * 1000);
      case SQL_SERVER ->
          "jdbc:sqlserver://"
              + w.host()
              + ":"
              + w.port()
              + ";databaseName="
              + w.database()
              // Local containers use a self-signed certificate; traffic is still encrypted.
              + ";encrypt=true;trustServerCertificate=true;applicationIntent=ReadOnly"
              + ";applicationName=text-to-sql;loginTimeout="
              + login;
    };
  }

  private static ApiException connectionError() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "WAREHOUSE_CONNECTION_ERROR",
        "Không kết nối được kho dữ liệu.",
        true,
        Map.of());
  }
}
