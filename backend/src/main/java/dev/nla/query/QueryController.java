package dev.nla.query;

import dev.nla.common.Json;
import dev.nla.common.Page;
import dev.nla.store.Store;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Natural-language query API (T-1.3.2). VIEWER can read history but cannot run queries. */
@RestController
@RequestMapping("/api/v1")
public class QueryController {
  private final QueryService queries;
  private final Store store;

  public QueryController(QueryService queries, Store store) {
    this.queries = queries;
    this.store = store;
  }

  @PostMapping("/query")
  @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
  ResponseEntity<?> run(@RequestBody Map<String, Object> b) {
    return ResponseEntity.status(201).body(queries.run(b));
  }

  /** Structured metric + dimension query compiled without an LLM (T-3.1.2). */
  @PostMapping("/datasets/{datasetId}/semantic-query")
  @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
  ResponseEntity<?> semanticQuery(
      @PathVariable String datasetId, @RequestBody Map<String, Object> b) {
    return ResponseEntity.status(201).body(queries.runSemantic(datasetId, b));
  }

  @GetMapping("/query/history")
  Object history(@RequestParam Map<String, String> p) {
    return Page.of(
        queries.history(), p, "createdAt", Set.of("createdAt", "latencyMs", "status", "queryId"));
  }

  @GetMapping("/query/{id}")
  Object query(@PathVariable String id) {
    return queries.get(id);
  }

  @GetMapping("/query/{id}/result")
  Object result(@PathVariable String id, @RequestParam Map<String, String> p) {
    return queries.result(id, p);
  }

  /** The authorized result snapshot as Excel-safe CSV (T-4.3.2); never re-runs the SQL. */
  @GetMapping(value = "/query/{id}/export.csv", produces = "text/csv")
  ResponseEntity<byte[]> csv(@PathVariable String id) {
    queries.result(id, Map.of());
    byte[] body = CsvExport.write(Json.map(queries.stored(id).get("result")));
    store.audit("QUERY_CSV_EXPORT", id);
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
        .header(
            "Content-Disposition",
            "attachment; filename=\"query-" + id.replaceAll("[^a-zA-Z0-9_-]", "_") + ".csv\"")
        .header("X-Content-Type-Options", "nosniff")
        .body(body);
  }
}
