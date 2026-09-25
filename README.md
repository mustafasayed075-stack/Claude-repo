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
  screenshots (every 6 s, every 1.5 s while a watched app is in the foreground),
  classifies them **on-device** with a TensorFlow Lite NSFW model, and — once 2
  frames with a suggestive/explicit signal ≥ 0.3 fall within 7 s (not necessarily in a row) — logs the detection, saves a small
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
├── scan/DetectionConfirmer            Stage 3: threshold + N-positives-in-window rule (pure, unit-tested)
├── scan/NsfwPreprocessor              Stage 3: pixels → model input tensor (pure, unit-tested)
├── scan/NsfwClassifier                Stage 3: TFLite interpreter over the bundled model
├── scan/DetectionEvent                Stage 3: DetectionEvent, DetectionListener, DetectionBus (for later stages)
├── scan/DetectionStore                Stage 3: local thumbnails + detections.jsonl
├── scan/DetectionNotifier             Stage 3: temporary stub reaction (local notification)
├── boot/BootReceiver                  Restart service + filtering on boot
└── util/GuardianLog                   Append-only local event log (timestamps)

app/src/main/res/xml/device_admin_policies.xml        Stage 1: device-admin policy declaration
app/src/main/res/xml/accessibility_service_config.xml Stage 3: accessibility service declaration
app/src/main/assets/models/nsfw_mobilenet_v2_140_224.tflite  Stage 3: bundled 5-class NSFW model (see below)
app/src/test/...                                       Unit tests (pure JVM)
tools/verify_nsfw_model.py                             Stage 3: re-verifies the bundled model + preprocessing
third_party/nsfw_model/                                Stage 3: model licenses + class labels
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
  - *Periodic:* every `BASELINE_INTERVAL_MS` = **6 s** while the service is active.
    Kept 1 s below the 7 s confirmation window so two normal-mode frames can
    still confirm despite timer/classification jitter.
  - *Foreground fast capture:* when a `TYPE_WINDOW_STATE_CHANGED` event shows a
    package from `WATCHED_PACKAGES` (WhatsApp, Telegram, major browsers — edit the
    list freely) in the foreground, capture immediately and then every
    `FAST_INTERVAL_MS` = **1.5 s**; revert to 6 s when another app comes to the
    foreground. System UI and keyboard windows are ignored so the notification
    shade or keyboard doesn't drop fast mode. Notifications arriving are *not* a
    trigger. Mode switches are written to the event log.
- **Capture:** `AccessibilityService.takeScreenshot()` (Android 11 / API 30+) — a
  single frame, no screen-recording notification. Skipped while the screen is off.
  Below API 30 the service logs *"Screen scanning unsupported on this Android
  version"* and stays idle.
- **Classifier:** TensorFlow Lite on-device (`NsfwClassifier`), 2 threads, running
  the GantMan 5-class model (drawings / hentai / neutral / porn / sexy). The frame
  is shrunk to 224×224 by repeated filtered halving (≈ area averaging — a single
  big bitmap scale aliases and made a plain logo read as 0.65 "hentai" in testing),
  converted to RGB in 0..1, and classified.
