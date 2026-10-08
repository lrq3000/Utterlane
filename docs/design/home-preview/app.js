import { DemoSession, Preferences, duration, escapeHtml } from './state.js';
import { TranscriptPanel, WaveformControl, SpeakerToggle, HistoryNavigation, brandHeader, icon, iconButton } from './components.js';

const CONCEPTS = {
  focus: { name: 'A / Focus', theme: 'light', description: 'One calm screen, one obvious action. The transcript, speaker labels and recording control stay together, with both histories directly below.', tradeoff: 'Best balance for a first launch. The transcript has less reading room than Notebook.' },
  notebook: { name: 'B / Notebook', theme: 'light', description: 'A document-first canvas. Your words take most of the screen, while a lower recording dock stays within easy reach. Histories become primary navigation.', tradeoff: 'Best for longer reading and everyday dictation. The history destinations are a little less descriptive.' },
  studio: { name: 'C / Studio', theme: 'dark', description: 'A compact, dark-first workspace. A library strip sits above the live transcript, with a precise recording console below. Quiet surfaces, clear boundaries.', tradeoff: 'Best for frequent use. The denser layout is more utilitarian than Focus. Light mode is included.' },
};

/** Route ownership is independent from the recording model. In Android the home
 * activity should similarly observe the shared capture state, not own the service.
 */
