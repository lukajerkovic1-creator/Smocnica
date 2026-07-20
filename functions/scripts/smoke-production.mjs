import fs from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const {
  BACKEND_API_VERSION,
  BACKEND_CAPABILITIES,
  BACKEND_FUNCTIONS,
  CALLABLE_FUNCTIONS,
  deployedFunctionNames,
} = require("../lib/backend-contract.js");

const argument = (name) => {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : undefined;
};
const projectId = argument("--project");
const functionsListPath = argument("--functions-list");
if (!projectId || !functionsListPath) {
  throw new Error("Upotreba: node scripts/smoke-production.mjs --project PROJECT_ID --functions-list functions-list.json");
}

const deployed = deployedFunctionNames(JSON.parse(fs.readFileSync(functionsListPath, "utf8")));
const normalizedDeployed = new Set([...deployed].map((name) => name.toLowerCase()));
const missingFunctions = BACKEND_FUNCTIONS.filter((name) => !normalizedDeployed.has(name.toLowerCase()));
if (missingFunctions.length > 0) {
  throw new Error(`Nisu objavljene funkcije: ${missingFunctions.join(", ")}`);
}

const endpoint = (name) => `https://europe-west1-${projectId}.cloudfunctions.net/${name}`;
const invoke = async (name) => {
  const response = await fetch(endpoint(name), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ data: {} }),
    signal: AbortSignal.timeout(30_000),
  });
  const body = await response.text();
  return { name, status: response.status, body };
};

const capabilitySmoke = await invoke("getBackendCapabilities");
if (capabilitySmoke.status !== 200) {
  throw new Error(`getBackendCapabilities nije dostupan: HTTP ${capabilitySmoke.status} ${capabilitySmoke.body}`);
}
const capabilityEnvelope = JSON.parse(capabilitySmoke.body);
const capabilityResult = capabilityEnvelope.result ?? capabilityEnvelope.data;
if (capabilityResult?.backendApiVersion !== BACKEND_API_VERSION) {
  throw new Error(`Objavljen je backend API ${capabilityResult?.backendApiVersion}; očekivan je ${BACKEND_API_VERSION}.`);
}
const advertisedCapabilities = new Set(capabilityResult.capabilities ?? []);
const missingCapabilities = BACKEND_CAPABILITIES.filter((value) => !advertisedCapabilities.has(value));
if (missingCapabilities.length > 0) {
  throw new Error(`Capability odgovor ne sadrži: ${missingCapabilities.join(", ")}`);
}

const securedSmokes = await Promise.all(
  CALLABLE_FUNCTIONS.filter((name) => name !== "getBackendCapabilities").map(invoke),
);
const unsafe = securedSmokes.filter(({ status }) => status !== 401 && status !== 403);
if (unsafe.length > 0) {
  throw new Error(
    `Zaštićene callable funkcije nisu odbile neautorizirani zahtjev: ${unsafe.map(({ name, status }) => `${name}=${status}`).join(", ")}`,
  );
}

const report = {
  projectId,
  checkedAt: new Date().toISOString(),
  backendApiVersion: capabilityResult.backendApiVersion,
  deployedFunctions: BACKEND_FUNCTIONS,
  callableSmokes: [
    { name: capabilitySmoke.name, status: capabilitySmoke.status, result: "capabilities verified" },
    ...securedSmokes.map(({ name, status }) => ({ name, status, result: "unauthorized request rejected" })),
  ],
};
fs.writeFileSync("production-smoke-report.json", `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify(report, null, 2));
