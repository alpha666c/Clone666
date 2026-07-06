# Build Progress — Game Autopilot

Shared status log for this rebuild. Read this file first in any new
session before touching the repo. Full plan/rationale is at
`/root/.claude/plans/a-generic-no-root-android-purring-bee.md` (may not
be reachable from a different container — this file is the durable copy).

## Architecture (LLM-brain version, not JSON-rules)

The autopilot is an AI brain that watches the screen via MediaProjection
+ accessibility + ML Kit OCR, calls a cloud LLM (OpenAI-compatible:
OpenAI, NVIDIA NIM, any compatible vision model — or Google Gemini via
its own `generateContent` REST brain), and dispatches taps / swipes /
waits via accessibility gestures. A persistent overlay sits on top of
the running game with Start / Stop / Quit. Per-game knowledge lives in
each game's system prompt plus a small persistent per-game **memory**
(goal/progress notes the brain writes itself), stored in a small game
library.

User flow: open app → game library → tap "+" to add a game (pick app,
name, system prompt, tick rate) → Settings to set API key + model →
tap a game's "Launch & Autopilot" → permission check → overlay foreground
service starts → MediaProjection consent → game launches → tap Start in
the overlay → DecisionLoop runs: capture → think → act → wait.

## Status table

| Batch | Description | Status | Commit |
|------|------------------------------------------------|--------|---------|
| A | Foundation + UI shell (deps, manifest, all resources) | done | dbc4b0e |
| B | Data + brain (Game, Settings repos; OpenAI-compatible brain; core/Action) | done | 77f4573 |
| C | Capture + accessibility + core loop (ScreenCaptureManager, ML Kit OCR, AccessibilityService, GestureDispatcher, DecisionLoop, AutopilotController) | done | 0845eee |
| D | UI + overlay + wiring (Activities, GameListAdapter, OverlayService) | done | f0a5fb5 |
| E | README + verification pass | done | 0770aef |
| F | Set-of-marks + TypeText + stuck-state + cycle log | done | 4740b3d |
| G | Debug overlay + a11y fast path | done | (this batch) |
| H | Reasoner/ScreenReader/ActionExecutor interface refactor | done | (this batch) |
| I | Gemini provider + persistent per-game memory | done | 625302b |
| J | Wake lock, ad/interruption recovery, structured memory, web research | done | 18db172 |
| K | Rotation handling, key encryption, IME/node-recycle fixes, multi-script OCR, unit tests | done | (this batch) |
| - | Review-driven fixes (backup exclusions, dead code, privacy doc) | done | 694609a |
| - | Gradle wrapper files + GitHub Actions APK build workflow | done | (this commit) |

**Next batch: none queued.**

## Batch K — fix everything the Batch J audit left open (this session)

User asked for all six items the prior session flagged as "left open" to
actually get fixed, not just documented, plus a fresh pass over every file
Batch J hadn't touched. Verified locally (see the build recipe above) —
`assembleDebug` succeeds, `testDebugUnitTest` passes 48/48.

1. **Rotation handling, for real.** `ScreenCaptureManager` gained a
   `resize()` that recreates the VirtualDisplay+ImageReader at new
   dimensions using the *same* MediaProjection (no re-consent), and is
   now fully synchronized (`synchronized(lock)` around start/resize/
   captureLatest/tearDown) since resize can now race a concurrent tick's
   `captureLatest()`. `AutopilotController.onConfigurationChanged()` was
   previously an empty stub that wasn't even wired to anything —
   `OverlayService` now overrides `onConfigurationChanged` and forwards
   into it.
2. **Fixed a real, silently-always-failing bug.** `submitImeAction()`
   reflected on `AccessibilityNodeInfo::class.java.getDeclaredField
   ("ACTION_IME_ENTER")` — that field has never existed on that class;
   the real constant is the API-30+ public
   `AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER`. The
   reflection always threw and was silently swallowed by `runCatching`,
   so `typeText`'s `submit: true` has never actually submitted anything
   in any build. Fixed with the real API behind an `SDK_INT >= R` guard
   (no accessibility-safe equivalent exists below API 30).
