package dev.nla.platform;

import dev.nla.common.ApiException;
import dev.nla.common.Json;
import dev.nla.common.Requests;
import dev.nla.modelprovider.ModelProviderService;
import dev.nla.query.QueryService;
import dev.nla.store.Store;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PlatformController {
  private final Store store;
  private final QueryService queries;
  private final Environment env;
  private final ModelProviderService providers;

  public PlatformController(
      Store store, QueryService queries, Environment env, ModelProviderService providers) {
    this.store = store;
    this.queries = queries;
    this.env = env;
    this.providers = providers;
  }

  @GetMapping("/health")
  public Object health() {
    return Map.of("status", "UP");
  }

  @GetMapping("/platform/summary")
  public Object summary() {
    var warehouses = store.warehouses();
    Set<String> ids =
        warehouses.stream()
            .map(w -> Json.str(w, "warehouseId"))
            .collect(java.util.stream.Collectors.toSet());
    var history = queries.history();
    long successes = history.stream().filter(q -> "SUCCESS".equals(q.get("status"))).count();
    return Json.obj(
        "warehouseCount",
        warehouses.size(),
        "datasetCount",
        store.all("dataset").stream().filter(d -> ids.contains(d.get("warehouseId"))).count(),
        "healthyWarehouseCount",
        warehouses.stream().filter(w -> "ACTIVE".equals(w.get("status"))).count(),
        "querySuccessRate",
        history.isEmpty() ? null : (double) successes / history.size(),
        "recentQueries",
        history.stream()
            .sorted(Comparator.comparing(q -> Json.str(q, "createdAt"), Comparator.reverseOrder()))
            .limit(5)
            .toList(),
        "recentEvaluationRuns",
        store.runs("EVALUATION").stream().limit(5).toList(),
        "recentIngestionRuns",
        store.runs("INGESTION").stream().limit(5).toList(),
        "systemHealth",
        warehouses.stream().allMatch(w -> "ACTIVE".equals(w.get("status"))) ? "UP" : "PARTIAL");
  }

  @GetMapping("/monitoring/summary")
  public Object monitoring(@RequestParam Map<String, String> p) {
    String from = p.getOrDefault("from", Instant.now().minus(Duration.ofDays(1)).toString());
    String to = p.getOrDefault("to", Instant.now().toString());
    Instant start, end;
    try {
      start = Instant.parse(from);
      end = Instant.parse(to);
      if (start.isAfter(end)) throw new Exception();
    } catch (Exception e) {
      throw ApiException.bad("Khoảng thời gian không hợp lệ.");
    }
    var list =
        queries.history().stream()
            .filter(
                q -> {
                  var created = Instant.parse(Json.str(q, "createdAt"));
                  return !created.isBefore(start) && created.isBefore(end);
                })
            .filter(
                q ->
                    List.of("warehouseId", "datasetId", "engine").stream()
                        .allMatch(
                            k ->
                                !p.containsKey(k)
                                    || p.get(k).isBlank()
                                    || p.get(k).equals(q.get(k))))
            .toList();
    var latency =
        list.stream()
            .map(q -> q.get("latencyMs"))
            .filter(Number.class::isInstance)
            .map(Number.class::cast)
            .mapToLong(Number::longValue)
            .sorted()
            .toArray();
    long ok = list.stream().filter(q -> "SUCCESS".equals(q.get("status"))).count();
    long validation =
        list.stream()
            .filter(q -> Set.of("REJECTED", "VALIDATION_ERROR").contains(q.get("status")))
            .count();
    var engines = new TreeMap<String, Integer>();
    var datasets = new TreeMap<String, Integer>();
    for (var q : list) {
      engines.merge(Json.str(q, "engine"), 1, Integer::sum);
      datasets.merge(Json.str(q, "datasetId"), 1, Integer::sum);
    }
    long llmErrors =
        list.stream()
            .filter(
                q -> {
                  var stored = queries.stored(Json.str(q, "queryId"));
                  return "LLM_ERROR".equals(Json.map(stored.get("error")).get("code"));
                })
            .count();
    return Json.obj(
        "queryVolume",
        list.size(),
        "successRate",
        list.isEmpty() ? null : (double) ok / list.size(),
        "llmErrorCount",
        llmErrors,
        "sqlValidationRejectionCount",
        validation,
        "timeoutCount",
        list.stream().filter(q -> "TIMEOUT".equals(q.get("status"))).count(),
        "avgLatencyMs",
        latency.length == 0 ? null : Math.round(Arrays.stream(latency).average().orElse(0)),
        "p95LatencyMs",
        latency.length == 0 ? null : latency[(int) Math.ceil(latency.length * .95) - 1],
        "queriesByEngine",
        engines.entrySet().stream()
            .map(e -> Json.obj("engine", e.getKey(), "count", e.getValue()))
            .toList(),
        "queriesByDataset",
        datasets.entrySet().stream()
            .map(
                e ->
                    Json.obj(
                        "datasetId",
                        e.getKey(),
                        "name",
                        store.dataset(e.getKey()).get("name"),
                        "count",
                        e.getValue()))
            .toList(),
        "warehouseHealth",
        store.warehouses().stream()
            .map(
                w ->
                    Json.obj(
                        "id",
                        w.get("warehouseId"),
                        "name",
                        w.get("name"),
                        "status",
                        w.get("status")))
            .toList(),
        "ingestionHealth",
        store.runs("INGESTION").stream()
            .limit(10)
            .map(
                r ->
                    Json.obj(
                        "id", r.get("runId"), "name", r.get("sourceId"), "status", r.get("status")))
            .toList(),
        "from",
        from,
        "to",
        to,
        "denominator",
        list.size());
  }

  @GetMapping("/settings")
  public Object settings() {
    var s = store.settings();
    var profiles = providers.list();
    var selected =
        Json.objects(profiles.get("items")).stream()
            .filter(
                item -> Objects.equals(item.get("providerId"), profiles.get("defaultProviderId")))
            .findFirst()
            .orElse(null);
    s.put(
        "providerConfigured",
        selected != null
            && Boolean.TRUE.equals(selected.get("configured"))
            && Boolean.TRUE.equals(selected.get("enabled")));
    s.put("modelDisplayName", selected == null ? null : selected.get("model"));
    s.put("availableModes", List.of("AUTO", "API", "DETERMINISTIC"));
    return s;
  }

  @PatchMapping("/settings")
  @PreAuthorize("hasRole('ADMIN')")
  public Object settingsUpdate(@RequestBody Map<String, Object> b) {
    Requests.fields(b, Set.of("locale", "timezone", "defaultQueryMode", "limits"));
    Requests.required(b, "locale", "timezone", "defaultQueryMode");
    if (!Set.of("vi", "en").contains(Json.str(b, "locale"))
        || !Set.of("AUTO", "API", "DETERMINISTIC").contains(Json.str(b, "defaultQueryMode")))
      throw ApiException.bad("Cài đặt không hợp lệ.");
    try {
      ZoneId.of(Json.str(b, "timezone"));
    } catch (Exception e) {
      throw ApiException.bad("Timezone không hợp lệ.");
    }
    var limits = Json.map(b.get("limits"));
    Requests.fields(
        limits, Set.of("maxRows", "maxBytes", "statementTimeoutMs", "queryDeadlineMs"));
    for (var limit :
        Map.of(
                "maxRows",
                1000,
                "maxBytes",
                5242880,
                "statementTimeoutMs",
                15000,
                "queryDeadlineMs",
                60000)
            .entrySet())
      if (!(limits.get(limit.getKey()) instanceof Number n)
          || n.longValue() < 1
          || n.longValue() > limit.getValue())
        throw ApiException.bad("Giới hạn vượt server bounds.");
    if (((Number) limits.get("statementTimeoutMs")).intValue()
        >= ((Number) limits.get("queryDeadlineMs")).intValue())
      throw ApiException.bad("Statement timeout phải nhỏ hơn deadline.");
    var s = store.settings();
    s.putAll(b);
    store.settings(s);
    store.audit("SETTINGS_UPDATE", "platform");
    return settings();
  }
}
