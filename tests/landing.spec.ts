import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

test('meeting speech flows into matching transcript rows and respects motion controls', async ({ page }) => {
  await page.goto('./');
  await page.getByRole('button', { name: 'Pause animations' }).click();
  const scene = page.locator('.meeting-scene');
  const bubbles = scene.locator('.meeting-bubble');
  const rows = scene.locator('.meeting-row');
  await expect(bubbles).toHaveCount(3);
  await expect(rows).toHaveCount(3);
  // Pausing presents the complete story, rather than freezing a partial transcript.
  for (const row of await rows.all()) await expect(row).toHaveCSS('opacity', '1');
  await page.getByRole('button', { name: 'Play animations' }).click();
  await scene.scrollIntoViewIfNeeded();
  await expect(bubbles.first()).toHaveCSS('animation-play-state', 'running');
  // Sample one shared cycle deterministically: a bubble travels first, then
  // its row arrives, and all three rows accumulate before the cycle restarts.
  const sample = async (time: number) => scene.evaluate((element, currentTime) => {
    for (const animation of element.getAnimations({ subtree: true })) {
      animation.pause();
      animation.currentTime = currentTime;
    }
    return {
      transforms: [...element.querySelectorAll('.meeting-bubble')].map(bubble => getComputedStyle(bubble).transform),
      opacity: [...element.querySelectorAll('.meeting-row')].map(row => Number(getComputedStyle(row).opacity)),
    };
  }, time);
  const start = await sample(0);
  const transit = await sample(1500);
  expect(transit.transforms[0]).not.toBe(start.transforms[0]);
  expect((await sample(3300)).transforms[1]).not.toBe(start.transforms[1]);
  expect((await sample(5100)).transforms[2]).not.toBe(start.transforms[2]);
  expect(start.opacity).toEqual([0, 0, 0]);
  expect((await sample(3000)).opacity).toEqual([1, 0, 0]);
  expect((await sample(5000)).opacity).toEqual([1, 1, 0]);
  expect((await sample(7000)).opacity).toEqual([1, 1, 1]);
  await page.getByRole('button', { name: 'Pause animations' }).click();
  for (const row of await rows.all()) await expect(row).toHaveCSS('opacity', '1');
  await expect(bubbles.first()).toHaveCSS('animation-name', 'none');
});

test('presents the promised product and usable download route', async ({ page }) => {
  await page.goto('./');
  await expect(page.getByRole('heading', { level: 1 })).toContainText(
    'Near real-time transcription. On your Android phone.',
  );
  await expect(page.getByRole('heading', { level: 1 })).toContainText('100%');
  const downloads = page.getByRole('main').getByRole('link', { name: 'Get Utterlane', exact: true });
  await expect(downloads.first()).toHaveAttribute('href', 'https://github.com/lrq3000/Utterlane/releases');
  await expect(page.locator('#setup')).toContainText('Size varies by model');
});

test('places the speed interlude before the complete on-device and everyday story', async ({ page }) => {
  await page.goto('./');
  await expect(page.locator('#speed').getByRole('heading', { level: 2 })).toHaveText(
    'Experience the fastest accurate offline transcription on Android.',
  );
  const sectionIds = await page.locator('main > section[id]').evaluateAll(sections => sections.map(section => section.id));
  expect(sectionIds).toEqual(['speed', 'on-device', 'everyday', 'privacy', 'setup', 'faq']);
  await expect(page.locator('#speed')).toContainText('25 languages.');
  await expect(page.locator('#setup')).toContainText('Moondream Parakeet Ultra');
  await expect(page.locator('.closing')).toContainText('Open source from the start.');
});

for (const width of [390, 1440]) {
  test(`makes the phone turn noticeable while it is visible at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await page.goto('./');
    const art = page.locator('.hero-art');
    const bounds = await art.evaluate(el => ({ top: el.getBoundingClientRect().top + scrollY, height: el.clientHeight }));
    await page.evaluate(top => window.scrollTo({ top, behavior: 'instant' }), Math.max(0, bounds.top - 900 * .65));
    // Let the coalesced scroll frame settle before measuring the initial angle.
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const phone = page.locator('.phone');
    const start = await phone.evaluate(el => {
      const matrix = new DOMMatrix(getComputedStyle(el).transform);
      return Math.atan2(-matrix.m13, matrix.m11) * 180 / Math.PI;
    });
    await page.evaluate(top => window.scrollTo({ top, behavior: 'instant' }), bounds.top + bounds.height * .3);
    await expect.poll(() => phone.evaluate((el, initial) => {
      const matrix = new DOMMatrix(getComputedStyle(el).transform);
      return Math.abs(Math.atan2(-matrix.m13, matrix.m11) * 180 / Math.PI - initial);
    }, start)).toBeGreaterThan(25);
    await expect(phone).toBeInViewport();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });

  test(`gives the waveform real vertical presence at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('./');
    const wave = page.locator('.phone .wave');
    expect(await wave.evaluate(el => el.clientHeight)).toBeGreaterThanOrEqual(96);
    const peak = await wave.locator('i').evaluateAll(bars => Math.max(...bars.map(bar => (bar as HTMLElement).offsetHeight)));
    expect(peak).toBeGreaterThanOrEqual(75);
    expect(await page.locator('.engine .wave').evaluate(el => el.clientHeight)).toBeGreaterThanOrEqual(96);
  });
}

