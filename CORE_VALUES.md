# Utterlane core values

This document defines Utterlane's enduring product values and added value: turning
speech into accurate, usable text with exceptional responsiveness, real speed,
reliability, and privacy on the user's own device. It is a shared guide for human
contributors and AI agents to the app's future direction, guiding product,
interface, architecture, and optimization decisions. Each value states its purpose,
followed by practical examples; the examples illustrate the principle rather than exhaust
it. These are requirements to work toward and preserve, not a claim that every
example is already implemented in every release.

## 1. Extreme responsiveness: keep the user's flow uninterrupted

**Intent:** The app should feel immediately available. Users should be able to act
when a thought occurs, without arranging their speech around internal loading or
processing. Use engineering optimizations and thoughtful UI/UX together to make
waiting unnecessary wherever possible. When a wait cannot be eliminated, make it understandable, visibly progressing, and compatible with doing something else without significantly
slowing down the processing.

**In practice:**

- Starting a recording should start listening immediately, once required
  microphone access is available. Model loading, including reloading an unloaded
  model, must happen in the background rather than gate audio capture. Show both
  facts clearly: **recording is active** and **the model is still loading**.
- Aim for the transcript to be finished, or almost finished, when the user stops
  recording. Deliver usable completed segments while the user continues speaking
  instead of withholding everything until the end.
- When work remains, provide meaningful, frequently updated, near-real-time
  feedback: the current stage, completed and remaining work, a progress bar where
  measurable, and an estimated remaining time where reasonably estimable. Update
  estimates as conditions change; make uncertainty clear rather than inventing
  precision or presenting animation as evidence of actual progress.
- Let users continue other activities while processing runs and make completion
  easy to notice and return to. The second-best experience after an instant result
  is a comprehensible wait with a useful sense of its remaining duration, not an
  unexplained spinner that demands attention.

## 2. Intrinsic speed and efficiency: do less work, overlap what can run independently

**Intent:** Responsiveness must be supported by genuinely fast processing, not
merely the appearance of speed. Minimize end-to-end latency and resource use so
that local transcription remains practical on the devices people already own,
including during long recordings and resource contention.

**In practice:**

- Minimize synchronous blocking, unnecessary dependencies, repeated computation,
  and data copying. Overlap capture, model preparation, transcription, and other
  independent work; keep expensive operations off the interactive path.
- Protect timely microphone capture and interface interaction when background
  inference competes for CPU or memory. More concurrency is useful only if it
  improves the overall experience without starving essential work.
- Process incrementally and keep working memory and queues bounded. Long sessions
  should not require holding the entire recording or transcript in RAM.
- Offer suitable smaller models and sensible defaults for constrained devices,
  alongside more capable models when resources permit. Explain speed, memory, and
  accuracy trade-offs rather than assuming one model suits every phone.
- Backpressure principle of shedding work before data: When the system falls behind, reduce redundant or optional work before discarding information: coalesce obsolete processing, reduce preview frequency, or disable enhancements before allowing queues to grow without bound and always avoid dropping user input at all cost, the user input is always to be saved.

## 3. Reliability through isolation: degrade gracefully, not all at once

**Intent:** Components should operate as independently as practical so that one
component's failure does not cascade into unrelated components or destroy the
overall workflow. The app should progressively lose only the capabilities that
actually depend on the failed component, retaining all useful functioning parts.

**In practice:**

- Model-loading or transcription failure must not stop audio recording. Audio
  capture has value independently of whether recognition is currently available.
- Optional speaker labeling (diarization) failing should leave plain transcription
  and audio capture working. A failure to insert text into another app should
  leave the transcript available for copying or export.
- Separate component lifecycles, failure handling, and recovery paths so that a
  failed processing task does not automatically cancel healthy tasks. Optional
  enhancements must not become prerequisites for the core workflow.
- Distinguish an unavailable enhancement from an actual capture failure. If a
  genuine microphone or storage constraint prevents continued capture, explain
  what stopped and preserve what was successfully captured or processed.
- Sustained device performance: Optimize for sustained responsiveness, not benchmark peaks. Calculate median or average and 95% quantile metrics to know how long it takes half or most of the time. Heat, battery use, memory pressure, and thermal throttling are part of performance, but do not be over precautious, these aspects should not stiffle innovation.

