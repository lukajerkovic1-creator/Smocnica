import { describe, expect, it } from "vitest";
import * as exportedFunctions from "../src/index";
import {
  BACKEND_API_VERSION,
  BACKEND_CAPABILITIES,
  BACKEND_FUNCTIONS,
  backendCapabilities,
  deployedFunctionNames,
} from "../src/backend-contract";

describe("production backend contract", () => {
  it("advertises the current API version and required capabilities", () => {
    expect(backendCapabilities()).toEqual({
      backendApiVersion: BACKEND_API_VERSION,
      capabilities: BACKEND_CAPABILITIES,
      functions: BACKEND_FUNCTIONS,
    });
    expect(BACKEND_API_VERSION).toBeGreaterThanOrEqual(4);
    expect(BACKEND_CAPABILITIES).toContain("operation:delete_shopping");
    expect(BACKEND_CAPABILITIES).toContain("device-registration:v2");
    expect(BACKEND_CAPABILITIES).toContain("notification-privacy:v1");
    expect(BACKEND_CAPABILITIES).toContain("single-active-pantry:v1");
    expect(BACKEND_CAPABILITIES).toContain("canonical-names:v1");
    expect(BACKEND_CAPABILITIES).toContain("manual-shopping-merge:v1");
    expect(BACKEND_CAPABILITIES).toContain("account-deletion:v1");
    expect(BACKEND_FUNCTIONS).toContain("deleteAccount");
  });

  it("keeps the deploy manifest equal to every exported function", () => {
    expect(Object.keys(exportedFunctions).sort()).toEqual([...BACKEND_FUNCTIONS].sort());
  });

  it("extracts deployed entry points from Firebase CLI output", () => {
    const names = deployedFunctionNames({
      status: "success",
      result: [
        { id: "createPantry", region: "europe-west1" },
        { name: "projects/demo/locations/europe-west1/functions/applyOperation" },
        { entryPoint: "notifyLowStock" },
      ],
    });
    expect(names).toEqual(new Set(["createPantry", "applyOperation", "notifyLowStock"]));
  });
});
