/** Animations start on every visit, including when the OS requests reduced
 * motion. The page's explicit Play/Pause control owns this product preference;
 * nothing is stored or transmitted. Static HTML remains the no-JS fallback. */
export class MotionPreference extends EventTarget {
  private readonly button = document.querySelector<HTMLButtonElement>('.motion-toggle');
  private playing = true;

  constructor() {
    super();
    this.button?.addEventListener('click', () => {
      this.playing = !this.playing;
      this.apply();
    });
    this.apply();
    if (this.button) this.button.hidden = false;
  }

  get enabled(): boolean {
    return this.playing;
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

/** One entrance per target, independent of scroll-frame work. Content is
 * readable by default; only a successfully initialized observer opts into
 * the pre-entry styles. Pausing motion restores the static CSS immediately. */
export class ViewportReveals {
  private readonly observer: IntersectionObserver | null;
  private readonly entering = new Set<HTMLElement>();

  constructor(private readonly motion: MotionPreference) {
    if (!('IntersectionObserver' in window)) {
      this.observer = null;
      return;
    }
    this.observer = new IntersectionObserver(entries => {
      for (const entry of entries) {
        if (entry.isIntersecting) this.reveal(entry.target as HTMLElement);
      }
    }, { threshold: .08, rootMargin: '0px 0px -32px 0px' });

    document.querySelectorAll<HTMLElement>('[data-reveal]').forEach(target => this.observer!.observe(target));
    document.documentElement.dataset.reveals = 'ready';
    // Retire CSS entrance eligibility after its final animation. Merely keeping
    // data-revealed would recreate animations whenever Pause changes to Play.
    // The speed heading's detail is its last section-arrival (750ms including
    // delay); ordinary targets and quickstart rules finish together at 650ms.
    document.addEventListener('animationend', event => {
      if (event.animationName !== 'section-arrival' || !(event.target instanceof Element)) return;
      const target = event.target.closest<HTMLElement>('[data-reveal]');
      if (target) this.settle(target);
    });
    motion.addEventListener('change', () => {
      if (!motion.enabled) {
        for (const target of this.entering) this.settle(target);
      }
    });
    // Keyboard navigation must never wait for a viewport threshold or an
    // entrance delay. Only walk the focused element's ancestors, not the page.
    document.addEventListener('focusin', event => {
      let target = event.target instanceof Element ? event.target.closest<HTMLElement>('[data-reveal]') : null;
      while (target) {
        this.reveal(target);
        this.settle(target);
        target = target.parentElement?.closest<HTMLElement>('[data-reveal]') ?? null;
      }
    });
  }

  private reveal(target: HTMLElement): void {
    target.dataset.revealed = '';
    this.observer?.unobserve(target);
    if (!this.motion.enabled) this.settle(target);
    else if (!target.hasAttribute('data-reveal-settled')) this.entering.add(target);
  }

  private settle(target: HTMLElement): void {
    target.dataset.revealSettled = '';
    this.entering.delete(target);
  }
}