3. **Fixed a node-recycling gap.** Neither `NodeTreeReader.traverse()`
   nor `AutopilotAccessibilityService.findEditable()` recycled child
   `AccessibilityNodeInfo` objects obtained via `getChild()` (only the
   root was recycled) — on API 26-32 (before object pooling was
   removed), running an 80-node traversal every tick for a long session
   could exhaust the node pool. Both now recycle children they've
   finished visiting.
4. **Encrypted API keys at rest.** `SettingsRepository` moved from plain
   `SharedPreferences` to `EncryptedSharedPreferences` (new file
   `autopilot_settings_secure`, AES-256-SIV keys / AES-256-GCM values via
   Android Keystore), with a one-time migration that copies any existing
   plaintext `autopilot_settings` values over and wipes the old file —
   existing installs don't lose settings or crash on upgrade. Falls back
   to a plain (distinctly-named) file if Keystore is unavailable on the
   device, rather than crashing every Settings access.
5. **Multi-script OCR.** `OcrEngine` now takes an `OcrScript` (LATIN /
   CHINESE / JAPANESE / KOREAN / DEVANAGARI) and picks the matching
   ML Kit recognizer options class; `Settings.ocrScript` + a Settings
   radio group expose it, `AutopilotController.start()` swaps the
   `OcrEngine`/`ScreenReader` instances when the setting changes between
   runs. All four extra ML Kit artifacts are the "unbundled" variant —
   models download via Play Services on first use, not bundled in the
   APK, so this doesn't bloat the base install.
