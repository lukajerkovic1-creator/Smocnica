import { readFileSync, readdirSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const repositoryRoot = resolve(process.cwd(), "..");
const workflowsDirectory = resolve(repositoryRoot, ".github", "workflows");

describe("GitHub Actions supply-chain controls", () => {
  it("pins every external Action to a full commit SHA", () => {
    const workflowFiles = readdirSync(workflowsDirectory).filter((name) => name.endsWith(".yml") || name.endsWith(".yaml"));
    expect(workflowFiles.length).toBeGreaterThan(0);

    for (const workflowFile of workflowFiles) {
      const workflow = readFileSync(resolve(workflowsDirectory, workflowFile), "utf8");
      const references = [...workflow.matchAll(/^\s*-?\s*uses:\s*([^\s#]+)/gm)].map((match) => match[1]!);
      for (const reference of references) {
        if (reference.startsWith("./")) continue;
        expect(reference, `${workflowFile}: ${reference}`).toMatch(
          /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+(?:\/[A-Za-z0-9_.-]+)*@[0-9a-f]{40}$/,
        );
      }
    }
  });

  it("removes decoded release secrets before notes, manifest and publishing", () => {
    const release = readFileSync(resolve(workflowsDirectory, "release.yml"), "utf8");
    const buildIndex = release.indexOf("- name: Build and verify signed APK");
    const cleanupIndex = release.indexOf("- name: Remove decoded signing and Firebase secrets");
    const notesIndex = release.indexOf("- name: Prepare release notes");
    const manifestIndex = release.indexOf("- name: Generate update manifest");
    const publishIndex = release.indexOf("- name: Publish verified release with GitHub CLI");

    expect(buildIndex).toBeGreaterThan(0);
    expect(cleanupIndex).toBeGreaterThan(buildIndex);
    expect(notesIndex).toBeGreaterThan(cleanupIndex);
    expect(manifestIndex).toBeGreaterThan(notesIndex);
    expect(publishIndex).toBeGreaterThan(manifestIndex);

    const cleanup = release.slice(cleanupIndex, notesIndex);
    expect(cleanup).toContain("if: always()");
    expect(cleanup).toContain("rm -f app/src/release/google-services.json keys/smocnica-release.jks");
    expect(cleanup).toContain("test ! -e app/src/release/google-services.json");
    expect(cleanup).toContain("test ! -e keys/smocnica-release.jks");

    const afterCleanup = release.slice(notesIndex);
    expect(afterCleanup).not.toContain("secrets.");
    expect(afterCleanup).not.toContain("softprops/action-gh-release");
    expect(afterCleanup).toContain("gh release create");
  });

  it("gates the release job with the protected production environment", () => {
    const release = readFileSync(resolve(workflowsDirectory, "release.yml"), "utf8");
    expect(release).toMatch(/jobs:\s+[\s\S]*release:\s+[\s\S]*environment:\s*production/);
    expect(release).toMatch(/permissions:\s+contents:\s*write/);
  });
});