## 4. Preserve the user's work: recovery is part of the normal experience

**Intent:** A person's words and time are more valuable than a successful run of
any particular model. Save useful progress incrementally and make failures
recoverable, with clear next actions, so users do not have to repeat themselves or
reconstruct work unnecessarily.

**In practice:**

- Do not interrupt capture to repair a processing failure. Preserve the recording, explain the failure at an appropriate moment, and offer actionable recovery—such as retrying or choosing another compatible model—without requiring the user to repeat the recording.
- Authoritative data vs speculative work: Keep authoritative user input and committed results distinct from speculative or best-effort processing. Derived work may be recomputed, coalesced, or discarded for efficiency; source material and already-committed progress must never be silently lost.

**Examples:**
- Preserve completed transcript segments as work advances. Keep saved audio
  available for replay and retranscription, subject to the user's retention and
  deletion choices; changing models or retrying must not discard usable results.
- Do not interrupt the user's speech with a model-recovery dialog. If the model
  fails to load while recording, continue recording. After the user taps Stop,
  explain that **the transcription model failed to load**, say what audio was
  retained, and present a button beneath the message that opens **Settings
  directly at model selection**. Let the user choose a smaller model that is more
  likely to fit available memory, or retry loading, and transcribe the retained
  audio without recording it again.
- Keep loading, recording, processing, and failure states distinguishable. Never
  report that audio is saved or transcription is complete unless it actually is.
- Make recovery compatible with privacy: respect disabled history, retention
  limits, and explicit deletion. Explain any resulting recovery limitation;
  recovery is not permission to retain recordings secretly or indefinitely.

## 5. Privacy and offline independence: keep the user's words on their device

**Intent:** Turning speech into text should not require surrendering private
thoughts or depending on a remote service. Local processing provides both privacy
and independence from network availability, service outages, and remote access
requirements.

**In practice:**

- Run speech recognition and speaker labeling on-device, without uploading audio
  or transcripts to a speech service. Recognition must remain usable offline after
  model setup, including in airplane mode.
- Support local model import as well as user-requested downloads. A download may
  require connectivity; ordinary recognition must not.
- Require no account and include no analytics or automatic remote reporting. Keep
  optional diagnostics local and under the user's control.
- Keep recording history private, with understandable retention, deletion, and
  export controls. Sharing or exporting must be intentional, with a clear boundary
  between Utterlane's local handling and the receiving app's handling of the data.

## 6. Faithful transcription: preserve meaning, language, and useful context

**Intent:** Speed is valuable when the resulting text represents what the person
actually said. Pursue accurate recognition in everyday conditions, including noisy ones,
preserving the speaker's meaning and language rather than making users adapt their
thoughts to the tool.

**In practice:**

- Use capable modern models and validate practical accuracy across ordinary
  voices, environments, and recordings. Do not trade away speech or usable context
  merely to make processing look faster.
- Capability-driven behavior and replaceability: Prefer capability-driven behavior over assumptions tied to model names, runtimes, or device models. Treat models and processing engines as replaceable components.
- Support natural multilingual speech and language changes within a recording
  without forcing restarts or unwanted translation.
- Allow personal vocabulary corrections for names and recurring errors. Offer
  speaker labels when they help follow a conversation, while making uncertainty
  explicit rather than confidently assigning an unsupported speaker.
- Communicate model and device limitations honestly. Near-real-time segment output
  is useful without promising instantaneous word-by-word text or perfect accuracy.

## 7. Fit into everyday life: reduce friction from speech to usable text

**Intent:** The app should fit the user's existing activities, language, and
preferred interaction methods. Capturing an idea, replying to a message, reading
a voice memo, or following a meeting should take little setup or context
switching, and should not require understanding speech-recognition internals.
The number of user actions (eg, taps) required for them to access the feature they
want or need should be as short as possible (eg, starting a transcript should be
one or two actions at most, sharing should be one, etc).

**In practice:**

