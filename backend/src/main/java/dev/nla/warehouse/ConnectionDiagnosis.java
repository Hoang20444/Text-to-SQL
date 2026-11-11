package dev.nla.warehouse;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

/**
 * Turns a JDBC failure into a safe reason code (US-2.1 AC1). Only SQLState and vendor codes are
 * read; driver messages, hosts and credentials never reach the response.
 */
public final class ConnectionDiagnosis {
  private ConnectionDiagnosis() {}

  public static String reason(SQLException e) {
    if (e instanceof SQLTimeoutException) return "TIMEOUT";
    String state = e.getSQLState() == null ? "" : e.getSQLState();
    int vendor = e.getErrorCode();
    // PostgreSQL 28P01/28000, MySQL 1045, SQL Server 18456.
    if (state.startsWith("28") || vendor == 1045 || vendor == 18456) return "AUTHENTICATION_FAILED";
    // PostgreSQL 3D000, MySQL 1049, SQL Server 4060.
    if (state.equals("3D000") || vendor == 1049 || vendor == 4060) return "DATABASE_NOT_FOUND";
    if (state.startsWith("08")) return "HOST_UNREACHABLE";
    return "CONNECTION_FAILED";
  }

  public static String message(String reason) {
    return switch (reason) {
      case "AUTHENTICATION_FAILED" -> "Sai tài khoản hoặc mật khẩu của kho dữ liệu.";
      case "DATABASE_NOT_FOUND" -> "Không tìm thấy cơ sở dữ liệu đã khai báo.";
      case "HOST_UNREACHABLE" -> "Không kết nối được tới máy chủ hoặc cổng của kho dữ liệu.";
      case "TIMEOUT" -> "Kho dữ liệu không phản hồi trong thời gian cho phép.";
      case "CREDENTIAL_NOT_CONFIGURED" -> "Biến môi trường chứa mật khẩu chưa được cấu hình.";
      case "HOST_NOT_ALLOWED" -> "Máy chủ kho không nằm trong danh sách được phép.";
      default -> "Không thể kết nối kho dữ liệu.";
    };
  }
}