6. **Unit test suite.** `app/src/test/` now covers every pure-logic
   class with no Android framework dependency: `RateLimiter`,
   `PerceptualHash.hamming`, `Action.fromJson`/`shortLabel`,
   `BrainResponseParser.parse`, `GameMemory`, `InterruptionRecovery`,
   `A11yFastPath` — 48 tests, `./gradlew :app:testDebugUnitTest`. Needed
   `testOptions.unitTests.isReturnDefaultValues = true` in
   `app/build.gradle.kts` since `Logger` wraps `android.util.Log`, which
   throws "not mocked" on a plain JVM test run otherwise; and a
   test-only `org.json:json` dependency since production code relies on
   Android shipping `org.json` natively (a deliberate decision — see
   "Key decisions" below — that doesn't hold on a plain JVM test runner).

**Deliberately left alone, and why:**
- **One game per session** (singleton `AutopilotController`) — this
  isn't a bug. Android has exactly one foreground app at a time, so
  "run two autopilots at once" isn't a coherent ask on a single device;
  building multi-instance support would add real complexity for a
  scenario the OS doesn't actually allow.
- **`AccessibilityService.onInterrupt()` being empty** — that's the
  correct, normal implementation of that callback; there's nothing an
  autopilot needs to do when the system interrupts accessibility
  feedback. Not a stub, not a bug.

## Batch J — elite-autopilot gap closure (this session)

Prompted by a from-scratch codebase audit against 5 requirements: Gemini-driven
play, internet research, ad/interruption recovery, persistent per-game memory,
keep-screen-on. Findings: memory existed but was a flat text blob; no wake
lock at all; no research capability at all; ad recovery was implicit
(foreground-guard just paused the loop, no active dismiss). Plan, in order:

1. **Wake lock** — `OverlayService` acquires a `SCREEN_BRIGHT_WAKE_LOCK`
   (deprecated but the only wake-lock flag that keeps the *display* on, not
   just the CPU — `FLAG_KEEP_SCREEN_ON` doesn't help here since the game
   activity owns the window, not ours) for the duration of a run, released on
   stop/quit. New `WAKE_LOCK` manifest permission.
2. **Finish Batch G** — wire `A11yFastPath` into `DecisionLoop` (checked
   before the brain call, skips the round-trip on repeat Collect/Claim
   buttons) and `DebugOverlayView` into `OverlayService` behind a
   `showDebugOverlay` setting.
3. **Batch H** — extract `ScreenReader` (capture+OCR+a11y+marks),
   `ActionExecutor` (gesture dispatch), `Reasoner` (brain call) interfaces;
   existing classes become the default impls, `AutopilotController` wires
   them. Groundwork for swapping in e.g. a Shizuku executor later without
   touching `DecisionLoop`.
4. **Ad/interruption recovery** — foreground-guard tick no longer just
   idles when off-target; it OCR-keyword-matches common ad-close patterns
   ("skip", "close", "no thanks", "x", "continue") against on-screen text
   and taps the best match, else sends `BACK`, bounded to a few attempts
   before giving up and idling as before.
5. **Structured per-game memory** — `GameMemoryStore`/`Brain` moved from a
   flat text blob to a small JSON doc (`goal`, `unlocks[]`, `notes`,
   `updatedAtMs`), still brain-writable, with transparent migration of any
   pre-existing plain-text memory file into `notes`.
6. **Internet research** — new `Action.WebSearch(query)` the brain can
   emit; `WebSearchProvider` (Brave Search API, needs a Settings API key)
   runs it, result snippet gets folded into the next tick's `BrainContext`
   as `researchNotes` (not persisted — ephemeral, one-tick scratch space)
   so the brain can look up unfamiliar mechanics without a human alt-tabbing
   out of the loop for it.

None of this changes the core `capture → think → act → wait` loop shape —
it's additive on top of what F/I already built.

**Build verification note:** this sandbox still has no Android SDK (same
limitation as the gradle-wrapper session) and the Gradle wrapper's
distribution download is blocked by this environment's proxy — but only
because `services.gradle.org/distributions/...` 307-redirects to
`github.com/gradle/gradle-distributions/releases/download/...`, and raw
GitHub release-asset downloads are the specific thing this session's
egress policy blocks (API/git-over-https to github.com work fine, that's
a different host path). So none of Batch J was compiled locally at
first — verified only by manual read-through — and CI turned out to be a
dead end too: it built successfully once (`ca4c28c`, ~2.5 min) but every
run after that fails in 3-4 seconds with zero logs, which is the
signature of GitHub Actions minutes/spending-limit exhaustion on the
account, not a code problem. Nothing here can fix that; the repo owner
needs to check Settings → Billing on the `alpha666c` account.

**Actually verified Batch J locally instead**, bypassing both dead ends:
`dl.google.com` (Android SDK) and Maven Central *are* reachable through
the proxy, and this sandbox already has a system Gradle (8.14.3) install
separate from the project's wrapper. Recipe, repeatable in any session
with this same proxy policy:

```
mkdir -p /opt/android-sdk/cmdline-tools
cd /tmp && curl -sSL -o cmdline-tools.zip \
  "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
unzip -q cmdline-tools.zip -d /opt/android-sdk/cmdline-tools
mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
export ANDROID_HOME=/opt/android-sdk
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
  "platforms;android-34" "build-tools;34.0.0" "platform-tools"
echo "sdk.dir=$ANDROID_HOME" > local.properties   # gitignored, don't commit
ANDROID_HOME=$ANDROID_HOME gradle :app:assembleDebug --console=plain
```

This found a real bug the manual review missed: `DecisionLoop.kt`'s
`webSearch != null` smart-cast doesn't survive into the `runCatching {
webSearch.search(...) }` lambda (Kotlin doesn't smart-cast a nullable
member `val` across a lambda boundary even when it's provably immutable
in that scope) — fixed by capturing it to a local `val` first. Fixed in
`86fc5a7`; `:app:assembleDebug` now succeeds and produces a real
`app-debug.apk`. Lesson: manual read-through catches wiring/type
mismatches but not this class of Kotlin flow-analysis gap — always
prefer an actual compile when one is reachable at all.

## Gradle wrapper was missing (fixed 2026-07-06)

The repo had `gradle/wrapper/gradle-wrapper.properties` but **not**
`gradlew`, `gradlew.bat`, or `gradle-wrapper.jar` — an oversight from the
original Batch A scaffold. This meant `./gradlew` (as documented in the
README) never actually worked, locally or in CI. Regenerated via
`gradle wrapper --gradle-version 8.7 --distribution-type bin` (had to run
with `--offline` in this sandbox since the distribution-URL validation
network call is blocked by this environment's GitHub-scoped proxy — not
an issue for real CI runners or local dev machines, which have normal
internet access). Also added `.github/workflows/build-apk.yml`
(`workflow_dispatch` + push to `main`/this branch) to build the debug
APK on GitHub's runners and upload it as a downloadable artifact, since
this sandbox has no Android SDK and can't build/verify an APK directly.

## Push access note (2026-07-06)

Commits `3c9c1d1` (batch G, wip) and this Batch I commit are sitting on
the local branch `claude/android-game-autopilot-7mc6mx` **unpushed**.
`git push` fails with `Permission to viktorhedklin/Androidapp.git denied
to alpha666c` — the GitHub identity behind this session has read access
(fetch/branch-list work fine) but not write access. The repo owner needs
to grant `alpha666c` collaborator/write access (or fix the GitHub App
installation) before any further pushes can land. Diagnosed via direct
curl against the git relay (`127.0.0.1:41729`) and the GitHub MCP tools —
not a proxy glitch, a real permissions gap. Next session: check whether
push works now before assuming it's still blocked.

## Batch I — Gemini provider + persistent per-game memory (done)

**Gemini provider:**
- `brain/PromptBuilder.kt` (new) + `brain/BrainResponseParser.kt` (new) +
  `brain/CallAwait.kt` (new) — extracted the system/user prompt text and
  the strict-JSON response parsing out of `OpenAiCompatibleBrain` so both
  it and the new `GeminiBrain` share identical prompt/schema logic; only
  the HTTP envelope differs per provider.
- `brain/GeminiBrain.kt` (new) — calls Gemini's `generateContent` REST
  endpoint (`system_instruction` + `contents[].parts[]` with
  `inline_data` base64 image, `generationConfig.responseMimeType =
  "application/json"`), auth via `x-goog-api-key` header. Reports
  `promptFeedback.blockReason` in the exception when Gemini blocks a
  response instead of just failing opaquely.
- `data/Settings.kt` — `useNvidia: Boolean` replaced with
  `provider: BrainProvider` (`OPENAI`/`NVIDIA`/`GEMINI` enum) plus
  `defaultUrlFor`/`defaultModelFor` helpers. Default Gemini model is
  `gemini-3.5-flash` (GA as of mid-2026, vision-capable, supports
  Computer Use) — **not** `gemini-3.5-pro`, which per Google's own
  materials is scheduled to ship ~2026-07-17 and doesn't exist yet as an
  API model id. Once it (or any newer model) ships, just paste its model
  id into Settings → Model — no code change needed.
- `data/SettingsRepository.kt` — persists `provider` as a string enum
  name instead of the old boolean pref key.
- `ui/SettingsActivity.kt` + `res/layout/activity_settings.xml` — the
  provider toggle became a 3-way `RadioGroup` (OpenAI / NVIDIA NIM /
  Google Gemini) since a boolean switch no longer fits 3 options.
- `brain/BrainFactory.kt` — branches on `settings.provider`.

**Persistent per-game memory:**
- `brain/Brain.kt` — `BrainContext.gameMemory: String` (fed into the
  prompt each tick) and `BrainDecision.memoryUpdate: String?` (brain
  writes back an updated summary when something worth remembering
  happened — current goal, progress, what to try next).
- `data/GameMemoryStore.kt` (new) — one small text file per game at
  `filesDir/memory/{gameId}.txt`, capped at 4000 chars.
- `core/DecisionLoop.kt` — constructor gained `initialMemory` +
  `onMemoryUpdate`; keeps memory in a `@Volatile var`, includes it in
  every `BrainContext`, and calls back out when the brain updates it.
- `core/AutopilotController.kt` — loads memory from `GameMemoryStore` in
  `start()`, persists updates via the loop's callback (fire-and-forget
  `scope.launch`). Memory intentionally survives `stop()`/`quit()` — the
  whole point is that it persists across sessions.
- `ui/AddGameActivity.kt` + `activity_add_game.xml` — **Reset memory**
  button (visible only when editing an existing game), separate from
  **Delete**. Deleting a game also clears its memory file.
- `PromptBuilder.kt`'s system prompt documents the optional `"memory"`
  JSON field and instructs the brain to keep it under ~120 words and
  only update it when something changed.

## Batch G — in progress (paused before session limit)

Two new files are on disk and committed but **not yet wired in**:

- `core/A11yFastPath.kt` — cheap heuristic: after a successful TapMark
  the loop remembers the label; on subsequent ticks with the same
  label still visible AND small perceptual-hash delta, re-issues the
  tap without calling the brain. Capped at 3 consecutive fast-path
  ticks. Created but `DecisionLoop` does not call it yet.
- `overlay/DebugOverlayView.kt` — full-screen non-interactive View
  that draws current SoM mark boxes + highlights the last-tapped
  mark in red. Created but `OverlayService` does not add it to the
  WindowManager yet.

**To finish G in the next session:**

1. `core/AutopilotController.kt`: expose a `debugFrame: SharedFlow<DebugFrame>`
   (where `DebugFrame(marks, lastTappedMarkId)`) emitted from
   `buildSnapshot` / after each dispatch.
2. `overlay/OverlayService.kt`: when `Settings.showDebugOverlay` is on
   (new setting), add a second WindowManager view of type
   `TYPE_APPLICATION_OVERLAY` with flags
   `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_NO_LIMITS`
   sized to MATCH_PARENT, hosting `DebugOverlayView`. Collect the
   debug flow into `view.update(...)`. Remove on quit.
3. `core/DecisionLoop.kt`: before calling `brain.decide`, ask the
   `A11yFastPath` instance (held in the loop) for a fast-path action
   list; if non-null, dispatch those and skip the brain call. On
   normal brain decisions, call `fastPath.recordBrainDispatch(...)`.
   Add fastPath as a constructor parameter.
4. `core/AutopilotController.start()`: instantiate `A11yFastPath()` and
   pass to DecisionLoop. Reset it in `quit()`.
5. `data/Settings` + `SettingsRepository` + `SettingsActivity` + layout
   + strings: add `showDebugOverlay: Boolean = false` and
   `useFastPath: Boolean = true` toggles.

## Batch H — still not started

Extract `ScreenReader`, `ActionExecutor`, `Reasoner` interfaces. The
existing classes become default impls. Lets us later add Shizuku
executor / on-device Gemini-Nano reasoner without touching the loop.

## Architecture upgrades landed in F

- **Set-of-marks prompting.** Each tick, `CandidateExtractor` builds up
  to 80 `MarkBox` candidates from (clickable a11y nodes ∪ OCR line
  boxes), de-duplicated by IoU > 0.6, sorted by area DESC. The
  screenshot sent to the brain has translucent numbered rectangles
  drawn on top by `SetOfMarksOverlay`. The brain's system prompt now
  strongly prefers `tapMark`/`longPressMark`/`typeText` over raw
  pixel coordinates — VLMs are much better at picking IDs than coords.
- **`Action.TypeText`** routed via
  `AutopilotAccessibilityService.typeOnFocused()` →
  `AccessibilityNodeInfo.ACTION_SET_TEXT` on the focused editable
  node, with optional `IME_ENTER` submit.
- **Stuck-state circuit breaker.** `DecisionLoop` tracks a 3-tick
  rolling buffer of `dHash` values (`util/PerceptualHash`). If all
  three are within Hamming distance 5, sets `BrainContext.stuckHint`
  so the brain knows to try a recovery action; trips into
  `LoopPhase.ERROR` after 6 consecutive stuck ticks.
- **Cycle log.** `core/CycleLog` writes one JSONL line per tick to
  `filesDir/cycles/YYYY-MM-DD.log` (timestamp, hash, delta, mark count,
  thought, action labels, dispatch results, foreground pkg). Auto-prunes
  after 7 days. Toggle in Settings.

Note: `core/Action.kt` was moved up from Batch C to Batch B because the
brain depends on it (`BrainDecision.actions: List<Action>`).

## Already pushed

- `c80a455` — Gradle/module scaffold: root + `app/build.gradle.kts`,
  `settings.gradle.kts`, `gradle.properties`, wrapper, `.gitignore`,
  `proguard-rules.pro`.
- `fe1da06` — `PROGRESS.md` shared cross-session build log.
- (this commit) — Batch A.

## Key decisions to preserve (don't regress these)

- Package/namespace: `com.gameautopilot.app`. minSdk 26, target/compile 34.
  AGP 8.5.2, Kotlin 1.9.24, JDK 17, Gradle wrapper 8.7.
- **No** `org.json` Gradle dependency — Android ships it natively.
- **No** `buildFeatures.viewBinding` — use `findViewById`.
- `DecisionLoop.tick()` must treat `Action.Wait(ms)` as additive delay
  for the *next* tick, not a no-op. This is the most important behavioral
  fix — verify by inspection in Batch C.
- ML Kit text-recognition only for OCR (no OpenCV).
- Brain is OpenAI-compatible Chat Completions with vision (image_url with
  base64 jpeg). Default model `gpt-4o-mini`. NVIDIA NIM works by setting
  base URL to `https://integrate.api.nvidia.com/v1` + a vision-capable
  model. Anthropic & Gemini explicitly out of scope for v1.
