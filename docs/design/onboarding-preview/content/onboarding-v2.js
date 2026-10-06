/* Design-only interaction layer. No browser recording, network transfer,
   clipboard write, or Android permission is performed by this study. */
'use strict';

class OnboardingPreview {
  static models = [
    { id: 'ultra-q8', name: 'Parakeet Ultra Q8', size: 674, title: 'Fuller precision', description: 'Our default for phones with more memory. A larger download, with less quantization.', meta: 'Q8 · Higher memory use' },
    { id: 'ultra-q4', name: 'Parakeet Ultra Q4', size: 402, title: 'A lighter balance', description: 'Ultra in a smaller package. Lower precision in exchange for a lighter model.', meta: 'Q4 · Smaller Ultra model' },
    { id: 'redux', name: 'Parakeet Redux', size: 159, title: 'The smallest option', description: 'Native ternary model for limited memory. Prioritizes size; speed and accuracy can vary.', meta: 'Ternary · Lowest model footprint' },
    { id: 'parakeet-v3', name: 'Parakeet v3', size: 670, title: 'The original', description: 'Original NVIDIA model with the ONNX engine. A familiar baseline, with a larger footprint.', meta: 'ONNX INT8 · Vanilla option' },
  ];

  // Stable IDs separate the flow from screen presentation. The native proposal
  // uses the same principle, with localized resources and app-owned model data.
  static steps = [
    { id: 'welcome', label: 'Welcome', phase: 0, title: 'Comfort on every screen.', note: 'A compact Appearance selector stays in the top-right header of every onboarding page.', points: ['Choose System, Light, or Dark at any step.', 'System follows the device’s appearance preference.', 'The selection applies immediately and stays with you through setup.'], renderer: 'welcome' },
    { id: 'uses', label: 'Everyday uses', phase: 0, title: 'Give speech a grounded advantage.', note: 'The comparison uses published mobile-typing and natural-conversation studies. Source details explain the different tasks and counting methods.', points: ['36.2 WPM typing; 164 WPM turn-wise conversation.', 'About 4.5× the pace across these English-language studies, not an app benchmark.', 'Single-line profile, reused voice-note artwork, and two connected head-and-shoulder figures.'], renderer: 'uses' },
    { id: 'models', label: 'Choose a model', phase: 1, title: 'A recommendation, not a lock-in.', note: 'Change the simulated RAM above to see your exact recommendation rules. Every model remains selectable.', points: ['Up to and including 1 GB → native ternary Redux.', 'Above 1 GB, up to and including 2 GB → Ultra Q4.', 'Above 2 GB → Ultra Q8.', 'Filled cards, no decorative borders. Selection and recommendation are separate states.'], renderer: 'models' },
    { id: 'download', label: 'Model download', phase: 1, title: 'Make the waiting understandable.', note: 'Download size, progress, and the selected model stay visible. Use the download control above to inspect an interrupted transfer.', points: ['The preview simulates progress in a few seconds.', 'Retry and change-model routes are explicit.', 'In the app, ready means installed and verified, not merely 100% received.'], renderer: 'download' },
    { id: 'microphone', label: 'Microphone', phase: 1, title: 'Ask with a reason.', note: 'Microphone access is tied to dictation. People who only share audio can skip it.', points: ['The OS prompt follows an explicit tap.', 'A denial is not a dead end.', 'Check the permission again when returning to the app.'], renderer: 'microphone' },
    { id: 'input', label: 'Your shortcuts', phase: 1, title: 'Meet people where they type.', note: 'Explain the three existing input routes. None is required for the in-app try-it screen.', points: ['Keyboard integration, floating mic, accessibility.', 'Show only the relevant Android setup steps.', 'Return to this same card after visiting Settings.'], renderer: 'input' },
    { id: 'folders', label: 'Watch a folder', phase: 1, title: 'Extra access is an extra choice.', note: 'Folder monitoring starts off. Sharing a voice note needs no broad audio-library permission.', points: ['Choose a folder before enabling monitoring.', 'Explain audio access and notifications in context.', 'Actual storage/provider support must be validated on Android.'], renderer: 'folders' },
    { id: 'speakers', label: 'Speaker labels', phase: 1, title: 'Describe the benefit, not the jargon.', note: '“Tell voices apart” introduces diarization with two simple speech bubbles and an explicit download size.', points: ['Off by default; no surprise download.', 'Automatic speaker count is the simple initial choice.', 'Labels distinguish voices; they do not identify people.'], renderer: 'speakers' },
    { id: 'speaker-download', label: 'Speaker model', phase: 1, title: 'A conditional, reusable step.', note: 'This page appears in the journey only when speaker labels are chosen. It shares the speech-model download layout.', points: ['107 MB from the existing model catalog.', 'Enable only after the model is ready.', 'Skip keeps speech recognition available.'], renderer: 'speakerDownload' },
    { id: 'try-voice', label: 'Try your voice', phase: 2, title: 'A first success, right here.', note: 'A real native text field and a recording action will connect to the existing capture pipeline. No keyboard setup is needed.', points: ['Tap the mic, then tap again to see the simulated result.', 'The real app requests mic access if still needed.', 'Keep partial text and show a useful retry on failure.'], renderer: 'tryVoice' },
    { id: 'try-file', label: 'Try sharing audio', phase: 2, title: 'Teach a transferable action.', note: 'Try the sample button to see a simulated chooser and the transcription result, then return to onboarding.', points: ['The real app will use Android’s share sheet.', 'Bundle a short, verified reusable recording and attribution.', 'Both try-it screens have their own Skip action.'], renderer: 'tryFile' },
    { id: 'finish', label: 'Ready to go', phase: 2, title: 'Your choices, with room to breathe.', note: 'Every setting occupies its own full-width row. Labels sit above values, and the summary scrolls vertically within the phone.', points: ['Includes appearance, model, permissions, shortcuts, monitoring, and speaker labels.', 'No side-by-side summary cells or horizontal scrolling.', 'The final action stays within reach while the content scrolls.'], renderer: 'finish' },
  ];

