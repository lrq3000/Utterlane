# Dialog polish and recovery retention

Approved in conversation on 2026-10-09, including the final request to implement.

## User-visible behavior

- Onboarding presents accessibility before floating microphone setup. Settings
  uses an AutoFixHigh wizard icon and **Run onboarding wizard again**, without a
  subtitle. Replay starts at Welcome with existing choices; opening, Back and
  Skip preserve settings, while deliberate choices continue to apply.
- Home actions read **Pin transcript**, **Pin audio**, **Show more details**,
  and **Reset**.
- Short toasts report completed pin/unpin, copy, save to history, and audio export.
  Sharing reports **Share menu opened**, not delivery to another app. Existing
  catch paths report failures; cancellation produces no failure/success toast.
  No extra timeout, polling or promise infrastructure is introduced for feedback.
- Both history destinations have a bottom legend for ordinary and recovered
  entries, pinned retention, and the current independent pruning policy.
- Recovered audio/text retain a durable recovered-origin flag, independent of
  pending recovery. Pins, successful retries, source audio expiry and restarts
  do not erase this flag. Legacy metadata without evidence of recovery stays normal.
- Ordinary dialog exit retains useful recovered material under the relevant
  enabled history and retention settings. Existing retention clocks do not reset
  when viewed. Explicit deletion remains authoritative.
- Toolbar Back and Android Back use one loss check. If exit would lose content,
  name that content and offer **Discard**, **Pin**, **Go back**. Discard permits
  removal of at-risk data but preserves independently retained/pinned data. Pin
  saves both available audio and the displayed transcript indefinitely, then
  closes. Go back leaves the dialog open. Save failures keep it open with an error.

## Architecture and invariants

Extend the existing file-backed repositories and atomic properties sidecars;
no new database engine. Recovery origin is separate from temporary ownership and
notification acknowledgement. Preserve bounded IO, indexed lookup and independent
audio/text retention. Reuse the dialog model's coroutine ownership and existing
publication/deletion guards. Keep explicit Home retirement separate from the
interactive dialog exit so invisible owners cannot wait for confirmation.

Exit examines current metadata and settings off Main. Temporary material is only
promoted when automatic history permits it or the user explicitly pins it. Earlier
saved transcript versions remain independent of the displayed result's exit choice.
Recheck disposition before final close; do not recreate explicitly deleted IDs.
Unexpected Activity destruction remains recovery, never consent to discard.

## Verification

Regression tests cover recovery retention/persistence, expiry boundaries,
independent histories, normal-versus-recovered badges, and existing deletion races.
Android tests exercise both Back routes, all exit choices, failed saving, feedback,
and non-resetting wizard replay. Run focused tests before broad unit tests and
assembleDebug/assembleDebugAndroidTest. Use a separate QA application ID on an
available emulator. Keep local commits for coherent verified milestones.
