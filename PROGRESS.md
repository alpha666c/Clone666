# Build Progress — Game Autopilot

Shared status log for this rebuild. Read this file first in any new
session before touching the repo. Full plan/rationale is at
`/root/.claude/plans/a-generic-no-root-android-purring-bee.md` (may not
be reachable from a different container — this file is the durable copy).

## Architecture (LLM-brain version, not JSON-rules)

The autopilot is an AI brain that watches the screen via MediaProjection
+ accessibility + ML Kit OCR, calls a cloud LLM (OpenAI-compatible:
OpenAI, NVIDIA NIM, any compatible vision model), and dispatches taps /
swipes / waits via accessibility gestures. A persistent overlay sits on
top of the running game with Start / Stop / Quit. Per-game knowledge
lives in each game's system prompt, stored in a small game library.

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
| C | Capture + accessibility + core loop (ScreenCaptureManager, ML Kit OCR, AccessibilityService, GestureDispatcher, DecisionLoop, AutopilotController) | done | (this commit) |
| D | UI + overlay + wiring (Activities, GameListAdapter, OverlayService) | not done | - |
| E | README + verification pass | not done | - |

**Next batch: D — UI activities + overlay service + App class.**

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
