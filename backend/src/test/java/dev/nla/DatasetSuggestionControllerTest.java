package dev.nla;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DatasetSuggestionControllerTest {
  @Test
  void returnsDatasetScopedQuestionSuggestions() {
    var service = mock(DatasetSuggestionService.class);
    var controller = new DatasetSuggestionController(service);
    var parameters = Map.of("page", "1", "pageSize", "10");
    var expected = Json.obj("items", java.util.List.of());
    when(service.suggestions("dataset", parameters)).thenReturn(expected);

    assertSame(expected, controller.suggestions("dataset", parameters));
  }
}
