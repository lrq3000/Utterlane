import { duration, escapeHtml } from './state.js';

const PATHS = {
  mic: '<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8"/>',
  document: '<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9zM14 3v6h6M8 13h8m-8 4h5"/>',
  audio: '<path d="M3 10v4m4-7v10m5-14v18m5-15v12m4-8v4"/>',
  gear: '<path d="m9.5 3-.5 2-2 .9-1.9-.6-2 3.4 1.5 1.4v2.4l-1.5 1.4 2 3.4 1.9-.6 2 .9.5 2h4l.5-2 2-.9 1.9.6 2-3.4-1.5-1.4v-2.4l1.5-1.4-2-3.4-1.9.6-2-.9-.5-2z"/><circle cx="11.5" cy="11.5" r="3"/>',
  copy: '<rect x="8" y="8" width="12" height="13" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/>',
  share: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.6 10.5 6.8-4m-6.8 7 6.8 4"/>',
  more: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
  people: '<circle cx="9" cy="7" r="3"/><path d="M3 21v-3a6 6 0 0 1 12 0v3M16 4a3 3 0 0 1 0 6m2 4a5 5 0 0 1 3 4v3"/>',
  arrow: '<path d="m9 5 7 7-7 7"/>',
  back: '<path d="m14 5-7 7 7 7M7 12h14"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>',
  upload: '<path d="M12 16V4m-5 5 5-5 5 5M4 16v5h16v-5"/>',
  home: '<path d="m3 10 9-7 9 7v10H3zM9 20v-7h6v7"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
};
export function icon(name, className = '') {
  return `<svg class="icon ${className}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${PATHS[name] || PATHS.document}</svg>`;
}
export function iconButton(action, label, name) {
  return `<button class="icon-button" data-action="${action}" aria-label="${label}" title="${label}">${icon(name)}</button>`;
}

/** Shared presentation components. Layout variants only arrange these components;
 * they do not fork transcript, waveform, actions or preference behavior.
 */
export class TranscriptPanel {
  static render() {
    return `<section class="transcript-panel" aria-label="Live transcript">
      <header class="transcript-heading"><span>${icon('document')}<strong>Transcript</strong></span><span class="transcript-status" id="transcript-status" role="status" aria-live="polite">Ready</span></header>
      <div class="transcript-body" id="transcript-body" tabindex="0" aria-label="Transcript text"></div>
      <div class="transcript-meta"><span id="word-count">No words yet</span><span id="transcript-detail">Appears automatically</span></div>
      <div class="transcript-actions"><button class="copy-button" data-action="copy" disabled>${icon('copy')}Copy</button><button class="share-button" data-action="share" disabled>${icon('share')}Share</button>${iconButton('more', 'More transcript actions', 'more')}</div>
    </section>`;
  }
  static update(session) {
    const body = document.querySelector('#transcript-body');
    if (!body) return;
    const empty = !session.text;
    const html = empty ? `<div class="empty-transcript"><div class="empty-mark">${icon('document')}</div><h3>${session.phase === 'recording' ? 'Listening to you…' : 'A space for your words'}</h3><p>${session.phase === 'recording' ? 'Your transcript will appear here<br>as you speak.' : 'Tap the waveform below and speak.<br>Your transcript will appear here.'}</p></div>` : session.segments.map(segment => `<p>${session.labels ? `<span class="speaker speaker-${segment.speaker}">Speaker ${segment.speaker}</span>` : ''}${escapeHtml(segment.text)}</p>`).join('');
    // Keep the scroll position and selection stable; a time/level update is not a
    // reason to replace transcript nodes. Only follow new text near the bottom.
    if (body.dataset.content !== html) {
      const alreadyRendered = body.dataset.content !== undefined;
      const follow = body.scrollHeight - body.scrollTop - body.clientHeight < 40;
      body.innerHTML = html;
      body.dataset.content = html;
      if (follow && (alreadyRendered || session.phase === 'recording')) body.scrollTop = body.scrollHeight;
    }
    const status = document.querySelector('#transcript-status');
    const statusText = ({ ready: 'Ready', recording: 'Live preview', processing: 'Finishing', complete: 'Complete' })[session.phase];
    if (status.textContent !== statusText) status.textContent = statusText;
    document.querySelector('#word-count').textContent = empty ? 'No words yet' : `${session.text.replace(/Speaker \d: /g, '').trim().split(/\s+/).length} words`;
    document.querySelector('#transcript-detail').textContent = session.phase === 'complete' ? 'In demo history' : session.phase === 'ready' ? 'Appears automatically' : 'On-device transcription';
    document.querySelectorAll('[data-action="copy"], [data-action="share"], [data-action="more"]').forEach(button => { button.disabled = empty; });
  }
}

