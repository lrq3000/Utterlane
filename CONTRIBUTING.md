# Contributing to Utterlane

Utterlane is maintained by Stephen Karl Larroque <LRQ3000@GMAIL.COM>.
Please use [Utterlane issues](https://github.com/lrq3000/Utterlane/issues) and pull
requests for this fork, rather than directing its support requests upstream.

## Reports and proposals

For bugs, include the application version, Android version, device, selected
speech model, expected and actual behavior, and the smallest useful reproduction.
Share sanitized logs or an audio fixture only if you have permission to share it.
For substantial features, discuss the intended user experience before coding.
Translation fixes, accessibility feedback, and documentation improvements count
as valuable contributions too.

## Changes and verification

- Make focused changes and use conventional commit messages.
- Explain the motivation, implementation, and relevant verification in the PR.
- Keep model inference and speech data local; preserve cancellation and bounded
  memory behavior when changing the transcription pipeline.
- Run `./gradlew testDebugUnitTest assembleDebug` for application changes and
  relevant device tests for Android integration. Windows uses `gradlew.bat`.
- Include meaningful regression tests for behavior changes. Commit fixtures and
  reproduction tools that future contributors need, respecting their licenses.
- Update documentation and user-facing resources when behavior changes.
- Preserve third-party copyright/license notices. Contributions to this project
  are submitted under its existing Apache-2.0 license.

See the README for build prerequisites and [QA notes](docs/qa/README.md) for
device-test context. Brand assets have their own reproducible
[design workflow](docs/design/utterlane-branding.md).

## AI-assisted contributions

**AI contributions are welcome as long as the outputs are sanity checked by
humans.** This includes code, prose, translations, tests, and artwork.

Before submitting, a human contributor must read and understand the output,
check factual and licensing claims, and test relevant behavior. State what was
verified and any remaining uncertainty. Tool-generated output is not evidence
that its own claims are correct, and responsibility for the contribution stays
with the person submitting it.
