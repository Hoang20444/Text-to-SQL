package dev.nla.query;

import dev.nla.common.Json;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Writes a stored result snapshot as CSV that is safe to open in Excel (T-4.3.2, US-4.3 AC3).
 *
 * <ul>
 *   <li>UTF-8 with a byte order mark, so Excel shows Vietnamese text instead of mojibake.
 *   <li>RFC 4180 quoting with CRLF line ends; a SQL NULL is an empty field while an empty string is
 *       {@code ""}.
 *   <li>Formula injection (OWASP CSV Injection): a text cell whose first non-blank character is
 *       {@code = + - @}, or that starts with a tab or carriage return, gets a leading apostrophe so
 *       Excel shows it as text. Numeric and boolean columns come from the JDBC driver and keep
 *       their value, so negative numbers stay numbers.
 * </ul>
 */
final class CsvExport {
  static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
  private static final Set<String> SAFE_TYPES =
      Set.of("INTEGER", "BIGINT", "DECIMAL", "FLOAT", "BOOLEAN");
  private static final Pattern FORMULA =
      Pattern.compile("(?s)^(?:[\\t\\r].*|[\\s\\u0000-\\u001f]*[=+\\-@].*)");

  private CsvExport() {}

  static byte[] write(Map<String, Object> result) {
    var columns = Json.objects(result.get("columns"));
    StringBuilder out = new StringBuilder();
    out.append(line(columns.stream().map(c -> text(Json.str(c, "name"))).toList()));
    for (var row : Json.objects(result.get("rows"))) {
      out.append(
          line(
              columns.stream()
                  .map(c -> value(row.get(Json.str(c, "name")), Json.str(c, "dataType")))
                  .toList()));
    }
    byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
    byte[] bytes = new byte[BOM.length + body.length];
    System.arraycopy(BOM, 0, bytes, 0, BOM.length);
    System.arraycopy(body, 0, bytes, BOM.length, body.length);
    return bytes;
  }

  private static String line(List<String> cells) {
    return cells.stream().collect(Collectors.joining(",")) + "\r\n";
  }

  private static String value(Object value, String dataType) {
    if (value == null) return "";
    if (SAFE_TYPES.contains(dataType)) return quote(value.toString());
    return text(value.toString());
  }

  /** A text cell, neutralised when Excel would read it as a formula. */
  static String text(String value) {
    return quote(FORMULA.matcher(value).matches() ? "'" + value : value);
  }

  private static String quote(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }
}
