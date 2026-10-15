import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "e2e",
  fullyParallel: false,
  use: { baseURL: "http://localhost:4200", trace: "retain-on-failure" },
  webServer: {
    command:
      "node node_modules/@angular/cli/bin/ng.js serve --host localhost --ssl=false --port 4200 --configuration browser-test",
    url: "http://localhost:4200",
    reuseExistingServer: !process.env["CI"],
  },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
});