- Screenshot encoding: longest edge ≤ 1024px, JPEG q=80, base64.
- API key stored in plain SharedPreferences (`autopilot_settings.xml`,
  excluded from backups). Documented in README. "Clear API key" button
  in Settings.
- Safety: per-game `targetPackage` foreground check before dispatch,
  configurable max actions/minute (default 30), always-visible
  Stop/Quit in overlay.
- Target repo: `viktorhedklin/androidapp`, branch
  `claude/android-game-autopilot-7mc6mx`. `main` is fast-forwarded from
  this branch on demand. No PR opens unless user asks.
- This is a **rebuild from a textual handoff description**, not a restore
  of the original 2-commit history (`1bfc063`, `e8fa68a`) — that bundle
  is unreachable from this environment.

## File layout (LLM-brain target)

```
app/src/main/
├── AndroidManifest.xml
├── res/{values,xml,layout,drawable,mipmap-anydpi-v26,menu}/...
└── java/com/gameautopilot/app/
    ├── App.kt
    ├── MainActivity.kt
    ├── ui/{GameListAdapter, AddGameActivity, AppPickerAdapter,
    │       SettingsActivity, PermissionsActivity, ProjectionRequestActivity}.kt
    ├── data/{Game, GameRepository, Settings, SettingsRepository}.kt
    ├── brain/{Brain, BrainContext, OpenAiCompatibleBrain, BrainFactory}.kt
    ├── core/{Action, ScreenSnapshot, ActionDispatcher, DecisionLoop,
    │         AutopilotController, ActionRing}.kt
    ├── accessibility/{AutopilotAccessibilityService, NodeTreeReader, GestureDispatcher}.kt
    ├── capture/{ScreenCaptureManager, ScreenshotEncoder}.kt
    ├── vision/OcrEngine.kt
    ├── overlay/{OverlayService, OverlayView}.kt
    └── util/{BitmapUtils, PermissionsUtil, Logger}.kt
```

Note: after Batch A the project **will not compile yet** — the manifest
references classes (`App`, `MainActivity`, `ui.*`, `accessibility.*`,
`overlay.OverlayService`) that don't exist until Batches B–D. This is
intentional pacing; the next batch is meaningful work and the manifest
is locked in.