- Expose transcription through the interaction surfaces users already work in, and minimize the number of deliberate actions required for common journeys.
- Use clear setup guidance, sensible defaults, understandable status messages, and
  accessible, localized controls. Keep advanced tuning available without making
  it a prerequisite for ordinary use.
- Base user interface and user experience decisions on typical and common user journeys,
  apply the pareto rule: aim to fulfill with minimal burden/user actions 80% of the
  users' common needs, but implement options to support the more advanced/customized
  needs of the remaining 20%.

**Examples:**
- Make voice input available through compatible keyboard microphones,
  accessibility controls, and a floating microphone. Deliver text where the user
  is working, with copying or export as practical fallbacks.
- Accept shared or opened audio and offer optional folder monitoring so existing
  recordings can enter the same useful transcription workflow.
- Keep optional features and their permissions optional. File transcription should
  not require configuring live microphone shortcuts, and basic dictation should
  not require setting up meeting speaker labels.

## 8. Ownership and openness: a tool that remains the user's

**Intent:** People should control the tool, their data, and how they use it.
Utterlane's value includes being freely usable, inspectable, and adaptable, without
subscription dependence or artificial scarcity imposed on transcription. This is
not just ideological but implies technical objectives: the components should be
engineered and optimized to provide virtually unlimited transcripts, both in numbers
and duration, notably via using incremental and bounded processing (eg, streaming, rolling windows in RAM, etc), so resource use does not grow unnecessarily with session length.

**In practice:**

- Regularly reevaluate if better (more accurate, faster, smaller) models are available.
- Regularly monitor if implementations can be optimized further (eg, for speed, lower RAM usage, accuracy, etc).
- Keep the app free and open source, with no required account or subscription,
  per-minute charges, or service-imposed transcription quotas. Physical device
  limits still exist; they should be handled transparently rather than confused
  with artificial usage limits.
- Let users choose compatible models, customize corrections, control optional
  features, and decide what recordings to retain, delete, or export.
- Keep the source available to inspect, build, improve, and redistribute under
  its license. Make contributions and corrections welcome, with verification
  proportionate to their impact.
- Abstraction and portability must earn their complexity. Prefer the simplest architecture that preserves these values on the primary platform; cross-platform reuse is beneficial only when it does not materially weaken performance, reliability, maintainability, or access to platform capabilities.

## 9. Evidence-guided adaptation: measure the whole experience

**Intent:** Engineering decisions should follow observed end-to-end behavior rather than assumptions, fashionable techniques, or isolated microbenchmarks. Optimize what materially improves the user's experience while protecting accuracy, reliability, privacy, and recoverability.

**In practice:**

- Measure latency and correctness together. A change that reduces inference time but increases missing last words is not an optimization. A backend that wins a synthetic benchmark but takes longer to initialize may not improve the user's experience. A lower-latency preview that makes finalization slower can be a regression. Emphasize those metrics without being limited to them: time-to-capture, time-to-useful-text, stop-to-finish, accuracy, resources and failure survival.
- Prefer representative workloads and on-device behavior (eg, in emulator or real device) when deciding what to optimize.
- Use adaptive defaults and detected capabilities where practical instead of hard-coded assumptions. If defaults cannot be adaptively set automatically, offer to add a user-facing option.
- Treat measurements as diagnostic evidence, not as product goals in themselves; an optimization is valuable only when the complete workflow becomes better without violating the other core values.

## Applying these values

Evaluate changes by their effect on the complete user journey: time to start
capturing, time to useful text, remaining work after Stop, practical accuracy,
resource use, and what survives a component failure. Optimize these together:
faster-looking UI must not conceal failure, speed must not silently drop words,
recovery must respect retention choices, and an optional feature must not make
the core experience fragile.

When values conflict, protect user input, correctness, privacy, and recoverability before optimizing secondary presentation or convenience. Prefer removing redundant work to removing information.

This file records the enduring intent that should guide their evolution.
Current capabilities and operational details are described in other documents: the [Utterlane website](https://lrq3000.github.io/Utterlane/) and the project's [overview](README.md), [user guide](docs/user-guide.md), [privacy policy](PRIVACY_POLICY.md), and [contribution guidance](CONTRIBUTING.md). Those documents describe current capabilities and operational details;