- **Signal:** the threshold applies to **sexy + porn + hentai** probability. On
  suggestive photos (swimwear, lingerie…) that is essentially the *sexy* class,
  which is the primary signal; explicit content moves its probability to
  *porn*/*hentai* (a sexy-only score would miss it), so those count too.
- **Confirmation:** `DetectionConfirmer` — a frame is positive when its signal ≥
  `NSFW_THRESHOLD` = **0.3**; a detection is confirmed after
  at least `CONFIRMATION_COUNT` = **2** positive frames within the last
  `CONFIRMATION_WINDOW_MS` = **7 s**. Negative frames in between don't reset
  anything (e.g. positive → negative → positive within 7 s confirms); positives
  simply age out of the window. This bounds time-to-detection at about 7 s in
  fast mode (1.5 s frames) and normal mode (6 s frames).
  - **Why 0.3 (and how this scale differs from the old model):** the previous
    model (OpenNSFW) only separated "explicit" from "everything else", so a swimwear
    photo and an ordinary photo both scored low and no threshold could split them.
    This model is a 5-way softmax with a separate *sexy* class, and it is
    **confident**: ordinary content puts almost everything on *neutral* (or
    *drawings*). In testing (`tools/verify_nsfw_model.py`: sample photos plus the
    same photos laid out as 1080×2400 phone screens) the signal had a median of
    0.01 and a maximum of 0.18 (textures/illustrations leaking into *hentai*).
    0.3 sits just above that noise while still firing when only ~30% of the
    probability is suggestive/explicit — far below "sexy is the most likely class"
    (~0.5). Lower it for more sensitivity; the per-class frame log shows where real
    content lands.
- **Calibration logging (temporary):** with `LOG_EVERY_FRAME_SCORE = true` every
  classified frame is logged, e.g.
  `Scan frame: signal=0.6430 [>= 0.30] sexy=0.612 porn=0.031 hentai=0.000 neutral=0.340 drawings=0.017 trigger=event app=com.whatsapp positives=1/2`
  (`positives` = positive frames currently inside the window).
  Set the flag to `false` (or delete it and its one use) when calibration is done;
  while on, the event log rotates within a few hours of heavy use.
- **Lifecycle diagnostics:** the service logs each connect (instance number, pid,
  process age), each unbind (whether it was still enabled in Settings), and — on
  Android 11+ — the system-recorded reason the previous Guardian process ended
  (`CRASH`, `CRASH_NATIVE`, `LOW_MEMORY`, `ANR`, `USER_REQUESTED`, …). These lines
  (plus core-service create/destroy) are also written to a separate
  `files/guardian-diagnostics.log` (256 KiB cap, own rotation) that per-frame
  lines never go to, so they can't be rotated away; it is shown in its own
  "Diagnostics log" section on the main screen.
- **On a confirmed detection:**
  - a thumbnail (longest side 256 px, JPEG) is saved to
    `files/detections/detection-<timestamp>.jpg` (newest 100 kept) and a metadata
    line (timestamp, signal as `confidence`, per-class scores, trigger
    `periodic`/`event`, foreground app, thumbnail name) is appended to
    `files/detections/detections.jsonl`;
  - a `CONFIRMED screen detection …` entry is written to the event log;
  - a `DetectionEvent` is published on `DetectionBus` — the later lock stage
    registers a `DetectionListener` there;
  - stub reaction: a local notification **"Guardian: flagged content detected"**.
    No lock, no blocking. It sounds/vibrates only when first posted; later
    detections update it silently until it is dismissed.
- **Same-content cooldown:** each confirmed detection is fingerprinted (64-bit
  dHash of the screen). If it matches content already reported within
  `DETECTION_COOLDOWN_MS` = **60 s** (≤ `SAME_CONTENT_MAX_DISTANCE` = 10 differing
  bits), it is suppressed entirely — no notification, thumbnail, log entry or
  `DetectionBus` event. Different content is reported immediately; the same content
  is reported again after 60 s. The number suppressed is shown on the main screen
  and in the next `CONFIRMED` log line.

### Model: source and license

| | |
|---|---|
| Model | **GantMan nsfw_model** — MobileNetV2 (depth 1.4, 224×224) fine-tuned into 5 classes: drawings, hentai, neutral, porn, sexy (~92% validation accuracy per its training log) |
| Source | https://github.com/GantMan/nsfw_model — official release `1.2.0`, asset `mobilenet_v2_140_224.1.zip` (SHA-256 `22c0892695929639c16ea302996b8f64df9c52e7a6c1d874c1de1047bfe109f7`). Bundled unmodified: its `saved_model.tflite`. (The README's S3 links return 403; the GitHub release asset is reachable.) |
| Licenses | nsfw_model: **MIT** (© 2020 The nsfw_model Developers); base MobileNetV2 weights: **Apache 2.0** (Google) — full texts in `third_party/nsfw_model/` |
| Bundled file | `assets/models/nsfw_mobilenet_v2_140_224.tflite`, float32, 17.4 MB, SHA-256 `380f98f7685f9d8a386f8cc595b6dfcb972989aae3d1b8b270d3a4a5b96fab40` |
| I/O | input `1×224×224×3` float32, RGB scaled to 0..1 (as in the project's `predict.py`); output `1×5` softmax in `class_labels.txt` order |
| Verification | `tools/verify_nsfw_model.py`: bundled file is byte-identical to the release's; TFLite matches the release's SavedModel to within 0.000004 |

### Known limitations

- **View-once / screenshot-protected content** (FLAG_SECURE windows, e.g. WhatsApp
  and Telegram view-once media, some banking apps) cannot be captured — accepted
  per spec. These frames are skipped and logged (rate-limited) as "secure window".
- The **whole screen** is squashed into one 224×224 input, so a small image inside
  a larger page (e.g. a thumbnail in a chat list) may score low until opened
  full-screen.
- Accuracy on real content can only be judged on-device; the threshold (0.3) is a
  starting point to tune with the per-frame (per-class) score log.
- The model is a MobileNet: fast and small, but less accurate than large models.
  It was trained on whole photos, so screens with a lot of UI around an image score
  lower than the image alone.

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
- **One positive frame doesn't confirm; N positives within the window do** —
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
- **Screen-scan logic:** `DetectionConfirmerTest`, `DetectionCooldownTest`,
  `CaptureSchedulerTest`, `NsfwPreprocessorTest`, `DetectionEventTest`,
  `ScanLogTest` (pure JVM).
- **Log retention:** `LogFilesTest` (diagnostics survive main-log rotation).
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
