package com.fsa.textsql.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsa.textsql.common.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReadOnlySqlCheckTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT COUNT(*) AS track_count FROM public.track",
        "select * from \"public\".\"album\" order by \"album_id\" limit 5;",
        "WITH t AS (SELECT 1 AS x) SELECT x FROM t",
        "SELECT TOP (5) [Name] FROM [dbo].[Track] ORDER BY [TrackId]",
        "SELECT `Name` FROM `nla_chinook`.`Track` LIMIT 5",
        // Forbidden words inside string literals or quoted identifiers are data, not commands.
        "SELECT name FROM track WHERE name = 'Drop the bass; delete everything'",
        "SELECT \"update\" FROM t",
        "SELECT REPLACE(name, 'a', 'b') AS n FROM track"
      })
  void allowsSingleReadOnlyStatements(String sql) {
    assertThat(ReadOnlySqlCheck.requireSingleSelect(sql)).doesNotEndWith(";");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DELETE FROM track",
        "UPDATE track SET name = 'x'",
        "DROP TABLE track",
        "INSERT INTO track VALUES (1)",
        "SELECT * INTO copy_of_track FROM track",
        "SELECT 1; DROP TABLE track",
        "SELECT 1; SELECT 2",
        "/* hidden */ DELETE FROM track",
        "-- comment\nTRUNCATE track",
        "SELECT pg_sleep(30)",
        "WITH x AS (DELETE FROM track RETURNING *) SELECT * FROM x",
        "EXEC xp_cmdshell 'dir'",
        "SELECT name FROM track WHERE name = 'unterminated",
        "SELECT 1 /* unterminated comment",
        ""
      })
  void rejectsWritesMultipleStatementsAndTricks(String sql) {
    assertThatThrownBy(() -> ReadOnlySqlCheck.requireSingleSelect(sql))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("SQL_SECURITY_ERROR");
  }

  /** Every sample question shipped for the three engines must pass the check. */
  @ParameterizedTest
  @ValueSource(strings = {"postgresql", "mysql", "mssql"})
  void allChinookExamplesPass(String engine) throws Exception {
    Path file = Path.of("../data/chinook/semantic-" + engine + ".json");
    JsonNode examples = new ObjectMapper().readTree(Files.readString(file)).get("examples");
    assertThat(examples.size()).isGreaterThan(0);
    for (JsonNode example : examples) {
      String sql = example.get("sql").asText();
      assertThat(ReadOnlySqlCheck.requireSingleSelect(sql)).as(sql).isNotBlank();
    }
  }
}
