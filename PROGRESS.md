# Build Progress — Game Autopilot

Shared status log for this rebuild. Read this file first in any new
session before touching the repo — it's the source of truth across
sessions/agents, not the chat history. Full plan/rationale is at
`/root/.claude/plans/a-generic-no-root-android-purring-bee.md` (may not
be reachable from a different container — this file is the durable copy).

Working rule: **one step per session turn**, then commit + push + update
this file. Don't chain multiple steps in one turn.

## Status table

| Step | Description | Status | Commit |
|------|------------------------------------------------|--------|---------|
| 0 | PROGRESS.md + plan reference | done | (this commit) |
| 1 | AndroidManifest.xml + res/ resources | not done | - |
| 2 | `core/` package (Action, ScreenState, GameProfile, ProfileRegistry, ProfileMemory, ActionDispatcher, DecisionLoop) | not done | - |
| 3 | `accessibility/` package (AutopilotAccessibilityService, NodeTreeReader, GestureDispatcher) | not done | - |
| 4 | `capture/` + `vision/` packages (ScreenCaptureManager, OcrEngine, TemplateMatcher) | not done | - |
| 5 | `profile/` package + `assets/profiles/generic_idle_farm.json` | not done | - |
| 6 | `overlay/` package + `MainActivity.kt` | not done | - |
| 7 | `README.md` | not done | - |
| 8 | Verification pass (consistency check across all steps) | not done | - |

**Next step: 1 — AndroidManifest.xml + res/ resources.**

## Already pushed

- `c80a455` — Gradle/module scaffold: root + `app/build.gradle.kts`,
  `settings.gradle.kts`, `gradle.properties`, wrapper, `.gitignore`,
  `proguard-rules.pro`.

## Key decisions to preserve (don't regress these)

- Package/namespace: `com.gameautopilot.app`. minSdk 26, target/compile 34.
  AGP 8.5.2, Kotlin 1.9.24, JDK 17, Gradle wrapper 8.7.
- **No** `org.json` Gradle dependency — Android ships it natively.
- **No** `buildFeatures.viewBinding` — unused, was removed in original review.
- `DecisionLoop.tick()` must treat `Action.Wait(ms)` as additive delay
  for the *next* tick, not a no-op. This is the most important behavioral
  fix to carry into Step 2 — verify by inspection before committing.
- ML Kit text-recognition only for OCR (no OpenCV). `TemplateMatcher`
  (Step 4) is plain `Bitmap`/`IntArray` NCC matching, kept small, and is
  **intentionally not wired into any profile yet** — it exists for
  icon-only buttons OCR can't read, for future use.
- `generic_idle_farm.json` (Step 5) uses placeholder package
  `"com.example.targetgame"` — user replaces before installing on a real
  device.
- Target repo: `viktorhedklin/androidapp`, branch
  `claude/android-game-autopilot-7mc6mx`. No PR opens unless user asks.
- This is a **rebuild from a textual handoff description**, not a restore
  of the original 2-commit history (`1bfc063`, `e8fa68a`) — that bundle
  is unreachable from this environment. Each step here gets its own
  fresh commit.

## Full file layout target (for reference)

```
app/src/main/
├── AndroidManifest.xml
├── assets/profiles/generic_idle_farm.json
├── res/{values,layout,drawable,mipmap-anydpi-v26,xml}/...
└── java/com/gameautopilot/app/
    ├── MainActivity.kt
    ├── core/ (Action, ScreenState, GameProfile, ProfileRegistry, ProfileMemory, ActionDispatcher, DecisionLoop)
    ├── accessibility/ (AutopilotAccessibilityService, NodeTreeReader, GestureDispatcher)
    ├── capture/ (ScreenCaptureManager)
    ├── vision/ (OcrEngine, TemplateMatcher)
    ├── profile/ (JsonRuleProfile, ProfileLoader)
    └── overlay/ (OverlayService)
```
