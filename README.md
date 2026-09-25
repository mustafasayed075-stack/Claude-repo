# Guardian

A **personal, on-device** accountability tool for Android. It is a single-user app
(not for public distribution) that:

- **Stage 1 — Provisioning:** runs as **Device Owner**, so it cannot be removed
  through normal Settings (only a full factory reset), backed by a persistent core
  foreground service that later stages build on.
- **Stage 2 — Blocking engine:** runs a **local** DNS-filtering `VpnService` that
  blocks known adult-content domains immediately. **No browsing data leaves the
  device** — allowed lookups go to a normal public resolver exactly as they would
  without the app; blocked lookups are answered locally with `NXDOMAIN`.
- **Stage 3 — Screen scanning:** an Accessibility Service takes one-shot
  screenshots (every 7 s, every 1.5 s while a watched app is in the foreground),
  classifies them **on-device** with a TensorFlow Lite NSFW model, and — after 2
  consecutive frames scoring ≥ 0.5 within 10 s — logs the detection, saves a small
  review thumbnail locally and shows a notification. **Detection and logging only;
  no lock action yet.**

Later stages (text detection, lock mechanism, reminder, persistence) are
**intentionally not implemented here** — they will be layered on top in separate
specs.

---

## Tech

| | |
|---|---|
| Language | Kotlin |
| Min SDK | 21 (Android 5.0 — lowest version with Device Owner) |
| Target / Compile SDK | 35 |
| Build | Gradle (wrapper pinned to 8.9), Android Gradle Plugin 8.7.2 |
| Async / scheduling | Coroutines, WorkManager |
| On-device ML | TensorFlow Lite 2.17 (`org.tensorflow:tensorflow-lite`), bundled model |

Open the project root in Android Studio and let it sync, or build from the command
line (see below). Building requires internet access to Google's Maven repo
(`dl.google.com`) for the Android Gradle Plugin.

```bash
./gradlew :app:assembleRelease      # build the APK
./gradlew testDebugUnitTest         # run the pure-JVM unit tests
```

---

## Project layout

```
app/src/main/java/com/personal/guardian/
├── MainActivity.kt                    Status & manual-control screen
├── admin/GuardianDeviceAdminReceiver  Stage 1: DeviceAdminReceiver; logs deactivation attempts
├── service/GuardianForegroundService  Stage 1: persistent core service (extension point for later stages)
├── vpn/GuardianVpnService             Stage 2: local DNS-filtering VpnService
├── vpn/GuardianVpnController          Stage 2: always-on VPN setup (Device Owner) / consent
├── vpn/DnsPacket.kt                   Stage 2: IPv4/UDP/DNS parse + response build
├── blocklist/BlocklistManager         Stage 2: fetch/cache/parse list, subdomain-aware matching
├── blocklist/BlocklistUpdateWorker    Stage 2: periodic (~48h) unattended refresh
├── vpn/DnsForwarder                   Stage 2: concurrent upstream DNS forwarding
├── scan/GuardianAccessibilityService  Stage 3: capture triggers, takeScreenshot(), detection pipeline
├── scan/ScanConfig                    Stage 3: all tunable constants (intervals, watched apps, threshold, N, window)
├── scan/CaptureScheduler              Stage 3: baseline vs fast capture timing (pure, unit-tested)
├── scan/DetectionConfirmer            Stage 3: threshold + N-consecutive-in-window rule (pure, unit-tested)
├── scan/NsfwPreprocessor              Stage 3: pixels → model input tensor (pure, unit-tested)
├── scan/NsfwClassifier                Stage 3: TFLite interpreter over the bundled model
├── scan/DetectionEvent                Stage 3: DetectionEvent, DetectionListener, DetectionBus (for later stages)
├── scan/DetectionStore                Stage 3: local thumbnails + detections.jsonl
├── scan/DetectionNotifier             Stage 3: temporary stub reaction (local notification)
├── boot/BootReceiver                  Restart service + filtering on boot
└── util/GuardianLog                   Append-only local event log (timestamps)

app/src/main/res/xml/device_admin_policies.xml        Stage 1: device-admin policy declaration
app/src/main/res/xml/accessibility_service_config.xml Stage 3: accessibility service declaration
app/src/main/assets/models/open_nsfw.tflite           Stage 3: bundled NSFW model (see below)
app/src/test/...                                       Unit tests (pure JVM)
tools/convert_open_nsfw.py                             Stage 3: rebuilds the .tflite from source weights
third_party/open_nsfw/                                 Stage 3: model licenses
```

