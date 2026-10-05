/** The website's explicit choice is independent of device appearance and motion.
 * Cached references keep switching constant-sized; CSS applies the palette to
 * existing content without rebuilding illustrations or restarting their loops. */
export class ThemePreference {
  private static readonly STORAGE_KEY = 'utterlane-theme';
  private readonly root = document.documentElement;
  private readonly button = document.querySelector<HTMLButtonElement>('.theme-toggle');
  private readonly icon = this.button?.querySelector('use');
  private readonly chrome = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]');

  constructor() {
    this.button?.addEventListener('click', () => this.toggle());
    // The head bootstrap already restored the theme before paint. Reflect that
    // same state in the button before exposing an actionable control.
    this.apply();
    if (this.button) this.button.hidden = false;
  }

  private toggle(): void {
    this.root.dataset.theme = this.root.dataset.theme === 'dark' ? 'light' : 'dark';
    try {
      localStorage.setItem(ThemePreference.STORAGE_KEY, this.root.dataset.theme);
    } catch (_) {
      // Private browsing policies or a full storage quota must not prevent an
      // explicit choice from working for the current page visit.
    }
    this.apply();
  }

  private apply(): void {
    const dark = this.root.dataset.theme === 'dark';
    const label = dark ? 'Switch to light mode' : 'Switch to dark mode';
    this.button?.setAttribute('aria-label', label);
    this.button?.setAttribute('title', label);
    this.icon?.setAttribute('href', dark ? '#sun' : '#moon');
    this.chrome?.setAttribute('content', dark ? '#0E1727' : '#F1F5FB');
  }
}
