import { chromium } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

// Reproducible visual-review captures, separate from behavioral assertions.
// Reduced motion exposes the complete story without a pinned scroll scene.
const url = process.argv[2] ?? 'http://127.0.0.1:4174';
const output = resolve(process.argv[3] ?? 'test-results/visual');
await mkdir(output, { recursive: true });
const browser = await chromium.launch();
try {
  for (const [name, width, height] of [['desktop', 1440, 1000], ['mobile', 390, 844]]) {
    const page = await browser.newPage({ viewport: { width, height }, reducedMotion: 'reduce' });
    await page.goto(url);
    await page.locator('[data-enhanced]').first().waitFor();
    // Lazy artwork should be decoded before the full-page capture.
    await page.locator('footer').scrollIntoViewIfNeeded();
    await page.evaluate(async () => {
      await Promise.all([...document.images].map(image => image.decode().catch(() => {})));
      scrollTo({ top: 0, behavior: 'instant' });
    });
    await page.screenshot({ path: resolve(output, `utterlane-${name}.png`), fullPage: true });
    await page.screenshot({ path: resolve(output, `utterlane-${name}-hero.png`) });
    console.log(`${name}: ${width}×${height} screenshots saved to ${output}`);
    await page.close();
  }
} finally {
  await browser.close();
}
