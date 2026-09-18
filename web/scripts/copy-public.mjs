import { copyFile, mkdir } from "node:fs/promises";
await mkdir(new URL("../public/", import.meta.url), { recursive: true });
for (const file of ["privacy-policy.html", "delete-account.html", "styles.css"])
  await copyFile(
    new URL(`../../public/${file}`, import.meta.url),
    new URL(`../public/${file}`, import.meta.url),
  );
