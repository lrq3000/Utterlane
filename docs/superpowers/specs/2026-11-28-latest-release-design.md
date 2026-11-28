# Latest release under the download button

Put the current GitHub release next to the first place visitors go to download Utterlane: immediately below the APK button row in the project README, and below the hero button row on the website. Leave navigation and later calls to action free of release metadata.

## Approaches

- **Use existing services:** the GitHub-rendered README reuses Shields badges, which already work there without JavaScript. The website reads GitHub's public latest-release endpoint and writes the tag and UTC publication date as text. No per-release edits or deployments are needed.
- **Generate static metadata on release:** this avoids browser requests, but would add coordination between the app and the orphan website branch, plus deployment permissions and recovery for failed refreshes.
- **Maintain a manual value:** this adds no code but can advertise an outdated release. Use it only if automatic updates are not practical.

The first approach keeps GitHub Releases authoritative. The website makes one request without cookies or a referrer. Missing metadata, a failed request, and disabled JavaScript must never prevent the download link from working.
