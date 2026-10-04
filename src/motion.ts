/** A single motion preference drives CSS and scroll effects. An explicit choice
 * wins over OS changes for this page visit; nothing is stored or transmitted. */
export class MotionPreference extends EventTarget {
  private readonly media = matchMedia('(prefers-reduced-motion: reduce)');
  private readonly button = document.querySelector<HTMLButtonElement>('.motion-toggle');
  private explicitChoice: boolean | null = null;

  constructor() {
    super();
    this.button?.addEventListener('click', () => {
      this.explicitChoice = !this.enabled;
      this.apply();
    });
    this.media.addEventListener('change', () => this.apply());
    this.apply();
    if (this.button) this.button.hidden = false;
  }

  get enabled(): boolean {
    return this.explicitChoice ?? !this.media.matches;
  }

  private apply(): void {
    document.documentElement.dataset.motion = this.enabled ? 'running' : 'paused';
    const label = this.enabled ? 'Pause animations' : 'Play animations';
    this.button?.setAttribute('aria-label', label);
    this.button?.setAttribute('aria-pressed', String(this.enabled));
    this.button?.setAttribute('title', label);
    const text = this.button?.querySelector('span');
    if (text) text.textContent = label;
    this.button?.querySelector('use')?.setAttribute('href', this.enabled ? '#pause' : '#play');
    this.dispatchEvent(new Event('change'));
  }
}

/** Visibility is orthogonal to the visitor's preference. Pausing a background
 * tab must not turn an explicit 'Play' choice into a different preference. */
export class MotionVisibility {
  constructor() {
    const updatePage = () => {
      document.documentElement.dataset.pageHidden = String(document.hidden);
    };
    document.addEventListener('visibilitychange', updatePage);
    updatePage();

    if (!('IntersectionObserver' in window)) return;
    const observer = new IntersectionObserver(entries => {
      for (const entry of entries) {
        (entry.target as HTMLElement).dataset.inView = String(entry.isIntersecting);
      }
    }, { rootMargin: '80px' });

    document.querySelectorAll<HTMLElement>('[data-motion-surface]').forEach(surface => {
      observer.observe(surface);
    });
  }
}