  constructor() {
    this.modelById = new Map(OnboardingPreview.models.map(model => [model.id, model]));
    this.stepById = new Map(OnboardingPreview.steps.map((step, index) => [step.id, { ...step, index }]));
    this.current = 'welcome';
    this.ram = 8;
    this.model = 'ultra-q8';
    this.explicitModel = false;
    this.installedModel = null;
    this.downloadState = 'downloading';
    this.progress = 64;
    this.mic = false;
    this.shortcuts = new Set();
    this.folderWanted = false;
    this.folder = false;
    this.audioAccess = false;
    this.notifications = false;
    this.monitorEnabled = false;
    this.speakersWanted = false;
    this.speakersReady = false;
    this.recording = false;
    this.transcript = '';
    this.fileTried = false;
    this.phone = document.querySelector('#phone-app');
    this.device = document.querySelector('#device');
    this.nav = document.querySelector('#screen-nav');
    this.systemTheme = matchMedia('(prefers-color-scheme: dark)');
    this.appearance = 'system';
    this.modal = null;
    this.setAppearance(this.appearance);
    this.bind();
    this.fitPreview();
    this.navigate(location.hash.slice(1) || 'welcome', false);
  }

  icon(name, extra = '') { return `<svg class="icon ${extra}" aria-hidden="true"><use href="#i-${name}"/></svg>`; }
  escape(value) { return String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]); }
  recommended() { return this.ram <= 1 ? 'redux' : this.ram <= 2 ? 'ultra-q4' : 'ultra-q8'; }
  wave(count = 19) { return `<div class="wave-bars">${Array.from({ length: count }, (_, index) => `<i style="--bar:${9 + Math.round(Math.abs(Math.sin(index * 1.83)) * 28)}px"></i>`).join('')}</div>`; }
  title(kicker, heading, description, optional = false) { return `<div class="kicker ${optional ? 'optional' : ''}">${kicker}</div><h2 id="screen-title" tabindex="-1">${heading}</h2><p class="intro">${description}</p>`; }
  button(label, action, style = 'primary-button', disabled = false) { return `<button type="button" class="${style}" data-action="${action}" ${disabled ? 'disabled' : ''}>${label}</button>`; }
  nextButton(label = 'Continue', action = 'next') { return this.button(`${label} ${this.icon('arrow')}`, action); }
  skip(label = 'Skip for now') { return this.button(label, 'skip', 'text-button'); }
  note(icon, text) { return `<p class="inline-note">${this.icon(icon)}<span>${text}</span></p>`; }
  toggle(label, hint, key, enabled) { return `<button type="button" class="switch-row" role="switch" aria-checked="${enabled}" data-action="${key}" aria-label="${label}"><span><strong>${label}</strong><small>${hint}</small></span><span class="switch-track" aria-hidden="true"></span></button>`; }

  appearanceSelector() {
    return `<label class="onboarding-appearance" for="onboarding-appearance"><span>Appearance</span><select id="onboarding-appearance">${['system', 'light', 'dark'].map(mode => `<option value="${mode}" ${mode === this.appearance ? 'selected' : ''}>${mode[0].toUpperCase() + mode.slice(1)}</option>`).join('')}</select></label>`;
  }

  setAppearance(mode) {
    // The in-app control and review control share one preference. System is a
    // live preference, not a one-time copy of the current light/dark setting.
    this.appearance = mode;
    this.device.dataset.theme = mode === 'system' ? (this.systemTheme.matches ? 'dark' : 'light') : mode;
    document.querySelector('#theme').value = mode;
    const selector = document.querySelector('#onboarding-appearance');
    const restoreFocus = selector && document.activeElement === selector;
    if (selector) selector.value = mode;
    if (this.current === 'finish') {
      this.render(true);
      // Refreshing the summary must not lose keyboard focus on its own selector.
      if (restoreFocus) this.phone.querySelector('#onboarding-appearance').focus({ preventScroll: true });
    }
  }

  bind() {
    document.addEventListener('click', event => {
      const link = event.target.closest('[data-screen]');
      if (link) { this.navigate(link.dataset.screen); return; }
      const model = event.target.closest('[data-model]');
      if (model) { this.model = model.dataset.model; this.explicitModel = true; this.render(true); return; }
      const action = event.target.closest('[data-action]');
      if (action && !action.disabled) this.act(action.dataset.action);
    });
    document.querySelector('#theme').addEventListener('change', event => this.setAppearance(event.target.value));
    this.phone.addEventListener('change', event => {
      if (event.target.id === 'onboarding-appearance') this.setAppearance(event.target.value);
    });
    this.systemTheme.addEventListener('change', () => {
      if (this.appearance === 'system') this.setAppearance('system');
    });
    document.querySelector('#width').addEventListener('change', event => { this.device.style.setProperty('--width', `${event.target.value}px`); });
    document.querySelector('#text-size').addEventListener('change', event => {
      this.device.style.setProperty('--scale', event.target.value);
      this.device.dataset.largeText = Number(event.target.value) >= 1.3;
    });
    document.querySelector('#ram').addEventListener('change', event => {
      this.ram = Number(event.target.value);
      if (!this.explicitModel) this.model = this.recommended();
      this.render(true);
    });
    document.querySelector('#download-state').addEventListener('change', event => {
      this.downloadState = event.target.value;
      this.progress = this.downloadState === 'ready' ? 100 : 64;
      this.markDownloaded();
      this.render(true);
      this.startProgress();
    });
    this.phone.addEventListener('input', event => { if (event.target.id === 'transcript') this.transcript = event.target.value; });
    window.addEventListener('hashchange', () => { if (location.hash.slice(1) !== this.current) this.navigate(location.hash.slice(1), false); });
    window.addEventListener('resize', () => this.fitPreview());
    document.addEventListener('keydown', event => { if (event.key === 'Escape' && this.modal) this.closeDialog(); });
  }

  fitPreview() {
    // Scale the review canvas, not the proposed Android layout, so the whole
    // phone and its bottom actions fit a laptop display. Narrow browsers keep
    // a full-size preview and ordinary page scrolling for legible touch review.
    this.device.style.zoom = innerWidth > 580 ? Math.min(1, Math.max(.65, (innerHeight - 215) / 806)) : 1;
  }

  navigate(id, focus = true) {
    const resolved = this.stepById.has(id) ? id : 'welcome';
    clearInterval(this.progressTimer);
    this.recording = false;
    this.closeDialog(false);
    this.current = resolved;
    // replaceState avoids filling browser history with review selections.
    if (location.hash.slice(1) !== resolved) history.replaceState(null, '', `#${resolved}`);
    this.render();
    this.markDownloaded();
    this.startProgress();
    if (focus) this.phone.querySelector('#screen-title')?.focus({ preventScroll: true });
  }

  render(keepScroll = false) {
    const scroll = keepScroll ? this.phone.querySelector('.screen-body')?.scrollTop || 0 : 0;
    const focusedAction = this.phone.contains(document.activeElement) ? document.activeElement.dataset.action : null;
    const step = this.stepById.get(this.current);
    const view = this[step.renderer]();
    const phase = ['Discover', 'Make it yours', 'Try it out'][step.phase];
    this.phone.innerHTML = `<header class="app-nav">${step.index ? `<div class="app-nav-leading"><button class="nav-back" data-action="back" aria-label="Previous step">${this.icon('back')}</button><span class="phase-label">${phase}</span></div>` : '<span class="nav-brand">Utterlane</span>'}${this.appearanceSelector()}</header><div class="phase-progress" aria-label="${phase}, stage ${step.phase + 1} of 3">${[0, 1, 2].map(index => `<span class="${index < step.phase ? 'past' : index === step.phase ? 'active' : ''}"></span>`).join('')}</div><section class="screen-body ${view.className || ''}" aria-labelledby="screen-title" tabindex="0">${view.body}</section><footer class="app-footer">${view.footer}</footer>`;
    this.phone.querySelector('.screen-body').scrollTop = scroll;
    if (focusedAction) this.phone.querySelector(`[data-action="${focusedAction}"]`)?.focus({ preventScroll: true });
    let previousPhase = -1;
    this.nav.innerHTML = OnboardingPreview.steps.map((item, index) => {
      const heading = item.phase !== previousPhase ? `<p class="nav-group">${['01 / Discover', '02 / Set up', '03 / Try it'][item.phase]}</p>` : '';
      previousPhase = item.phase;
      return `${heading}<button class="screen-link" data-screen="${item.id}" ${item.id === this.current ? 'aria-current="step"' : ''}><span class="num">${String(index + 1).padStart(2, '0')}</span>${item.label}</button>`;
    }).join('');
    document.querySelector('#stage-name').textContent = `${String(step.index + 1).padStart(2, '0')} — ${step.label}`;
    document.querySelector('#design-note').innerHTML = `<span class="note-tag">DESIGN INTENT</span><h2>${step.title}</h2><p>${step.note}</p><ul>${step.points.map(point => `<li>${point}</li>`).join('')}</ul>`;
    document.querySelector('#download-state').value = this.downloadState;
  }

  welcome() {
    return { body: `<div class="hero-illustration" aria-hidden="true"><div class="hero-orbit"></div><div class="art-phone"><div class="art-chat">Coffee after the walk?</div><div class="art-chat reply">Meet you at the usual place.</div><div class="art-capture">${this.wave(16)}</div></div><div class="art-seal">${this.icon('shield')}</div><div class="art-float"><small>${this.icon('check')} YOUR WORDS, READY</small><p>“Meet you at the<br>usual place.”</p></div></div><div class="kicker">YOUR VOICE. YOUR PHONE.</div><h2 id="screen-title" class="hero-heading" tabindex="-1">Less typing.<br><em>More freedom.</em></h2><p class="intro hero-description">Turn speech into text, right on your phone. For messages, ideas, and everything worth keeping.</p><div class="promise-row"><span>${this.icon('check')} Offline after setup</span><span>${this.icon('check')} Free & open source</span></div>`, footer: `${this.nextButton('Let’s begin')}<p class="footer-note">No account. No subscription. Just your words.</p>` };
  }

  useIllustration(kind) {
    // The original dictation artwork becomes the voice-note illustration;
    // the new symbols keep their shapes local and independently exportable.
    const image = kind === 'file'
      ? `<div class="mini-page"><i></i><i></i><i></i></div><span class="mini-disc">${this.icon('mic')}</span>`
      : `<svg class="use-illustration" aria-hidden="true"><use href="#art-${kind}"/></svg>`;
    return `<div class="use-art" aria-hidden="true">${image}</div>`;
  }

  uses() {
    const examples = [
      ['speaking', 'Say it instead of typing.', 'Write messages and notes with your keyboard mic or a floating button.'],
      ['file', 'Read a voice note.', 'Share a recording to Utterlane. Get the words, even when you cannot listen.'],
      ['connected-people', 'Follow the conversation.', 'Record a meeting. Add optional speaker labels to tell voices apart.'],
    ];
    return {
      body: `${this.title('MADE FOR THE EVERYDAY', 'Your voice has<br><em>a head start.</em>', 'Speak naturally. Utterlane turns speech into text on your phone.')}
        <section class="speed-comparison" aria-label="Comparison of published speech and mobile typing rates">
          <div class="speed-lead"><strong>≈4.5×</strong><span>the pace of phone typing<br>in these studies</span></div>
          <div class="speed-figures"><span><b>36</b> WPM · mobile typing</span><span><b>164</b> WPM · conversation</span></div>
          <div class="speed-source"><span>English-language studies</span>${this.button('About these numbers', 'speed-sources', 'text-button blue')}</div>
        </section>
        ${examples.map(([art, heading, copy]) => `<article class="use-card">${this.useIllustration(art)}<div><h3>${heading}</h3><p>${copy}</p></div></article>`).join('')}
        ${this.note('shield', 'Recognition stays on your device. After model setup, it works without a connection.')}`,
      footer: `${this.nextButton('Set up my phone')}<p class="footer-note">Choose the features that work for you.</p>`,
    };
  }

  models() {
    const recommendation = this.recommended();
    const cards = OnboardingPreview.models.map(model => `<button type="button" class="model-card ${model.id === this.model ? 'selected' : ''}" data-model="${model.id}" aria-pressed="${model.id === this.model}" aria-label="${model.name}, ${model.size} MB${model.id === recommendation ? ', recommended for this phone' : ''}">${model.id === recommendation ? '<span class="recommended">Recommended for this phone</span>' : ''}<span class="model-radio" aria-hidden="true"></span><h3>${model.name}</h3><p>${model.description}</p><span class="model-meta">${model.size} MB download · ${model.title}</span></button>`).join('');
    return { className: 'model-body', body: `${this.title('A LOCAL SPEECH MODEL', 'Find your fit.', 'A model turns audio into words. Download it once; use it offline.')}<p class="device-note">${this.ram < 1 ? '768 MB' : `${this.ram} GB`} device RAM · You can change models later.</p><div class="model-list" aria-label="Speech model choices">${cards}</div><p class="device-note">Recommendation is based on total RAM. Actual memory use also depends on your phone and what is running.</p>`, footer: `${this.nextButton(`Download · ${this.modelById.get(this.model).size} MB`, 'start-download')}${this.button('I already have model files', 'import-model', 'text-button blue')}` };
  }

  download(isSpeaker = false) {
    const model = isSpeaker ? { name: 'Nemotron speaker labels', size: 107 } : this.modelById.get(this.model);
    const ready = this.downloadState === 'ready';
    const error = this.downloadState === 'error';
    const heading = ready ? 'Ready for your words.' : error ? 'Let’s try that again.' : 'A little download.<br>A lot of possibility.';
    const description = ready ? 'Your model is installed. Recognition can now happen right on your phone.' : error ? 'The connection was interrupted. Reconnect and retry, or choose another model.' : 'Your phone is getting its own speech tools. This is the part that needs a connection.';
    return { body: `<div class="download-illustration" aria-hidden="true"><span class="download-ring"></span><span class="download-core">${this.icon(ready ? 'check' : 'chip')}</span><span class="download-stamp">${this.icon(ready ? 'check' : 'download')}</span></div>${this.title(isSpeaker ? 'OPTIONAL · SPEAKER MODEL' : 'YOUR OFFLINE TOOLKIT', isSpeaker && ready ? 'Voices, with labels.' : heading, isSpeaker && ready ? 'The speaker model is installed. Speaker labels will now be enabled.' : description)}<div class="download-card"><h3>${model.name}</h3><div class="download-stats"><span id="download-bytes">${ready ? `${model.size} MB · Installed` : `${Math.round(model.size * this.progress / 100)} of ${model.size} MB`}</span><strong id="download-percent">${ready ? 'Ready' : `${this.progress}%`}</strong></div><div class="progress-track" role="progressbar" aria-label="Simulated model download" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${ready ? 100 : this.progress}"><span style="--progress:${ready ? 100 : this.progress}%"></span></div><p class="download-detail ${error ? 'error' : ''}">${ready ? 'Downloaded and verified.' : error ? 'Download interrupted. Your setup choices are kept.' : 'Keep Utterlane open while downloading.'}</p></div>${this.note('shield', 'Only the model is downloaded. Your speech is never sent to a recognition server.')}`, footer: `${ready ? this.nextButton('Continue', isSpeaker ? 'finish-speaker-download' : 'next') : error ? this.nextButton('Retry download', 'retry-download') : this.button('Downloading…', 'next', 'primary-button', true)}${isSpeaker ? this.skip('Skip speaker labels') : this.button(ready ? 'Choose a different model' : 'Cancel and change model', 'change-model', 'text-button')}` };
  }

  speakerDownload() { return this.download(true); }

  microphone() {
    return { body: `<div class="large-icon-art" aria-hidden="true">${this.icon('mic')}<span class="orbit-dot"></span></div>${this.title('WHEN YOU WANT TO SPEAK', 'Let your phone<br>hear you.', 'Allow microphone access to dictate and try your first transcription.')}<div class="explanation-row">${this.icon('mic')}<div><h3>You start the recording.</h3><p>The microphone is used when you choose to record.</p></div></div><div class="explanation-row">${this.icon('shield')}<div><h3>Your phone does the work.</h3><p>Audio is processed locally, without uploading your speech.</p></div></div>${this.note('file', 'Only here for audio files? You can share recordings without microphone access.')}`, footer: `${this.mic ? this.nextButton('Microphone allowed · Continue') : this.button(`${this.icon('mic')} Allow microphone`, 'allow-mic')}${this.skip('Not now')}` };
  }

  input() {
    const options = [
      ['keyboard', 'Your keyboard mic', 'Use Utterlane from a compatible keyboard, such as HeliBoard.', 'Enable voice input'],
      ['float', 'A floating microphone', 'Keep a mic above your other apps. Needs overlay and accessibility access.', 'Set up floating mic'],
      ['access', 'Accessibility shortcut', 'Put recognized text in the focused field using Android’s shortcut.', 'Set up shortcut'],
    ];
    return { body: `${this.title('OPTIONAL · EVERYDAY SHORTCUTS', 'Your words.<br>Your way in.', 'Pick a shortcut, or leave this for later. You can try dictation here without one.', true)}${options.map(([id, title, description, action]) => `<article class="setup-card"><div class="setup-card-header">${this.icon(id)}<div><h3>${title}</h3><p>${description}</p></div></div>${this.shortcuts.has(id) ? `<div class="enabled">${this.icon('check')} Enabled</div>` : this.button(`${action} ${this.icon('arrow')}`, `shortcut-${id}`, 'text-button blue')}</article>`).join('')}`, footer: `${this.nextButton()}${this.skip('Set up shortcuts later')}` };
  }

  folders() {
    let setup = '';
    if (this.folderWanted) {
      setup = `<article class="setup-card"><div class="setup-card-header">${this.icon('folder')}<div><h3>${this.folder ? 'Downloads / Voice notes' : 'Choose an audio folder'}</h3><p>${this.folder ? 'Watch for new recordings in this folder.' : 'Select where new recordings will arrive.'}</p></div></div>${this.button(this.folder ? 'Change folder' : 'Choose folder', 'choose-folder', 'text-button blue')}</article><article class="setup-card"><div class="setup-card-header">${this.icon('file')}<div><h3>Audio-file access</h3><p>Needed to read recordings for folder monitoring.</p></div></div>${this.button(this.audioAccess ? 'Allowed ✓' : 'Allow audio access', 'allow-audio', 'text-button blue', this.audioAccess)}</article><article class="setup-card"><div class="setup-card-header">${this.icon('bell')}<div><h3>New-recording notifications</h3><p>See when new audio is ready to transcribe.</p></div></div>${this.button(this.notifications ? 'Allowed ✓' : 'Allow notifications', 'allow-notifications', 'text-button blue', this.notifications)}</article>`;
    }
    return { body: `${!this.folderWanted ? `<div class="folder-art" aria-hidden="true">${this.icon('folder')}<span class="folder-file">${this.icon('file')}</span></div>` : ''}${this.title('OPTIONAL · AUDIO FILES', 'New recording.<br>Ready to read.', 'Watch a selected folder for new audio and let Utterlane start transcription.', true)}${this.toggle('Watch a folder', 'You choose where to look.', 'toggle-folder', this.folderWanted)}${setup}${this.note('shield', 'Sharing one audio file does not require this access. Folder monitoring is entirely optional.')}`, footer: `${this.nextButton(this.folderWanted && !this.monitorEnabled ? 'Enable monitoring' : 'Continue', this.folderWanted ? 'enable-monitor' : 'next')}${this.skip('Skip folder monitoring')}` };
  }

  speakers() {
    return { body: `${this.title('OPTIONAL · SPEAKER LABELS', 'Tell voices apart.', 'Make conversations easier to follow with labels for each speaker.', true)}<div class="speaker-art" aria-hidden="true"><div class="speaker-bubble"><b>Speaker 1</b><p>Shall we meet tomorrow?</p></div><div class="speaker-bubble"><b>Speaker 2</b><p>Yes, that works for me.</p></div></div>${this.toggle('Add speaker labels', 'Useful for meetings and interviews.', 'toggle-speakers', this.speakersWanted)}<div class="setup-card"><div class="setup-card-header">${this.icon('download')}<div><h3>One extra model · 107 MB</h3><p>Downloaded only if you choose this feature. Uses additional memory and processing.</p></div></div></div>${this.note('people', 'Speaker count is detected automatically. Labels tell voices apart; they do not identify people.')}`, footer: `${this.nextButton(this.speakersWanted ? 'Download speaker model' : 'Continue', this.speakersWanted ? 'start-speaker-download' : 'next')}${this.skip('Skip speaker labels')}` };
  }

  tryVoice() {
    return { body: `${this.title('TRY IT · OPTIONAL', 'Something to say?', 'Tap the mic, say a sentence, then tap again to finish. Your words will appear here.', true)}<span class="tag">${this.icon('chip')} ${this.modelById.get(this.model).name}</span><label class="transcript-label" for="transcript"><span>YOUR WORDS</span><span>${this.transcript ? 'Editable' : 'Ready when you are'}</span></label><textarea id="transcript" class="transcript" placeholder="Try: ‘Less typing. More freedom.’">${this.escape(this.transcript)}</textarea><div class="record-area"><button class="record-button ${this.recording ? 'recording' : ''}" data-action="record" aria-label="${this.recording ? 'Stop simulated recording' : 'Start simulated recording'}">${this.icon(this.recording ? 'stop' : 'mic')}</button><p class="record-caption">${this.recording ? 'Listening… tap to finish' : this.transcript ? 'Try another sentence' : 'Tap to speak'}</p><p class="record-detail">${this.recording ? 'Preview only · no microphone is active' : 'No keyboard setup needed.'}</p></div>${this.note('mic', '<strong>Tip:</strong> Enunciate clearly and speak close to the microphone for better accuracy, especially in noisy places.')}${this.note('shield', 'Recognition stays on your phone. You control recording history in Settings.')}`, footer: `${this.nextButton()}${this.skip('Skip this test')}` };
  }

  tryFile() {
    return { body: `${this.title('TRY IT · OPTIONAL', 'Voice note in.<br>Words out.', 'In another app, share an audio recording to Utterlane. Let’s try it with a sample.', true)}<article class="audio-sample"><div class="sample-header">${this.icon('file')}<div><h3>A short audio story</h3><small>Sample recording · 15 seconds</small></div></div><div class="sample-wave" aria-hidden="true">${this.wave(30)}</div><div class="sample-foot"><span>Included with the app</span><span>00:15</span></div></article><ol class="numbered-steps"><li><span>1</span>Tap “Share sample audio” below.</li><li><span>2</span>Choose Utterlane in the share sheet.</li><li><span>3</span>Read the transcription. That is it.</li></ol>${this.button(`${this.icon('share')} Share sample audio`, 'share-sample', 'secondary-button')}${this.fileTried ? this.note('check', 'That is the same flow you can use from your messaging or recording app.') : this.note('file', 'Sharing grants access to just that file. No audio-library permission is needed.')}`, footer: `${this.nextButton('Finish setup')}${this.skip('Skip this test')}` };
  }

  finish() {
    const model = this.modelById.get(this.model);
    const shortcutNames = { keyboard: 'Keyboard mic', float: 'Floating microphone', access: 'Accessibility shortcut' };
    const speakerLabelsEnabled = this.speakersWanted && this.speakersReady;
    const items = [
      ['Speech model', model.name, this.installedModel === this.model ? 'Installed · ready offline' : 'Selected · finish the download in Settings'],
      ['Appearance', { system: 'System', light: 'Light', dark: 'Dark' }[this.appearance], this.appearance === 'system' ? 'Follows your device’s appearance' : 'Your choice throughout Utterlane'],
      ['Microphone', this.mic ? 'Allowed' : 'Not enabled', this.mic ? 'Ready when you choose to record' : 'You can still transcribe shared audio files'],
      ['Input shortcuts', this.shortcuts.size ? [...this.shortcuts].map(id => shortcutNames[id]).join(', ') : 'Set up later', 'You can always dictate in the app'],
      ['Folder monitoring', this.monitorEnabled ? 'Enabled' : 'Off', this.monitorEnabled ? 'Downloads / Voice notes' : 'New files are not monitored'],
      ['Audio-file access', this.audioAccess ? 'Allowed' : 'Not granted', 'Only needed for folder monitoring'],
      ['Notifications', this.notifications ? 'Allowed' : 'Not enabled', 'Manage notification access in Android Settings'],
      ['Speaker labels', speakerLabelsEnabled ? 'Enabled' : 'Off', speakerLabelsEnabled ? 'Speaker model installed · automatic speaker count' : 'You can add the speaker model later'],
    ];
    return {
      className: 'completion-body',
      body: `<div class="success-art" aria-hidden="true"><span>${this.icon('check')}</span><i></i><i></i></div>
        ${this.title('WELCOME TO UTTERLANE', 'Your next thought<br><em>is all it takes.</em>', '<span class="finish-scenarios">A message. An idea. A conversation. Agentic instructions.</span><span class="finish-promise">Your thoughts into words. Anywhere. Right from your pocket.</span>')}
        <h3 class="setup-summary-heading">Your setup at a glance</h3>
        <dl class="setup-summary">${items.map(([label, value, detail]) => `<div class="setup-summary-item"><dt>${label}</dt><dd><strong>${this.escape(value)}</strong><small>${detail}</small></dd></div>`).join('')}</dl>
        <p class="device-note">You can revisit this guide and change every choice in Settings.</p>`,
      footer: `${this.nextButton('Start using Utterlane', 'finish')}<p class="footer-note">Your voice. Your phone. Your words.</p>`,
    };
  }

  act(action) {
    if (action === 'speed-sources') { this.speedSources(); return; }
    if (action === 'next' || action === 'skip') {
      if (this.current === 'folders' && action === 'skip') { this.folderWanted = false; this.monitorEnabled = false; }
      if (['speakers', 'speaker-download'].includes(this.current)) {
        if (action === 'skip') { this.speakersWanted = false; this.speakersReady = false; }
        this.navigate('try-voice');
      } else this.navigate(OnboardingPreview.steps[Math.min(this.stepById.get(this.current).index + 1, OnboardingPreview.steps.length - 1)].id);
      return;
    }
    if (action === 'back') {
      const previous = this.current === 'try-voice' && !this.speakersWanted ? 'speakers' : OnboardingPreview.steps[Math.max(0, this.stepById.get(this.current).index - 1)].id;
      this.navigate(previous); return;
    }
    if (action === 'start-download' || action === 'start-speaker-download') {
      this.downloadState = 'downloading'; this.progress = 12;
      if (action === 'start-speaker-download') this.speakersWanted = true;
      this.navigate(action === 'start-download' ? 'download' : 'speaker-download'); return;
    }
    if (action === 'change-model') { this.navigate('models'); return; }
    if (action === 'retry-download') { this.downloadState = 'downloading'; this.progress = 12; this.render(true); this.startProgress(); return; }
    if (action === 'finish-speaker-download') { this.speakersReady = true; this.navigate('try-voice'); return; }
    if (action === 'import-model') { this.dialog('folder', 'Use your model files.', 'Android’s folder picker will let you select an existing compatible model. Import and verification will use the app’s existing model manager.', 'Preview successful import', 'import-confirm'); return; }
    if (action === 'import-confirm') { this.installedModel = this.model; this.closeDialog(false); this.navigate('microphone'); return; }
    if (action === 'allow-mic') { this.micDialog(); return; }
    if (action === 'mic-confirm') { this.mic = true; this.closeDialog(false); if (this.current === 'microphone') this.navigate('input'); else { this.recording = true; this.render(true); } return; }
    // Confirmation shares the shortcut prefix, so resolve it before the opener.
    if (action.startsWith('shortcut-confirm-')) { this.shortcuts.add(action.slice(17)); this.closeDialog(false); this.render(true); return; }
    if (action.startsWith('shortcut-')) { this.shortcutDialog(action.slice(9)); return; }
    if (action === 'toggle-folder') { this.folderWanted = !this.folderWanted; if (!this.folderWanted) this.monitorEnabled = false; this.render(true); return; }
    if (action === 'choose-folder') { this.dialog('folder', 'Choose an audio folder.', 'Android will open its folder picker. This preview uses a Voice notes folder in Downloads.', 'Use Voice notes', 'folder-confirm'); return; }
    if (action === 'folder-confirm') { this.folder = true; this.closeDialog(false); this.render(true); return; }
    if (action === 'allow-audio') { this.dialog('file', 'Allow access to audio?', 'Utterlane needs audio access for the selected folder-monitoring feature. Android controls the exact permission wording for your version.', 'Allow in preview', 'audio-confirm'); return; }
    if (action === 'audio-confirm') { this.audioAccess = true; this.closeDialog(false); this.render(true); return; }
    if (action === 'allow-notifications') { this.dialog('bell', 'Keep up with new audio.', 'Allow notifications to see when new recordings are detected. You can change this in Android Settings.', 'Allow in preview', 'notifications-confirm'); return; }
    if (action === 'notifications-confirm') { this.notifications = true; this.closeDialog(false); this.render(true); return; }
    if (action === 'enable-monitor') {
      if (!this.folder || !this.audioAccess) { this.toast('Choose a folder and allow audio access, or skip monitoring.'); return; }
      this.monitorEnabled = true; this.navigate('speakers'); return;
    }
    if (action === 'toggle-speakers') { this.speakersWanted = !this.speakersWanted; this.render(true); return; }
    if (action === 'record') {
      if (!this.mic) { this.micDialog(); return; }
      this.recording = !this.recording;
      if (!this.recording) this.transcript = 'Less typing. More freedom.';
      this.render(true); return;
    }
    if (action === 'share-sample') { this.shareDialog(); return; }
    if (action === 'transcribe-sample') { this.fileTried = true; this.sampleResult(); return; }
    if (action === 'close-dialog') { this.closeDialog(); return; }
    if (action === 'copy-preview') { this.toast('Preview only: the real app will copy the transcript.'); return; }
    if (action === 'finish') { this.toast('Setup complete in this preview. The native app will now open Settings.'); }
  }

  markDownloaded() {
    if (this.downloadState !== 'ready') return;
    if (this.current === 'download') this.installedModel = this.model;
    if (this.current === 'speaker-download') this.speakersReady = true;
  }

  startProgress() {
    clearInterval(this.progressTimer);
    if (!['download', 'speaker-download'].includes(this.current) || this.downloadState !== 'downloading') return;
    const size = this.current === 'speaker-download' ? 107 : this.modelById.get(this.model).size;
    this.progressTimer = setInterval(() => {
      this.progress = Math.min(100, this.progress + 8);
      if (this.progress === 100) {
        clearInterval(this.progressTimer); this.downloadState = 'ready'; this.markDownloaded(); this.render(true);
        return;
      }
      this.phone.querySelector('#download-bytes').textContent = `${Math.round(size * this.progress / 100)} of ${size} MB`;
      this.phone.querySelector('#download-percent').textContent = `${this.progress}%`;
      this.phone.querySelector('.progress-track').setAttribute('aria-valuenow', this.progress);
      this.phone.querySelector('.progress-track span').style.setProperty('--progress', `${this.progress}%`);
    }, 500);
  }

  micDialog() { this.dialog('mic', 'Allow Utterlane to record audio?', 'The native app will show Android’s microphone permission prompt. This browser preview never opens your microphone.', 'Allow in preview', 'mic-confirm'); }

  speedSources() {
    this.openDialog(`<p class="dialog-kicker">The research behind the comparison</p><h3 id="dialog-title">Speech and typing, in context.</h3>
      <p><strong>36.2 WPM · mobile typing.</strong> Palin et al. (MobileHCI 2019): 37,370 volunteers copied English sentences on mobile devices. A typing word is five characters.</p>
      <a class="source-link" href="https://userinterfaces.aalto.fi/typing37k/" target="_blank" rel="noopener noreferrer">Read the typing study ↗</a>
      <p><strong>164 WPM · natural conversation.</strong> Yuan, Liberman & Cieri (Interspeech 2006): turn-wise rate in English telephone conversations. This measures human speech, not voice typing or transcription software.</p>
      <a class="source-link" href="https://www.isca-archive.org/interspeech_2006/yuan06_interspeech.html" target="_blank" rel="noopener noreferrer">Read the speech study ↗</a>
      <p>164 ÷ 36.2 ≈ 4.5. These are different studies and counting methods, not a guaranteed personal speed gain or an Utterlane benchmark. App speed and accuracy vary with model, device, and audio.</p>
      ${this.button('Back to everyday uses', 'close-dialog')}`);
  }

  shortcutDialog(id) {
    const options = {
      keyboard: ['keyboard', 'Enable voice input.', 'Android will open the input-method settings. Enable Utterlane, then use the mic in a compatible keyboard.'],
      float: ['float', 'A microphone above your apps.', 'The native flow will guide you through overlay permission and accessibility access. Accessibility reads the focused field to insert your recognized text.'],
      access: ['access', 'Put your words in the right place.', 'Utterlane uses Android Accessibility to read the focused text field and insert recognized text. The app will show its disclosure before opening Android Settings.'],
    };
    const [icon, title, text] = options[id] || [];
    if (icon) this.dialog(icon, title, text, 'Preview enabled', `shortcut-confirm-${id}`);
  }

  dialog(icon, title, description, confirmLabel, action) {
    this.openDialog(`${this.icon(icon)}<p class="dialog-kicker">System handoff · simulated</p><h3 id="dialog-title">${title}</h3><p>${description}</p>${this.button(confirmLabel, action)}${this.button('Not now', 'close-dialog', 'text-button')}`);
  }

  shareDialog() {
    this.openDialog(`<p class="dialog-kicker">Android share sheet · simulated</p><h3 id="dialog-title">Share audio with</h3><p>sample-recording.wav<br>Audio · included example</p><button class="share-target" data-action="transcribe-sample">${this.icon('mic')}<strong>Utterlane</strong>${this.icon('arrow')}</button>${this.button('Cancel', 'close-dialog', 'text-button')}`);
  }

  sampleResult() {
    this.openDialog(`${this.icon('file')}<p class="dialog-kicker">Utterlane · sample result</p><h3 id="dialog-title">Your audio, in words.</h3><div class="dialog-transcript">The train arrives at six. I will call when I am nearby.</div><p>Illustrative text for the design preview.</p>${this.button('Copy text', 'copy-preview', 'secondary-button')}${this.button('Back to setup', 'close-dialog')}`);
  }

  openDialog(content) {
    this.closeDialog(false);
    this.lastFocus = document.activeElement;
    this.modal = document.createElement('div');
    this.modal.className = 'dialog-scrim';
    this.modal.innerHTML = `<section class="dialog-card" role="dialog" aria-modal="true" aria-labelledby="dialog-title">${content}</section>`;
    this.device.append(this.modal);
    this.phone.inert = true;
    this.modal.querySelector('button')?.focus();
    this.modal.addEventListener('keydown', event => {
      if (event.key !== 'Tab') return;
      const controls = [...this.modal.querySelectorAll('button:not(:disabled), a[href]')];
      const first = controls[0], last = controls[controls.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    });
  }

  closeDialog(restoreFocus = true) {
    if (!this.modal) return;
    this.modal.remove(); this.modal = null;
    this.phone.inert = false;
    if (restoreFocus && this.lastFocus?.isConnected) this.lastFocus.focus({ preventScroll: true });
  }

  toast(message) {
    const toast = document.querySelector('#toast');
    clearTimeout(this.toastTimer);
    toast.textContent = message; toast.hidden = false;
    this.toastTimer = setTimeout(() => { toast.hidden = true; }, 4500);
  }
}

new OnboardingPreview();
