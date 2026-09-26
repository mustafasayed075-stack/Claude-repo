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
- **Stage 4 — Text scanning:** the same Accessibility Service reads the **visible
  text** in watched apps (WhatsApp, Telegram, browsers) whenever their window
  content changes, and checks it **on-device** against a bundled Arabic/English
  keyword list (based on LDNOOBW, extended for Egyptian Arabic and Franco-Arabic).
  Matches go through the same pipeline as image detections (cooldown, local review
  log with a short text snippet, notification, `DetectionBus`). **Detection and
  logging only.**

Later stages (lock mechanism, reminder, persistence) are
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
├── text/TextNormalizer                Stage 4: text → normalised tokens (Arabic/Latin, leetspeak…) (pure)
├── text/KeywordMatcher                Stage 4: KeywordList (asset parser) + matcher + snippets (pure)
├── text/TextScan                      Stage 4: node-tree text extraction, trigger/debounce, fingerprints (pure)
├── boot/BootReceiver                  Restart service + filtering on boot
└── util/GuardianLog                   Append-only local event log (timestamps)

app/src/main/res/xml/device_admin_policies.xml        Stage 1: device-admin policy declaration
app/src/main/res/xml/accessibility_service_config.xml Stage 3: accessibility service declaration
app/src/main/assets/models/nsfw_mobilenet_v2_140_224.tflite  Stage 3: bundled 5-class NSFW model (see below)
app/src/main/assets/text/keywords.txt                  Stage 4: bundled keyword/phrase list (generated, editable)
app/src/test/...                                       Unit tests (pure JVM)
tools/verify_nsfw_model.py                             Stage 3: re-verifies the bundled model + preprocessing
third_party/nsfw_model/                                Stage 3: model licenses + class labels
tools/build_keyword_list.py                            Stage 4: builds keywords.txt from LDNOOBW + additions,
                                                       context rules, noun-mode terms, roots, exceptions
third_party/ldnoobw/                                   Stage 4: LDNOOBW license (CC BY 4.0)
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

## Stage 4 — Text scanning (conversation context)

No extra setup: it uses the same Accessibility permission as Stage 3 (the service
now also requests window-content-changed events). The main screen shows
**Text scanning: Active**, the number of list entries, text checks, the last check
time and how many were flagged or suppressed.

### How it works

- **Trigger (event-driven only):** a `TYPE_WINDOW_CONTENT_CHANGED` (or window-state)
  event from a package in `ScanConfig.WATCHED_PACKAGES` — the same list as image
  scanning. Bursts of events (typing, scrolling, incoming messages) are coalesced:
  the first schedules one check `TEXT_CHECK_DEBOUNCE_MS` (0.75 s) later and the rest
  are absorbed. No timer. Events from other apps are ignored, and the check also
  verifies the active window still belongs to a watched app.
- **Extraction:** the active window's node tree (`rootInActiveWindow`), text and
  content description of every node visible to the user — no OCR, no screenshot.
  Capped at 2,000 nodes / 50,000 characters per check. Works on every Android
  version the app supports (image scanning still needs Android 11+).
