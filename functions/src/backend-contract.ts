export const BACKEND_API_VERSION = 5;

export const BACKEND_CAPABILITIES = [
  "operation:delete_shopping",
  "device-registration:v2",
  "notification-privacy:v1",
  "trusted-audit:v1",
  "canonical-category:v1",
  "single-active-pantry:v1",
  "canonical-names:v1",
  "manual-shopping-merge:v1",
] as const;

export const CALLABLE_FUNCTIONS = [
  "getBackendCapabilities",
  "createPantry",
  "listMyPantries",
  "createInvitation",
  "joinPantry",
  "registerDevice",
  "unregisterDevice",
  "manageMember",
  "transferOwnership",
  "deletePantry",
  "purgeTrash",
  "applyOperation",
] as const;

export const EVENT_FUNCTIONS = [
  "notifyLowStock",
  "purgeExpiredData",
  "purgeOldActivities",
] as const;

export const BACKEND_FUNCTIONS = [...CALLABLE_FUNCTIONS, ...EVENT_FUNCTIONS] as const;

export type BackendCapabilitiesResponse = {
  backendApiVersion: number;
  capabilities: readonly string[];
  functions: readonly string[];
};

export function backendCapabilities(): BackendCapabilitiesResponse {
  return {
    backendApiVersion: BACKEND_API_VERSION,
    capabilities: BACKEND_CAPABILITIES,
    functions: BACKEND_FUNCTIONS,
  };
}

export function deployedFunctionNames(value: unknown): Set<string> {
  const names = new Set<string>();
  const visit = (node: unknown): void => {
    if (Array.isArray(node)) {
      node.forEach(visit);
      return;
    }
    if (node === null || typeof node !== "object") return;
    const record = node as Record<string, unknown>;
    for (const key of ["id", "name", "entryPoint"]) {
      const candidate = record[key];
      if (typeof candidate === "string") {
        const lastSegment = candidate.split("/").pop();
        if (lastSegment) names.add(lastSegment);
      }
    }
    Object.values(record).forEach(visit);
  };
  visit(value);
  return names;
}
