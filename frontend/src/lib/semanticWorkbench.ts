import type { SemanticAssist, SemanticDocument } from "../api/semanticApi";

export type AssistSelection = ReadonlySet<string>;

export function nextSemanticVersion(version: string) {
  const match = /^(\d+)\.(\d+)\.(\d+)$/.exec(version);
  if (!match || version === "0.0.0") return "1.0.0";
  return `${match[1]}.${Number(match[2]) + 1}.0`;
}

export function tableKey(table: { schema: string; name: string }) {
  return `${table.schema}.${table.name}`.toLocaleLowerCase("en-US");
}

export function relationshipKey(relationship: {
  from: { table: { schema: string; name: string }; column: string };
  to: { table: { schema: string; name: string }; column: string };
}) {
  const endpoints = [
    `${tableKey(relationship.from.table)}.${relationship.from.column}`,
    `${tableKey(relationship.to.table)}.${relationship.to.column}`,
  ].sort();
  return endpoints.join("|");
}

function keyByName(value: { name: string }) {
  return value.name.trim().toLocaleLowerCase("en-US");
}

export function applySemanticDraft(
  current: SemanticDocument,
  proposal: SemanticAssist,
  selected: AssistSelection,
): SemanticDocument {
  const next: SemanticDocument = structuredClone(current);

  for (const [index, item] of proposal.metrics.entries()) {
    if (!selected.has(`metric:${index}`) || item.status === "INVALID") continue;
    const name = keyByName(item.current ?? item.definition);
    const existing = next.metrics.findIndex(
      (metric) => keyByName(metric) === name,
    );
    if (existing < 0) next.metrics.push(item.definition);
    else next.metrics[existing] = item.definition;
  }

  for (const [index, item] of proposal.dimensions.entries()) {
    if (!selected.has(`dimension:${index}`) || item.status === "INVALID")
      continue;
    const name = keyByName(item.current ?? item.definition);
    const existing = next.dimensions.findIndex(
      (dimension) => keyByName(dimension) === name,
    );
    if (existing < 0) next.dimensions.push(item.definition);
    else next.dimensions[existing] = item.definition;
  }

  const rules = new Set(next.businessRules);
  proposal.businessRules.forEach((item, index) => {
    if (selected.has(`rule:${index}`)) rules.add(item.rule);
  });
  next.businessRules = Array.from(rules);
  next.semanticVersion = proposal.suggestedSemanticVersion;
  return next;
}
