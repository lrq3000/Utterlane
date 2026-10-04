import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  workers: process.env.CI ? 2 : 3,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:4175/Utterlane/',
    viewport: { width: 1440, height: 1000 },
    reducedMotion: 'reduce',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  // Exercise the built artifact under a project path, not just Vite's dev root.
  webServer: {
    command: 'npm run preview -- --host 127.0.0.1 --port 4175 --strictPort --base /Utterlane/',
    url: 'http://127.0.0.1:4175/Utterlane/',
    reuseExistingServer: !process.env.CI,
  },
});
