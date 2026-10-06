import { expect, test, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

test('comparison and sources remain usable without JavaScript', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();
  await page.goto(baseURL!);
  const section = page.locator('#compare');
  const cards = section.locator('.comparison-card');
  await expect(cards).toHaveCount(8);
  const summary = section.locator('.comparison-disclosure > summary');
  await expect(cards.first()).toBeHidden();
  await summary.getByText('Expand the comparison', { exact: true }).click();
  await expect(summary.getByText('Hide comparison', { exact: true })).toBeVisible();
  for (const card of await cards.all()) {
    await expect(card.getByRole('term')).toHaveText([
      'Phone keyboard', 'Cloud transcription', 'Other offline transcription', 'Utterlane',
    ]);
    await expect(card.getByRole('definition')).toHaveCount(4);
  }
  await summary.getByText('Hide comparison', { exact: true }).click();
  await expect(cards.first()).toBeHidden();
  await summary.getByText('Expand the comparison', { exact: true }).click();
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
    const summary = section.locator('.comparison-disclosure > summary');
    const closedResults = await new AxeBuilder({ page }).include('.comparison-disclosure > summary')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
    expect(closedResults.violations).toEqual([]);
    await summary.click();
    await page.locator('#comparison-sources summary').click();
    for (const width of [320, 390, 768, 1440]) {
      await page.setViewportSize({ width, height: 844 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      // Long labels and user-supplied paragraphs must stay inside their cells,
      // not merely inside the overall page's overflow boundary.
      expect(await section.locator('.comparison-cell').evaluateAll(cells =>
        cells.every(cell => cell.scrollWidth <= cell.clientWidth))).toBe(true);
      // A fitting outer card must not hide overflowing invitation copy or CTA.
      expect(await summary.evaluate(element =>
        [element, ...element.querySelectorAll<HTMLElement>('span')].every(node =>
          node.clientWidth === 0 || node.scrollWidth <= node.clientWidth))).toBe(true);
    }
    await expect(section.locator('.comparison-card').first()).toHaveCSS('background-color',
      theme === 'dark' ? 'rgb(24, 36, 56)' : 'rgb(255, 255, 255)');
    const results = await new AxeBuilder({ page }).include('#compare')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
    expect(results.violations).toEqual([]);
  });
}

test('comparison cards expand and collapse with the keyboard', async ({ page }) => {
  await page.goto('./');
  const disclosure = page.locator('.comparison-disclosure');
  const summary = disclosure.locator(':scope > summary');
  await expect(summary).toContainText('What makes Utterlane different?');
  await expect(summary.getByText('Expand the comparison', { exact: true })).toBeVisible();
  await expect(summary.getByText('Hide comparison', { exact: true })).toBeHidden();
  await expect(page.locator('.comparison-card').first()).toBeHidden();
  await expect(page.locator('.comparison-pace')).toBeVisible();
  await summary.focus();
  await page.keyboard.press('Enter');
  await expect(disclosure).toHaveAttribute('open', '');
  await expect(summary.getByText('Hide comparison', { exact: true })).toBeVisible();
  await expect(summary.getByText('Expand the comparison', { exact: true })).toBeHidden();
  await expect(disclosure.getByRole('heading', { name: 'Same thought. A different experience.' })).toBeVisible();
  await expect(page.locator('.comparison-card').last()).toBeVisible();
  await page.keyboard.press('Space');
  await expect(page.locator('.comparison-card').first()).toBeHidden();
  await expect(summary.getByText('Expand the comparison', { exact: true })).toBeVisible();
  await expect(summary).toBeFocused();
});

/** Read the rendered transform, not the controller's private state. */
class PaceProbe {
  constructor(private readonly page: Page) {}

  scales() {
    return this.page.locator('.comparison-bar').evaluateAll(bars =>
      bars.map(bar => new DOMMatrix(getComputedStyle(bar).transform).a));
  }

  stripe(property: 'animationName' | 'animationPlayState') {
    return this.page.locator('.comparison-bar-speech').evaluate((bar, key) =>
      getComputedStyle(bar, '::before')[key], property);
  }
}

test('pace bars grow once on entry and retain their meaningful ratio', async ({ page }) => {
  await page.goto('./');
  const probe = new PaceProbe(page);
  const chart = page.locator('.comparison-pace');
  await expect.poll(() => probe.scales()).toEqual([0, 0]);
  await chart.scrollIntoViewIfNeeded();
  await expect(chart).toHaveAttribute('data-pace-entered', '');
  const sample = await chart.evaluate(element => {
    const bars = [...element.querySelectorAll('.comparison-bar')];
    const animations = element.getAnimations({ subtree: true })
      .filter(animation => (animation as CSSAnimation).animationName === 'comparison-grow');
    // Freeze both fills at identical times, avoiding wall-clock timing flakiness.
    for (const animation of animations) { animation.pause(); animation.currentTime = 0; }
    const initial = bars.map(bar => new DOMMatrix(getComputedStyle(bar).transform).a);
    for (const animation of animations) animation.currentTime = 600;
    const middle = bars.map(bar => new DOMMatrix(getComputedStyle(bar).transform).a);
    for (const animation of animations) animation.finish();
    return { count: animations.length, initial, middle };
  });
  expect(sample.count).toBe(2);
  expect(sample.initial).toEqual([0, 0]);
  expect(sample.middle.every(scale => scale > 0 && scale < 1)).toBe(true);
  await expect(chart.locator('[data-expanded]')).toHaveCount(2);
  await expect.poll(() => probe.scales()).toEqual([1, 1]);
  const ratio = await chart.locator('.comparison-bar').evaluateAll(bars =>
    bars[1].getBoundingClientRect().width / bars[0].getBoundingClientRect().width);
  expect(ratio).toBeCloseTo(.226, 2);
  await expect.poll(() => probe.stripe('animationName')).toBe('comparison-spiral');
  await expect.poll(() => probe.stripe('animationPlayState')).toBe('running');
  await page.locator('header').scrollIntoViewIfNeeded();
  await expect.poll(() => probe.stripe('animationPlayState')).toBe('paused');
  await chart.scrollIntoViewIfNeeded();
  expect(await probe.scales()).toEqual([1, 1]);
  expect(await chart.evaluate(el => el.getAnimations({ subtree: true })
    .filter(animation => (animation as CSSAnimation).animationName === 'comparison-grow').length)).toBe(0);
});

for (const phase of ['before entry', 'during entry']) {
  test(`pausing pace bars ${phase} shows the complete chart permanently`, async ({ page }) => {
    await page.goto('./');
    const probe = new PaceProbe(page);
    await expect.poll(() => probe.scales()).toEqual([0, 0]);
    if (phase === 'during entry') {
      const chart = page.locator('.comparison-pace');
      await chart.scrollIntoViewIfNeeded();
      await expect(chart).toHaveAttribute('data-pace-entered', '');
      await chart.evaluate(el => {
        for (const animation of el.getAnimations({ subtree: true })) {
          if ((animation as CSSAnimation).animationName === 'comparison-grow') {
            animation.pause(); animation.currentTime = 300;
          }
        }
      });
    }
    await page.getByRole('button', { name: 'Pause animations' }).click();
    expect(await probe.scales()).toEqual([1, 1]);
    expect(await probe.stripe('animationName')).toBe('none');
    await page.getByRole('button', { name: 'Play animations' }).click();
    expect(await probe.scales()).toEqual([1, 1]);
    await page.locator('.comparison-pace').scrollIntoViewIfNeeded();
    expect(await probe.scales()).toEqual([1, 1]);
  });
}

test('pace bars remain static without intersection observers', async ({ page }) => {
  await page.addInitScript(() => Reflect.deleteProperty(window, 'IntersectionObserver'));
  await page.goto('./');
  const probe = new PaceProbe(page);
  expect(await probe.scales()).toEqual([1, 1]);
  expect(await probe.stripe('animationName')).toBe('none');
});

test('printing before chart entry shows complete pace bars', async ({ page }) => {
  await page.goto('./');
  const probe = new PaceProbe(page);
  await expect.poll(() => probe.scales()).toEqual([0, 0]);
  await page.emulateMedia({ media: 'print' });
  expect(await probe.scales()).toEqual([1, 1]);
  expect(await probe.stripe('animationName')).toBe('none');
});
