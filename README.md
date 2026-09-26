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
  بيضان (slang "lame"), suck/sucks ("that sucks"), xx (maths, placeholders).
- **noun mode** (`=word`): Arabic nouns whose letters are also a productive verb/
  adjective stem take only noun affixes (article/prepositions + pronoun endings):
  فرج (اتفرج "watch", افرج "release"), جماع (اجماع "consensus", جماعي
  "collective"), ثدي, شرج, مبادل, شهوة, لبوة, حلمة, بيضان, خنثي, قضيب, نودز, عاريه,
  عاريات, بورن, زبر.

Every term-specific rule (generated from the list file):

<details><summary>Context rules — term → innocent-context companions</summary>

| Term | Suppressed near |
|---|---|
| anus | cancer surgery doctor hospital medical colon bowel anatomy disease patient fissure hemorrhoids colorectal biopsy |
| ball kicking | football soccer match practice drill kids players goal |
| big black | dog car cat bag box hole cloud eyes hat suv truck bird bear coat jacket boots door horse |
| butt | kick kicked kicking cigarette cigarettes rifle gun joke jokes heads head |
| cialis | doctor pharmacy prescription medicine drug pill dose heart pressure pfizer generic |
| circlejerk | reddit thread sub subreddit forum echo |
| cornhole | game board bags tournament toss backyard yard |
| domination | world market military global economic team game league political sports empire |
| escort | police vessel ship ships convoy security guard guards military troops soldiers car ford mission motorcade |
| eunuch | palace court emperor dynasty ottoman china historical ancient servant |
| fecal | matter sample samples test bacteria transplant contamination coliform occult water |
| fingering | guitar piano violin chord chords notes scale bass flute instrument technique taste execution play playing |
| girl on | phone team bike bus train street screen stage instagram tv show left right cover fire |
| hard core | fans fan music punk rock band gamer gamers gaming workout training supporter supporters mode player players |
| hardcore | development developer fans fan music punk rock band gamer gamers gaming workout training supporter supporters mode player players |
| huge fat | cat pay bonus raise salary paycheck lie mistake check |
| intercourse | social friendly familiar daily commercial trade business polite pleasant conversation society family human cultural intellectual frequent constant delightful gaieties renewed acquaintance friends |
| jelly donut | bakery coffee breakfast dunkin shop sugar glazed |
| lolita | nabokov novel book fashion film kubrick style dress gothic |
| make me come | over back home down up with early late again to here there |
| octopussy | bond film movie 007 moore |
| pissing | rain raining down off contest about around |
| rectum | cancer surgery doctor hospital medical colon bowel anatomy disease patient fissure hemorrhoids colorectal biopsy |
| santorum | rick senator campaign republican gop pennsylvania election candidate |
| scat | singing jazz sing singer singers music animal droppings wildlife |
| sex | opposite same gender other bias assault offender offenders offence offense discrimination education trafficking |
| sexual | harassment assault abuse violence health education orientation identity reproductive transmitted crimes crime misconduct allegations rights minorities humiliation |
| sexually | harassed assaulted abused transmitted active explicit |
| sexuality | education identity orientation gender rights human |
| shrimping | boat boats shrimp fishing season net nets gulf trawler |
| skeet | shooting shoot shooter clay trap range gun olympic |
| snatch | thief thieves bag purse phone victory win defeat jaws weightlifting grab stole gold title medal application memory |
| snowballing | effect debt costs problem problems crisis rolling quickly fast snow |
| spunk | courage spirit determination character plucky |
| suck (corroboration-only) | — |
| sucks (corroboration-only) | — |
| tainted love | song "soft cell" cover band album |
| taste my | food cake soup recipe dish sauce pie cookies dinner coffee tea drink cooking |
| tea bagging | game gaming halo players online match kill |
| tied up | work busy meeting meetings traffic phone call boat dog "loose ends" office moment |
| tight white | shirt jeans pants dress top sneakers socks |
| tit | tat bird birds blue great coal |
| tongue in a | cheek |
| tushy | baby diaper rash bidet |
| twinkie | snack hostess cake cream box lunch defense |
| viagra | doctor pharmacy prescription medicine drug pill dose heart pressure pfizer generic |
| xx (corroboration-only) | — |
| xxx | chapter part vol volume phone number format price dollars dollar bowl olympiad pounds code name اسم اسمي رقم هاتف سعر دولار |
| شرج (noun mode) | طبيب دكتور جراحه عمليه بواسير ناسور شرخ مستشفي علاج مرض قولون فتحه منظار |
| لعق | ملعقه عسل "ايس كريم" اصابع طعام قطه كلب جرح |
| لحس | جزم جزمه اقدام رجلين حذاء بياده كلامه كلام وعده وعوده مخه دماغه عقله "ايس كريم" جيلاتي بسكوت شيكولاته ملعقه صحن طبق كلب قطه القطه الكلب اصابع صوابع عسل مربي |
| مص | قصب عصير شفاطه دم دماء سيجاره شيشه ليمون مانجا مصاصه بونبوني حلويات اصابع صوابع ابهام صباع الشعب فلوس |
| تمص | قصب عصير شفاطه دم دماء سيجاره شيشه ليمون مانجا مصاصه بونبوني حلويات اصابع صوابع ابهام صباع الشعب فلوس |
| بيضان (corroboration-only · noun mode) | — |
| ثدي (noun mode) | غرسات سرطان الكشف فحص اشعه ماموجرام طبيب دكتور مستشفي رضاعه رضيع طبي اورام اكتشاف مبكر توعيه زراعه تجميل |
| حلمة (corroboration-only · noun mode) | — |
| فرج (corroboration-only · noun mode) | — |
| شهوة (noun mode) | الله رمضان صيام نفس النفس دين عباده تقوي الدنيا المال الطعام الاكل السلطه الحكم |
| شاذ (corroboration-only) | — |
| مبادل (corroboration-only · noun mode) | — |
| جماع (noun mode) | حكم كفاره صيام رمضان نهار الصوم فقه فتوي شرعا الحج الاحرام |
| قضيب (noun mode) | حديد معدن معدني خرساني صلب تسليح نحاس المونيوم سكه قطار حديديه تنظيف محور مكبس توصيل فوهه اسطواني برغي ميكانيكي مغناطيس كهرباء كهربائي تحكم وقود نووي سلك بندقيه صيد ستاره |
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
| kos | theta sin cos tan |
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
- **Matcher unit-testable with plain strings** — `KeywordMatcherTest` (17 tests)
  and `KeywordRulesTest` (22 tests: context rules, restored terms, morphology).
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
  morphology — triggered and still-suppressed cases), `TextScanTest` (extraction,
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
