/* This is a local design study, not an adapter to the Android app. Actions change
   only this page's memory; even copy/share deliberately leave device data alone. */
"use strict";

class PreviewIcons {
  static paths = {
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    copy: '<rect x="8" y="8" width="12" height="13" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h3"/>',
    share: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.6 10.5 6.8-4M8.6 13.5l6.8 4"/>',
    play: '<path d="m8 4 12 8-12 8Z"/>',
    pause: '<rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/>',
    stop: '<rect x="5" y="5" width="14" height="14" rx="2"/>',
    retry: '<path d="M20 7v5h-5M19.5 12a8 8 0 1 0-2.3 5.7"/>',
    save: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3"/>',
    previous: '<path d="m14 6-6 6 6 6"/>',
    next: '<path d="m10 6 6 6-6 6"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    signal: '<path d="M3 20V15m6 5V11m6 9V7m6 13V3"/>',
    battery: '<rect x="2" y="6" width="18" height="12" rx="2"/><path d="M23 10v4M6 9v6m4-6v6m4-6v6"/>',
    // Material Icons PushPin paths match the app's Filled/Outlined.PushPin pair.
    pin: '<path fill="currentColor" d="M16 9V4h1V2H7v2h1v5c0 1.66-1.34 3-3 3v2h6v7l1 1 1-1v-7h6v-2c-1.66 0-3-1.34-3-3Zm-8 3c1.21-.91 2-2.37 2-4V4h4v4c0 1.63.79 3.09 2 4H8Z"/>',
    pinned: '<path fill="currentColor" d="M16 9V4h1V2H7v2h1v5c0 1.66-1.34 3-3 3v2h6v7l1 1 1-1v-7h6v-2c-1.66 0-3-1.34-3-3Z"/>',
  };

  static render(name) {
    return `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${this.paths[name]}</svg>`;
  }

  static button(action, label, icon = action, extra = "") {
    return `<button class="icon-button ${action}" data-action="${action}" aria-label="${label}" ${extra}>${this.render(icon)}</button>`;
  }
}

class TranscriptSample {
  static pages = [
    [
      "I took the long way home this morning. The streets were still quiet, and the bakery on the corner had just opened its doors. For once, I was not in a hurry to get anywhere.",
      "There is something useful about saying an idea out loud before trying to write it down. You notice where the thought is clear, where it wanders, and which part you actually want to remember.",
      "The idea for next week is simple: leave a little more room between things. A few minutes after a conversation to capture what mattered. A walk without a destination. Time to finish one thought before starting another.",
      "I would like to talk this through with the team on Monday. We do not need another long meeting. We need a short conversation about what is working, what feels unnecessarily complicated, and what we can make easier.",
      "One thing I want to keep is the habit of recording ideas while they are still fresh. They do not have to be polished. A rough sentence that captures the right thought is more useful than a perfect sentence that arrives too late.",
      "Afterwards, I can return to the transcript, find the part I was looking for, and share it. The rest can stay here until I need it. That is enough for today.",
    ],
    [
      "A second thought, before I forget: we should keep a little space for the unexpected. The best part of a conversation is often the question nobody planned to ask.",
      "When I read these notes later, I want them to sound like me. Not a summary of what I might have meant, but a record of what I actually said, with all the details that make it useful.",
      "For Monday, the first question is where we lose time. The second is what we can stop doing. The third is what people wish they could spend more attention on.",
      "I will send the relevant paragraph before the meeting, then keep the full transcript in my history. That way there is a short starting point and the original context is still easy to find.",
      "There is no need to resolve everything at once. Let us choose one change, try it for a week, and come back with something concrete to discuss.",
    ],
  ];

  static render(page) {
    return this.pages[page].map(text => `<p>${text}</p>`).join("");
  }
}

class DialogPreview {
  constructor(root, concept) {
    this.root = root;
    this.concept = concept;
    this.reset();
    this.root.addEventListener("click", event => this.onClick(event));
    this.root.addEventListener("input", event => {
      if (event.target.matches(".seek")) {
        this.position = Number(event.target.value);
        this.updatePlayback();
      }
    });
    this.root.addEventListener("keydown", event => this.onKeyDown(event));
  }

