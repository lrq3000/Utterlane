/** Construct bounded, deterministic decorative waveforms once. CSS transforms
 * animate the bars, avoiding per-frame DOM creation or JavaScript timers. */
export class DemoIllustrations {
  constructor() {
    document.querySelectorAll<HTMLElement>('[data-bars]').forEach(surface => this.populate(surface));
  }

  private populate(surface: HTMLElement): void {
    const count = Math.min(40, Math.max(0, Number(surface.dataset.bars) || 0));
    const fragment = document.createDocumentFragment();
    for (let index = 0; index < count; index++) {
      const bar = document.createElement('i');
      // Proportions preserve a substantial signal in every panel, from the
      // audio-file card to the phone and the full-width speed interlude.
      bar.style.setProperty('--bar-height', `${24 + Math.abs(Math.sin(index * 1.83)) * 62}%`);
      bar.style.setProperty('--bar-delay', `${-(index % 9) * .14}s`);
      fragment.append(bar);
    }
    surface.append(fragment);
    surface.dataset.enhanced = '';
  }
}
