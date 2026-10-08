/** Shared demo-only preferences: all three concepts and Settings observe one value.
 * Only these non-sensitive preferences persist; sample sessions live in memory.
 * The native equivalent is SettingsRepository, not a second home-screen setting.
 */
export class Preferences extends EventTarget {
  constructor() {
    super();
    this.speakerLabels = this.read();
    window.addEventListener('storage', event => {
      if (event.key === 'utterlane-home-study-speakers') {
        this.speakerLabels = this.read();
        this.dispatchEvent(new Event('change'));
      }
    });
  }
  read() {
    try { return localStorage.getItem('utterlane-home-study-speakers') === 'true'; }
    catch { return false; }
  }
  toggle() {
    this.speakerLabels = !this.speakerLabels;
    try { localStorage.setItem('utterlane-home-study-speakers', String(this.speakerLabels)); }
    catch { /* Private/file browsing may reject storage; the current tab still works. */ }
    this.dispatchEvent(new Event('change'));
  }
}

/** One review configuration for every design URL. Validate stored values so an
 * older or malformed preference cannot leave a select or the phone in limbo.
 * Persist only presentation settings, never sample transcripts or recordings.
 */
export class PreviewPreferences {
  static key = 'utterlane-home-study-preview';
  static choices = {
    theme: ['light', 'dark'],
    width: ['390', '360', '430'],
    textSize: ['1', '1.25'],
    state: ['ready', 'recording', 'processing', 'complete'],
  };
  constructor() { this.values = this.read(); }
  read() {
    let stored = this.values;
    try { stored = JSON.parse(localStorage.getItem(PreviewPreferences.key)); }
    catch { /* Keep the in-memory settings if browser storage is unavailable. */ }
    return Object.fromEntries(Object.entries(PreviewPreferences.choices).map(([key, choices]) =>
      [key, choices.includes(stored?.[key]) ? stored[key] : choices[0]]));
  }
  set(key, value) {
    if (!PreviewPreferences.choices[key]?.includes(value)) return;
    // Merge the latest settings so an already-open tab cannot overwrite another
    // tab's width or appearance when its recording state changes.
    const latest = this.read();
    const changed = latest[key] !== value;
    this.values = { ...latest, [key]: value };
    if (changed) {
      try { localStorage.setItem(PreviewPreferences.key, JSON.stringify(this.values)); }
      catch { /* The controls remain usable without persistence. */ }
    }
  }
}

export const SAMPLE_SEGMENTS = [
  { speaker: 1, text: 'I had an idea on the way here. Let us make a little more room for the things that matter.' },
  { speaker: 2, text: 'I like that. We could start with a quiet morning, a good conversation, and time to think.' },
  { speaker: 1, text: 'Exactly. I will write down a few thoughts, and we can pick this up tomorrow.' },
];

/** One bounded session model drives every layout. Timers emit state changes, never
 * own DOM nodes, so opening Settings/history does not interrupt the demo capture.
 * Completion creates an independent history entry before another session starts.
 */
export class DemoSession extends EventTarget {
  constructor(preferences) {
    super();
    this.preferences = preferences;
    this.history = [
      { id: 'sample-1', title: 'A thought for tomorrow', date: 'Today · 09:24', seconds: 42, labels: false, text: 'Make a little room each morning to write down the ideas worth keeping.' },
      { id: 'sample-2', title: 'A conversation over coffee', date: 'Today · 08:50', seconds: 128, labels: true, text: 'Speaker 1: What if we tried a simpler approach?\n\nSpeaker 2: That sounds good. Let us start with the part people use every day.' },
      { id: 'sample-3', title: 'Notes from the walk', date: 'Yesterday · 17:16', seconds: 63, labels: false, text: 'Remember to take the longer path next time. There is a quiet place by the river that would be perfect for a break.' },
    ];
    this.reset();
  }
  reset() {
    clearInterval(this.timer);
    this.phase = 'ready';
    this.seconds = 0;
    this.wordCount = 0;
    this.progress = 0;
    this.labels = this.preferences.speakerLabels;
    this.entry = null;
    this.emit();
  }
  emit() { this.dispatchEvent(new Event('change')); }
  start() {
    clearInterval(this.timer);
    this.phase = 'recording';
    this.started = performance.now();
    this.seconds = 0;
    this.wordCount = 0;
    this.labels = this.preferences.speakerLabels;
    this.entry = null;
    this.emit();
    this.timer = setInterval(() => {
      this.seconds = Math.floor((performance.now() - this.started) / 1000);
      // A bounded sample, intentionally delivered in segments rather than pretending
      // that the Android engine promises instantaneous word-by-word recognition.
      this.wordCount = Math.min(80, Math.floor(this.seconds / 2) * 9);
      this.emit();
    }, 250);
  }
  stop() {
    if (this.phase !== 'recording') return;
    clearInterval(this.timer);
    this.phase = 'processing';
    this.progress = 0;
    this.wordCount = Math.max(18, this.wordCount);
    const start = performance.now();
    this.emit();
    this.timer = setInterval(() => {
      this.progress = Math.min(100, Math.floor((performance.now() - start) / 42));
      if (this.progress === 100) this.complete();
      else this.emit();
    }, 150);
  }
  complete() {
    clearInterval(this.timer);
    this.phase = 'complete';
    this.progress = 100;
    this.wordCount = Math.max(27, this.wordCount);
    this.entry = {
      id: `demo-${Date.now()}`, title: 'A new thought', date: 'Just now · demo',
      seconds: this.seconds, labels: this.labels, text: this.text,
    };
    this.history.unshift(this.entry);
    this.emit();
  }
  preview(phase) {
    this.reset();
    if (phase === 'ready') return;
    this.start();
    if (phase === 'recording') return;
    this.seconds = 24;
    this.wordCount = 80;
    if (phase === 'processing') this.stop();
    else this.complete();
  }
  get segments() {
    let remaining = this.wordCount;
    const result = [];
    for (const segment of SAMPLE_SEGMENTS) {
      const words = segment.text.split(' ');
      if (remaining <= 0) break;
      result.push({ speaker: segment.speaker, text: words.slice(0, remaining).join(' ') });
      remaining -= words.length;
    }
    return result;
  }
  get text() {
    return this.segments.map(segment => `${this.labels ? `Speaker ${segment.speaker}: ` : ''}${segment.text}`).join('\n\n');
  }
  get remainingSeconds() { return Math.max(1, Math.ceil((100 - this.progress) * .042)); }
}

export function duration(seconds) {
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
}

export function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
}