  reset(state = "ready", stats = false) {
    clearTimeout(this.toastTimer);
    this.state = state;
    this.showStats = stats;
    this.pinned = false;
    this.playing = false;
    this.playbackActive = false;
    this.position = 0;
    this.page = 0;
    this.menu = null;
    this.closed = false;
    this.render();
  }

  get running() { return this.state === "running"; }
  get noAudio() { return this.state === "text-only"; }

  transcriptActions() {
    const button = PreviewIcons.button.bind(PreviewIcons);
    return `<div class="transcript-actions" role="group" aria-label="Transcript actions">
      ${button("pin", this.pinned ? "Transcript kept forever" : "Keep transcript forever", this.pinned ? "pinned" : "pin", `aria-pressed="${this.pinned}" ${this.running ? "disabled" : ""}`)}
      ${button("copy", "Copy transcript")}${button("share", "Share transcript")}
    </div>`;
  }

  navigation() {
    const button = PreviewIcons.button.bind(PreviewIcons);
    return `<div class="page-navigation" role="group" aria-label="Transcript pages">
      ${button("previous", "Previous page", "previous", this.page === 0 ? "disabled" : "")}
      <span class="page-label">${this.page + 1} / 2</span>
      ${button("next", "Next page", "next", this.page === 1 ? "disabled" : "")}
    </div>`;
  }

  deleteButton() {
    const label = this.state === "temporary" ? "Discard" : this.noAudio ? "Delete transcript" : "Delete";
    return `<button class="delete" data-action="delete" aria-label="${this.noAudio ? "Delete transcript" : this.state === "temporary" ? "Discard temporary recording" : "Delete recording"}">${label}</button>`;
  }

  status() {
    let text = "Audio saved in history";
    let details = "";
    if (this.noAudio) text = "Transcript only · Audio unavailable";
    if (this.state === "temporary") {
      text = "Temporary audio";
      details = '<p class="notice">Audio is discarded on close. Use the download icon to keep a copy.</p>';
    }
    if (this.running) {
      text = "Transcribing · 68%";
      details = '<div class="progress" role="progressbar" aria-label="Illustrative transcription progress" aria-valuenow="68" aria-valuemin="0" aria-valuemax="100"><span></span></div>';
      if (this.showStats) details += '<div class="stats">Processing audio · 00:54 remaining<br>Backlog: 18 seconds · Illustrative statistics</div>';
    }
    if (this.state === "recovery") {
      text = "Audio retained · Transcription failed";
      details = '<p class="notice">The model could not load. Your audio is available to try again.</p><button class="recovery-action" data-action="choose-model">Choose another model</button>';
    }
    return `<div class="status"><div class="status-line">${this.state === "ready" ? PreviewIcons.render("check") : ""}<span>${text}</span></div>${details}</div>`;
  }

  audioStrip() {
    const button = PreviewIcons.button.bind(PreviewIcons);
    const unavailable = this.noAudio ? "disabled" : "";
    return `<div class="audio-strip" role="group" aria-label="Audio playback and tools">
      ${button("play", this.playing ? "Pause audio" : "Play audio", this.playing ? "pause" : "play", unavailable)}
      ${button("stop", "Stop audio", "stop", `${unavailable} ${this.playbackActive ? "" : "hidden"}`)}
      <div class="seek-group"><input class="seek" type="range" min="0" max="168" value="${this.position}" aria-label="Audio playback position" ${unavailable}><span class="seek-time">${this.noAudio ? "No audio" : `${this.time(this.position)} / 02:48`}</span></div>
      <div class="tools">
        <div class="tool-holder">${button("retry", "Retranscribe", "retry", `aria-expanded="false" aria-controls="${this.concept.id}-retry-menu" ${this.running || this.noAudio ? "disabled" : ""}`)}
          <div class="menu" id="${this.concept.id}-retry-menu" hidden><p>Retranscribe</p><button data-action="same-model">Use the same model</button><button data-action="choose-model">Choose another model</button></div>
        </div>
        <div class="tool-holder">${button("save", "Save or share audio", "save", `aria-expanded="false" aria-controls="${this.concept.id}-save-menu" ${unavailable}`)}
          <div class="menu" id="${this.concept.id}-save-menu" hidden><p>Audio options</p><button data-action="save-history">Keep audio in history</button><button data-action="share-audio">Share audio</button><button data-action="save-device">Save to device</button></div>
        </div>
      </div>
    </div>`;
  }

