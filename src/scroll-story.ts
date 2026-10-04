import type { MotionPreference } from './motion';

/** Scroll is observational, never intercepted. Geometry is measured after
 * layout changes; a frame only updates a fixed handful of CSS properties.
 * There is no perpetual rAF loop and no document query inside a scroll frame. */
export class ScrollStory {
  private readonly hero = document.querySelector<HTMLElement>('.hero');
  private readonly story = document.querySelector<HTMLElement>('.story');
  private readonly steps = [...document.querySelectorAll<HTMLElement>('[data-story-step]')];
  private readonly segments = [...document.querySelectorAll<HTMLElement>('.segment')];
  private frame = 0;
  private needsMeasure = true;
  private heroTop = 0;
  private heroHeight = 1;
  private storyTop = 0;
  private storyHeight = 1;
  private sticky = false;
  private activeStep = -1;
  private lastHeroProgress = '';
  private lastStoryProgress = '';

  constructor(private readonly motion: MotionPreference) {
    if (!this.hero || !this.story) return;
    window.addEventListener('scroll', () => this.schedule(), { passive: true });
    window.addEventListener('resize', () => this.invalidate(), { passive: true });
    motion.addEventListener('change', () => this.invalidate());
    // Native FAQ disclosures above/below a scene, font scaling, or viewport
    // resizing can change geometry independently of a scroll event.
    const observer = new ResizeObserver(() => this.invalidate());
    observer.observe(this.hero);
    observer.observe(this.story);
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) this.invalidate();
    });
    this.schedule();
  }

  private invalidate(): void {
    this.needsMeasure = true;
    this.schedule();
  }

  private schedule(): void {
    if (this.frame || document.hidden) return;
    this.frame = requestAnimationFrame(() => {
      this.frame = 0;
      this.update();
    });
  }

  private measure(): void {
    const heroRect = this.hero!.getBoundingClientRect();
    const storyRect = this.story!.getBoundingClientRect();
    this.heroTop = heroRect.top + scrollY;
    this.heroHeight = heroRect.height;
    this.storyTop = storyRect.top + scrollY;
    this.storyHeight = storyRect.height;
    this.sticky = matchMedia('(min-width: 761px) and (min-height: 700px)').matches;
    this.needsMeasure = false;
  }

  private update(): void {
    if (this.needsMeasure) this.measure();
    if (!this.motion.enabled) {
      this.segments.forEach(segment => segment.classList.add('revealed'));
      this.setActiveStep(0);
      // The readable paused state changes classes without changing scrollY.
      // Invalidate the cache so resuming at the same position restores them.
      this.lastStoryProgress = '';
      return;
    }

    const heroProgress = this.clamp((scrollY - this.heroTop) / this.heroHeight).toFixed(3);
    if (heroProgress !== this.lastHeroProgress) {
      this.hero!.style.setProperty('--hero-progress', heroProgress);
      this.lastHeroProgress = heroProgress;
    }

    // Small screens use the natural pass through the viewport instead of a
    // pinned scene. This also keeps landscape and enlarged text scrollable.
    const progress = this.sticky
      ? this.clamp((scrollY - this.storyTop) / Math.max(1, this.storyHeight - innerHeight))
      : this.clamp((scrollY + innerHeight * .5 - this.storyTop) / (this.storyHeight * .7));
    const formatted = progress.toFixed(3);
    if (formatted !== this.lastStoryProgress) {
      this.story!.style.setProperty('--story-progress', formatted);
      this.segments.forEach((segment, index) => segment.classList.toggle('revealed', progress > .35 + index * .28));
      this.setActiveStep(Math.min(2, Math.floor(progress * 3)));
      this.lastStoryProgress = formatted;
    }
  }

  private setActiveStep(index: number): void {
    if (index === this.activeStep) return;
    this.steps.forEach((step, position) => {
      if (position === index) step.setAttribute('aria-current', 'step');
      else step.removeAttribute('aria-current');
    });
    this.activeStep = index;
  }

  private clamp(value: number): number {
    return Math.min(1, Math.max(0, value));
  }
}
