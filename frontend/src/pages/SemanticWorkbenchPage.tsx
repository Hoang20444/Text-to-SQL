import { useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import {
  AlertTriangle,
  ArrowRight,
  BookOpen,
  Braces,
  Check,
  CircleHelp,
  Database,
  GitBranch,
  Pencil,
  Play,
  Plus,
  Save,
  Sparkles,
  Trash2,
  TrendingUp,
} from "lucide-react";
import { useAuth } from "../app/auth";
import { useLocale, useWarehouse } from "../app/providers";
import {
  DimensionSchema,
  MetricSchema,
  RelationshipSchema,
  type Dimension,
  type Metric,
  type Relationship,
} from "../api/schema";
import { ApiError } from "../api/client";
import {
  type SemanticAssist,
  type SemanticDocument,
  semanticApi,
} from "../api/semanticApi";
import {
  applySemanticDraft,
  nextSemanticVersion,
  relationshipKey,
} from "../lib/semanticWorkbench";
import { SemanticDocumentSchema } from "../api/workbenchSchema";
import { PageHeading } from "../components/Shell";
import {
  ApiAccess,
  Badge,
  Button,
  Card,
  Empty,
  ErrorState,
  Field,
  Modal,
  Notice,
  Resource,
  Stat,
  activateTabByKeyboard,
} from "../components/ui";

type Editor =
  | { kind: "metric"; index: number | null; value: Metric }
  | { kind: "dimension"; index: number | null; value: Dimension }
  | { kind: "relationship"; value: Relationship };

type Section =
  "metrics" | "dimensions" | "relationships" | "rules" | "examples";

const sectionLabels: Record<Section, string> = {
  metrics: "Chỉ số",
  dimensions: "Chiều phân tích",
  relationships: "Mối quan hệ",
  rules: "Quy tắc nghiệp vụ",
  examples: "Ví dụ câu hỏi",
};

function labelTable(table: { schema: string; name: string }) {
  return `${table.schema}.${table.name}`;
}

function labelRelationship(relationship: Relationship) {
  return `${labelTable(relationship.from.table)}.${relationship.from.column} → ${labelTable(relationship.to.table)}.${relationship.to.column}`;
}

function asList(value: string) {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function pathFor(error: unknown) {
  return error instanceof ApiError && typeof error.details.path === "string"
    ? error.details.path
    : null;
}

export function SemanticWorkbenchPage() {
  const context = useWarehouse();
  const auth = useAuth();
  const { t } = useLocale();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [section, setSection] = useState<Section>("metrics");
  const [draft, setDraft] = useState<SemanticDocument | null>(null);
  const draftRef = useRef<SemanticDocument | null>(null);
  const loadedDataset = useRef("");
  const loadedDocument = useRef<SemanticDocument | null>(null);
  draftRef.current = draft;
  const [editor, setEditor] = useState<Editor | null>(null);
  const [editorError, setEditorError] = useState("");
  const [assistOpen, setAssistOpen] = useState(false);
  const [assistApplyError, setAssistApplyError] = useState("");
  const [assistInstruction, setAssistInstruction] = useState("");
  const [assistDraft, setAssistDraft] = useState<SemanticAssist | null>(null);
  const [assistSelected, setAssistSelected] = useState<Set<string>>(
    () => new Set(),
  );
  const [selectedMetrics, setSelectedMetrics] = useState<string[]>([]);
  const [selectedDimensions, setSelectedDimensions] = useState<string[]>([]);
  const [validatedFingerprint, setValidatedFingerprint] = useState("");
  const [validationResult, setValidationResult] = useState<{
    diagnostics: string[];
    semanticVersion: string;
    metricCount: number;
    dimensionCount: number;
    relationshipCount?: number;
  } | null>(null);

  const model = useQuery({
    queryKey: ["semantic-model", context.datasetId],
    queryFn: ({ signal }) => semanticApi.get(context.datasetId, signal),
    enabled: context.enabled && Boolean(context.datasetId),
  });

  const currentFingerprint = draft ? JSON.stringify(draft) : "";
  const dirty = Boolean(
    draft && model.data && currentFingerprint !== JSON.stringify(model.data),
  );
  const remoteChanged = Boolean(
    dirty &&
    loadedDataset.current === context.datasetId &&
    loadedDocument.current &&
    model.data &&
    JSON.stringify(model.data) !== JSON.stringify(loadedDocument.current),
  );
  const validatedCurrent =
    Boolean(validationResult) && validatedFingerprint === currentFingerprint;

  const validate = useMutation({
    mutationFn: ({
      datasetId,
      document,
    }: {
      datasetId: string;
      document: SemanticDocument;
      fingerprint: string;
    }) => semanticApi.validate(datasetId, document),
    onSuccess: (result, variables) => {
      if (context.datasetId !== variables.datasetId) return;
      setValidationResult(result);
      setValidatedFingerprint(variables.fingerprint);
    },
  });

  const save = useMutation({
    mutationFn: ({
      datasetId,
      document,
      baseVersion,
    }: {
      datasetId: string;
      document: SemanticDocument;
      fingerprint: string;
      baseVersion: string;
    }) => {
      const semanticVersion =
        document.semanticVersion === baseVersion
          ? nextSemanticVersion(document.semanticVersion)
          : document.semanticVersion;
      return semanticApi.save(datasetId, { ...document, semanticVersion });
    },
    onSuccess: (saved, variables) => {
      queryClient.setQueryData(["semantic-model", variables.datasetId], saved);
      void queryClient.invalidateQueries({
        queryKey: ["catalog", variables.datasetId],
      });
      if (context.datasetId !== variables.datasetId) return;
      loadedDataset.current = variables.datasetId;
      loadedDocument.current = structuredClone(saved);
      if (JSON.stringify(draftRef.current) === variables.fingerprint) {
        setDraft(structuredClone(saved));
        setValidationResult(null);
        setValidatedFingerprint("");
      }
    },
  });

  const assist = useMutation({
    mutationFn: ({
      datasetId,
      instruction,
    }: {
      datasetId: string;
      instruction: string;
    }) =>
      semanticApi.assist(datasetId, {
        ...(instruction.trim() ? { instruction: instruction.trim() } : {}),
      }),
    onSuccess: (result) => {
      if (context.datasetId !== result.datasetId) return;
      setAssistDraft(result);
      setAssistSelected(
        new Set([
          ...result.metrics.flatMap((item, index) =>
            ["NEW", "CHANGED"].includes(item.status) ? [`metric:${index}`] : [],
          ),
          ...result.dimensions.flatMap((item, index) =>
            ["NEW", "CHANGED"].includes(item.status)
              ? [`dimension:${index}`]
              : [],
          ),
          ...result.businessRules.flatMap((item, index) =>
            item.status === "NEW" ? [`rule:${index}`] : [],
          ),
        ]),
      );
    },
  });

  const run = useMutation({
    mutationFn: ({
      datasetId,
      metrics,
      dimensions,
    }: {
      datasetId: string;
      metrics: string[];
      dimensions: string[];
    }) =>
      semanticApi.run(datasetId, {
        metrics,
        dimensions,
        filters: [],
        limit: 100,
      }),
    onSuccess: (query, variables) => {
      if (context.datasetId === variables.datasetId) {
        navigate(`/query/${encodeURIComponent(query.queryId)}/overview`);
      }
    },
  });
  const currentValidateError =
    validate.variables?.fingerprint === currentFingerprint
      ? validate.error
      : null;
  const currentSaveError =
    save.variables?.fingerprint === currentFingerprint ? save.error : null;
  const currentRunError =
    run.variables?.datasetId === context.datasetId ? run.error : null;

  useEffect(() => {
    if (!model.data) return;
    const datasetChanged = loadedDataset.current !== context.datasetId;
    const hasUnsavedEdits = Boolean(
      draftRef.current &&
      loadedDocument.current &&
      JSON.stringify(draftRef.current) !==
        JSON.stringify(loadedDocument.current),
    );
    if (!datasetChanged && hasUnsavedEdits) return;

    loadedDataset.current = context.datasetId;
    loadedDocument.current = structuredClone(model.data);
    setDraft(structuredClone(model.data));
    setSelectedMetrics([]);
    setSelectedDimensions([]);
    setValidationResult(null);
    setValidatedFingerprint("");
    if (!validate.isPending) validate.reset();
    if (!save.isPending) save.reset();
    if (!assist.isPending) assist.reset();
    if (!run.isPending) run.reset();
  }, [context.datasetId, model.data]);

  const tables = draft?.tables ?? [];
  const discoveredRelationships = useMemo(() => {
    const keys = new Set<string>();
    tables.forEach((table) =>
      table.foreignKeys.forEach((relationship) =>
        keys.add(relationshipKey(relationship)),
      ),
    );
    return keys;
  }, [tables]);

  const compatibleDimensions = useMemo(() => {
    if (!draft || selectedMetrics.length === 0) return new Set<string>();
    const selected = selectedMetrics
      .map((name) => draft.metrics.find((metric) => metric.name === name))
      .filter((metric): metric is Metric => Boolean(metric));
    if (!selected.length) return new Set<string>();
    return new Set(
      selected.reduce(
        (allowed, metric) =>
          allowed.filter((name) => metric.compatibleDimensions.includes(name)),
        selected[0].compatibleDimensions,
      ),
    );
  }, [draft, selectedMetrics]);

  const updateDraft = (
    updater: (current: SemanticDocument) => SemanticDocument,
  ) => {
    if (!draft) return;
    const next = updater(structuredClone(draft));
    const metricNames = new Set(next.metrics.map((metric) => metric.name));
    const dimensionNames = new Set(
      next.dimensions.map((dimension) => dimension.name),
    );
    setDraft(next);
    setSelectedMetrics((selected) =>
      selected.filter((name) => metricNames.has(name)),
    );
    setSelectedDimensions((selected) =>
      selected.filter((name) => dimensionNames.has(name)),
    );
    if (!validate.isPending) validate.reset();
    if (!save.isPending) save.reset();
    setValidationResult(null);
    setValidatedFingerprint("");
    setAssistApplyError("");
  };

  const toggleSelected = (kind: "metric" | "dimension", name: string) => {
    if (kind === "metric") {
      setSelectedMetrics((current) =>
        current.includes(name)
          ? current.filter((item) => item !== name)
          : current.length < 10
            ? [...current, name]
            : current,
      );
      setSelectedDimensions([]);
      return;
    }
    setSelectedDimensions((current) =>
      current.includes(name)
        ? current.filter((item) => item !== name)
        : current.length < 5
          ? [...current, name]
          : current,
    );
  };

  const startMetricEditor = (index: number | null) => {
    const value =
      index === null
        ? {
            name: "",
            description: "",
            expression: "",
            unit: "number",
            tableRefs: tables[0]
              ? [{ schema: tables[0].schema, name: tables[0].name }]
              : [],
            compatibleDimensions: [],
            synonyms: [],
          }
        : draft!.metrics[index];
    setEditor({ kind: "metric", index, value: structuredClone(value) });
    setEditorError("");
  };

  const startDimensionEditor = (index: number | null) => {
    const firstTable = tables[0];
    const firstColumn = firstTable?.columns[0];
    const value =
      index === null
        ? {
            name: "",
            description: "",
            table: firstTable
              ? { schema: firstTable.schema, name: firstTable.name }
              : { schema: "", name: "" },
            column: firstColumn?.name ?? "",
            dataType: firstColumn?.dataType ?? "STRING",
            synonyms: [],
          }
        : draft!.dimensions[index];
    setEditor({ kind: "dimension", index, value: structuredClone(value) });
    setEditorError("");
  };

  const startRelationshipEditor = () => {
    const from = tables[0];
    const to = tables.find((table) => table !== from);
    if (!from || !to) return;
    setEditor({
      kind: "relationship",
      value: {
        from: {
          table: { schema: from.schema, name: from.name },
          column: from.columns[0]?.name ?? "",
        },
        to: {
          table: { schema: to.schema, name: to.name },
          column: to.columns[0]?.name ?? "",
        },
        cardinality: "MANY_TO_ONE",
      },
    });
    setEditorError("");
  };

  const saveEditor = () => {
    if (!editor) return;
    if (editor.kind === "metric") {
      const result = MetricSchema.safeParse(editor.value);
      if (!result.success) {
        setEditorError(
          result.error.issues[0]?.message ??
            t("Kiểm tra lại thông tin chỉ số."),
        );
        return;
      }
      updateDraft((current) => {
        const metrics = [...current.metrics];
        if (editor.index === null) metrics.push(result.data);
        else metrics[editor.index] = result.data;
        return { ...current, metrics };
      });
    } else if (editor.kind === "dimension") {
      const result = DimensionSchema.safeParse(editor.value);
      if (!result.success) {
        setEditorError(
          result.error.issues[0]?.message ??
            t("Kiểm tra lại thông tin chiều phân tích."),
        );
        return;
      }
      updateDraft((current) => {
        const dimensions = [...current.dimensions];
        if (editor.index === null) dimensions.push(result.data);
        else dimensions[editor.index] = result.data;
        return { ...current, dimensions };
      });
    } else {
      const result = RelationshipSchema.safeParse(editor.value);
      if (!result.success) {
        setEditorError(
          result.error.issues[0]?.message ??
            t("Kiểm tra lại hai đầu mối quan hệ."),
        );
        return;
      }
      if (discoveredRelationships.has(relationshipKey(result.data))) {
        setEditorError(
          t("Mối quan hệ này đã được phát hiện từ khóa ngoại của warehouse."),
        );
        return;
      }
      updateDraft((current) => ({
        ...current,
        relationships: [...current.relationships, result.data],
      }));
    }
    setEditor(null);
  };

  const applyAssist = () => {
    if (!draft || !assistDraft) return;
    const candidate = applySemanticDraft(draft, assistDraft, assistSelected);
    const result = SemanticDocumentSchema.safeParse(candidate);
    if (!result.success) {
      const issue = result.error.issues[0];
      const path = issue?.path.join(".");
      setAssistApplyError(
        path
          ? `${t("Gợi ý chưa hợp lệ tại trường")}: ${path}`
          : t("Một số gợi ý không hợp lệ. Hãy sửa chúng trước khi áp dụng."),
      );
      return;
    }
    updateDraft(() => result.data);
    setAssistOpen(false);
    setAssistDraft(null);
  };

  const toggleAssistItem = (key: string, enabled: boolean) => {
    setAssistSelected((current) => {
      const next = new Set(current);
      if (enabled) next.add(key);
      else next.delete(key);
      return next;
    });
  };

  const updateAssistMetric = (index: number, patch: Partial<Metric>) => {
    setAssistDraft((current) => {
      if (!current) return current;
      const metrics = [...current.metrics];
      const item = metrics[index];
      metrics[index] = {
        ...item,
        definition: { ...item.definition, ...patch },
      };
      return { ...current, metrics };
    });
    setAssistApplyError("");
  };

  const updateAssistDimension = (index: number, patch: Partial<Dimension>) => {
    setAssistDraft((current) => {
      if (!current) return current;
      const dimensions = [...current.dimensions];
      const item = dimensions[index];
      dimensions[index] = {
        ...item,
        definition: { ...item.definition, ...patch },
      };
      return { ...current, dimensions };
    });
    setAssistApplyError("");
  };

  if (!auth.can("ADMIN")) {
    return (
      <ApiAccess>
        <PageHeading
          title={t("Ngữ nghĩa nghiệp vụ")}
          description={t(
            "Quản lý định nghĩa chỉ số, chiều phân tích và quan hệ an toàn cho dataset.",
          )}
        />
        <Notice tone="warning">
          {t("Chỉ quản trị viên mới có thể chỉnh sửa mô hình ngữ nghĩa.")}
        </Notice>
      </ApiAccess>
    );
  }

  return (
    <ApiAccess>
      <PageHeading
        title={t("Ngữ nghĩa nghiệp vụ")}
        description={t(
          "Quản lý định nghĩa chỉ số, chiều phân tích và quan hệ an toàn cho dataset.",
        )}
      />
      {!context.datasetId ? (
        <Card>
          <Empty
            title={t("Chưa chọn dataset")}
            description={t(
              "Chọn một dataset đang hoạt động trên thanh đầu trang để tải mô hình thật.",
            )}
          />
        </Card>
      ) : (
        <Resource resource={model}>
          {(activeModel) => {
            const value = draft ?? activeModel;
            const hasSelection = selectedMetrics.length > 0;
            return (
              <div className="semantic-workbench">
                <div className="semantic-workbench__header">
                  <div>
                    <div className="semantic-workbench__identity">
                      <Database size={20} />
                      <strong>
                        {context.dataset?.name ?? context.datasetId}
                      </strong>
                      <Badge status={context.dataset?.status ?? "UNKNOWN"} />
                    </div>
                    <p>
                      {t("Schema version")}: <code>{value.schemaVersion}</code>
                      <span aria-hidden="true"> · </span>
                      {t("Semantic version")}:{" "}
                      <code>{value.semanticVersion}</code>
                    </p>
                  </div>
                  <div className="inline-actions">
                    <Button
                      onClick={() => {
                        setAssistDraft(null);
                        setAssistApplyError("");
                        setAssistInstruction("");
                        setAssistOpen(true);
                      }}
                    >
                      <Sparkles size={17} />
                      {t("Gợi ý bằng AI")}
                    </Button>
                    <Button
                      onClick={() =>
                        draft &&
                        validate.mutate({
                          datasetId: context.datasetId,
                          document: draft,
                          fingerprint: currentFingerprint,
                        })
                      }
                      disabled={!dirty || remoteChanged || validate.isPending}
                    >
                      <Check size={17} />
                      {validate.isPending
                        ? t("Đang kiểm tra…")
                        : t("Kiểm tra mô hình")}
                    </Button>
                    <Button
                      variant="primary"
                      onClick={() =>
                        draft &&
                        model.data &&
                        save.mutate({
                          datasetId: context.datasetId,
                          document: draft,
                          fingerprint: currentFingerprint,
                          baseVersion: model.data.semanticVersion,
                        })
                      }
                      disabled={!dirty || remoteChanged || save.isPending}
                    >
                      <Save size={17} />
                      {save.isPending ? t("Đang lưu…") : t("Lưu phiên bản mới")}
                    </Button>
                  </div>
                </div>

                {(model.error ||
                  currentValidateError ||
                  currentSaveError ||
                  currentRunError) && (
                  <ErrorState
                    error={
                      model.error ||
                      currentValidateError ||
                      currentSaveError ||
                      currentRunError
                    }
                  />
                )}
                {(currentValidateError || currentSaveError) &&
                  pathFor(currentValidateError || currentSaveError) && (
                    <Notice tone="error">
                      <strong>{t("Vị trí cần sửa")}: </strong>
                      <code>
                        {pathFor(currentValidateError || currentSaveError)}
                      </code>
                      <p>
                        {t(
                          "Hãy kiểm tra cột, công thức hoặc cardinality được API chỉ ra rồi xác thực lại.",
                        )}
                      </p>
                    </Notice>
                  )}
                {save.isSuccess && !dirty && (
                  <Notice tone="success">
                    {t("Đã lưu mô hình ngữ nghĩa phiên bản")}{" "}
                    <code>{save.data.semanticVersion}</code>.
                  </Notice>
                )}
                {remoteChanged && model.data && (
                  <Notice tone="warning">
                    <strong>{t("Mô hình đã được cập nhật ở nơi khác.")}</strong>
                    <p>
                      {t(
                        "Bản nháp hiện tại vẫn được giữ. Tải bản mới nhất trước khi xác thực hoặc lưu để tránh ghi đè thay đổi khác.",
                      )}
                    </p>
                    <Button
                      onClick={() => {
                        loadedDocument.current = structuredClone(model.data!);
                        setDraft(structuredClone(model.data!));
                        setSelectedMetrics([]);
                        setSelectedDimensions([]);
                        setValidationResult(null);
                        setValidatedFingerprint("");
                        validate.reset();
                        save.reset();
                      }}
                    >
                      {t("Tải bản mới nhất và bỏ bản nháp")}
                    </Button>
                  </Notice>
                )}
                {validatedCurrent && validationResult && (
                  <Notice tone="success">
                    <strong>{t("Mô hình hợp lệ")}</strong>
                    <p>
                      {validationResult.metricCount} {t("chỉ số")},{" "}
                      {validationResult.dimensionCount} {t("chiều phân tích")},{" "}
                      {validationResult.relationshipCount ??
                        value.relationships.length}{" "}
                      {t("mối quan hệ")}.
                    </p>
                    {validationResult.diagnostics.length > 0 && (
                      <ul>
                        {validationResult.diagnostics.map(
                          (diagnostic, index) => (
                            <li key={`${index}-${diagnostic}`}>{diagnostic}</li>
                          ),
                        )}
                      </ul>
                    )}
                  </Notice>
                )}
                {validate.isPending && (
                  <Notice>
                    {t(
                      "Đang kiểm tra metadata và khóa nối trên warehouse thật…",
                    )}
                  </Notice>
                )}

                <div className="semantic-workbench__stats">
                  <Stat
                    label={t("Bảng trong dataset")}
                    value={value.tables.length}
                    icon={<Database />}
                  />
                  <Stat
                    label={t("Chỉ số")}
                    value={value.metrics.length}
                    icon={<TrendingUp />}
                    tone="purple"
                  />
                  <Stat
                    label={t("Chiều phân tích")}
                    value={value.dimensions.length}
                    icon={<Braces />}
                    tone="green"
                  />
                  <Stat
                    label={t("Quan hệ bảng")}
                    value={value.relationships.length}
                    icon={<GitBranch />}
                  />
                </div>

                <nav
                  className="semantic-section-tabs"
                  role="tablist"
                  aria-label={t("Các phần mô hình ngữ nghĩa")}
                  onKeyDown={activateTabByKeyboard}
                >
                  {(Object.keys(sectionLabels) as Section[]).map((key) => (
                    <button
                      type="button"
                      key={key}
                      role="tab"
                      aria-selected={section === key}
                      className={section === key ? "active" : ""}
                      onClick={() => setSection(key)}
                    >
                      {t(sectionLabels[key])}
                      <span>
                        {key === "metrics"
                          ? value.metrics.length
                          : key === "dimensions"
                            ? value.dimensions.length
                            : key === "relationships"
                              ? value.relationships.length
                              : key === "rules"
                                ? value.businessRules.length
                                : value.examples.length}
                      </span>
                    </button>
                  ))}
                </nav>

                {section === "metrics" && (
                  <Card
                    title={t("Chỉ số nghiệp vụ")}
                    icon={<TrendingUp size={22} />}
                    actions={
                      <Button onClick={() => startMetricEditor(null)}>
                        <Plus size={17} />
                        {t("Thêm chỉ số")}
                      </Button>
                    }
                  >
                    {value.metrics.length === 0 ? (
                      <Empty
                        title={t("Chưa có chỉ số")}
                        description={t(
                          "Tạo một chỉ số hoặc yêu cầu AI đề xuất từ schema thật.",
                        )}
                      />
                    ) : (
                      <div className="semantic-definition-list">
                        {value.metrics.map((metric, index) => (
                          <article
                            className="semantic-definition"
                            key={`${metric.name}-${index}`}
                          >
                            <div className="semantic-definition__body">
                              <div className="semantic-definition__title">
                                <label className="semantic-select-line">
                                  <input
                                    type="checkbox"
                                    checked={selectedMetrics.includes(
                                      metric.name,
                                    )}
                                    onChange={() =>
                                      toggleSelected("metric", metric.name)
                                    }
                                    aria-label={`${t("Chạy thử chỉ số")}: ${metric.name}`}
                                  />
                                  <strong>{metric.name}</strong>
                                </label>
                                <span className="semantic-unit">
                                  {metric.unit}
                                </span>
                              </div>
                              <p>{metric.description || t("Chưa có mô tả.")}</p>
                              <code className="semantic-expression">
                                {metric.expression}
                              </code>
                              <small>
                                {t("Bảng")}:{" "}
                                {metric.tableRefs.map(labelTable).join(", ") ||
                                  "—"}
                              </small>
                              <small>
                                {t("Chiều tương thích")}:{" "}
                                {metric.compatibleDimensions.join(", ") || "—"}
                              </small>
                              {metric.synonyms.length > 0 && (
                                <small>
                                  {t("Từ đồng nghĩa")}:{" "}
                                  {metric.synonyms.join(", ")}
                                </small>
                              )}
                            </div>
                            <div className="semantic-definition__actions">
                              <Button
                                aria-label={`${t("Sửa")}: ${metric.name}`}
                                onClick={() => startMetricEditor(index)}
                              >
                                <Pencil size={16} />
                              </Button>
                              <Button
                                aria-label={`${t("Xóa")}: ${metric.name}`}
                                variant="danger"
                                onClick={() =>
                                  updateDraft((current) => ({
                                    ...current,
                                    metrics: current.metrics.filter(
                                      (_, i) => i !== index,
                                    ),
                                  }))
                                }
                              >
                                <Trash2 size={16} />
                              </Button>
                            </div>
                          </article>
                        ))}
                      </div>
                    )}
                  </Card>
                )}

                {section === "dimensions" && (
                  <Card
                    title={t("Chiều phân tích")}
                    icon={<Braces size={22} />}
                    actions={
                      <Button onClick={() => startDimensionEditor(null)}>
                        <Plus size={17} />
                        {t("Thêm chiều")}
                      </Button>
                    }
                  >
                    {value.dimensions.length === 0 ? (
                      <Empty
                        title={t("Chưa có chiều phân tích")}
                        description={t(
                          "Tạo chiều từ cột vật lý trong dataset hoặc dùng gợi ý AI để bắt đầu.",
                        )}
                      />
                    ) : (
                      <div className="semantic-definition-list">
                        {value.dimensions.map((dimension, index) => (
                          <article
                            className="semantic-definition"
                            key={`${dimension.name}-${index}`}
                          >
                            <div className="semantic-definition__body">
                              <div className="semantic-definition__title">
                                <strong>{dimension.name}</strong>
                                <span className="semantic-unit">
                                  {dimension.dataType}
                                </span>
                              </div>
                              <p>
                                {dimension.description || t("Chưa có mô tả.")}
                              </p>
                              <small>
                                {t("Cột nguồn")}:{" "}
                                <code>
                                  {labelTable(dimension.table)}.
                                  {dimension.column}
                                </code>
                              </small>
                              {dimension.synonyms.length > 0 && (
                                <small>
                                  {t("Từ đồng nghĩa")}:{" "}
                                  {dimension.synonyms.join(", ")}
                                </small>
                              )}
                            </div>
                            <div className="semantic-definition__actions">
                              <Button
                                aria-label={`${t("Sửa")}: ${dimension.name}`}
                                onClick={() => startDimensionEditor(index)}
                              >
                                <Pencil size={16} />
                              </Button>
                              <Button
                                aria-label={`${t("Xóa")}: ${dimension.name}`}
                                variant="danger"
                                onClick={() =>
                                  updateDraft((current) => ({
                                    ...current,
                                    dimensions: current.dimensions.filter(
                                      (_, i) => i !== index,
                                    ),
                                    metrics: current.metrics.map((metric) => ({
                                      ...metric,
                                      compatibleDimensions:
                                        metric.compatibleDimensions.filter(
                                          (name) => name !== dimension.name,
                                        ),
                                    })),
                                  }))
                                }
                              >
                                <Trash2 size={16} />
                              </Button>
                            </div>
                          </article>
                        ))}
                      </div>
                    )}
                  </Card>
                )}

                {section === "relationships" && (
                  <Card
                    title={t("Quan hệ giữa các bảng")}
                    icon={<GitBranch size={22} />}
                    actions={
                      <Button
                        onClick={startRelationshipEditor}
                        disabled={tables.length < 2}
                      >
                        <Plus size={17} />
                        {t("Thêm quan hệ")}
                      </Button>
                    }
                  >
                    <Notice>
                      {t(
                        "Quan hệ khóa ngoại được phát hiện từ warehouse và không thể sửa tại đây. Quan hệ khai báo phải qua kiểm tra dữ liệu thật trước khi lưu.",
                      )}
                    </Notice>
                    {value.relationships.length === 0 ? (
                      <Empty
                        title={t("Chưa có quan hệ bảng")}
                        description={t(
                          "Quan hệ vật lý sẽ xuất hiện sau khi metadata được đọc từ warehouse.",
                        )}
                      />
                    ) : (
                      <div className="table-scroll semantic-table-scroll">
                        <table>
                          <thead>
                            <tr>
                              <th>{t("Đường nối")}</th>
                              <th>{t("Cardinality")}</th>
                              <th>{t("Nguồn")}</th>
                              <th>{t("Trạng thái kiểm tra")}</th>
                              <th>{t("Thao tác")}</th>
                            </tr>
                          </thead>
                          <tbody>
                            {value.relationships.map((relationship, index) => {
                              const discovered = discoveredRelationships.has(
                                relationshipKey(relationship),
                              );
                              return (
                                <tr
                                  key={`${relationshipKey(relationship)}-${index}`}
                                >
                                  <td>
                                    <code>
                                      {labelRelationship(relationship)}
                                    </code>
                                  </td>
                                  <td>
                                    <code className="semantic-cardinality">
                                      {relationship.cardinality}
                                    </code>
                                  </td>
                                  <td>
                                    {discovered
                                      ? t("Khóa ngoại vật lý")
                                      : t("Khai báo nghiệp vụ")}
                                  </td>
                                  <td>
                                    {discovered
                                      ? t("Đọc từ schema")
                                      : t("Sẽ được xác thực khi lưu")}
                                  </td>
                                  <td>
                                    {discovered ? (
                                      <span className="muted">—</span>
                                    ) : (
                                      <Button
                                        variant="danger"
                                        aria-label={`${t("Xóa quan hệ")}: ${labelRelationship(relationship)}`}
                                        onClick={() =>
                                          updateDraft((current) => ({
                                            ...current,
                                            relationships:
                                              current.relationships.filter(
                                                (_, i) => i !== index,
                                              ),
                                          }))
                                        }
                                      >
                                        <Trash2 size={16} />
                                      </Button>
                                    )}
                                  </td>
                                </tr>
                              );
                            })}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </Card>
                )}

                {section === "rules" && (
                  <Card
                    title={t("Quy tắc nghiệp vụ")}
                    icon={<BookOpen size={22} />}
                  >
                    <Field
                      label={t("Mỗi dòng là một quy tắc")}
                      hint={t(
                        "Các quy tắc này cung cấp ngữ cảnh cho lập kế hoạch truy vấn; không thay thế quyền và kiểm tra SQL của backend.",
                      )}
                    >
                      <textarea
                        rows={9}
                        maxLength={20000}
                        value={value.businessRules.join("\n")}
                        onChange={(event) =>
                          updateDraft((current) => ({
                            ...current,
                            businessRules: event.target.value
                              .split("\n")
                              .map((rule) => rule.trim())
                              .filter(Boolean),
                          }))
                        }
                      />
                    </Field>
                    <small className="muted">
                      {value.businessRules.length}/200 {t("quy tắc")}
                    </small>
                  </Card>
                )}

                {section === "examples" && (
                  <Card
                    title={t("Ví dụ câu hỏi đã duyệt")}
                    icon={<CircleHelp size={22} />}
                  >
                    {value.examples.length === 0 ? (
                      <Empty
                        title={t("Chưa có ví dụ câu hỏi")}
                        description={t(
                          "Thêm câu hỏi và SQL đã kiểm tra vào mô hình để cung cấp ví dụ sát với dataset này.",
                        )}
                      />
                    ) : (
                      <div className="semantic-example-list">
                        {value.examples.map((example, index) => (
                          <article
                            className="semantic-example"
                            key={`${example.question}-${index}`}
                          >
                            <div>
                              <strong>{example.question}</strong>
                              <p>
                                {example.sqlDialect} ·{" "}
                                {example.selectedMetrics.join(", ") ||
                                  t("Không chọn metric")}{" "}
                                ·{" "}
                                {example.selectedDimensions.join(", ") ||
                                  t("Không chọn dimension")}
                              </p>
                            </div>
                            <code>{example.sql}</code>
                          </article>
                        ))}
                      </div>
                    )}
                  </Card>
                )}

                <Card
                  title={t("Chạy thử truy vấn theo mô hình")}
                  icon={<Play size={21} />}
                >
                  <p className="muted">
                    {t(
                      "Chọn một hoặc nhiều chỉ số. Chiều chỉ được chọn nếu tương thích với tất cả chỉ số đang chọn.",
                    )}
                  </p>
                  <div className="semantic-run-summary">
                    <span>
                      {t("Đã chọn")}{" "}
                      <strong>{selectedMetrics.length}/10</strong> {t("chỉ số")}
                    </span>
                    <span>
                      {t("Đã chọn")}{" "}
                      <strong>{selectedDimensions.length}/5</strong>{" "}
                      {t("chiều")}
                    </span>
                    <Button
                      variant="primary"
                      disabled={!hasSelection || dirty || run.isPending}
                      onClick={() =>
                        run.mutate({
                          datasetId: context.datasetId,
                          metrics: selectedMetrics,
                          dimensions: selectedDimensions,
                        })
                      }
                    >
                      <Play size={16} />
                      {run.isPending ? t("Đang chạy…") : t("Biên dịch và chạy")}
                      <ArrowRight size={16} />
                    </Button>
                  </div>
                  <small className="muted">
                    {remoteChanged
                      ? t(
                          "Bản nháp đã lệch phiên bản đang hoạt động. Hãy tải bản mới nhất trước khi tiếp tục.",
                        )
                      : dirty
                        ? t(
                            "Lưu mô hình trước khi chạy thử; semantic-query chỉ sử dụng phiên bản đang hoạt động.",
                          )
                        : t(
                            "Java biên dịch lựa chọn theo mô hình đã lưu, sau đó kiểm tra an toàn và chạy trên warehouse.",
                          )}
                  </small>
                </Card>

                {!dirty && (
                  <p className="semantic-clean-state">
                    <Check size={16} />
                    {t("Đang hiển thị mô hình đã lưu từ API.")}
                  </p>
                )}
                {dirty && (
                  <p className="semantic-dirty-state">
                    <AlertTriangle size={16} />
                    {t("Có thay đổi chưa lưu")}
                    {validatedCurrent
                      ? ` · ${t("đã kiểm tra")}`
                      : ` · ${t("chưa kiểm tra")}`}
                  </p>
                )}

                {editor && (
                  <Modal
                    title={
                      editor.kind === "metric"
                        ? editor.index === null
                          ? t("Thêm chỉ số")
                          : t("Sửa chỉ số")
                        : editor.kind === "dimension"
                          ? editor.index === null
                            ? t("Thêm chiều phân tích")
                            : t("Sửa chiều phân tích")
                          : t("Thêm quan hệ bảng")
                    }
                    onClose={() => setEditor(null)}
                  >
                    {editor.kind === "metric" && (
                      <MetricEditor
                        editor={editor}
                        tables={tables}
                        dimensions={value.dimensions}
                        onChange={(next) => setEditor(next)}
                      />
                    )}
                    {editor.kind === "dimension" && (
                      <DimensionEditor
                        editor={editor}
                        tables={tables}
                        onChange={(next) => setEditor(next)}
                      />
                    )}
                    {editor.kind === "relationship" && (
                      <RelationshipEditor
                        editor={editor}
                        tables={tables}
                        onChange={(next) => setEditor(next)}
                      />
                    )}
                    {editorError && <Notice tone="error">{editorError}</Notice>}
                    <div className="inline-actions">
                      <Button onClick={() => setEditor(null)}>
                        {t("Hủy")}
                      </Button>
                      <Button variant="primary" onClick={saveEditor}>
                        <Save size={16} />
                        {t("Áp dụng vào bản nháp")}
                      </Button>
                    </div>
                  </Modal>
                )}

                {assistOpen && (
                  <Modal
                    title={
                      assistDraft
                        ? t("Rà soát gợi ý AI")
                        : t("Gợi ý ngữ nghĩa từ AI")
                    }
                    onClose={() => setAssistOpen(false)}
                  >
                    {!assistDraft ? (
                      <div className="stack">
                        <p className="muted">
                          {t(
                            "AI chỉ nhận metadata bảng và cột của dataset này. Đề xuất được kiểm tra bằng schema thật; không tự lưu hoặc kích hoạt.",
                          )}
                        </p>
                        <Field label={t("Hướng dẫn bổ sung (tùy chọn)")}>
                          <textarea
                            rows={4}
                            maxLength={2000}
                            value={assistInstruction}
                            onChange={(event) =>
                              setAssistInstruction(event.target.value)
                            }
                            placeholder={t(
                              "Ví dụ: ưu tiên chỉ số doanh thu và chiều theo thời gian.",
                            )}
                          />
                        </Field>
                        {assist.error && <ErrorState error={assist.error} />}
                        <div className="inline-actions">
                          <Button onClick={() => setAssistOpen(false)}>
                            {t("Hủy")}
                          </Button>
                          <Button
                            variant="primary"
                            disabled={assist.isPending}
                            onClick={() =>
                              assist.mutate({
                                datasetId: context.datasetId,
                                instruction: assistInstruction,
                              })
                            }
                          >
                            <Sparkles size={17} />
                            {assist.isPending
                              ? t("Đang yêu cầu model…")
                              : t("Tạo gợi ý")}
                          </Button>
                        </div>
                      </div>
                    ) : (
                      <div className="semantic-assist-review">
                        {assistApplyError && (
                          <Notice tone="error">{assistApplyError}</Notice>
                        )}
                        <Notice>
                          {t("Bản nháp từ")}{" "}
                          <strong>{assistDraft.model}</strong> ·{" "}
                          {assistDraft.provider.name} (
                          {assistDraft.provider.model}). {t("Gốc")}:{" "}
                          {assistDraft.baseSemanticVersion};{" "}
                          {t("phiên bản đề xuất")}:{" "}
                          {assistDraft.suggestedSemanticVersion}.
                        </Notice>
                        {assistDraft.warnings.length > 0 && (
                          <Notice tone="warning">
                            <ul>
                              {assistDraft.warnings.map((warning, index) => (
                                <li key={`${index}-${warning}`}>{warning}</li>
                              ))}
                            </ul>
                          </Notice>
                        )}
                        <h3>{t("Chỉ số đề xuất")}</h3>
                        {assistDraft.metrics.map((item, index) => (
                          <AssistDefinition
                            key={`metric-${index}`}
                            status={item.status}
                            selected={assistSelected.has(`metric:${index}`)}
                            disabled={
                              item.status === "INVALID" ||
                              item.status === "UNCHANGED"
                            }
                            onSelect={(checked) =>
                              toggleAssistItem(`metric:${index}`, checked)
                            }
                          >
                            <div className="semantic-assist-edit-grid">
                              <Field label={t("Tên chỉ số")}>
                                <input
                                  value={item.definition.name}
                                  onChange={(event) =>
                                    updateAssistMetric(index, {
                                      name: event.target.value,
                                    })
                                  }
                                  maxLength={200}
                                />
                              </Field>
                              <Field label={t("Mô tả")}>
                                <input
                                  value={item.definition.description}
                                  onChange={(event) =>
                                    updateAssistMetric(index, {
                                      description: event.target.value,
                                    })
                                  }
                                  maxLength={2000}
                                />
                              </Field>
                            </div>
                            <code>{item.definition.expression}</code>
                            <small>
                              {t("Đơn vị")}: {item.definition.unit} ·{" "}
                              {t("Bảng")}:{" "}
                              {item.definition.tableRefs
                                .map(labelTable)
                                .join(", ")}
                            </small>
                            {item.current && (
                              <small>
                                {t("Định nghĩa hiện tại")}: {item.current.name}{" "}
                                — {item.current.description}
                              </small>
                            )}
                            {item.reason && (
                              <p className="text-danger">{item.reason}</p>
                            )}
                          </AssistDefinition>
                        ))}
                        <h3>{t("Chiều đề xuất")}</h3>
                        {assistDraft.dimensions.map((item, index) => (
                          <AssistDefinition
                            key={`dimension-${index}`}
                            status={item.status}
                            selected={assistSelected.has(`dimension:${index}`)}
                            disabled={
                              item.status === "INVALID" ||
                              item.status === "UNCHANGED"
                            }
                            onSelect={(checked) =>
                              toggleAssistItem(`dimension:${index}`, checked)
                            }
                          >
                            <div className="semantic-assist-edit-grid">
                              <Field label={t("Tên chiều")}>
                                <input
                                  value={item.definition.name}
                                  onChange={(event) =>
                                    updateAssistDimension(index, {
                                      name: event.target.value,
                                    })
                                  }
                                  maxLength={200}
                                />
                              </Field>
                              <Field label={t("Mô tả")}>
                                <input
                                  value={item.definition.description}
                                  onChange={(event) =>
                                    updateAssistDimension(index, {
                                      description: event.target.value,
                                    })
                                  }
                                  maxLength={2000}
                                />
                              </Field>
                            </div>
                            <small>
                              <code>
                                {labelTable(item.definition.table)}.
                                {item.definition.column}
                              </code>{" "}
                              · {item.definition.dataType}
                            </small>
                            {item.current && (
                              <small>
                                {t("Định nghĩa hiện tại")}: {item.current.name}{" "}
                                — {item.current.description}
                              </small>
                            )}
                            {item.reason && (
                              <p className="text-danger">{item.reason}</p>
                            )}
                          </AssistDefinition>
                        ))}
                        <h3>{t("Quy tắc đề xuất")}</h3>
                        {assistDraft.businessRules.map((item, index) => (
                          <label
                            className="semantic-assist-rule"
                            key={`rule-${index}`}
                          >
                            <input
                              type="checkbox"
                              checked={assistSelected.has(`rule:${index}`)}
                              disabled={item.status === "UNCHANGED"}
                              onChange={(event) =>
                                toggleAssistItem(
                                  `rule:${index}`,
                                  event.target.checked,
                                )
                              }
                            />
                            <span>{item.rule}</span>
                            <AssistStatus status={item.status} />
                          </label>
                        ))}
                        <div className="inline-actions">
                          <Button
                            onClick={() => {
                              setAssistDraft(null);
                              setAssistOpen(false);
                            }}
                          >
                            {t("Đóng")}
                          </Button>
                          <Button
                            variant="primary"
                            disabled={assistSelected.size === 0}
                            onClick={applyAssist}
                          >
                            <Check size={16} />
                            {t("Đưa lựa chọn vào bản nháp")}
                          </Button>
                        </div>
                      </div>
                    )}
                  </Modal>
                )}
                <small className="semantic-workbench__footnote">
                  {t(
                    "Bản nháp AI chỉ cập nhật bộ soạn thảo trên trang này. Lưu phiên bản mới mới thay đổi mô hình đang hoạt động.",
                  )}
                </small>
              </div>
            );
          }}
        </Resource>
      )}
    </ApiAccess>
  );
}

function AssistDefinition({
  status,
  selected,
  disabled,
  onSelect,
  children,
}: {
  status: string;
  selected: boolean;
  disabled: boolean;
  onSelect: (selected: boolean) => void;
  children: React.ReactNode;
}) {
  const { t } = useLocale();
  return (
    <article
      className={`semantic-assist-item ${status === "INVALID" ? "invalid" : ""}`}
    >
      <header>
        <label>
          <input
            type="checkbox"
            checked={selected}
            disabled={disabled}
            onChange={(event) => onSelect(event.target.checked)}
          />
          {t("Chọn áp dụng")}
        </label>
        <AssistStatus status={status} />
      </header>
      <div className="semantic-assist-item__content">{children}</div>
    </article>
  );
}

function AssistStatus({ status }: { status: string }) {
  const { t } = useLocale();
  const labels: Record<string, string> = {
    NEW: t("Mới"),
    CHANGED: t("Đề xuất thay đổi"),
    UNCHANGED: t("Không thay đổi"),
    INVALID: t("Không hợp lệ"),
  };
  return (
    <span className={`semantic-assist-status ${status.toLowerCase()}`}>
      {labels[status] ?? status}
    </span>
  );
}

function MetricEditor({
  editor,
  tables,
  dimensions,
  onChange,
}: {
  editor: Extract<Editor, { kind: "metric" }>;
  tables: SemanticDocument["tables"];
  dimensions: Dimension[];
  onChange: (editor: Editor) => void;
}) {
  const { t } = useLocale();
  const update = (patch: Partial<Metric>) =>
    onChange({ ...editor, value: { ...editor.value, ...patch } });
  return (
    <div className="semantic-editor-form">
      <Field label={t("Tên chỉ số")}>
        <input
          autoFocus
          required
          maxLength={200}
          value={editor.value.name}
          onChange={(event) => update({ name: event.target.value })}
        />
      </Field>
      <Field label={t("Mô tả")}>
        <textarea
          rows={2}
          maxLength={2000}
          value={editor.value.description}
          onChange={(event) => update({ description: event.target.value })}
        />
      </Field>
      <Field
        label={t("Biểu thức tổng hợp")}
        hint={t(
          "Biểu thức sẽ được backend parse và kiểm tra trên các bảng đã chọn.",
        )}
      >
        <input
          required
          maxLength={4000}
          value={editor.value.expression}
          onChange={(event) => update({ expression: event.target.value })}
          placeholder="SUM(order_items.total_amount)"
        />
      </Field>
      <Field label={t("Đơn vị")}>
        <input
          required
          maxLength={80}
          value={editor.value.unit}
          onChange={(event) => update({ unit: event.target.value })}
        />
      </Field>
      <Field
        label={t("Bảng nguồn")}
        hint={t("Chọn bảng chứa biểu thức metric.")}
      >
        <select
          multiple
          size={Math.min(5, Math.max(2, tables.length))}
          value={editor.value.tableRefs.map(labelTable)}
          onChange={(event) =>
            update({
              tableRefs: Array.from(
                event.currentTarget.selectedOptions,
                (option) => {
                  const [schema, ...name] = option.value.split(".");
                  return { schema, name: name.join(".") };
                },
              ),
            })
          }
        >
          {tables.map((table) => (
            <option key={table.tableId} value={labelTable(table)}>
              {labelTable(table)}
            </option>
          ))}
        </select>
      </Field>
      <Field
        label={t("Chiều tương thích")}
        hint={t(
          "Chỉ các chiều có đường nối an toàn với metric mới được đưa vào lựa chọn chạy thử.",
        )}
      >
        <select
          multiple
          size={Math.min(5, Math.max(2, dimensions.length))}
          value={editor.value.compatibleDimensions}
          onChange={(event) =>
            update({
              compatibleDimensions: Array.from(
                event.currentTarget.selectedOptions,
                (option) => option.value,
              ),
            })
          }
        >
          {dimensions.map((dimension) => (
            <option key={dimension.name} value={dimension.name}>
              {dimension.name}
            </option>
          ))}
        </select>
      </Field>
      <Field label={t("Từ đồng nghĩa")} hint={t("Phân tách bằng dấu phẩy.")}>
        <input
          value={editor.value.synonyms.join(", ")}
          onChange={(event) => update({ synonyms: asList(event.target.value) })}
        />
      </Field>
    </div>
  );
}

function DimensionEditor({
  editor,
  tables,
  onChange,
}: {
  editor: Extract<Editor, { kind: "dimension" }>;
  tables: SemanticDocument["tables"];
  onChange: (editor: Editor) => void;
}) {
  const { t } = useLocale();
  const selectedTable = tables.find(
    (table) => labelTable(table) === labelTable(editor.value.table),
  );
  const update = (patch: Partial<Dimension>) =>
    onChange({ ...editor, value: { ...editor.value, ...patch } });
  return (
    <div className="semantic-editor-form">
      <Field label={t("Tên chiều")}>
        <input
          autoFocus
          required
          maxLength={200}
          value={editor.value.name}
          onChange={(event) => update({ name: event.target.value })}
        />
      </Field>
      <Field label={t("Mô tả")}>
        <textarea
          rows={2}
          maxLength={2000}
          value={editor.value.description}
          onChange={(event) => update({ description: event.target.value })}
        />
      </Field>
      <Field label={t("Bảng nguồn")}>
        <select
          value={labelTable(editor.value.table)}
          onChange={(event) => {
            const table = tables.find(
              (candidate) => labelTable(candidate) === event.target.value,
            );
            if (!table) return;
            const column = table.columns[0];
            update({
              table: { schema: table.schema, name: table.name },
              column: column?.name ?? "",
              dataType: column?.dataType ?? "STRING",
            });
          }}
        >
          {tables.map((table) => (
            <option key={table.tableId} value={labelTable(table)}>
              {labelTable(table)}
            </option>
          ))}
        </select>
      </Field>
      <Field label={t("Cột nguồn")}>
        <select
          value={editor.value.column}
          onChange={(event) => {
            const column = selectedTable?.columns.find(
              (item) => item.name === event.target.value,
            );
            update({
              column: event.target.value,
              dataType: column?.dataType ?? editor.value.dataType,
            });
          }}
        >
          {selectedTable?.columns.map((column) => (
            <option key={column.name} value={column.name}>
              {column.name} · {column.dataType}
            </option>
          ))}
        </select>
      </Field>
      <Field label={t("Từ đồng nghĩa")} hint={t("Phân tách bằng dấu phẩy.")}>
        <input
          value={editor.value.synonyms.join(", ")}
          onChange={(event) => update({ synonyms: asList(event.target.value) })}
        />
      </Field>
    </div>
  );
}

function RelationshipEditor({
  editor,
  tables,
  onChange,
}: {
  editor: Extract<Editor, { kind: "relationship" }>;
  tables: SemanticDocument["tables"];
  onChange: (editor: Editor) => void;
}) {
  const { t } = useLocale();
  const updateSide = (
    side: "from" | "to",
    patch: Partial<Relationship["from"]>,
  ) =>
    onChange({
      ...editor,
      value: { ...editor.value, [side]: { ...editor.value[side], ...patch } },
    });
  const selectTable = (side: "from" | "to", tableKeyValue: string) => {
    const table = tables.find(
      (candidate) => labelTable(candidate) === tableKeyValue,
    );
    if (!table) return;
    updateSide(side, {
      table: { schema: table.schema, name: table.name },
      column: table.columns[0]?.name ?? "",
    });
  };
  const side = (key: "from" | "to", label: string) => {
    const endpoint = editor.value[key];
    const table = tables.find(
      (candidate) => labelTable(candidate) === labelTable(endpoint.table),
    );
    return (
      <div className="semantic-endpoint">
        <strong>{label}</strong>
        <Field label={t("Bảng")}>
          <select
            value={labelTable(endpoint.table)}
            onChange={(event) => selectTable(key, event.target.value)}
          >
            {tables.map((item) => (
              <option key={item.tableId} value={labelTable(item)}>
                {labelTable(item)}
              </option>
            ))}
          </select>
        </Field>
        <Field label={t("Cột")}>
          <select
            value={endpoint.column}
            onChange={(event) =>
              updateSide(key, { column: event.target.value })
            }
          >
            {table?.columns.map((column) => (
              <option key={column.name} value={column.name}>
                {column.name} · {column.dataType}
              </option>
            ))}
          </select>
        </Field>
      </div>
    );
  };
  return (
    <div className="semantic-editor-form">
      <div className="semantic-endpoint-grid">
        {side("from", t("Bảng bắt đầu"))}
        {side("to", t("Bảng đích"))}
      </div>
      <Field label={t("Cardinality")}>
        <select
          value={editor.value.cardinality}
          onChange={(event) =>
            onChange({
              ...editor,
              value: {
                ...editor.value,
                cardinality: event.target.value as Relationship["cardinality"],
              },
            })
          }
        >
          <option value="MANY_TO_ONE">MANY_TO_ONE</option>
          <option value="ONE_TO_ONE">ONE_TO_ONE</option>
          <option value="ONE_TO_MANY">ONE_TO_MANY</option>
          <option value="MANY_TO_MANY">MANY_TO_MANY</option>
        </select>
      </Field>
      <Notice tone="warning">
        {t(
          "Cardinality là giả định nghiệp vụ, backend sẽ kiểm tra khóa ở warehouse thật khi xác thực/lưu.",
        )}
      </Notice>
    </div>
  );
}