  document() {
    const id = this.concept.id;
    const footer = id === "a" ? `<div class="page-bar">${PreviewIcons.button("previous", "Previous page", "previous", this.page === 0 ? "disabled" : "")}<span class="page-label">Page ${this.page + 1} of 2</span>${PreviewIcons.button("next", "Next page", "next", this.page === 1 ? "disabled" : "")}</div>` : "";
    return `<div class="reading-layout"><section class="document" aria-label="Transcript document"><div class="transcript" tabindex="0" role="region" aria-label="Scrollable transcript, page ${this.page + 1}">${TranscriptSample.render(this.page)}</div>${footer}</section>${id === "c" ? `<div class="rail">${this.transcriptActions()}</div>` : ""}</div>`;
  }

  render() {
    const { id, title, recommendation, caption } = this.concept;
    const close = PreviewIcons.button("close", "Close transcription");
    const heading = `<header class="dialog-heading"><h3 id="${id}-title">Transcription</h3>${id === "b" ? `<div class="header-actions">${this.transcriptActions()}${close}</div>` : close}</header>`;
    // Each concept reuses the same components and action controller. Only their
    // order/placement changes, so a visual variation cannot silently omit a tool.
    const contents = id === "b"
      ? `${heading}${this.status()}${this.document()}${this.audioStrip()}<div class="page-bar">${this.deleteButton()}${this.navigation()}</div>`
      : `${heading}${this.status()}${this.audioStrip()}${this.document()}<div class="bottom-actions">${this.deleteButton()}${id === "a" ? this.transcriptActions() : this.navigation()}</div>`;
    this.root.innerHTML = `<div class="proposal-heading"><span class="letter">${id.toUpperCase()}</span><h2>${title}</h2>${recommendation ? `<span class="recommendation">${recommendation}</span>` : ""}</div>
      <div class="phone"><div class="system-bar" aria-hidden="true"><span>9:41</span><span class="system-icons">${PreviewIcons.render("signal")}${PreviewIcons.render("battery")}</span></div>
        <div class="screen"><div class="app-context" aria-hidden="true">Utterlane</div>
          <section class="dialog" role="dialog" aria-labelledby="${id}-title">${contents}<div class="toast" role="status" hidden></div></section>
          <div class="closed-state" hidden><p>Preview closed</p><button class="reopen" data-action="reopen">Reopen transcription</button></div>
        </div><div class="gesture-bar" aria-hidden="true"></div>
      </div><div class="proposal-caption"><p>${caption}</p><div class="measurement"></div></div>`;
    // A vertical toolbar, without changing button order or accessible names.
    if (id === "c") this.root.querySelector(".rail .transcript-actions").style.flexDirection = "column";
    requestAnimationFrame(() => this.measure());
  }

  measure() {
    const transcript = this.root.querySelector(".transcript");
    if (!this.root.hidden && !this.closed) {
      this.root.querySelector(".measurement").textContent = `${Math.round(transcript.clientWidth)} × ${Math.round(transcript.clientHeight)} px reading viewport`;
    }
  }

  time(seconds) { return `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`; }

  notify(message) {
    const toast = this.root.querySelector(".toast");
    clearTimeout(this.toastTimer);
    toast.textContent = message;
    toast.hidden = false;
    this.toastTimer = setTimeout(() => { toast.hidden = true; }, 3200);
  }

