# Restricted Settings help in onboarding

On Android 13 or later, an installer other than Google Play can leave Utterlane's accessibility or overlay setup blocked by Android. The user confirmed that Android's **Allow restricted settings** option restored setup on the reported device.

## Approaches

- **Chosen: conditional help on the existing shortcut page.** Hide it on Android 12L and earlier, on installs Android attributes to Google Play, and when both permissions are already enabled. For other or unknown installers, explain what to do **if** Android blocks access; installer metadata does not prove a permission is restricted.
- **Not chosen: wait for Android to report a denial.** The Settings screens used by the wizard do not send back a result that distinguishes a restriction from a grant the user chose not to make.
- **Not chosen: add a mandatory page or replace the permission controls.** That adds friction on working paths and makes an optional setup look required.

The help card reuses Utterlane's existing App info action. It explains the **⋮ → Allow restricted settings** menu, including retrying after the first denial if the menu is initially absent, and tells the user to return and grant only the permissions they want. Plain text accommodates localized system labels and device-specific layouts. Keep the existing accessibility disclosure, direct permission actions, and skip path. Do not add permissions, network access, or automatic changes to system settings.

Read the platform's configured accessibility-service setting for the help card, independently of whether the service has connected yet. Functionality that depends on the live service keeps its current readiness check.

References: [Android's user instructions](https://support.google.com/android/answer/12623953#allowrestrictedsettings) and the [installer-of-record API](https://developer.android.com/reference/android/content/pm/InstallSourceInfo#getInstallingPackageName()).
