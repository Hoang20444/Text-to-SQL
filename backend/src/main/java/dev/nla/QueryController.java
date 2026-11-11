package dev.nla;

import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Natural-language query API (T-1.3.2). VIEWER can read history but cannot run queries. */
@RestController
@RequestMapping("/api/v1")
public class QueryController {
  private final QueryService queries;

  public QueryController(QueryService queries) {
    this.queries = queries;
  }

  @PostMapping("/query")
  @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
  ResponseEntity<?> run(@RequestBody Map<String, Object> b) {
    return ResponseEntity.status(201).body(queries.run(b));
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
}