  updatePlayback() {
    const play = this.root.querySelector('[data-action="play"]');
    play.innerHTML = PreviewIcons.render(this.playing ? "pause" : "play");
    play.setAttribute("aria-label", this.playing ? "Pause audio" : this.playbackActive ? "Resume audio" : "Play audio");
    this.root.querySelector('[data-action="stop"]').hidden = !this.playbackActive;
    this.root.querySelector(".seek").value = this.position;
    this.root.querySelector(".seek-time").textContent = `${this.time(this.position)} / 02:48`;
  }

  closeMenu(restoreFocus = false) {
    if (!this.menu) return;
    const trigger = this.root.querySelector(`[data-action="${this.menu}"]`);
    this.root.querySelector(`#${this.concept.id}-${this.menu}-menu`).hidden = true;
    trigger.setAttribute("aria-expanded", "false");
    this.menu = null;
    if (restoreFocus) trigger.focus();
  }

  openMenu(action) {
    const wasOpen = this.menu === action;
    this.closeMenu();
    if (wasOpen) return;
    this.menu = action;
    const menu = this.root.querySelector(`#${this.concept.id}-${action}-menu`);
    menu.hidden = false;
    this.root.querySelector(`[data-action="${action}"]`).setAttribute("aria-expanded", "true");
    menu.querySelector("button").focus();
  }

  onKeyDown(event) {
    if (event.key === "Escape" && this.menu) {
      event.preventDefault();
      this.closeMenu(true);
    } else if (event.target.closest(".menu") && ["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
      event.preventDefault();
      const buttons = [...event.target.closest(".menu").querySelectorAll("button")];
      const index = buttons.indexOf(event.target);
      const next = event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1 : (index + (event.key === "ArrowDown" ? 1 : -1) + buttons.length) % buttons.length;
      buttons[next].focus();
    }
  }

  onClick(event) {
    const button = event.target.closest("button[data-action]");
    if (!button) { this.closeMenu(); return; }
    const action = button.dataset.action;
    if (["retry", "save"].includes(action)) { this.openMenu(action); return; }
    this.closeMenu(Boolean(button.closest(".menu")));
    if (action === "pin") {
      if (this.pinned) { this.notify("Already kept forever · Preview only"); return; }
      this.pinned = true;
      button.innerHTML = PreviewIcons.render("pinned");
      button.setAttribute("aria-pressed", "true");
      button.setAttribute("aria-label", "Transcript kept forever");
      this.notify("Transcript kept forever · Preview only");
    } else if (action === "play") {
      this.playing = !this.playing;
      this.playbackActive = true;
      this.updatePlayback();
      this.notify(this.playing ? "Playback state preview · No audio plays" : "Paused · Preview only");
    } else if (action === "stop") {
      this.playing = false;
      this.playbackActive = false;
      this.position = 0;
      this.updatePlayback();
    } else if (["previous", "next"].includes(action)) {
      this.page = action === "next" ? 1 : 0;
      const transcript = this.root.querySelector(".transcript");
      transcript.innerHTML = TranscriptSample.render(this.page);
      transcript.scrollTop = 0;
      transcript.setAttribute("aria-label", `Scrollable transcript, page ${this.page + 1}`);
      this.root.querySelector('[data-action="previous"]').disabled = this.page === 0;
      this.root.querySelector('[data-action="next"]').disabled = this.page === 1;
      this.root.querySelector(".page-label").textContent = this.concept.id === "a" ? `Page ${this.page + 1} of 2` : `${this.page + 1} / 2`;
      transcript.focus({ preventScroll: true });
    } else if (action === "close" || action === "delete") {
      this.closed = true;
      this.root.querySelector(".dialog").hidden = true;
      const closed = this.root.querySelector(".closed-state");
      closed.hidden = false;
      closed.querySelector("p").textContent = action === "delete" ? "Deletion preview · No real data changed" : "Preview closed";
      closed.querySelector("button").focus({ preventScroll: true });
    } else if (action === "reopen") {
      this.closed = false;
      this.root.querySelector(".dialog").hidden = false;
      this.root.querySelector(".closed-state").hidden = true;
      this.root.querySelector('[data-action="close"]').focus({ preventScroll: true });
      this.measure();
    } else {
      const messages = {
        copy: "Copy transcript · Preview only",
        share: "Open Android text sharing · Preview only",
        "same-model": "Retry with the same model · Preview only",
        "choose-model": "Open Settings → Model selection · Preview only",
        "save-history": "Keep audio forever in history · Preview only",
        "share-audio": "Open Android audio sharing · Preview only",
        "save-device": "Open device file picker · Preview only",
      };
      if (messages[action]) this.notify(messages[action]);
    }
  }
}

class DesignStudy {
  static concepts = [
    { id: "a", title: "Quiet focus", recommendation: "Recommended", caption: "<strong>A familiar rhythm.</strong> Audio above, transcript in the centre, everyday actions within easy thumb reach." },
    { id: "b", title: "Open page", recommendation: "Most reading space", caption: "<strong>The document comes first.</strong> Actions move into the header. A compact footer gives the words more room." },
    { id: "c", title: "Reading rail", recommendation: "Tools beside the text", caption: "<strong>A quiet document workspace.</strong> A slim action rail keeps tools beside the transcript, with a narrower reading column." },
  ];

