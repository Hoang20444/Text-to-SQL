import { describe, expect, it } from "vitest";
import type { SemanticAssist, SemanticDocument } from "../api/semanticApi";
import {
  applySemanticDraft,
  nextSemanticVersion,
  relationshipKey,
} from "./semanticWorkbench";

function model(): SemanticDocument {
  return {
    tables: [],
    metrics: [
      {
        name: "revenue",
        description: "Old revenue",
        expression: "SUM(amount)",
        unit: "VND",
        tableRefs: [],
        compatibleDimensions: [],
        synonyms: [],
      },
    ],
    dimensions: [],
    relationships: [],
    examples: [],
    businessRules: ["Keep returned orders only"],
    semanticVersion: "1.2.0",
    schemaVersion: "sha256:sample",
    timezone: "Asia/Ho_Chi_Minh",
    referenceTime: "2026-11-19T00:00:00Z",
  };
}

function assistantDraft(): SemanticAssist {
  const metric = {
    name: "revenue",
    description: "Approved net revenue",
    expression: "SUM(amount - discount)",
    unit: "VND",
    tableRefs: [],
    compatibleDimensions: [],
    synonyms: [],
  };
  return {
    datasetId: "dataset-1",
    model: "qwen3:4b",
    provider: {
      providerId: "local",
      revision: "1",
      name: "Ollama local",
      kind: "OLLAMA",
      model: "qwen3:4b",
      source: "APPLICATION",
    },
    baseSemanticVersion: "1.2.0",
    suggestedSemanticVersion: "1.3.0",
    metrics: [
      { status: "CHANGED", definition: metric },
      { status: "INVALID", definition: { ...metric, name: "bad_metric" } },
    ],
    dimensions: [
      {
        status: "NEW",
        definition: {
          name: "region",
          description: "Sales region",
          table: { schema: "public", name: "orders" },
          column: "region",
          dataType: "STRING",
          synonyms: [],
        },
      },
    ],
    businessRules: [
      { status: "NEW", rule: "Exclude cancelled orders" },
      { status: "UNCHANGED", rule: "Keep returned orders only" },
    ],
    warnings: [],
  };
}

describe("semantic workbench operations", () => {
  it("bumps the minor semantic version and initializes the first version", () => {
    expect(nextSemanticVersion("0.0.0")).toBe("1.0.0");
    expect(nextSemanticVersion("1.4.8")).toBe("1.5.0");
    expect(nextSemanticVersion("invalid")).toBe("1.0.0");
  });

  it("applies only selected safe AI suggestions to the local draft", () => {
    const current = model();
    const next = applySemanticDraft(
      current,
      assistantDraft(),
      new Set(["metric:0", "metric:1", "dimension:0", "rule:0"]),
    );

    expect(next.metrics).toHaveLength(1);
    expect(next.metrics[0].description).toBe("Approved net revenue");
    expect(next.dimensions.map((item) => item.name)).toEqual(["region"]);
    expect(next.businessRules).toEqual([
      "Keep returned orders only",
      "Exclude cancelled orders",
    ]);
    expect(next.semanticVersion).toBe("1.3.0");
    expect(current.metrics[0].description).toBe("Old revenue");
  });

  it("replaces the prior definition when an edited AI draft renames it", () => {
    const current = model();
    const proposal = assistantDraft();
    proposal.metrics[0] = {
      ...proposal.metrics[0],
      current: current.metrics[0],
      definition: { ...proposal.metrics[0].definition, name: "net_revenue" },
    };

    const next = applySemanticDraft(current, proposal, new Set(["metric:0"]));

    expect(next.metrics).toHaveLength(1);
    expect(next.metrics[0].name).toBe("net_revenue");
  });

  it("normalizes join identity regardless of endpoint order", () => {
    const leftToRight = {
      from: {
        table: { schema: "public", name: "orders" },
        column: "customer_id",
      },
      to: {
        table: { schema: "public", name: "customers" },
        column: "id",
      },
    };
    const rightToLeft = {
      from: leftToRight.to,
      to: leftToRight.from,
    };
    expect(relationshipKey(leftToRight)).toBe(relationshipKey(rightToLeft));
  });
});
