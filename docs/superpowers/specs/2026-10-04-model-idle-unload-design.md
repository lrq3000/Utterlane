# Model idle unloading

Approved in conversation on 2026-10-04, including skipping startup preloading
when Immediate is selected.

## Behavior

Speech Model settings gains **Idle time before unload**: Immediate, 5 min,
20 min (default), 1 h, 3 h, 24 h, Never. Store a stable preference key; unknown
keys fall back to 20 min. Never prevents policy-driven unloading, not Android
process reclamation or explicit user resets.

Idle begins when the final transcription closes, including cancellation/error,
or when a standalone model load completes. Pending initialization/session
creation and active sessions protect the model. New transcription resets idle.
Setting changes use the existing idle start, so a shorter already-expired
timeout unloads promptly. Immediate releases idle manual loads too.

Automatic unloading releases only the recognition backend. It preserves services,
downloaded files, transcript results and on-demand loading. Startup preloading
is skipped for Immediate; its stored toggle is preserved and the UI explains why.

## Architecture

RecognizerManager owns a single cancellable coroutine timer. It shares the
existing state lock and inference mutex, checks session/operation ownership and
timer identity before detaching, and closes the backend before another model
can load. Automatic unloading never calls forceUnload, which cancels sessions.
A small Android-independent policy computes remaining time from a monotonic
elapsed clock. Application-scoped preference observation applies changes live.
The existing device-wake observer triggers deadline rechecks after sleep. No
alarm, polling loop or wake lock is added for idle unloading.

## Verification

Unit tests exercise all durations, deadline boundaries, busy transitions,
policy changes, Never, and elapsed sleep time. Android integration tests exercise
concurrent sessions, cancellation, reloading and settings persistence. Run
`gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest`, then
focused instrumentation tests on an available emulator. UI checks verify the
selector and the Immediate/preload explanation. Translation work follows the
project policy of deferring new translations until release preparation.
