package dev.nla.knowledge;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dataset knowledge API (T-4.1.2). Every role with access to the warehouse reads status, examples
 * and search results; only ADMIN changes examples or starts a sync.
 */
@RestController
@RequestMapping("/api/v1/datasets/{id}/knowledge")
public class KnowledgeController {
  private final KnowledgeService knowledge;

  public KnowledgeController(KnowledgeService knowledge) {
    this.knowledge = knowledge;
  }

  @GetMapping
  Map<String, Object> status(@PathVariable String id) {
    return knowledge.status(id);
  }

  @GetMapping("/examples")
  Map<String, Object> examples(@PathVariable String id) {
    return knowledge.examples(id);
  }

  @PostMapping("/examples")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Map<String, Object>> addExample(
      @PathVariable String id, @RequestBody Map<String, Object> body) {
    var added = knowledge.addExample(id, body);
    return ResponseEntity.status(added.created() ? 201 : 200).body(added.body());
  }

  @DeleteMapping("/examples/{entryId}")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Void> deleteExample(@PathVariable String id, @PathVariable String entryId) {
    knowledge.deleteExample(id, entryId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/reindex")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Map<String, Object>> reindex(@PathVariable String id) {
    return ResponseEntity.status(202).body(knowledge.reindex(id, "MANUAL"));
  }

  @PostMapping("/search")
  Map<String, Object> search(@PathVariable String id, @RequestBody Map<String, Object> body) {
    return knowledge.search(id, body);
  }
}
