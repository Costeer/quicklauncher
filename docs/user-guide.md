# Quicklauncher user guide

## Requirements and channels

Quicklauncher requires Android 15/API 35 or newer and currently targets phones in portrait and landscape. Stable and preview are separate applications:

| Channel | Application ID | Updates from |
| --- | --- | --- |
| Stable | `org.quicklauncher` | Stable tags and the stable Obtainium configuration |
| Preview | `org.quicklauncher.preview` | Preview tags and the preview Obtainium configuration |

The channels install side by side. They do not update one another because they have separate application IDs and signing identities. A portable Quicklauncher archive can move configuration between compatible versions, but Android permissions, document grants, widgets, profiles, and Home-role selection must be authorized again where the restore review says so.

## Install and verify

Download the channel's single `-universal.apk` only from its GitHub release. Also download `release-metadata.json` and the APK checksum asset. Verify the GitHub attestation and then follow [release verification](release-verification.md) before installing.

Android may ask permission for the browser or Obtainium to install unknown apps. Grant that permission only to the installer you intentionally chose, install the verified APK, and remove the installer permission afterward if you do not want unattended updates. First run lets you preview a modular, traditional, or blank map before requesting the Home role.

The checked Obtainium import files are:

- [Stable configuration](../.github/release/obtainium-stable.json)
- [Preview configuration](../.github/release/obtainium-preview.json)

Each configuration accepts only its channel's release titles and universal APK filenames. Obtainium selects releases; Android's signing check still decides whether an APK may update an installed app.

## Upgrade

Back up before an important upgrade. Verify the new APK just as for a first install. An in-place update succeeds only when the application ID and permanent channel certificate match and `versionCode` increases. Never uninstall first unless you have a tested portable backup: uninstalling removes private app state and may remove Android-managed grants and widget bindings.

Preview builds may be less stable. Moving between stable and preview is a backup-and-restore operation, not an APK update.

## Back up

Open Quicklauncher Settings and authorize a folder through Android's system document picker. Quicklauncher stores only the persisted folder authorization; it does not receive unrestricted filesystem access.

- Manual backups are created only on request and are never pruned automatically.
- Automatic backups run while charging when delivered between 03:00 and 06:00 local time and retain the newest seven valid automatic snapshots.
- Plaintext archives require an explicit choice. Passphrase-protected archives use authenticated encryption. The passphrase is never stored or recoverable, so keep it separately.
- Android device-to-device transfer includes durable launcher data and installed theme assets. Cloud backup is available only when Android reports client-side encryption capability. Restore staging, diagnostics, folder authorizations, and passphrases are excluded from both paths.

Keep at least one tested archive outside the phone. A file digest detects damage; encryption protects archive contents; neither substitutes for remembering the passphrase.

## Restore

Choose an archive from the authorized backup folder and review it before applying anything. Quicklauncher validates format, sizes, digests, assets, references, and optional encryption first. It then creates a pre-restore backup and lets you select the destination map, themes with their assets, and web-adapter data independently where safe.

The live launcher changes in one revision-checked replacement. A corrupt, rejected, failed, or cancelled restore leaves the previous state intact and retains the non-removable safe layout. After a successful restore, follow the review actions for document access, runtime permissions, widget rebinding, quarantined modules, and profiles. Restored web adapters and unknown extensions remain inert until supported and explicitly enabled.

## Recovery

- Repeated Home closes transient UI and returns to the configured start destination.
- Back closes the current overlay; predictive Back can cancel an in-progress spatial preview.
- Settings always exposes hidden-app recovery even when ordinary app visibility fails closed.
- Renderer crash-loop recovery quarantines the affected instance and selects the host-owned safe layout without deleting its raw configuration.
- Widget recovery rebinds widgets with new Android widget IDs; old IDs are never reused across a portable restore.
- Support diagnostics stay in a bounded private log. Export creates a redacted bundle only after an explicit action; a successful export clears only the exported prefix.

If an upgrade cannot start, keep the installation and archive intact, collect the redacted support bundle if Settings remains reachable, and report the installed version, channel, Android build, and the verifier result. Do not include archive passphrases, private keys, contacts, notification content, or Private Space data.

## Permissions and system access

| Access | Why it exists | When it is used |
| --- | --- | --- |
| Home role | Act as the launcher | After onboarding preview and explicit confirmation |
| Broad package visibility | Build the complete local app catalog | Declared at install; no Play declaration flow is used |
| Contacts | Optional contacts search provider | Requested only when that provider is enabled |
| Document folders/files | Optional search, import, and backup | Granted through Android's document picker |
| Notification listener | Dots or approximate counts without content persistence | Opened from Settings after explicit action |
| Hidden-profile access | Complete host-owned Private Space flow | Used only with secure lock/unlock and visibility handling |
| Wallpaper | Apply an explicitly previewed Home, Lock, or Both request | Revalidated immediately before the system call |
| Boot completion | Restore the opted-in nightly backup schedule | No background upload or network work |

Quicklauncher does not request the `INTERNET` permission. A web search action constructs a bounded HTTPS destination and hands it to another installed app; Quicklauncher does not fetch remote results.

## Privacy

Launcher state, ranking history, imported assets, archives, and diagnostics remain on the device unless you explicitly export a file. Search history stores only a bounded, decaying history for app and shortcut launches—never raw queries, contacts, files, web targets, or Private Space data. Notification content is neither exposed to contribution modules nor persisted. Locked, unavailable, or ambiguous private-profile data is removed before ordinary collections, search, fixtures, or diagnostics.

There is no telemetry, advertising SDK, Firebase, Google Play Services, or automatic support upload. External apps opened for HTTPS destinations, Settings, installation, or document selection apply their own privacy policies.

## Known limitations

- Android 15/API 35 is the minimum; tablet and foldable release support is deferred.
- There is no Google Play build, in-app updater, runtime third-party plugin system, linked-clone UI, or foreign-launcher import.
- Icon packs support the documented declarative Nova/ADW subset, not every dialect.
- GrapheneOS Settings links are manually enabled, versioned, and best effort; public Android Settings remains the fallback.
- Portable restore cannot restore Android permissions, document grants, Home role, profiles, or widget IDs automatically.
- A lost archive passphrase cannot be recovered. A lost release signing key would prevent compatible updates; maintainers use separate protected channel keys and offline recovery drills.
