import { initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";

initializeApp();

export const db = getFirestore();
db.settings({ ignoreUndefinedProperties: true });

export const now = (): Timestamp => Timestamp.now();
export const daysFromNow = (days: number): Timestamp => Timestamp.fromMillis(Date.now() + days * 86_400_000);