class PreviewApp {
  constructor() {
    const requested = new URLSearchParams(location.search).get('concept');
    this.concept = Object.hasOwn(CONCEPTS, requested) ? requested : 'focus';
    this.preferences = new Preferences();
    this.session = new DemoSession(this.preferences);
    this.route = 'home';
    this.device = document.querySelector('#device');
    this.root = document.querySelector('#app');
    this.sheet = document.querySelector('#sheet');
    this.device.classList.add(this.concept);
    this.theme = CONCEPTS[this.concept].theme;
    this.session.addEventListener('change', () => this.update());
    this.preferences.addEventListener('change', () => SpeakerToggle.update(this.preferences));
    this.device.addEventListener('click', event => {
      const target = event.target.closest('[data-action]');
      if (target && !target.disabled) this.act(target.dataset.action, target);
    });
    this.setupReview();
    this.render();
    // Native dialog focus restoration and Escape remain browser-owned.
    this.sheet.addEventListener('click', event => {
      if (event.target === this.sheet) {
        const bounds = this.sheet.getBoundingClientRect();
        if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) this.sheet.close();
      }
    });
  }
  setupReview() {
    const concept = CONCEPTS[this.concept];
    document.title = `Utterlane — ${concept.name} home concept`;
    document.querySelector(`[data-concept="${this.concept}"]`).setAttribute('aria-current', 'page');
    document.querySelector('#concept-caption').textContent = concept.name;
    document.querySelector('#direction-description').textContent = concept.description;
    document.querySelector('#direction-tradeoff').textContent = concept.tradeoff;
    document.querySelectorAll('[data-theme]').forEach(button => {
      if (button.tagName === 'BUTTON') button.addEventListener('click', () => this.setTheme(button.dataset.theme));
    });
    document.querySelector('#preview-width').addEventListener('change', event => this.device.style.setProperty('--phone-width', `${event.target.value}px`));
    document.querySelector('#preview-state').addEventListener('change', event => {
      this.route = 'home';
      this.sheet.close();
      this.session.preview(event.target.value);
      this.render();
    });
    document.querySelector('#text-size').addEventListener('change', event => this.device.style.setProperty('--font-scale', event.target.value));
    // Fit the complete phone into a laptop viewport without changing its layout
    // dimensions. Narrow browser windows instead show the phone at natural size.
    const fitPreview = () => {
      const scale = innerWidth > 800 ? Math.min(1, Math.max(.6, (innerHeight - 108) / 844)) : 1;
      this.device.style.zoom = scale;
    };
    window.addEventListener('resize', fitPreview);
    fitPreview();
    this.setTheme(this.theme);
  }
  setTheme(theme) {
    this.theme = theme;
    this.device.dataset.theme = theme;
    document.querySelectorAll('button[data-theme]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.theme === theme)));
  }
  intro(title, subtitle) {
    return `<div class="home-intro"><div><h1>${title}</h1><p>${subtitle}</p></div></div>`;
  }
  importButton() { return `<button class="text-button" data-action="import">${icon('upload')}Transcribe an audio file</button>`; }
  home() {
    const transcript = TranscriptPanel.render();
    const speakers = SpeakerToggle.render(this.preferences);
    const waveform = WaveformControl.render();
    if (this.concept === 'notebook') {
      return `${brandHeader()}<div class="home-content"><div class="home-intro"><div><h1>New transcript</h1><p>Your voice. Your own space.</p></div>${iconButton('import', 'Transcribe an audio file', 'upload')}</div>${transcript}</div><div class="capture-dock">${speakers}${waveform}</div><nav class="bottom-nav" aria-label="Main navigation"><button class="selected" data-action="home" aria-current="page">${icon('mic')}Record</button><button data-action="audio" aria-label="Audio history">${icon('audio')}Audio history</button><button data-action="transcripts" aria-label="Transcript history">${icon('document')}Transcripts</button></nav>`;
    }
    if (this.concept === 'studio') {
      return `${brandHeader()}<div class="home-content">${this.intro('Make yourself heard.', 'A private space to capture what matters.')}${HistoryNavigation.render('strip')}${transcript}${speakers}${waveform}<div class="import-row">${this.importButton()}</div></div>`;
    }
    return `${brandHeader()}<div class="home-content">${this.intro('Speak freely.', 'Turn a thought into something you can use.')}${transcript}${speakers}${waveform}${HistoryNavigation.render()}<div class="import-row">${this.importButton()}</div></div>`;
  }
  settings() {
    const row = (action, glyph, title, subtitle) => `<button class="setting-row" data-action="${action}">${icon(glyph)}<span><strong>${title}</strong><small>${subtitle}</small></span>${icon('arrow')}</button>`;
    return `${brandHeader(true, 'Settings')}<div class="route-content"><p class="route-intro">Make Utterlane work your way.</p><p class="section-label">Transcription</p><div class="settings-group">${SpeakerToggle.render(this.preferences)}${row('models', 'document', 'Speech recognition', 'Model and language preferences')}${row('dictionary', 'document', 'Word corrections', 'Your names, vocabulary and replacements')}</div><p class="section-label">Make it yours</p><div class="settings-group">${row('appearance', 'home', 'Appearance', this.theme === 'dark' ? 'Dark' : 'Light')}${row('shortcuts', 'mic', 'Voice input shortcuts', 'Keyboard microphone & floating button')}${row('retention', 'clock', 'History & retention', 'Audio and text have independent controls')}</div><p class="route-note">The home switch and this switch share the same app-wide speaker-label preference. Changes apply to new recordings.</p><p class="route-note">Settings destination preview. The Android version will open the existing Settings activity here.</p></div>`;
  }
  history() {
    const audio = this.route === 'audio';
    return `${brandHeader(true, audio ? 'Audio history' : 'Transcript history')}<div class="route-content"><p class="route-intro">${audio ? 'Your recordings, ready to revisit.' : 'Your words, ready when you need them.'}</p><p class="section-label">Sample library · this preview only</p><div class="settings-group">${this.session.history.map(entry => `<button class="history-entry" data-action="entry" data-id="${entry.id}">${icon(audio ? 'audio' : 'document')}<span><strong>${escapeHtml(entry.title)}</strong><small>${escapeHtml(entry.date)} · ${duration(entry.seconds)}${entry.labels ? ' · Speaker labels' : ''}</small></span>${icon('arrow')}</button>`).join('')}</div><p class="route-note">Demo sessions appear here after finishing. The sample library resets on reload; actual history follows your retention settings.</p>${this.importButton()}</div>`;
  }
  render() {
    this.root.innerHTML = this.route === 'home' ? this.home() : this.route === 'settings' ? this.settings() : this.history();
    this.root.scrollTop = 0;
    this.update();
  }
  update() {
    TranscriptPanel.update(this.session);
    WaveformControl.update(this.session);
    SpeakerToggle.update(this.preferences);
    document.querySelector('#preview-state').value = this.session.phase;
  }
  notify(message) {
    const toast = document.querySelector('#toast');
    clearTimeout(this.toastTimer);
    toast.textContent = message;
    toast.classList.add('visible');
    this.toastTimer = setTimeout(() => toast.classList.remove('visible'), 3500);
  }
  openSheet(title, body) {
    this.sheet.innerHTML = `<div class="sheet-header"><h2 id="sheet-title">${title}</h2>${iconButton('close', 'Close dialog', 'close')}</div>${body}`;
    if (!this.sheet.open) this.sheet.showModal();
  }
  sheetAction(action, glyph, title, subtitle = '') {
    return `<button class="sheet-action" data-action="${action}">${icon(glyph)}<span>${title}${subtitle ? `<small>${subtitle}</small>` : ''}</span></button>`;
  }
  async copy(text) {
    try {
      await navigator.clipboard.writeText(text);
      this.sheet.close();
      this.notify('Transcript copied. Ready to paste.');
    } catch {
      this.openSheet('Copy transcript', '<p>Clipboard access is unavailable. Select and copy this sample text.</p><textarea readonly aria-label="Transcript to copy"></textarea>');
      const field = this.sheet.querySelector('textarea');
      field.value = text;
      field.select();
    }
  }
  download(text) {
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'utterlane-sample-transcript.txt';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    this.notify('Sample transcript downloaded.');
  }
  act(action, target) {
    if (['home', 'settings', 'audio', 'transcripts'].includes(action)) {
      this.sheet.close();
      this.route = action;
      this.render();
      return;
    }
    switch (action) {
      case 'record':
        if (this.session.phase === 'recording') this.session.stop();
        else if (this.session.phase !== 'processing') this.session.start();
        break;
      case 'speakers':
        this.preferences.toggle();
        this.notify(`Speaker labels ${this.preferences.speakerLabels ? 'on' : 'off'} app-wide. Applies to new recordings.`);
        break;
      case 'copy': this.copy(this.session.text); break;
      case 'share':
        this.transferText = this.session.text;
        this.openSheet('Share transcript', `<p>In Android, Share opens the system share sheet. Try the sample text here.</p><p class="sample-text">${escapeHtml(this.transferText)}</p>${this.sheetAction('copy-transfer', 'copy', 'Copy text')}${this.sheetAction('download', 'download', 'Download as .txt')}${navigator.share ? this.sheetAction('native-share', 'share', 'Open browser share') : ''}`);
        break;
      case 'more':
        this.transferText = this.session.text;
        this.openSheet('Transcript actions', `<p>Your current sample transcript stays available while you explore.</p>${this.sheetAction('download', 'download', 'Export transcript', 'Download the sample as a text file')}${this.sheetAction('transcripts', 'document', 'Open transcript history')}${this.sheetAction('audio', 'audio', 'Open audio history')}`);
        break;
      case 'entry': {
        const entry = this.session.history.find(item => item.id === target.dataset.id);
        if (!entry) return;
        this.transferText = entry.text;
        this.openSheet(escapeHtml(entry.title), `<p>${this.route === 'audio' ? 'Sample recording detail · audio playback is not included in this design study.' : 'Saved sample transcript'}</p><p class="sample-text">${escapeHtml(entry.text)}</p>${this.sheetAction('copy-transfer', 'copy', 'Copy transcript')}${this.sheetAction('download', 'download', 'Export text')}`);
        break;
      }
      case 'copy-transfer': this.copy(this.transferText); break;
      case 'download': this.download(this.transferText); break;
      case 'native-share': navigator.share({ title: 'Utterlane sample transcript', text: this.transferText }).catch(error => { if (error.name !== 'AbortError') this.notify('Browser sharing is unavailable. You can copy or download the text.'); }); break;
      case 'close': this.sheet.close(); break;
      case 'appearance':
        this.setTheme(this.theme === 'dark' ? 'light' : 'dark');
        this.render();
        break;
      case 'import':
        this.openSheet('Transcribe an audio file', `<p>In Android, this opens the file picker. Your audio stays on your device.</p><p>For this design study, use a sample recording to preview the transcription flow.</p>${this.sheetAction('sample-import', 'audio', 'Use a sample recording', 'Simulates importing and transcribing an audio file')}`);
        break;
      case 'sample-import':
        if (['recording', 'processing'].includes(this.session.phase)) {
          this.sheet.close();
          this.notify('Finish the current recording before importing a sample.');
        } else {
          this.sheet.close();
          this.route = 'home';
          this.session.preview('processing');
          this.render();
        }
        break;
      case 'models': this.openSheet('Speech recognition', '<p>This destination opens the existing model and language settings in Android. Recording can begin while the selected model loads in the background.</p>'); break;
      case 'dictionary': this.openSheet('Word corrections', '<p>This destination opens the existing correction dictionary in Android. The same replacements apply to home-screen transcripts.</p>'); break;
      case 'shortcuts': this.openSheet('Voice input shortcuts', '<p>The existing keyboard microphone, accessibility control and floating microphone setup remain available here. The home recorder can be used independently of those shortcuts.</p>'); break;
      case 'retention': this.openSheet('History & retention', '<p>The Android settings keep separate controls for audio and transcript history, retention and deletion. The sample library in this prototype is held only in memory.</p>'); break;
    }
  }
}

new PreviewApp();
