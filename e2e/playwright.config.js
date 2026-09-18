const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: '.',
  testMatch: 'explorama.spec.js',
  fullyParallel: true,
  workers: process.env.CI ? 2 : 4,
  retries: process.env.CI ? 1 : 0,
  // The default 30s covers every other spec, but the graph-aggregation spec
  // chains several sequential save/preview/publish/delete backend round
  // trips in one test and needs more room.
  timeout: 90000,
  reporter: [['list'], ['junit', { outputFile: 'report.xml' }]],
  use: {
    baseURL: 'http://localhost:8099',
    viewport: { width: 1600, height: 1000 },
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'bash scripts/serve-browser-dist.sh',
    url: 'http://localhost:8099',
    reuseExistingServer: !process.env.CI,
    timeout: 60000,
  },
  projects: [{ name: 'browser-bundle' }],
});