for (const reducedMotion of ['reduce', 'no-preference'] as const) {
  test(`starts animations by default with ${reducedMotion} and keeps the manual control authoritative`, async ({ page }) => {
    await page.emulateMedia({ reducedMotion });
    await page.goto('./');
    const pause = page.getByRole('button', { name: 'Pause animations' });
    await expect(pause).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('html')).toHaveAttribute('data-motion', 'running');
    expect(await page.locator('.rotator-track').evaluate(el => getComputedStyle(el).animationName)).toBe('word-rotate');
    await pause.click();
    await expect(page.locator('html')).toHaveAttribute('data-motion', 'paused');
    await expect(page.locator('.static-promise')).toBeVisible();
    // OS changes cannot undo either explicit button choice during this visit.
    await page.emulateMedia({ reducedMotion: reducedMotion === 'reduce' ? 'no-preference' : 'reduce' });
    const play = page.getByRole('button', { name: 'Play animations' });
    await expect(play).toHaveAttribute('aria-pressed', 'false');
    await play.click();
    await page.emulateMedia({ reducedMotion });
    await expect(page.locator('html')).toHaveAttribute('data-motion', 'running');
    await pause.click();
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-motion', 'running');
  });
}

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

test('scroll entrances remain readable when reached, paused, or keyboard focused', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await page.goto('./');
  const point = page.locator('.privacy-point').first();
  await expect(point).toHaveCSS('opacity', '0');
  await point.scrollIntoViewIfNeeded();
  await expect(point).toHaveCSS('opacity', '1');
  await page.locator('#faq summary').first().focus();
  await expect(page.locator('.faq-list')).toHaveCSS('opacity', '1');
  await page.keyboard.press('Enter');
  await expect(page.locator('#faq details').first()).toHaveAttribute('open', '');
  await page.getByRole('button', { name: 'Pause animations' }).click();
  expect(await page.locator('[data-reveal]').evaluateAll(targets => targets.every(el => getComputedStyle(el).opacity === '1'))).toBe(true);
  await expect(page.locator('.speed-word')).toHaveCSS('opacity', '1');
});

for (const selector of ['.speed-word', '.privacy-point']) {
  test(`does not replay the completed ${selector} entrance after pause and play`, async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await page.goto('./');
    const target = page.locator(selector).first();
    await target.scrollIntoViewIfNeeded();
    // An empty animation list can also mean the entry observer has not fired
    // yet. Wait for visible content before considering its entrance complete.
    await expect(target).toHaveCSS('opacity', '1');
    await expect.poll(() => target.evaluate(el => el.getAnimations().every(animation => animation.playState === 'finished'))).toBe(true);
    await target.evaluate(el => {
      const element = el as HTMLElement;
      element.dataset.restarts = '0';
      element.addEventListener('animationstart', event => {
        if (event.target === element) element.dataset.restarts = String(Number(element.dataset.restarts) + 1);
      });
    });
    await page.getByRole('button', { name: 'Pause animations' }).click();
    await page.getByRole('button', { name: 'Play animations' }).click();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await expect(target).toHaveAttribute('data-restarts', '0');
    await expect(target).toHaveCSS('opacity', '1');
  });
}

test('pauses the signal loop outside the viewport', async ({ page }) => {
  // The interlude begins within the default 1000px viewport. A shorter view
  // starts it beyond the observer's intentional 80px prewarming margin.
  await page.setViewportSize({ width: 1440, height: 720 });
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await page.goto('./');
  const signal = page.locator('.signal-line span');
  await expect(signal).toHaveCSS('animation-play-state', 'paused');
  await page.locator('#speed').scrollIntoViewIfNeeded();
  await expect(signal).toHaveCSS('animation-play-state', 'running');
  await page.locator('footer').scrollIntoViewIfNeeded();
  await expect(signal).toHaveCSS('animation-play-state', 'paused');
});

test('shows complete content when viewport observers are unavailable', async ({ page }) => {
  await page.addInitScript(() => Reflect.deleteProperty(window, 'IntersectionObserver'));
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await page.goto('./');
  await expect(page.locator('.speed-word')).toHaveCSS('opacity', '1');
  await expect(page.locator('.privacy-point').first()).toHaveCSS('opacity', '1');
  await expect(page.locator('.closing')).toContainText('Explore GitHub releases');
});

test('keeps content and FAQ usable with JavaScript disabled', async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false, reducedMotion: 'reduce' });
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:4175/Utterlane/');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  for (const id of ['speed', 'on-device', 'everyday', 'privacy', 'setup', 'faq']) {
    await expect(page.locator(`#${id}`).getByRole('heading').first()).toBeVisible();
  }
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

for (const reducedMotion of ['reduce', 'no-preference'] as const) {
  for (const [width, height] of [[320, 740], [360, 900], [390, 844], [768, 900], [844, 390], [1280, 720], [1440, 900]]) {
    test(`fits the ${width}x${height} viewport with ${reducedMotion} motion`, async ({ page }) => {
      await page.setViewportSize({ width, height });
      await page.emulateMedia({ reducedMotion });
      await page.goto('./');
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      // Check every section, not only overflow at the hero.
      for (const id of ['speed', 'on-device', 'everyday', 'privacy', 'setup', 'faq']) {
        await page.locator(`#${id}`).scrollIntoViewIfNeeded();
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      }
    });
  }
}

test('supports keyboard access and has no detected WCAG AA violations', async ({ page }) => {
  await page.goto('./');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.locator('#main')).toBeFocused();
  // Audit the fully revealed static content, not an intermediate entrance frame.
  await page.getByRole('button', { name: 'Pause animations' }).click();
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(results.violations).toEqual([]);
});
