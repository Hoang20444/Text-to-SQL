package dev.nla.warehouse.dialect;

import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.Top;

public final class SqlServerDialect implements SqlDialect {
  public String name() {
    return "SQL_SERVER";
  }

  public String quoteIdentifier(String value) {
    return "[" + value.replace("]", "]]") + "]";
  }

  /**
   * Caps rows in the SQL itself while keeping ORDER BY semantics: OFFSET/FETCH after a top-level
   * ORDER BY, otherwise TOP on a plain SELECT. Queries that already page (TOP/OFFSET) or cannot
   * take either form are left unchanged; JDBC maxRows still bounds every result.
   */
  public String applySafeLimit(String sql, int rows) {
    if (rows < 1) throw new IllegalArgumentException();
    Select select;
    try {
      if (!(CCJSqlParserUtil.parse(sql, parser -> parser.withSquareBracketQuotation(true))
          instanceof Select parsed)) return sql;
      select = parsed;
    } catch (Exception unparsable) {
      return sql;
    }
    boolean top = select instanceof PlainSelect plain && plain.getTop() != null;
    boolean paged = select.getOffset() != null || select.getFetch() != null;
    if (top || paged) return sql;
    boolean ordered = select.getOrderByElements() != null && !select.getOrderByElements().isEmpty();
    if (ordered) return sql + " OFFSET 0 ROWS FETCH NEXT " + rows + " ROWS ONLY";
    if (select instanceof PlainSelect plain) {
      Top limit = new Top();
      limit.setExpression(new LongValue(rows));
      limit.setParenthesis(true);
      plain.setTop(limit);
      return plain.toString();
    }
    return sql;
  }
}