  constructor() {
    this.gallery = document.querySelector(".gallery");
    this.previews = DesignStudy.concepts.map(concept => {
      const root = document.createElement("article");
      root.className = "proposal";
      root.dataset.concept = concept.id;
      this.gallery.append(root);
      return new DialogPreview(root, concept);
    });
    document.querySelectorAll("[data-theme]").forEach(button => button.addEventListener("click", () => {
      document.documentElement.dataset.appearance = button.dataset.theme;
      this.setPressed("[data-theme]", button);
    }));
    document.querySelectorAll("[data-view]").forEach(button => button.addEventListener("click", () => {
      const view = button.dataset.view;
      this.gallery.dataset.view = view;
      this.previews.forEach(preview => {
        preview.root.hidden = view !== "all" && view !== preview.concept.id;
      });
      this.setPressed("button[data-view]", button);
      this.resize();
    }));
    document.querySelector("#phone-width").addEventListener("change", event => {
      document.documentElement.style.setProperty("--phone-width", `${event.target.value}px`);
      this.resize();
    });
    document.querySelector("#text-size").addEventListener("change", event => {
      document.documentElement.style.setProperty("--transcript-size", `${event.target.value}px`);
    });
    ["preview-state", "show-stats", "reset"].forEach(id => {
      document.getElementById(id).addEventListener(id === "reset" ? "click" : "change", () => this.reset());
    });
    document.addEventListener("click", event => {
      this.previews.forEach(preview => {
        if (!preview.root.contains(event.target)) preview.closeMenu();
      });
    });
    // One observer for the three bounded mockups; no polling or animation loop.
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.previews.forEach(preview => this.resizeObserver.observe(preview.root));
    window.addEventListener("resize", () => this.resize());
  }

  resize() {
    const width = Number(document.querySelector("#phone-width").value);
    const comparing = !this.gallery.dataset.view || this.gallery.dataset.view === "all";
    const columns = innerWidth > 980;
    // Comparison fits complete phones on screen, keeping their simulated pixel
    // geometry intact. Selecting one concept returns to full size for inspection.
    const availableHeight = innerHeight - this.gallery.offsetTop - 155;
    this.previews.forEach(preview => {
      if (preview.root.hidden) return;
      const widthScale = preview.root.clientWidth / width;
      const heightScale = comparing && columns ? Math.max(.55, availableHeight / 760) : 1;
      preview.root.style.setProperty("--preview-scale", String(Math.min(1, widthScale, heightScale)));
      preview.measure();
    });
  }

  setPressed(selector, active) {
    document.querySelectorAll(selector).forEach(button => button.setAttribute("aria-pressed", String(button === active)));
  }

  reset() {
    const state = document.querySelector("#preview-state").value;
    const stats = document.querySelector("#show-stats").checked;
    this.previews.forEach(preview => preview.reset(state, stats));
  }
}

new DesignStudy();
