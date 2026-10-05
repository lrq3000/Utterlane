import { MotionPreference } from './motion';

/** Decorative chart entrance, not an ASR benchmark. Work is bounded by the two
 * cached fills; CSS owns every animation frame and the site's visibility gates
 * suspend motion offscreen/in background tabs. Static HTML is always complete. */
export class PaceComparison {
  private readonly chart = document.querySelector<HTMLElement>('.comparison-pace');
  private readonly bars = [...this.chart?.querySelectorAll<HTMLElement>('.comparison-bar') ?? []];

  constructor(motion: MotionPreference) {
    if (!this.chart || !('IntersectionObserver' in window)) return;
    const chart = this.chart;
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting && entry.intersectionRatio >= .2)) {
        chart.dataset.paceEntered = '';
        // MotionVisibility separately keeps observing offscreen loop state.
        // Retire this observer as soon as its one-time entrance has triggered.
        observer.disconnect();
      }
    }, { threshold: .2 });
    observer.observe(chart);
    chart.dataset.paceReady = '';

    chart.addEventListener('animationend', event => {
      if (event.animationName === 'comparison-grow' && event.target instanceof HTMLElement) {
        event.target.dataset.expanded = '';
      }
    });
    const settleOnPause = () => {
      if (motion.enabled) return;
      // Show the final lengths when paused, even before first entry. A later
      // Play must never collapse a chart the visitor has already been shown.
      observer.disconnect();
      chart.dataset.paceEntered = '';
      for (const bar of this.bars) bar.dataset.expanded = '';
    };
    motion.addEventListener('change', settleOnPause);
    settleOnPause();
  }
}