export class WaveformControl {
  static privacyMessage = 'Audio recording and processing only happens on your device.';
  static render() {
    return `<div class="recording-control"><button class="waveform" data-action="record" aria-label="Start recording">
      <span class="waveform-top"><span id="capture-status">${icon('mic')}Ready to record</span><span class="elapsed" id="elapsed">00:00</span></span>
      <svg class="waveform-chart" viewBox="0 0 320 52" preserveAspectRatio="none" aria-hidden="true"><line x1="0" y1="26" x2="320" y2="26"/><g>${Array.from({ length: 64 }, (_, index) => `<rect x="${index * 5 + 1}" y="25" width="2.5" height="2" rx="1.25"/>`).join('')}</g></svg>
      <span class="finishing-progress" hidden><span class="progress-label"><span>Finalizing your words</span><strong id="progress-percent">0%</strong></span><span class="progress-track"><span id="progress-fill"></span></span></span>
      <span class="waveform-cta" id="waveform-cta">${icon('mic')}Tap to record</span>
    </button><p class="capture-note" id="capture-note">${icon('lock')}${WaveformControl.privacyMessage}</p></div>`;
  }
  static update(session) {
    const button = document.querySelector('.waveform');
    if (!button) return;
    const phase = session.phase;
    button.dataset.phase = phase;
    button.disabled = phase === 'processing';
    button.setAttribute('aria-label', ({ ready: 'Start recording', recording: 'Stop recording and finish transcript', processing: 'Finishing transcript', complete: 'Start a new recording' })[phase]);
    const status = document.querySelector('#capture-status');
    status.innerHTML = phase === 'recording' ? '<span class="record-dot"></span>Recording' : `${icon(phase === 'complete' ? 'check' : phase === 'processing' ? 'document' : 'mic')}${({ ready: 'Ready to record', processing: 'Finishing transcript', complete: 'Ready for another' })[phase]}`;
    document.querySelector('#elapsed').textContent = duration(session.seconds);
    document.querySelector('.waveform-chart').hidden = phase === 'processing';
    document.querySelector('.finishing-progress').hidden = phase !== 'processing';
    document.querySelector('#progress-fill').style.width = `${session.progress}%`;
    document.querySelector('#progress-percent').textContent = `${session.progress}%`;
    document.querySelector('#waveform-cta').innerHTML = phase === 'processing' ? `About ${session.remainingSeconds} ${session.remainingSeconds === 1 ? 'second' : 'seconds'} left` : `${icon(phase === 'recording' ? 'stop' : phase === 'complete' ? 'plus' : 'mic')}${({ ready: 'Tap to record', recording: 'Tap to stop', complete: 'New recording' })[phase]}`;
    document.querySelector('#capture-note').innerHTML = phase === 'complete' ? `${icon('check')}Previous result kept in demo history` : phase === 'processing' ? `${icon('lock')}Finishing locally · example estimate` : `${icon('lock')}${WaveformControl.privacyMessage}`;
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    // Fixed 64-bar cost, independent of recording duration. These deterministic
    // sample levels are solely a mockup; native bars must keep using actual PCM.
    document.querySelectorAll('.waveform-chart rect').forEach((bar, index) => {
      const time = reduced ? 2 : performance.now() / 450;
      const height = phase === 'recording' ? Math.max(3, Math.abs(Math.sin(index * .57 + time) * Math.cos(index * .21 - time * .7)) * 46) : 2;
      bar.setAttribute('height', height);
      bar.setAttribute('y', (52 - height) / 2);
    });
  }
}

export class SpeakerToggle {
  static render(preferences) {
    return `<button class="speaker-toggle" data-action="speakers" role="switch" aria-checked="${preferences.speakerLabels}" aria-label="Speaker labels (diarization), app-wide">${icon('people')}<span class="speaker-copy"><strong>Speaker labels</strong><small>Diarization · applies app-wide</small></span><span class="switch-track"><span></span></span></button>`;
  }
  static update(preferences) {
    document.querySelectorAll('[data-action="speakers"]').forEach(button => button.setAttribute('aria-checked', String(preferences.speakerLabels)));
  }
}

export class HistoryNavigation {
  static render(style = 'tiles') {
    return `<nav class="history-nav ${style}" aria-label="Your histories">
      ${['audio', 'transcripts'].map(type => `<button data-action="${type}" class="history-link">${icon(type === 'audio' ? 'audio' : 'document')}<span><strong>${type === 'audio' ? 'Audio history' : 'Transcript history'}</strong><small>${type === 'audio' ? 'Listen & retranscribe' : 'Revisit your words'}</small></span>${icon('arrow', 'history-arrow')}</button>`).join('')}
    </nav>`;
  }
}

export function brandHeader(back = false, title = '') {
  return `<header class="app-header">${back ? `${iconButton('home', 'Back to home', 'back')}<h1>${escapeHtml(title)}</h1>` : '<img class="wordmark light-wordmark" src="../../../app/src/main/res/drawable-nodpi/utterlane_wordmark.png" alt="Utterlane"><img class="wordmark dark-wordmark" src="../../../app/src/main/res/drawable-nodpi/utterlane_wordmark_dark.png" alt="Utterlane">'}${iconButton('settings', 'Settings', 'gear')}</header>`;
}
