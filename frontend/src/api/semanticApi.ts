import { z } from "zod";
import { encodeId, request } from "./client";
import { QuerySchema } from "./schema";
import {
  SemanticAssistInputSchema,
  SemanticAssistSchema,
  SemanticDocumentSchema,
  SemanticSelectionSchema,
  SemanticValidationSchema,
} from "./workbenchSchema";

export const semanticApi = {
  get: (datasetId: string, signal?: AbortSignal) =>
    request(
      `/datasets/${encodeId(datasetId)}/semantic-model`,
      SemanticDocumentSchema,
      { signal },
    ),

  validate: (datasetId: string, body: unknown, signal?: AbortSignal) =>
    request(
      `/datasets/${encodeId(datasetId)}/semantic-model/validate`,
      SemanticValidationSchema,
      {
        method: "POST",
        body: SemanticDocumentSchema.parse(body),
        signal,
      },
    ),

  save: (datasetId: string, body: unknown, signal?: AbortSignal) =>
    request(
      `/datasets/${encodeId(datasetId)}/semantic-model`,
      SemanticDocumentSchema,
      {
        method: "PUT",
        body: SemanticDocumentSchema.parse(body),
        signal,
      },
    ),

  assist: (datasetId: string, body: unknown, signal?: AbortSignal) =>
    request(
      `/datasets/${encodeId(datasetId)}/semantic-assist`,
      SemanticAssistSchema,
      {
        method: "POST",
        body: SemanticAssistInputSchema.parse(body),
        signal,
      },
    ),

  run: (datasetId: string, body: unknown, signal?: AbortSignal) =>
    request(`/datasets/${encodeId(datasetId)}/semantic-query`, QuerySchema, {
      method: "POST",
      body: SemanticSelectionSchema.parse(body),
      signal,
    }),
};

export type SemanticDocument = z.infer<typeof SemanticDocumentSchema>;
export type SemanticSelection = z.infer<typeof SemanticSelectionSchema>;
export type SemanticAssist = z.infer<typeof SemanticAssistSchema>;
