import { test as base } from '@playwright/test';

export { expect } from '@playwright/test';

export const latestReleaseApi = 'https://api.github.com/repos/lrq3000/Utterlane/releases/latest';

export const test = base.extend({
  context: async ({ context }, use) => {
    // Test the actual landing page without depending on the public API or its rate limit.
    await context.route(latestReleaseApi, route => route.fulfill({
      headers: { 'access-control-allow-origin': '*' },
      json: { tag_name: 'v90.8.7', published_at: '2025-02-03T00:04:00Z' },
    }));
    await use(context);
  },
});
