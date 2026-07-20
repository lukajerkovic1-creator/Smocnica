import { onCall } from "firebase-functions/v2/https";
import { backendCapabilities } from "./backend-contract";

// Namjerno je javno: odgovor sadrži samo verziju i statički popis mogućnosti.
// Klijent i post-deploy smoke test moraju ga moći provjeriti prije ostalih poziva.
export const getBackendCapabilities = onCall(
  { region: "europe-west1", enforceAppCheck: false },
  () => backendCapabilities(),
);
