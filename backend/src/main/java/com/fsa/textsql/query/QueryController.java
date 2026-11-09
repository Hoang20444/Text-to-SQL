package com.fsa.textsql.query;

import com.fsa.textsql.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/query")
public class QueryController {

  private final QueryService queries;

  public QueryController(QueryService queries) {
    this.queries = queries;
  }

  /** POST /api/v1/query: 201 with the terminal Query, or the error envelope (with queryId). */
  @PostMapping
  public ResponseEntity<QueryResponse> run(@RequestBody QueryRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(queries.run(request, CurrentUser.require()));
  }
}
