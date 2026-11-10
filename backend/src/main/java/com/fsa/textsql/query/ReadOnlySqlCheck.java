package com.fsa.textsql.query;

import com.fsa.textsql.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * Java-side pre-execution check for Sprint 1: exactly one statement starting with SELECT or WITH
 * and no write/admin keyword outside string literals. The AI candidate is never trusted on its own.
 * The reader account's database grants remain the independent last line of defense, and the full
 * recursive AST guard with table allowlists is backlog task T-2.2.1 (Sprint 2).
 */
final class ReadOnlySqlCheck {

  private static final Set<String> FORBIDDEN =
      Set.of(
          "alter",
          "attach",
          "backup",
          "call",
          "copy",
          "create",
          "delete",
          "detach",
          "drop",
          "exec",
          "execute",
          "grant",
          "insert",
          "into",
          "load",
          "lock",
          "merge",
          "openquery",
          "openrowset",
          "opendatasource",
          "outfile",
          "prepare",
          "reconfigure",
          "rename",
          "restore",
          "revoke",
          "set",
          "shutdown",
          "truncate",
          "update",
          "use",
          "vacuum",
          "waitfor",
          "sleep",
          "pg_sleep",
          "benchmark",
          "xp_cmdshell",
          "dblink");

  private ReadOnlySqlCheck() {}

  /** Returns the SQL without a trailing semicolon, or throws 422 SQL_SECURITY_ERROR. */
  static String requireSingleSelect(String sql) {
    String trimmed = sql.strip();
    while (trimmed.endsWith(";")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
    }
    Tokens tokens = tokenize(trimmed);
    if (tokens.statementSeparator || tokens.words.isEmpty()) {
      throw rejected("Chỉ cho phép đúng một câu lệnh.");
    }
    String first = tokens.words.getFirst();
    if (!first.equals("select") && !first.equals("with")) {
      throw rejected("Chỉ cho phép câu lệnh đọc dữ liệu (SELECT).");
    }
    for (String word : tokens.words) {
      if (FORBIDDEN.contains(word)) {
        throw rejected("Câu lệnh chứa thao tác không được phép.");
      }
    }
    return trimmed;
  }

  /** Lower-case identifier/keyword tokens outside quotes and comments. */
  static List<String> words(String sql) {
    return tokenize(sql).words;
  }

  private record Tokens(
      List<String> words, List<String> quotedIdentifiers, boolean statementSeparator) {}

  private static Tokens tokenize(String sql) {
    List<String> words = new ArrayList<>();
    List<String> quoted = new ArrayList<>();
    boolean separator = false;
    int i = 0;
    int n = sql.length();
    while (i < n) {
      char c = sql.charAt(i);
      if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        int end = sql.indexOf('\n', i);
        i = end < 0 ? n : end + 1;
      } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        int end = sql.indexOf("*/", i + 2);
        if (end < 0) {
          throw rejected("Chú thích SQL không được đóng.");
        }
        i = end + 2;
      } else if (c == '\'') {
        i = skipQuoted(sql, i, '\'', null);
      } else if (c == '"' || c == '`' || c == '[') {
        char close = c == '[' ? ']' : c;
        int end = skipQuoted(sql, i, close, quoted);
        i = end;
      } else if (c == ';') {
        separator = true;
        i++;
      } else if (Character.isLetter(c) || c == '_') {
        int start = i;
        while (i < n
            && (Character.isLetterOrDigit(sql.charAt(i))
                || sql.charAt(i) == '_'
                || sql.charAt(i) == '$')) {
          i++;
        }
        words.add(sql.substring(start, i).toLowerCase(Locale.ROOT));
      } else {
        i++;
      }
    }
    return new Tokens(words, quoted, separator);
  }

  /** Skips a quoted literal/identifier (doubled closing char escapes); records identifiers. */
  private static int skipQuoted(String sql, int start, char close, List<String> identifiers) {
    StringBuilder value = new StringBuilder();
    int i = start + 1;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      if (c == close) {
        if (i + 1 < sql.length() && sql.charAt(i + 1) == close) {
          value.append(c);
          i += 2;
          continue;
        }
        if (identifiers != null) {
          identifiers.add(value.toString());
        }
        return i + 1;
      }
      value.append(c);
      i++;
    }
    throw rejected("Chuỗi hoặc tên định danh trong SQL không được đóng.");
  }

  /** Identifiers used in the SQL (bare words and quoted names), lower-cased. */
  static Set<String> identifiers(String sql) {
    Tokens tokens = tokenize(sql);
    Set<String> all = new java.util.HashSet<>(tokens.words);
    tokens.quotedIdentifiers.forEach(q -> all.add(q.toLowerCase(Locale.ROOT)));
    return all;
  }

  private static ApiException rejected(String message) {
    return new ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY, "SQL_SECURITY_ERROR", message, false, Map.of());
  }
}
