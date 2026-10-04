import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

test('presents the promised product and usable download route', async ({ page }) => {
  await page.goto('./');
  await expect(page.getByRole('heading', { level: 1 })).toContainText(
    'Near real-time transcription. On your Android phone.',
  );
  await expect(page.getByRole('heading', { level: 1 })).toContainText('100%');
  const downloads = page.getByRole('main').getByRole('link', { name: 'Get Utterlane', exact: true });
  await expect(downloads.first()).toHaveAttribute('href', 'https://github.com/lrq3000/Utterlane/releases');
  await expect(page.locator('#setup')).toContainText('402–674 MB');
});

test('respects reduced motion and lets the reader explicitly start and pause it', async ({ page }) => {
  await page.goto('./');
  const button = page.getByRole('button', { name: 'Play animations' });
  await expect(button).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('data-motion', 'paused');
  await button.click();
  await expect(page.getByRole('button', { name: 'Pause animations' })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('html')).toHaveAttribute('data-motion', 'running');
  expect(await page.locator('.rotator-track').evaluate(el => getComputedStyle(el).animationName)).toBe('word-rotate');
  await page.getByRole('button', { name: 'Pause animations' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-motion', 'paused');
  await expect(page.locator('.static-promise')).toBeVisible();
});

test('turns speech into completed segments as the reader scrolls', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await page.goto('./');
  await expect(page.locator('html')).toHaveAttribute('data-motion', 'running');
  await page.locator('#on-device').evaluate(el => window.scrollTo(0, (el as HTMLElement).offsetTop + (el.clientHeight - innerHeight) * .94));
  await expect(page.locator('[data-story-step="2"]')).toHaveAttribute('aria-current', 'step');
  await expect(page.locator('.segment').last()).toHaveClass(/revealed/);
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect(page.locator('[data-story-step="0"]')).toHaveAttribute('aria-current', 'step');
});

test('restores the scroll illustration when motion resumes at the same position', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await page.goto('./');
  await expect(page.locator('.segment').first()).not.toHaveClass(/revealed/);
  await page.getByRole('button', { name: 'Pause animations' }).click();
  await expect(page.locator('.segment').first()).toHaveClass(/revealed/);
  await page.getByRole('button', { name: 'Play animations' }).click();
  await expect(page.locator('.segment').first()).not.toHaveClass(/revealed/);
});

test('keeps content and FAQ usable with JavaScript disabled', async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false, reducedMotion: 'reduce' });
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:4175/Utterlane/');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await page.getByText('Does it really work offline?', { exact: true }).click();
  await expect(page.locator('#faq details').first()).toHaveAttribute('open', '');
  await expect(page.locator('#faq details').first()).toContainText('import');
  await expect(page.getByRole('button', { name: /animations/ })).toBeHidden();
  await context.close();
});

test('loads assets from the Pages subpath without third-party requests or runtime errors', async ({ page }) => {
  const errors: string[] = [];
  const external: string[] = [];
  const failed: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => {
    if (!request.url().startsWith('http://127.0.0.1:4175/') && !request.url().startsWith('data:')) external.push(request.url());
  });
  page.on('response', response => { if (response.status() >= 400) failed.push(response.url()); });
  await page.goto('./');
  await page.locator('footer').scrollIntoViewIfNeeded();
  await expect.poll(() => page.evaluate(() => [...document.images].every(image => image.complete && image.naturalWidth > 0))).toBe(true);
  expect(errors).toEqual([]);
  expect(external).toEqual([]);
  expect(failed).toEqual([]);
});

for (const width of [360, 390, 768, 1280, 1440]) {
  test(`fits the ${width}px viewport with readable controls`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('./');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    // Check every section, not only overflow at the hero.
    for (const id of ['on-device', 'everyday', 'privacy', 'setup', 'faq']) {
      await page.locator(`#${id}`).scrollIntoViewIfNeeded();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    }
  });
}

test('supports keyboard access and has no detected WCAG AA violations', async ({ page }) => {
  await page.goto('./');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.locator('#main')).toBeFocused();
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(results.violations).toEqual([]);
});
