package dev.nla.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Version rules for the semantic model (T-3.1.2). A semanticVersion identifies one set of business
 * definitions: changing a metric, dimension, relationship, example or business rule without a new
 * version would let two different definitions share the same identifier in query history.
 */
final class SemanticVersions {
  /** Business definitions compared between the stored and the submitted model. */
  static final List<String> DEFINITIONS =
      List.of("metrics", "dimensions", "relationships", "examples", "businessRules", "timezone");

  private static final Pattern FORMAT = Pattern.compile("\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}");

  private SemanticVersions() {}

  static boolean validFormat(Object version) {
    return version instanceof String text && FORMAT.matcher(text).matches();
  }

  /** True when the definitions differ but the submitted version equals the active one. */
  static boolean conflict(
      Object activeVersion, Map<String, Object> stored, Map<String, Object> submitted) {
    if (!Objects.equals(activeVersion, submitted.get("semanticVersion"))) return false;
    return !definitions(stored).equals(definitions(submitted));
  }

  private static Map<String, Object> definitions(Map<String, Object> model) {
    var result = new LinkedHashMap<String, Object>();
    for (String field : DEFINITIONS) {
      Object value = model.get(field);
      // A missing list and an empty list mean the same thing: no definitions of that kind.
      if (field.equals("timezone")) result.put(field, value);
      else result.put(field, value instanceof List<?> list ? list : List.of());
    }
    return result;
  }
}
