import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

test('remembers the manual website theme regardless of device appearance', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'light' });
  await page.goto('./');
  const moon = page.getByRole('button', { name: 'Switch to dark mode' });
  await expect(moon).toBeVisible();
  await moon.click();
  await expect(page.locator('html')).toHaveCSS('background-color', 'rgb(14, 23, 39)');
  await expect(page.locator('.theme-toggle use')).toHaveAttribute('href', '#sun');
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#0E1727');
  await page.reload();
  await expect(page.getByRole('button', { name: 'Switch to light mode' })).toBeVisible();
  await expect(page.locator('html')).toHaveCSS('background-color', 'rgb(14, 23, 39)');
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.getByRole('button', { name: 'Switch to light mode' }).click();
  await expect(page.locator('html')).toHaveCSS('background-color', 'rgb(241, 245, 251)');
  await page.reload();
  await expect(moon).toBeVisible();
  await expect(page.locator('.theme-toggle use')).toHaveAttribute('href', '#moon');
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#F1F5FB');
  await expect(page.locator('html')).toHaveCSS('background-color', 'rgb(241, 245, 251)');
});

test('starts light without a valid saved preference even on a dark device', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.goto('./');
  await expect(page.getByRole('button', { name: 'Switch to dark mode' })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
});

test('ignores an invalid saved theme', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('utterlane-theme', 'invalid'));
  await page.goto('./');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await page.getByRole('button', { name: 'Switch to dark mode' }).click();
  await page.reload();
  // The init script deliberately reinstates the invalid value on every load.
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
});

test('restores dark before the main module runs', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('utterlane-theme', 'dark'));
  // A saved theme must be carried by the HTML bootstrap and CSS, not dependent
  // on a successful or fast main-module download.
  await page.route('**/assets/*.js', route => route.abort());
  await page.goto('./');
  await expect(page.locator('html')).toHaveCSS('background-color', 'rgb(14, 23, 39)');
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#0E1727');
  await expect(page.locator('.brand .wordmark-dark').first()).toBeVisible();
  await expect(page.locator('.theme-toggle')).toBeHidden();
});

test('switches for the current visit when local storage is inaccessible', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(() => {
    Object.defineProperty(window, 'localStorage', {
      get() { throw new DOMException('Storage denied', 'SecurityError'); },
    });
  });
  await page.goto('./');
  await page.getByRole('button', { name: 'Switch to dark mode' }).click();
  await expect(page.locator('html')).toHaveCSS('background-color', 'rgb(14, 23, 39)');
  await page.getByRole('button', { name: 'Switch to light mode' }).click();
  await expect(page.locator('html')).toHaveCSS('background-color', 'rgb(241, 245, 251)');
  expect(errors).toEqual([]);
});

test('keeps a restored choice usable when saving is denied', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('utterlane-theme', 'dark');
    Storage.prototype.setItem = () => { throw new DOMException('Storage full', 'QuotaExceededError'); };
  });
  await page.goto('./');
  await page.getByRole('button', { name: 'Switch to light mode' }).click();
  await expect(page.locator('html')).toHaveCSS('background-color', 'rgb(241, 245, 251)');
});

for (const width of [320, 390, 768, 1440]) {
  test(`fits the theme and download controls at ${width}px in both themes`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('./');
    const button = page.locator('.theme-toggle');
    const download = page.getByRole('navigation', { name: 'Main navigation' }).getByRole('link', { name: 'Get Utterlane' });
    for (const theme of ['light', 'dark']) {
      await expect(button).toBeInViewport();
      await expect(download).toBeInViewport();
      const buttonBounds = await button.boundingBox();
      const downloadBounds = await download.boundingBox();
      expect(buttonBounds!.width).toBeGreaterThanOrEqual(44);
      expect(buttonBounds!.height).toBeGreaterThanOrEqual(44);
      expect(downloadBounds!.x + downloadBounds!.width).toBeLessThanOrEqual(buttonBounds!.x);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      if (theme === 'light') await button.click();
    }
  });
}

test('supports keyboard switching and accessible dark page surfaces', async ({ page }) => {
  await page.goto('./');
  const moon = page.getByRole('button', { name: 'Switch to dark mode' });
  await moon.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: 'Switch to light mode' })).toBeFocused();
  await expect(page.locator('.brand .wordmark-light').first()).toBeHidden();
  await expect(page.locator('.brand .wordmark-dark').first()).toBeVisible();
  for (const selector of ['.story', '.use-card', '.privacy', '.faq']) {
    await expect(page.locator(selector).first()).toHaveCSS('background-color', 'rgb(24, 36, 56)');
  }
  await page.getByText('Does it really work offline?', { exact: true }).click();
  await expect(page.locator('#faq details').first()).toHaveAttribute('open', '');
  await expect(page.locator('.faq-list details').first()).toHaveCSS('background-color', 'rgb(14, 23, 39)');
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(results.violations).toEqual([]);
});
