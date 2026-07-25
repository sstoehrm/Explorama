const { test, expect } = require('@playwright/test');

// Playwright resolves fixtures by regex-parsing the test function signature for
// an object destructuring pattern, which ClojureScript does not emit. This file
// owns that signature so specs can be written in cljs.
globalThis.self = globalThis;

require('./out-test/specs.js');

for (const spec of global.explorama_e2e.specs) {
  test(spec.name, async ({ page }) => {
    await spec.run(page, expect);
  });
}
