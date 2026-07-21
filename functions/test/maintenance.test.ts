import { describe, expect, it } from "vitest";
import { Timestamp } from "firebase-admin/firestore";
import { groupNotificationTokens, lowStockNotificationContent, notificationRetentionFields } from "../src/maintenance";

describe("low-stock notification privacy", () => {
  const notification = { name: "Mlijeko", remaining: 1, required: 2 };

  it("uses private content without product data by default", () => {
    const content = lowStockNotificationContent(notification, false);
    expect(content).toEqual({
      title: "Smočnica",
      body: "Jedan artikl je ispod minimalne zalihe.",
    });
    expect(JSON.stringify(content)).not.toContain("Mlijeko");
    expect(JSON.stringify(content)).not.toContain("1");
  });

  it("uses product name and quantities only for detailed notifications", () => {
    expect(lowStockNotificationContent(notification, true)).toEqual({
      title: "Artikl je ispod minimuma",
      body: "Mlijeko: preostalo 1 kom, na popis dodano 2 kom.",
    });
  });

  it("treats missing preference as private and privacy wins for duplicate tokens", () => {
    expect(groupNotificationTokens([
      { fcmToken: "private-default" },
      { fcmToken: "detailed", detailedNotifications: true },
      { fcmToken: "duplicate", detailedNotifications: true },
      { fcmToken: "duplicate", detailedNotifications: false },
      { fcmToken: null, detailedNotifications: true },
    ])).toEqual({
      privateTokens: ["private-default", "duplicate"],
      detailedTokens: ["detailed"],
    });
  });

  it("expires a processed notification after 30 days", () => {
    const processedAt = Timestamp.fromMillis(1_700_000_000_000);
    const fields = notificationRetentionFields(3, processedAt);

    expect(fields.processedAt).toEqual(processedAt);
    expect(fields.recipientCount).toBe(3);
    expect(fields.expiresAt.toMillis() - processedAt.toMillis()).toBe(30 * 86_400_000);
  });
});
