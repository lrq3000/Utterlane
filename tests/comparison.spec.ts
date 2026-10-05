import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

test('comparison and sources remain usable without JavaScript', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();
  await page.goto(baseURL!);
  const section = page.locator('#compare');
  const cards = section.locator('.comparison-card');
  await expect(cards).toHaveCount(8);
  for (const card of await cards.all()) {
    await expect(card.getByRole('term')).toHaveText([
      'Phone keyboard', 'Cloud transcription', 'Other offline transcription', 'Utterlane',
    ]);
    await expect(card.getByRole('definition')).toHaveCount(4);
  }
  await expect(section.getByRole('link', { name: 'KASROZ' })).toHaveAttribute('href', 'https://futo.tech/blog/swipe-keyboard');
  await section.getByRole('link', { name: /Sources & how/ }).click();
  await page.locator('#comparison-sources summary').click();
  await expect(page.locator('#comparison-sources')).toHaveAttribute('open', '');
  await expect(page.locator('#comparison-sources')).toContainText('five-character words');
  const ratio = await section.locator('.comparison-bar').evaluateAll(bars =>
    bars[1].getBoundingClientRect().width / bars[0].getBoundingClientRect().width);
  expect(ratio).toBeCloseTo(.226, 2);
  await context.close();
});

for (const theme of ['light', 'dark']) {
  test(`comparison stays readable and accessible in ${theme} mode`, async ({ page }) => {
    await page.goto('./');
    if (theme === 'dark') await page.getByRole('button', { name: 'Switch to dark mode' }).click();
    await page.getByRole('button', { name: 'Pause animations' }).click();
    const section = page.locator('#compare');
    await section.scrollIntoViewIfNeeded();
    await page.locator('#comparison-sources summary').click();
    for (const width of [320, 390, 768, 1440]) {
      await page.setViewportSize({ width, height: 844 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      // Long labels and user-supplied paragraphs must stay inside their cells,
      // not merely inside the overall page's overflow boundary.
      expect(await section.locator('.comparison-cell').evaluateAll(cells =>
        cells.every(cell => cell.scrollWidth <= cell.clientWidth))).toBe(true);
    }
    await expect(section.locator('.comparison-card').first()).toHaveCSS('background-color',
      theme === 'dark' ? 'rgb(24, 36, 56)' : 'rgb(255, 255, 255)');
    const results = await new AxeBuilder({ page }).include('#compare')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
    expect(results.violations).toEqual([]);
  });
}