The single local event log lives on-device at
`/data/data/com.personal.guardian/files/guardian-events.log` and is also shown in
the app's UI. Every deactivation attempt, service lifecycle change, and blocklist
refresh/failure is recorded there with a timestamp.

---

## Stage 1 — Device Owner provisioning (manual steps)

Device Owner **must** be set on a freshly reset device with no accounts yet. Perform
these in order (from the spec):

1. **Factory reset** the device.
2. **Do not** sign into any Google account after the reset.
3. Enable **Developer Options** and connect the device via USB.
4. Enable **USB debugging**.
5. Install the app:
   ```bash
   adb install app-release.apk
   ```
6. Promote to Device Owner:
   ```bash
   adb shell dpm set-device-owner com.personal.guardian/.admin.GuardianDeviceAdminReceiver
   ```
7. Verify in **Settings → Apps → Guardian** that the **Uninstall** option is gone.
8. **Only now** sign into the regular Google account.

(The exact `set-device-owner` string is also shown on the app's main screen.)

### Stage 1 — Definition of Done → how it's met

- **Installed & running as Device Owner** — provisioned via `dpm set-device-owner`;
  status shown on the main screen (`isDeviceOwnerApp`).
- **Uninstall option gone from Settings** — a consequence of Device Owner status;
  verified in step 7.
- **Core foreground service running persistently** — `GuardianForegroundService`
  starts on launch/boot, runs in the foreground with an ongoing notification, and is
  `START_STICKY`.
- **Deactivation attempt logged with timestamp** — `GuardianDeviceAdminReceiver`
  logs `onDisableRequested`/`onDisabled` to `guardian-events.log` with a timestamp.
- **Reboot does not undo the above** — Device Owner persists across reboots by
  design; `BootReceiver` restarts the core service; the always-on VPN is restarted
  by the OS.

---

## Stage 2 — Blocking engine

- **Blocklist source:** oisd **NSFW** list (`https://nsfw.oisd.nl/`), a free,
  open-source, category-based DNS blocklist maintained externally. It is fetched,
  parsed and cached locally. The parser tolerates hosts-file, ABP (`||domain^`) and
  plain-domain formats.
- **Filtering:** the VPN advertises a single sentinel DNS server and routes **only**
  that address into the tunnel. Every DNS query is checked against the cached list
  (matching the domain and any parent domain); matches get a local `NXDOMAIN`,
  everything else is forwarded to a public resolver (Quad9, `9.9.9.9`) and relayed
  back. Non-DNS traffic bypasses the tunnel entirely, so ordinary connectivity and
  non-listed sites are unaffected (no over-blocking).
- **Auto-start & always-on:** as Device Owner the app registers itself as an
  always-on, lockdown VPN (`setAlwaysOnVpnPackage(..., lockdownEnabled = true)`),
  which grants consent and makes the OS start/keep the tunnel across reboots.
- **Periodic updates:** `BlocklistUpdateWorker` refreshes the list every ~48h via
  WorkManager, only when a network is available, surviving reboots. The previous
  cache stays in effect if a refresh fails.

### Stage 2 — Definition of Done → how it's met

- **Starts automatically on boot** — always-on VPN (Device Owner) + `BootReceiver`.
- **Listed sites are blocked** — `BlocklistManager.isBlocked` → local `NXDOMAIN`.
- **Non-listed sites work (no over-blocking)** — only DNS is routed through the
  tunnel; unmatched queries are forwarded unchanged; all non-DNS traffic bypasses.
- **List updates automatically & periodically** — WorkManager every ~48h.
- **No data leaves the device** — the only outbound request is the GET for the
  public blocklist file. Queries are matched locally; blocked ones are never sent.

---

## Stage 3 — Screen scanning

### One-time setup on the device

1. Install/update the app and open it once (on Android 13+ allow **notifications**
   when asked — detection alerts need it).
2. **Settings → Accessibility → Installed apps → Guardian → turn on** (the app's
   "Open Accessibility settings" button goes there).
   - If the toggle is greyed out with "Restricted setting" (Android 13+, APK
     installed from a browser/file manager rather than `adb install`): open
     **Settings → Apps → Guardian → ⋮ → Allow restricted settings**, then retry.
3. The main screen should now show **Screen scanning: Active**, plus the current
   mode (baseline / fast + app), frames scanned, the last frame's score and the
   number of confirmed detections. Tap **Refresh status** to update it.

### How it works

- **Capture triggers** (constants in `ScanConfig`):
  - *Periodic:* every `BASELINE_INTERVAL_MS` = **7 s** while the service is active.
  - *Foreground fast capture:* when a `TYPE_WINDOW_STATE_CHANGED` event shows a
    package from `WATCHED_PACKAGES` (WhatsApp, Telegram, major browsers — edit the
    list freely) in the foreground, capture immediately and then every
    `FAST_INTERVAL_MS` = **1.5 s**; revert to 7 s when another app comes to the
    foreground. System UI and keyboard windows are ignored so the notification
    shade or keyboard doesn't drop fast mode. Notifications arriving are *not* a
    trigger. Mode switches are written to the event log.
- **Capture:** `AccessibilityService.takeScreenshot()` (Android 11 / API 30+) — a
  single frame, no screen-recording notification. Skipped while the screen is off.
  Below API 30 the service logs *"Screen scanning unsupported on this Android
  version"* and stays idle.
- **Classifier:** TensorFlow Lite on-device (`NsfwClassifier`), 2 threads. The
  frame is scaled to 256×256, centre-cropped to 224×224, converted to BGR minus the
  VGG mean, and the model's NSFW probability is the score.
- **Confirmation:** `DetectionConfirmer` — a frame is positive when its score ≥
  `NSFW_THRESHOLD` = **0.5**; a detection is confirmed after
  `CONFIRMATION_COUNT` = **2** consecutive positive frames within
  `CONFIRMATION_WINDOW_MS` = **10 s**. A negative frame resets the streak.
  - The threshold is 0.5 (not Yahoo's 0.8 "very likely NSFW") so that general
    nudity and suggestive content such as swimwear is caught, not only explicit
    pornography. Expect more false positives on skin-heavy safe images (beach,
    sports, fitness); this is accepted while calibrating.
- **Calibration logging (temporary):** with `LOG_EVERY_FRAME_SCORE = true` every
  classified frame is logged, e.g.
  `Scan frame: score=0.6312 [>= 0.50] trigger=event app=com.whatsapp streak=1/2`.
  Set the flag to `false` (or delete it and its one use) when calibration is done;
  while on, the event log rotates within a few hours of heavy use.
- **Lifecycle diagnostics:** the service logs each connect (instance number, pid,
  process age), each unbind (whether it was still enabled in Settings), and — on
  Android 11+ — the system-recorded reason the previous Guardian process ended
  (`CRASH`, `CRASH_NATIVE`, `LOW_MEMORY`, `ANR`, `USER_REQUESTED`, …).
- **On a confirmed detection:**
  - a thumbnail (longest side 256 px, JPEG) is saved to
    `files/detections/detection-<timestamp>.jpg` (newest 100 kept) and a metadata
    line (timestamp, confidence, trigger `periodic`/`event`, foreground app,
    thumbnail name) is appended to `files/detections/detections.jsonl`;
  - a `CONFIRMED screen detection …` entry is written to the event log;
  - a `DetectionEvent` is published on `DetectionBus` — the later lock stage
    registers a `DetectionListener` there;
  - stub reaction: a local notification **"Guardian: flagged content detected"**.
    No lock, no blocking.

### Model: source and license

| | |
|---|---|
| Model | **Yahoo open_nsfw** (ResNet-50 "thin" NSFW classifier), via the Keras port **OpenNSFW2** |
| Sources | https://github.com/yahoo/open_nsfw (weights) · https://github.com/bhky/opennsfw2 (port; weights file `open_nsfw_weights.h5`, release v0.1.0, SHA-256 `14ca261f48bdd88c1eecba96a761bd1579b523adae1b749b0a4ffd8b7ed8babe`) |
| Licenses | open_nsfw: **BSD 2-Clause** (© 2016 Yahoo Inc.) · OpenNSFW2: **MIT** (© 2021 Bosco Yung) — full texts in `third_party/open_nsfw/` |
| Bundled file | `assets/models/open_nsfw.tflite`, float16 weights, 11.9 MB, SHA-256 `25275eb202277f35657acbfe7038647502c4ee3d45eef9c2eeccc26717ea7bb5` |
| I/O | input `1×224×224×3` float32 (BGR, mean-subtracted); output `1×2` softmax `[sfw, nsfw]` |
| Conversion | `tools/convert_open_nsfw.py`; converted model matches the Keras reference to within 0.0013 |

### Known limitations

- **View-once / screenshot-protected content** (FLAG_SECURE windows, e.g. WhatsApp
  and Telegram view-once media, some banking apps) cannot be captured — accepted
  per spec. These frames are skipped and logged (rate-limited) as "secure window".
- The **whole screen** is squashed into one 224×224 input, so a small image inside
  a larger page (e.g. a thumbnail in a chat list) may score low until opened
  full-screen.
- Accuracy on real content can only be judged on-device; the threshold (0.5) is a
  starting point to tune with the per-frame score log.

### Stage 3 — Definition of Done → how it's met

- **Service can be enabled from Settings; status screen shows whether it's active** —
  declared in the manifest with `accessibility_service_config.xml`; the main screen
  shows Active / Enabled-not-running / Off (+ unsupported below API 30).
- **Periodic capture on the configured interval** — `CaptureScheduler` +
  worker-thread timer; covered by `CaptureSchedulerTest` (cadence and a simulated
  timeline); visible on-device via "frames scanned" / "last" on the main screen.
- **Fast capture immediately on watched-app foreground; reverts when it leaves** —
  `onForegroundChanged` reschedules at once; covered by `CaptureSchedulerTest`;
  mode switches appear in the event log.
- **Classified fully on-device, zero network calls** — bundled model + plain
  TFLite runtime (no Play Services, no downloads); `DetectionEventTest` statically
  checks the `scan` package uses no networking APIs.
- **One positive frame doesn't confirm; N consecutive within the window do** —
  `DetectionConfirmerTest`.
- **Confirmed detection → notification + GuardianLog entry + saved thumbnail** —
  `GuardianAccessibilityService.onConfirmed`.
- **Below API 30: warning logged, no crash** — guarded in `onServiceConnected`;
  every API-30+ call is behind a version check (lint `NewApi` passes with minSdk 21).
- **Confirmation-window and threshold logic unit-testable without Android** —
  `DetectionConfirmer` / `CaptureScheduler` / `NsfwPreprocessor` are pure Kotlin
  with injected timestamps.

---

## Testing individual components

- **Blocklist parsing & matching:** `BlocklistManagerTest` (pure JVM), including a
  lookup benchmark at the live list size.
- **DNS packet parse/build:** `DnsPacketTest` (pure JVM).
- **Concurrent DNS forwarding:** `DnsForwarderTest` (pure JVM, fake local resolver).
- **Screen-scan logic:** `DetectionConfirmerTest`, `CaptureSchedulerTest`,
  `NsfwPreprocessorTest`, `DetectionEventTest` (pure JVM).
- **Screen scanning on a device:** enable the accessibility service, open WhatsApp
  or a browser and watch the event log for "fast capture ON/OFF"; the main screen
  shows frames scanned and the last score.
- **Device admin / VPN / service:** exercise on a device via the main screen
  buttons (activate admin, enable DNS filter, refresh blocklist, view log). On a
  non-provisioned test device you can activate plain device-admin and grant VPN
  consent interactively before doing full Device Owner provisioning.

## Scope note

This repository contains **Stages 1–3**. Stage 3 stops at detect → log → notify;
the lock stage will subscribe to `DetectionBus`. The core service keeps its single
`onServiceReady()` extension point for later stages.
