package dev.nla;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/datasets/{datasetId}")
public class DatasetSuggestionController {
  private final DatasetSuggestionService service;

  DatasetSuggestionController(DatasetSuggestionService service) {
    this.service = service;
  }

  @GetMapping("/question-suggestions")
  Object suggestions(@PathVariable String datasetId, @RequestParam Map<String, String> parameters) {
    return service.suggestions(datasetId, parameters);
  }
}