- **Matching:** `KeywordMatcher` (pure Kotlin, on-device, no network):
  - text is normalised — Unicode compatibility forms, case, Latin accents, Arabic
    harakat and tatweel removed, letter variants unified (أ/إ/آ→ا, ة→ه, ى→ي),
    invisible characters dropped;
  - matching is on **whole words** (so "Essex", "cocktail", "كسر", "زبادي",
    "زبون" don't match), with tolerance for **leetspeak** (p0rn, s3x, $ex),
    **stretched letters** (sexxx, boooobs, سكسسس), **spaced-out letters**
    (s e x, s.e.x, س ك س), **masked letters** (p*rn, s*xy), systematic **English
    and Arabic morphology** (inflections, prefixes/suffixes, verb-root derivations)
    and **context rules** for terms that also have an innocent meaning — all
    detailed in *Matching rules in detail* below;
  - multi-word phrases match word by word; `!exception` lines in the list veto
    innocent words (Nikon نيكون, customer زبون, Isaac اسحاق, zebra زبرة, cocky…).
- **On a match** — the same pipeline as image detections:
  - **cooldown**: one fingerprint per (app, matched term), `TEXT_COOLDOWN_MS` = 60 s
    like images; a detection is reported only if at least one term is new for that
    app within the cooldown. A lingering open conversation keeps matching the same
    terms and is suppressed; a new term, or another app, is reported. (The snippet
    itself isn't hashed: it changes with every keystroke and scroll.)
  - **review log**: a line in `files/detections/detections.jsonl` with
    `"kind":"text"`, the matched terms, and a short **snippet** (±40 characters of
    context) instead of a thumbnail — local only, never uploaded;
  - **GuardianLog**: `CONFIRMED text detection: terms=[…] app=…` (the snippet stays
    in the review log);
  - **notification**: the same "Guardian: flagged content detected" notification
    (text: "Flagged words on screen · app · time"), still alerting only once until
    dismissed;
  - **event**: a `DetectionEvent` with `kind = TEXT` on the same `DetectionBus` the
    lock stage will subscribe to. No lock action.

### Keyword list: source and methodology

- **Base:** [LDNOOBW](https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words)
  ("List of Dirty, Naughty, Obscene, and Otherwise Bad Words"), `en` and `ar` lists
  at commit `5faf2ba42d7b1c0977169ec3611df25a3c08eb13` — an established,
  open-source filter list, used the way Stage 2 uses oisd. License **CC BY 4.0**
  (© LDNOOBW contributors; `third_party/ldnoobw/`). **Changes made:** filtered and
  extended as below.
- **Filtering to sexual/explicit content.** LDNOOBW is a general "bad words" list;
  this detector looks for sexual conversations, not rudeness. **Excluded** (never
  matched), with the reason for each in `tools/build_keyword_list.py`: slurs and
  hate terms, generic swearing/insults, and violence/crime-news terms (e.g. rape,
  اغتصاب). Words with a common **innocent or technical meaning** ("suck", "tit",
  "escort", فرج, جماع, قضيب…) and medical terms are **kept with context rules**
  instead of being excluded (see below). Kept: 334 of 403 English and 33 of 38
  Arabic entries.
- **Additions** (73 Arabic/Franco-Arabic entries plus 4 verb roots, 35 English):
  Egyptian Arabic sexual slang (verb forms generated from roots — see below); Franco-Arabic (Arabizi) spellings using digits for
  letters (2 = ء/ق, 3 = ع, 5 = خ, 7 = ح — e.g. a7ba, e2la3y); sexting phrases;
  adult-site names; and evasion spellings the matcher can't derive by rule (pr0n).
  Generic evasions (leetspeak, repeated/spaced letters, diacritics, tatweel) are
  handled by the matcher, so the list doesn't need every variant.
- **Coverage expansion** (glued compounds, genitals / private areas, adult
  clothing, sex toys): see *Coverage expansion* below, including the borderline
  terms added by owner decision.
- **False-positive check:** see *Validation on ordinary-text corpora* below.
- **Editing:** edit `tools/build_keyword_list.py` and run it (or edit
  `app/src/main/assets/text/keywords.txt` directly) and rebuild the APK; no code
  changes are needed. File format, one directive per line (`#` = comment):

  | Line | Meaning |
  |---|---|
  | `word` / `a phrase` | an entry |
  | `word ~ c1 c2 "c 3"` | entry with a **context rule** (innocent-context companions) |
  | `?word` | **corroboration-only** entry |
  | `=word` | Arabic **noun-mode** entry (noun affixes only) — combinable: `?=word ~ …` |
  | `@root ن ي ك` (`~ …` optional) | Arabic **verb root**: derived forms are generated |
  | `@fuse كس طيز > ام م اخت` (`~ …` optional) | **glued compounds**: every front + back is an entry (كسم → كسمك) |
  | `!word` | **exception**: never matches (also behind an Arabic prefix) |

### Matching rules in detail

#### 1. Bare "نيك" and other over-restricted terms

"نيك" on its own never matched because the first false-positive cleanup **removed
it from the list** (it also transliterates "Nick", e.g. نيك فيوري) and kept only its
unambiguous verb forms — it was not an exception entry. It is now back as an entry
with a context rule: it matches on its own ("نيك", "نيييك", "ضحكت نيك"), and is
suppressed only when a name context is nearby (فيوري, جوناس, كيريوس, ممثل, لاعب,
رائد فضاء, ناسا, الأمريكي …). The exceptions for the *names* نيكو/نيكي/نيكول/نيكولا
(distinct words) and نيكون (Nikon) stay.

The same pass over every restriction from the first cleanup:

| Restriction | Verdict | Now |
|---|---|---|
| نيك removed (Nick) | too broad | restored + context rule |
| سحاق removed (إسحاق Isaac) | too broad | restored; `!اسحاق` exception is enough |
| زبر removed (زبرة zebra) | too broad | restored (زبري/زبرك/زبرها…); `!زبره` exception + context rule |
| بورن removed (Bourne, Dragonborn) | too broad | restored + context rule |
| نودز only in fixed phrases (network nodes) | too broad | bare نودز restored + context rule |
| عاريه only in fixed phrases (bare fibres) | too broad | restored + context rule |
| قضيب removed (rod) | too broad | restored + context rule |
| xxx, intercourse removed | too broad | restored + context rules |
| s&m removed (matched "SMD") | too broad — the real bug was affixes on 2-letter entries | restored; 1–2 letter Latin entries match exactly |
| 2-letter Latin entries exact-only | genuine fix | kept |
| 1–2 letter Arabic entries: short affix set (زب+ون = زبون) | genuine fix | kept |
| standalone زبي removed (took ح → حزبي) | genuine fix (covered by زب + ي) | kept |
| exceptions behind Arabic prefixes (ونيكي) | genuine fix | kept |

#### 2. Context rules (innocent-context disambiguation)

A term with a context rule is **suppressed** when one of its companion words (or
phrases) appears **in the same sentence within 12 words** of it — companions
themselves match with affixes (حديد → الحديد, حديدي). Otherwise it matches normally:
alone, or — even with a companion nearby — whenever an **unambiguous explicit term**
(one without a context rule) is elsewhere in the same sentence ("قضيب حديد و طيز"
matches). Ambiguous terms never vouch for each other. Sentences end at . ! ? ؟ ؛ ; …
or a line break.

Two stronger variants, used only where the data showed companions can't work:
- **corroboration-only** (`?word`): the innocent sense is a name or everyday usage
  no companion list captures — the term counts only alongside an unambiguous
  explicit term in the same sentence. Used for: فرج (common first name, "relief"),
  مبادل (Mubadala fund, "exchange"), حلمة (حلمه = "his dream"), شاذ ("anomalous"),
  بيضان (slang "lame"), suck/sucks ("that sucks"), xx (maths, placeholders) — and,
  in the coverage expansion, formal clinical vocabulary (testicle, خصية, مهبل…),
  هزاز (phone vibrate mode) and booty.
- **noun mode** (`=word`): Arabic nouns whose letters are also a productive verb/
  adjective stem take only noun affixes (article/prepositions + pronoun endings):
  فرج (اتفرج "watch", افرج "release"), جماع (اجماع "consensus", جماعي
  "collective"), ثدي, شرج, مبادل, شهوة, لبوة, حلمة, بيضان, خنثي, قضيب, نودز, عاريه,
  عاريات, بورن, زبر.

Every term-specific rule (generated from the list file):

<details><summary>Context rules — term → innocent-context companions</summary>

Shared companion sets, written once: **[clinical]** = doctor doctors dr physician urologist gynecologist gynaecologist dermatologist obgyn gp clinic hospital medical medically medicine medication medications meds treatment treatments treat treated ointment antibiotic antibiotics antifungal infection infections infected yeast uti std stds sti stis hpv herpes chlamydia gonorrhea syphilis wart warts rash itch itching itchy irritation swelling lump lumps bump bumps pimple pimples cyst cysts cancer tumor tumour biopsy ultrasound test tests tested scan exam examination checkup symptom symptoms diagnosis diagnosed disease condition surgery surgical circumcision circumcised pain painful ache aching sore sores ulcer ulcers lesion lesions discharge bleeding redness burning fungal bacterial viral vaccine smear hernia varicocele hydrocele phimosis balanitis vaginitis vaginosis thrush cervix cervical uterus ovary ovaries ovarian bladder kidney prostate urine urinate urination urinating hormone hormones testosterone estrogen period periods menstrual menstruation pregnancy pregnant fertility infertility sperm hygiene anatomy patient; **[clinical-ar]** = طبيب طبيبه دكتور دكتوره الطبيب الدكتور استشاري اخصائي مستشفي عياده كشف فحص فحوصات تحليل تحاليل اشعه سونار منظار علاج دواء ادويه مرهم مضاد التهاب التهابات عدوي فطريات فطري بكتيريا ميكروب افرازات افراز حكه هرش الم الام وجع اوجاع تورم ورم اورام انتفاخ نزيف حبوب بثور قرحه تقرحات اعراض تشخيص مرض امراض جراحه جراحيه عمليه ختان طهاره دوالي البروستاتا بروستاتا مثانه البول التبول الحمل الدوره الحيض الطمث التبويض الولاده العقم الخصوبه هرمون هرمونات تستوستيرون سرطان ثاليل ثالول هربس زهري سيلان الصحه طبي طبيه نظافه تشوه خلقي ضعف انتصاب منويه رضاعه ماموجرام استشاره المريض; **[clothing]** = dress dresses top tops shirt blouse skirt jeans pants trousers leggings shorts bra bras fit fits fitting fitted size sizes sizing petite xs xl material fabric cotton color colour colors shade wash washed washing laundry dryer pack pair pairs ordered returned store purchase bought neckline waist waistband hem length stretch coverage cover covers covered comfortable swimsuit suit bikini bottoms flattering sweater cardigan jacket coat romper jumpsuit tunic underwear lines cup cups padding padded lined support strapless thong thighs sagging photo model.

| Term | Suppressed near |
|---|---|
| adult toys | lego puzzle puzzles board game games collectible collectibles figures action figure |
| anal | [clinical] fissure fissures fistula canal gland glands sphincter hemorrhoids piles retentive colorectal stool stools |
| anus | cancer surgery doctor hospital medical colon bowel anatomy disease patient fissure hemorrhoids colorectal biopsy [clinical] |
| areola (corroboration-only) | — |
| ball kicking | football soccer match practice drill kids players goal |
| big black | dog car cat bag box hole cloud eyes hat suv truck bird bear coat jacket boots door horse |
| boob | [clothing] [clinical] |
| boobs | [clothing] [clinical] |
| booty (corroboration-only) | — |
| breasts | [clinical] [clothing] chest armpit armpits sweat sweating veins tender tenderness nursing cramps cramping cup cups exposed large heavy heaviness chicken turkey duck grilled roasted recipe breastfeeding feeding milk pump implants implant mammogram screening |
| butt | kick kicked kicking cigarette cigarettes rifle gun joke jokes heads head [clothing] [clinical] |
| buttocks | [clinical] [clothing] injection injections muscle muscles exercise squats glutes |
| cialis | doctor pharmacy prescription medicine drug pill dose heart pressure pfizer generic |
| circlejerk | reddit thread sub subreddit forum echo |
| clitoris | [clinical] |
| cooter | turtle turtles river |
| cornhole | game board bags tournament toss backyard yard |
| domination | world market military global economic team game league political sports empire |
| escort | police vessel ship ships convoy security guard guards military troops soldiers car ford mission motorcade |
| eunuch | palace court emperor dynasty ottoman china historical ancient servant |
| family jewels | grandmother grandma mother heirloom inherited ring rings necklace diamond diamonds stolen gold |
| fecal | matter sample samples test bacteria transplant contamination coliform occult water |
| fingering | guitar piano violin chord chords notes scale bass flute instrument technique taste execution play playing |
| foreskin (corroboration-only) | — |
| genitalia (corroboration-only) | — |
| genitals | [clinical] |
| girl on | phone team bike bus train street screen stage instagram tv show left right cover fire |
| glans (corroboration-only) | — |
| hard core | fans fan music punk rock band gamer gamers gaming workout training supporter supporters mode player players |
| hardcore | development developer fans fan music punk rock band gamer gamers gaming workout training supporter supporters mode player players |
| hooters | restaurant restaurants wings owl owls bar waitress |
| huge fat | cat pay bonus raise salary paycheck lie mistake check |
| intercourse | social friendly familiar daily commercial trade business polite pleasant conversation society family human cultural intellectual frequent constant delightful gaieties renewed acquaintance friends |
| jelly donut | bakery coffee breakfast dunkin shop sugar glazed |
| kegel balls | [clinical] pelvic floor postpartum physiotherapy physio exercise exercises incontinence |
| knob | door doors drawer drawers volume radio stove oven cabinet handle turn turned button gear dial |
| knockers | door doors brass iron |
| kos | theta sin cos tan |
| labia (corroboration-only) | — |
| lolita | nabokov novel book fashion film kubrick style dress gothic |
| make me come | over back home down up with early late again to here there |
| muff | ear earmuff earmuffs hand hands fur winter warm warmer |
| nipple | [clinical] [clothing] breastfeeding feeding baby latch bottle pacifier |
| nipples | [clinical] [clothing] breastfeeding feeding baby latch bottle pacifier |
| nude | [clothing] heels pumps lipstick tone beige lining neutral hosiery tights nail polish palette |
| octopussy | bond film movie 007 moore |
| panties | [clothing] liner liners |
| panty | [clothing] liner liners |
| pecker | wood woodpecker bird birds keep |
| penis | [clinical] |
| penis pump | [clinical] erectile dysfunction ed |
| pissing | rain raining down off contest about around |
| private parts (corroboration-only) | — |
| prostate massager | [clinical] |
| pubic (corroboration-only) | — |
| rectum | cancer surgery doctor hospital medical colon bowel anatomy disease patient fissure hemorrhoids colorectal biopsy [clinical] |
| s&m | size sizes small medium large xs xl fit fits ordered order petite |
| santorum | rick senator campaign republican gop pennsylvania election candidate |
| scat | singing jazz sing singer singers music animal droppings wildlife |
| scrotum (corroboration-only) | — |
| sex | opposite same gender other bias assault offender offenders offence offense discrimination education trafficking |
| sexual | harassment assault abuse violence health education orientation identity reproductive transmitted crimes crime misconduct allegations rights minorities humiliation |
| sexuality | education identity orientation gender rights human |
| sexually | harassed assaulted abused transmitted active explicit |
| shrimping | boat boats shrimp fishing season net nets gulf trawler |
| skeet | shooting shoot shooter clay trap range gun olympic |
| snatch | thief thieves bag purse phone victory win defeat jaws weightlifting grab stole gold title medal application memory up |
| snowballing | effect debt costs problem problems crisis rolling quickly fast snow |
| spunk | courage spirit determination character plucky |
| suck (corroboration-only) | — |
| sucks (corroboration-only) | — |
| tainted love | song "soft cell" cover band album |
| taste my | food cake soup recipe dish sauce pie cookies dinner coffee tea drink cooking |
| tea bagging | game gaming halo players online match kill |
| testicle (corroboration-only) | — |
| tied up | work busy meeting meetings traffic phone call boat dog "loose ends" office moment |
| tight white | shirt jeans pants dress top sneakers socks |
| tit | tat bird birds blue great coal |
| tongue in a | cheek |
| tushy | baby diaper rash bidet |
| twinkie | snack hostess cake cream box lunch defense |
| vagina | [clinical] |
| vaginal (corroboration-only) | — |
| viagra | doctor pharmacy prescription medicine drug pill dose heart pressure pfizer generic |
| vibrator | [clinical] phone phones motor motors haptic concrete massage massager |
| vulva | [clinical] |
| xx (corroboration-only) | — |
| xxx | chapter part vol volume phone number format price dollars dollar bowl olympiad pounds code name اسم اسمي رقم هاتف سعر دولار |
| شرج (noun mode) | طبيب دكتور جراحه عمليه بواسير ناسور شرخ مستشفي علاج مرض قولون فتحه منظار [clinical-ar] |
| لعق | ملعقه عسل "ايس كريم" اصابع طعام قطه كلب جرح |
| لحس | جزم جزمه اقدام رجلين حذاء بياده كلامه كلام وعده وعوده مخه دماغه عقله "ايس كريم" جيلاتي بسكوت شيكولاته ملعقه صحن طبق كلب قطه القطه الكلب اصابع صوابع عسل مربي |
| مص | قصب عصير شفاطه دم دماء سيجاره شيشه ليمون مانجا مصاصه بونبوني حلويات اصابع صوابع ابهام صباع الشعب فلوس |
| تمص | قصب عصير شفاطه دم دماء سيجاره شيشه ليمون مانجا مصاصه بونبوني حلويات اصابع صوابع ابهام صباع الشعب فلوس |
| بيضان (corroboration-only · noun mode) | — |
| ثدي (noun mode) | غرسات سرطان الكشف فحص اشعه ماموجرام طبيب دكتور مستشفي رضاعه رضيع طبي اورام اكتشاف مبكر توعيه زراعه تجميل [clinical-ar] |
| حلمة (corroboration-only · noun mode) | — |
| بظر | [clinical-ar] الاناث |
| فرج (corroboration-only · noun mode) | — |
| شهوة (noun mode) | الله رمضان صيام نفس النفس دين عباده تقوي الدنيا المال الطعام الاكل السلطه الحكم |
| شاذ (corroboration-only) | — |
| مبادل (corroboration-only · noun mode) | — |
| جماع (noun mode) | حكم كفاره صيام رمضان نهار الصوم فقه فتوي شرعا الحج الاحرام |
| قضيب (noun mode) | حديد معدن معدني خرساني صلب تسليح نحاس المونيوم سكه قطار حديديه تنظيف محور مكبس توصيل فوهه اسطواني برغي ميكانيكي مغناطيس كهرباء كهربائي تحكم وقود نووي سلك بندقيه صيد ستاره [clinical-ar] |
| خنثي (noun mode) | طبي حاله جراحه فقه حكم مولود طفل هرمونات |
| احتلام | بلوغ غسل الغسل حكم صيام رمضان فقه طهاره مراهق مراهقه |
| نيك | مارفل ممثل مغني لاعب تنس مدرب شخصيه النجم فيوري جوناس كارتر كيرجيوس كيريوس نولتي كيج رائد فضاء ناسا الامريكي الاميركي الامريكيان الاميركيان |
| لبوة (noun mode) | اسد اسود غابه حديقه حيوان حيوانات سفاري صيد شبل اشبال |
| نايك | كوتشي كوتش حذاء جزمه شوز سنيكرز اديداس بوما ماركه ماركات براند تيشيرت رياضي تريننج شنطه لوجو محل متجر جوردن شركه شركات كوكاكولا فيتون ابل |
| زبر (noun mode) | حيوان حمار وحشي مخطط مخططه حديقه غابه اسد زرافه سافاري خطوط عبور مشاه زرار كباسين جيب جيوب |
| بورن (noun mode) | جيسون دراجون ديمون مات برشلونه حي كاتالونيا كوميديا موسيقي اغاني فكاهه ساخره |
| بورنو | ولايه نيجيريا مايدوغوري يوب بوكو حرام |
| عاريه (noun mode) | تماما الصحه الياف سلك اسلاك ايد ايدي يد بيد العين بالعين عين الحقيقه حقيقه جدران جدار حيطان ارض اقدام قدم صخور جبال اشجار فروع اغصان شجر |
| عاريات (noun mode) | الياف سلك اسلاك جدران اشجار فروع اغصان |
| نودز (noun mode) | شبكه كلاستر سيرفر سيرفرات خوادم بلوك بلوكتشين بلوكشين عقد عقده جراف شجره كود برمجه خوارزميه كمبيوتر حواسيب داتا بيانات بايثون جافا وصل بيتوصلوا ببعض خلايا عصبيه استيراد اورج رسومي |
| مهبل (corroboration-only · noun mode) | — |
| العضو الذكري (corroboration-only) | — |
| عضو ذكري (corroboration-only) | — |
| عضوي الذكري (corroboration-only) | — |
| عضوه الذكري (corroboration-only) | — |
| عضوك الذكري (corroboration-only) | — |
| العضو التناسلي (corroboration-only) | — |
| عضو تناسلي (corroboration-only) | — |
| عضوي التناسلي (corroboration-only) | — |
| عضوه التناسلي (corroboration-only) | — |
| الاعضاء التناسليه (corroboration-only) | — |
| اعضاء تناسليه (corroboration-only) | — |
| اعضائي التناسليه (corroboration-only) | — |
| الاعضاء الحميمه (corroboration-only) | — |
| المنطقه الحميمه (corroboration-only) | — |
| منطقه حميمه (corroboration-only) | — |
| المناطق الحميمه (corroboration-only) | — |
| المنطقه الحساسه (corroboration-only) | — |
| خصيه (corroboration-only) | — |
| خصيتان (corroboration-only) | — |
| كيس الصفن (corroboration-only) | — |
| حشفه (corroboration-only · noun mode) | — |
| اثداء (noun mode) | [clinical-ar] |
| قلفه (corroboration-only · noun mode) | — |
| عانه (corroboration-only · noun mode) | — |
| موخره (noun mode) | [clinical-ar] سياره السياره عربيه طائره الطائره قطار سفينه حافله اتوبيس الصف الطابور الجيش القافله الموكب الترتيب جدول الدوري المركز الفريق المنتخب الملعب القائمه الركب الراس الجمجمه الدماغ الشاحنه المركبه حقنه العضل المسرح القاعه الطائرات القوات راس الصداره حلاقه الابط الابطين الدبوس حلت احتلت جاءت تذيلت الدول الترتيب الاكواع الركب الفخذين |
| نهود (noun mode) | السودان كردفان مدينه ولايه |
| ملابس مثيره | للجدل الجدل جدل للاهتمام للسخريه للانتباه للاعجاب للدهشه |
| مهبل صناعي | [clinical-ar] ترميم تجميل |
| منشط جنسي | [clinical-ar] القذف اضرار اضراره ضبط مصادره مغشوشه مجهوله المصدر هيئه الدواء وزاره |
| منشطات جنسيه | [clinical-ar] القذف اضرار اضراره ضبط مصادره مغشوشه مجهوله المصدر هيئه الدواء وزاره |
| هزاز (corroboration-only · noun mode) | — |
| root ن ي ك | — (no rule) |
| root ش ر م ط | — (no rule) |
| root ل ح س | جزم جزمه اقدام رجلين حذاء بياده كلامه كلام وعده وعوده مخه دماغه عقله "ايس كريم" جيلاتي بسكوت شيكولاته ملعقه صحن طبق كلب قطه القطه الكلب اصابع صوابع عسل مربي |
| root م ص ص | قصب عصير شفاطه دم دماء سيجاره شيشه ليمون مانجا مصاصه بونبوني حلويات اصابع صوابع ابهام صباع الشعب فلوس |

</details>

#### 3. Morphology

**Arabic** (on normalised text):
- **Prefixes** on entries of 3+ letters: conjunction و/ف × article/preposition/verb
  prefix (ال لل بال ب ل, future ه/ح, and the imperfect/progressive بي بت بن هي هت هن
  حي حت حن ي ت ن ا).
- **Suffixes**: pronoun and plural endings ي ك ه ها هم هن كم كو نا ني ات ين يه, verb
  endings و وا ت تي تني تو (+ object pronouns تك ته تها وه وها وهم وني وك), and the
  **ة → ت** change before a suffix (شرموطة → شرموطتك, متناكة → متناكتك).
- **Verb roots** (`@root`): the standard stems are generated, then take the affixes
  above — sound (ل ح س): لحس لاحس ملحوس اتلحس متلحس تلحس لحاس; hollow (ن ي ك): نيك ناك
  نايك منيوك اتناك متناك تناك نياك; doubled (م ص ص): مص مصاص ممصوص اتمص متمص + imperfect
  stems يمص بيمص هيمص…; four-letter (ش ر م ط): شرمط شرموط اتشرمط متشرمط تشرمط شراميط.
  So بيتناك, هينيكها, اتشرمطت, بيمص, متناكتك all match without being listed. Roots in
  the list: ن ي ك, ش ر م ط, ل ح س (context rule), م ص ص (context rule).
- 1–2 letter entries (كس, زب, بز) keep the short, safe affix set (ال/وال; ي ك ه ها هم كم
  نا ات).

**English / Franco-Arabic** (Latin entries of 3+ letters): plurals (-s -es -z,
y → -ies), -ed/-d, -er/-ers, -ing and informal -in (4+ letters), e-dropping
(grope → groping), consonant doubling with -ing/-ed/-y/-ie (cum → cumming,
slut → slutty), diminutives -y/-ie/-ies (boob → boobie/boobies), Arabizi endings
(-ak -ek -ik -ha -i -ny -ni); **spelling variants** ph→f, ck→k, z→s (puzzy, kok,
boobz); **masked** letters (p*rn, s*xy: same length, '*' for any letter, at least
half the letters kept). Leetspeak and stretched letters come from normalisation.

**Tried and rejected on the corpus** (each produced ordinary-word matches):
c→k spelling (success ~ sucks, skates ~ scat), collapsing every double letter
(seeks ~ seks, cookie ~ cock, pony ~ poon), doubling with -er (scatter, titter),
collapsed spellings with affixes (بتتفرج → بت + فرج), leetspeak on digit-only or
1–2 character tokens (717 ~ tit, 5M ~ sm), Egyptian attached datives لي/لك/لها
(العقلي "mental" = ا + لعق + لي, بناكلها "we eat it" = ب + ناك + لها), and the root
ه ي ج (هيجي/هاجي "will come" everywhere).

### Coverage expansion: glued compounds, anatomy, adult clothing, sex toys

Four additions on top of the same machinery (context rules, morphology, corpus
validation). Everything is generated by `tools/build_keyword_list.py`; exact
term lists and companions are in the list file and in the table above.

#### 1. Glued compounds (`@fuse`)

"كسمك" never matched: كس is a 2-letter entry, which only takes the short, safe
suffix set, and no compound entry existed. Rather than listing spellings one by
one, a new directive generates whole families:

```
@fuse كس طيز > ام م اخت خت خالت عمت مرات ست اهل ابو دين عرض
@fuse يا ابنال بنتال يابنال يابنتال > متناك متناكه شرموط شرموطه منيوك منيوكه قحبه عاهر عاهره مومس لبوه
@fuse kos koss kus kuss > om omm um omk umk okht o5t ekht e5t ukht ahl
@fuse cum cock dick pussy cunt tit tits boob boobs slut whore porn sex jizz twat clit anal butt > head face hole sucker licker lover slut whore fuck bag dump rag stain shot star slave doll toy tape cam chat pic vid video site plug ring pump cage machine swing shop …
```

Every front + back becomes an ordinary entry, so the usual morphology then applies:
كسم → كسم, كسمك, كسمها, وكسمين; كسخت → كسختك; كسام → كسامك; كسعرض; يامتناكه;
ابنالمتناكه; kosom → kosomak; kossokht → kossokhtak; cocksucker(s), cumslut,
dickhead, pornstar, sextape, buttplug (also leetspeak: c0cksucker).
- Contractions are listed as explicit backs (ام → م, اخت → خت). The ا is **never**
  dropped from اب/ابو/اهل: كس + ب = كسب "gain".
- The vocative يا is glued only onto a closed list of insult nouns. It is not a
  general prefix, because يا + نيك = يانيك (the footballer Yannick Carrasco).
- English and Franco-Arabic products were checked against a 370k-word English
  dictionary (dwyl/english-words). Only kusum (a name and a tree) collided and is an
  exception. cockhead and cockshot (archaic or technical words) were left in.
- On the 1.9M-word original corpora the rule added 17 matches (كسم, كسمك, كسمها in
  Egyptian tweets), all genuine fused insults.

**Bug fixed on the way:** entries ending in ة were not found when a suffix turns
ة into ت (قحبتك, عاهرتك, مؤخرتها). The affix rule allowed them, but the index
lookup never mapped ت back to ة. `ArabicMorphology.stems` now does.

#### 2. Genitals and private areas

| Group | Terms | Rule |
|---|---|---|
| English slang, no innocent sense | cameltoe, nutsack, ballsack, coochie, vajayjay, punani, poonani, minge (+ existing cock, dick, pussy, cunt, twat, clit, tits, titties, schlong, quim…) | always match |
| English slang with a homonym | pecker, knob, muff, cooter, hooters, knockers, "family jewels"; booty | context rule (woodpecker; door/volume knob; ear muff; turtle; restaurant/owls; door knockers; heirloom jewellery); booty is corroboration-only (fit talk in clothing reviews, pirate booty) |
| English formal / body words | breasts, buttocks (new); penis, vagina, vulva, clitoris, genitals, nipple(s), anal, anus, rectum, boob(s), butt (existing, now ruled) | **clinical** companions (doctor, symptoms, infection, pain, rash, discharge…); body/clothing words also get **clothing** companions (bra, fit, size, jeans…) |
| English clinical vocabulary | testicle, scrotum, foreskin, glans, labia, genitalia, vaginal, areola, pubic, "private parts" | **corroboration-only** (`?`) |
| Arabic slang | طياز, ازبار, خصاوي, نهدها, نهديها, نهداها, نهودها, نهود (+ existing كس, زب, زبر, طيز, بز, بزاز) | always match (نهود: Sudanese town En Nahud context) |
| Arabic formal | مؤخرة (noun mode), أثداء; قضيب, شرج, ثدي, بظر (existing, now with clinical companions) | clinical companions; مؤخرة also rankings (في المؤخرة, الترتيب), vehicles, the back of the head |
| Arabic clinical vocabulary | مهبل, خصية, خصيتان, كيس الصفن, حشفة, قلفة, عانة, العضو الذكري/التناسلي (+ possessive forms), الأعضاء التناسلية, المنطقة الحميمة/الحساسة | **corroboration-only** |

Why corroboration-only for clinical vocabulary: on the medical corpora these
words are overwhelmingly clinical, and often with no clinical *word* nearby ("حجم
الخصية اليسرى أكبر من اليمنى", "testicle pain"). Explicit text almost always uses
slang. When a formal word does appear in explicit text, it usually has an
unambiguous term in the same sentence, so it still counts ("كسها ومهبلها").

**Tried and dropped, or not added** (an everyday sense dominates):

| Term | Reason |
|---|---|
| dong | Chinese/Vietnamese name (Zheng Yu Dong) and a currency |
| fanny | first name (Fanny Price in the literature corpus); "fanny pack" |
| jugs | ordinary containers ("ritual jugs") |
| اير (Levantine) | ف + اير = فاير "fire"; إير in names (Air France, AirPods, "ذا إير" restaurant) |
| عير (Levantine) | typo of غير ("تويتر عير فيسبوك"); Egyptian عيرة "fake" |
| bare نهد | نهدى/نهدي "we gift / calm down"; only possessive forms kept |
| wang, johnson, member, package, junk, tool, rod, meat, wiener, balls, nuts, rack, melons, bush | everyday words or names |
| prick, arse, bum | generic insults (excluded like "asshole") |
| taint, gooch, shaft, beaver, manhood, hymen, penile, perineum | ordinary or purely clinical words |
| بتاعي/بتاعك, صدرها | "my thing" and "her chest": far too generic |
| شفرات (labia) | "blades"; covered by المهبل in "شفرات المهبل" |

#### 3. Adult and sexual clothing

Only items and phrasing specific to a sexual context are added:
- **English:** crotchless, edible underwear/panties, peekaboo / open cup / cupless
  bra, nipple tassels/pasties, bodystocking, latex catsuit, gimp suit/mask, fetish
  wear/outfit/gear, bondage gear, assless chaps, stripper heels/outfit.
- **Arabic:** ملابس داخلية مثيرة/شفافة, ملابس/قمصان نوم مثيرة, قميص نوم مثير/شفاف,
  لانجري مثير, ملابس/لبس/بدلة/قميص إغراء, فتيش and ملابس فتيش.
- **Rules:** فتيش is in noun mode, so ت + فتيش = تفتيش "inspection" is not matched.
  ملابس مثيرة has a context rule for "مثيرة للجدل" (controversial clothing in news).

**Borderline terms — added by your decision (unconditional, no context rule).**
These were flagged in the coverage round and are now in the list on purpose: max
sensitivity, with false positives on ordinary shopping and clothing talk accepted.
The counts are all matches on the corpora (every one is an ordinary shopping,
fashion or everyday use):

| Term | Known innocent sense (accepted) | Matches |
|---|---|---|
| lingerie | ordinary shops, fashion/retail news | 10 in clothing reviews ("lingerie bag", "my lingerie drawer") |
| thong | underwear style; flip-flop | 6 in clothing reviews ("no need to wear a thong") |
| g-string (also g string, gstring) | underwear style; violin/guitar string | 0 |
| garter | wedding garter; garter snake | 1 ("use a garter" for stockings) |
| babydoll | dress style ("babydoll dress") | 2 |
| corset | mainstream fashion; medical back brace | 1 ("corset-style summer tops") |
| fishnets | hosiery | 0 |
| micro bikini | swimwear | 0 |
| لانجري | lingerie-shop vocabulary | 0 |
| قميص نوم | everyday nightgown, bridal trousseau talk | 0 |
| بيبي دول | babydoll sleepwear | 0 |
| كلوت فتلة | thong-cut underwear | 0 |
| بدلة رقص | belly-dance costume (weddings, classes) | 0 |
| ملابس فاضحة | "indecent clothing" in news and dress-code debates | 0 |

Still **not** added, because they were not in the approved list: suspenders,
negligee, chemise, teddy, bustier, bralette, stockings, sheer, see-through and
اندر فتلة.

Existing generic underwear and colour words exposed by the clothing-review
corpus now have clothing/laundry companions: panty, panties, nude (a colour),
s&m (sizes "S & M"). Other shopping fixes: "tees" was matching Arabizi teez
through the spelling key, and booties (ankle boots), muffin ("muffin top") and
knobby are now exceptions.

#### 4. Sex toys and sexual aids

- **English:** sex toy, sextoy, butt/anal plug, anal beads, fleshlight, cock/penis
  ring, penis extender, love/vibrating egg, rabbit/bullet/wand vibrator, sex/love
  doll, blow up doll, realdoll, masturbator, pocket pussy, ben wa balls, nipple
  clamps, clit clamp, chastity/cock cage, sex swing, sex/fucking machine (+ existing
  dildo, vibrator, strap on, ball gag, bullet vibe).
- **Arabic:** ألعاب/لعبة/أدوات جنسية, قضيب/زب/زبر/كس صناعي, دمية/دمى جنسية, ديلدو,
  فايبريتور, فلشلايت, هزاز جنسي, منشط جنسي, منشطات جنسية.
- **Context rules:**
  - vibrator: phone/haptic motor, concrete vibrator, clinical.
  - penis pump: erectile dysfunction, clinical.
  - prostate massager, kegel balls: pelvic floor, physiotherapy, clinical.
  - adult toys: LEGO, board games, collectibles.
  - مهبل صناعي: reconstructive surgery.
  - منشط جنسي: seizure and counterfeit-drug news, clinical.
  - هزاز alone is corroboration-only (a phone's vibrate mode, كرسي هزاز "rocking
    chair"); the phrase هزاز جنسي always matches.
- **Borderline aids — added by your decision** (unconditional; same accepted
  tradeoff). Matches on the corpora are in brackets, all ordinary or clinical uses:
  - lube [1], lubricant [8]: bike and car lubricant; medical advice on dryness and
    condoms.
  - مزلق [2, as المزلقات in medical advice], جل مزلق [0]: medical gel, playground
    slide. Egyptian مزلقان (level crossing) is **not** matched: ان is not an accepted
    ending.
  - magic wand [0]: a toy or fairy wand, a muscle massager.
  - aphrodisiac [4, doctors describing sildenafil or "aphrodisiac foods"], spanish
    fly [0]: food and folklore writing.
  - lubricant, جل مزلق and spanish fly were part of the same flagged rows as lube,
    مزلق and aphrodisiac, so they were added with them.

#### Decisions taken

- **Activity words have no clinical context rule — your max-sensitivity choice.**
  sex, intercourse, masturbation, semen, جماع, احتلام and شهوة (and sexual,
  ejaculation) match in medical and health text too, so sexual-health Q&A alerts.
  - **Measured cost** in the medical samples: 2,504 of their 2,543 occurrences are
    active. They drive most alerts there: 541 of 12,432 Arabic lines and 3,384 of
    4,474 English consultations.
  - **The rule that was declined** would have cut those to about 294 and 2,368.
  - **The older context rules are unchanged.** They are for these words' non-sexual
    senses: gender ("the opposite/same/other sex"), social intercourse ("daily,
    frequent intercourse"), and fiqh rulings (حكم الجماع…). They still suppress 39 of
    the 2,543 occurrences in the medical text: sex 24, intercourse 6, شهوة 6, جماع 2,
    احتلام 1. They were not added for health text, so they stay.
- **Still open: "sexy" (83) and "busty" (46) in clothing reviews** ("a sexy
  strapless dress", "great for busty women"). These are existing entries and are
  unchanged.

### Validation on ordinary-text corpora

No explicit content is used: the check measures **false positives** on ordinary
text. `CorpusEvaluationTest` (skipped unless `GUARDIAN_CORPUS_DIR` is set) runs the
bundled list over a directory of text files and writes every match, active or
context-suppressed, to `matches.tsv`.

| Corpus (public, Hugging Face / Project Gutenberg / BBC) | Kind | Words |
|---|---|---|
| `Elfsong/egyptian-tweets` (sample) | Egyptian everyday social text | 301k |
| `Qanadil/ASTD_Arabic_Sentiment_Tweets_Dataset` | Egyptian-dialect tweets | 53k |
| `arbml/Arabic_News` (sample) | Arabic news | 258k |
| `kokojake/oasst2_egyptian_arabic_convs` | Egyptian-Arabic conversations, incl. technical | 947k |
| `HeshamHaroon/Egyptian_English_parallel` | everyday Egyptian / English sentences | 22k |
| `pixelsandpointers/better_daily_dialog` | English everyday conversation | 55k |
| `fancyzhx/ag_news` (sample) | English news | 157k |
| *Pride and Prejudice*, BBC Arabic headlines | literature / news | 131k |

(About 1.9 M words. The Stage 4 README's "~206,000 words" for the conversations
corpus was a word-count error; it is ~947k.)

**Method.** Every corpus was split by line hash into a **dev** half (used to choose
companions, exceptions and the rules above) and a held-out **test** half (only
measured). Each match was then read and labelled by hand as innocent (false
positive) or a genuine sexual/explicit use.

**Results on the held-out test half:**
- *Context rules* (frozen before looking at the test half): of the 151 matches of
  context-ruled terms, 128 were innocent. With the rules, 75 remained active
  (52 innocent, 23 genuine) — **false positives −59%, no genuine match lost**; all
  76 suppressed matches were innocent (breast cancer, CHAPTER XXX, sex
  discrimination, same-sex, "فرج الله قريب", حي البورن, "that sucks"…).
- The remaining clusters (xx in maths, ثديي "a mammal", غرسات الثدي, زبرة zebra, بزي
  مدني "in civilian clothes", ولاية بورنو Nigeria, astronaut Nick Hague, Alexei) were
  then fixed; those fixes were informed by the test half, so the following numbers
  are no longer unbiased: context-ruled terms ≈ 19 innocent / 23 genuine active.
- *Whole list vs. the Stage 4 version, same test half:* 135 → 130 active matches.
  Removed: 35 matches, all innocent ("seeks" ×12, "sex" as gender/discrimination
  ×12, بزي مدني ×3, Borno ×3, تناكة "snobbery" ×3, Alexei, garbled text). Added: 13
  genuine (bare نيك, نودز, بورن هب, escorts…) and 17 innocent — 7 of them tweets
  truncated mid-word (مص… for مصر) or typos (ولحس), 4 Viagra marketing news.
- Known remaining false positives: "sex" meaning gender in classic literature,
  كس as a maths variable in machine-translated code, names like "Dick", tech uses
  of نودز in a different sentence from the tech words, Viagra/Playboy news.

**Coverage expansion: validation.** The same check was re-run on the corpora above
and on about 1.4M words of new text chosen where the new terms have innocent uses:

| Corpus (Hugging Face) | Kind | Words |
|---|---|---|
| `Ahmed-Selem/Shifaa_Arabic_Medical_Consultations` (sample; targeted: reproductive and sexual health, gynaecology, plus other specialties) | Arabic medical Q&A | 382k |
| `lavita/ChatDoctor-HealthCareMagic-100k` (sample; targeted: questions naming genital, breast or anal anatomy, plus others) | English medical Q&A | 420k |
| `saattrupdan/womens-clothing-ecommerce-reviews` (all intimates, sleep, lounge, swim and legwear, plus a sample) | English clothing reviews | 283k |
| `Ruqiya/Arabic_Reviews_of_SHEIN` | Arabic clothing-shop reviews | 22k |
| `IbrahimAmin/egyptian-arabic-fake-reviews` (translated reviews, sample) | Egyptian-Arabic reviews | 150k |
| `arbml/arabic_100k_reviews` (sample) | Arabic hotel, book and product reviews | 150k |

- **Original 1.9M words:** 20 matches added, none removed. 18 are genuine: 17 fused
  insults (كسم/كسمك/كسمها) and the insult مؤخرتك. 2 are innocent: a joke about breast
  milk (بنهودي) and the idiom "إخفاء مؤخرته".
- **Medical and shopping text, lines that alert, before → after:**

  | Corpus | Lines | Before | After |
  |---|---|---|---|
  | Arabic medical | 12,432 | 798 | 536 |
  | English medical | 4,474 consultations | 3,858 | 3,368 |
  | English clothing reviews | 5,399 | 284 | 165 |
  | Arabic shopping (SHEIN) | 2,414 | 0 | 0 |
  | Arabic reviews | 2,683 | 25 | 25 |
  | Egyptian reviews | 1,959 | 8 | 9 |

  The one new Egyptian-review line is "شهوته لساندويتش" ("his craving for a
  sandwich"), found through the ة→ت fix.
- **Anatomy terms in the medical samples, active matches before → after:**
  - English: penis 614 → 377, vagina 200 → 113, nipple(s) 112 → 27, anal 81 → 20,
    anus 80 → 34.
  - Arabic: قضيب 263 → 73, شرج 75 → 12.
  - The remaining ones are mostly in sentences that also contain an unambiguous
    explicit term (masturbation, anal sex…), which overrides suppression. The
    activity words themselves deliberately have no clinical rule (see *Decisions taken*).
- **Across the new corpora:** 945 active matches removed, all clinical or shopping
  uses. 74 added:
  - corroboration-only clinical words next to an explicit term (vaginal, testicle,
    foreskin…);
  - "breasts" in symptom descriptions with no clinical word nearby (16);
  - a few Arabic مؤخرة and خصية uses.
- **Clothing and toy terms (coverage round, before the borderline additions):** no matches on about 600k words of shopping and review
  text, so no false positives. That text has no genuine uses either, so recall rests
  on the unit tests.
- **Owner-approved borderline terms** (added after the coverage round, rerun on
  every corpus):
  - Original 1.9M words: no change.
  - New corpora: 44 matches added, none removed. All are the intended words, with
    no word-form artifacts. All are accepted false positives.
  - Clothing reviews, 23: 20 direct (lingerie 10, thong 6, babydoll 2, corset 1,
    garter 1), plus 3 "panty/panties" matches they make active, because an unconditional term in the
    sentence overrides context suppression.
  - Medical text, 21: 15 direct (lubricant 8, aphrodisiac 4, مزلق 2, lube 1), plus 6
    clinical-word matches they make active through the same override.
  - Lines that alert, before → after: clothing reviews 165 → 188, English medical
    3,368 → 3,384, Arabic medical 536 → 541. Arabic shopping and reviews are
    unchanged.
- **Caveat:** for these additions the matches of both halves were read while tuning,
  so these numbers are not held-out. The dev/test split files are kept, so reruns
  are reproducible.

### Known limitations

- **View-once media with no text at all** is not helped by this stage (accepted;
  only the OS's screenshot protection governs that).
- Only text exposed through accessibility is seen; apps that draw text as images
  or in custom canvases (some games, some web content) show nothing.
- A keyword list can't judge context: explicit words quoted in news or health
  pages in a watched browser will match; innocent new slang won't.
- The list covers English, Arabic script (MSA + Egyptian) and Franco-Arabic; other
  languages, and look-alike letters from other scripts (e.g. Cyrillic "ѕ"), aren't
  handled.

### Stage 4 — Definition of Done → how it's met

- **Runs only within watched apps, on content-changed events** — `TextScanTrigger`
  accepts only content/state-change events from `WATCHED_PACKAGES`, and the check
  re-verifies the active window's package; the accessibility config requests
  `typeWindowContentChanged` (tests: `TextScanTest`).
- **Matching fully on-device, zero network calls** — bundled list + pure-Kotlin
  matcher; `DetectionEventTest` statically checks the `scan` and `text` packages
  use no networking APIs.
- **A match produces a notification, a GuardianLog entry and a saved snippet** —
  `GuardianAccessibilityService.onTextMatched` (notification + `CONFIRMED text
  detection` log line + `detections.jsonl` line with `snippet`; JSON format tested
  in `DetectionEventTest`).
- **Lingering conversation suppressed by a cooldown, like images** —
  `DetectionCooldown.shouldReportAny` with per-(app, term) fingerprints and the same
  60 s period (tests: `DetectionCooldownTest`, and an end-to-end lingering-chat
  simulation in `TextScanTest`).
- **Keyword list is a separate bundled asset** — `assets/text/keywords.txt`, parsed
  at runtime by `KeywordList`; no code change needed to edit it.
- **Matcher unit-testable with plain strings** — `KeywordMatcherTest` (17 tests),
  `KeywordRulesTest` (22 tests: context rules, restored terms, morphology) and
  `KeywordCoverageTest` (17 tests: glued compounds, anatomy, adult clothing, sex
  toys — matched and correctly-excluded cases).
- **No obvious false matches on ordinary conversation** —
  `ordinaryConversationHasNoFalseMatches` (English, Egyptian Arabic, Franco-Arabic,
  news text, and every false positive found by the corpus check) plus the corpus
  check above.

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
- **Text scanning:** `KeywordMatcherTest` (matcher + real list + ordinary-text
  spot-check), `KeywordRulesTest` (context rules, restored terms, Arabic/English
  morphology — triggered and still-suppressed cases), `KeywordCoverageTest` (glued
  compounds, anatomy, adult clothing, sex toys), `TextScanTest` (extraction,
  trigger/debounce, fingerprints, lingering-chat simulation) (pure JVM).
- **Corpus evaluation:** `GUARDIAN_CORPUS_DIR=/path/to/corpora ./gradlew
  testDebugUnitTest --tests '*CorpusEvaluationTest'` → `matches.tsv`.
- **Screen scanning on a device:** enable the accessibility service, open WhatsApp
  or a browser and watch the event log for "fast capture ON/OFF"; the main screen
  shows frames scanned and the last score.
- **Device admin / VPN / service:** exercise on a device via the main screen
  buttons (activate admin, enable DNS filter, refresh blocklist, view log). On a
  non-provisioned test device you can activate plain device-admin and grant VPN
  consent interactively before doing full Device Owner provisioning.

## Scope note

This repository contains **Stages 1–4**. Stages 3 and 4 stop at detect → log →
notify; the lock stage will subscribe to `DetectionBus` (one event type for both,
told apart by `DetectionEvent.kind`). The core service keeps its single
`onServiceReady()` extension point for later stages.
