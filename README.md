# Game Autopilot

A no-root Android app that plays mobile games on your behalf using an
LLM that **sees the screen** (like Gemini "search on screen"), thinks
about what to do, and dispatches taps / swipes via the Accessibility
service. The app sits as a persistent floating overlay on top of your
running game, with Start / Stop / Quit always one tap away.

Package: `com.gameautopilot.app` · minSdk 26 · target/compile 34 ·
Kotlin 1.9.24 · AGP 8.5.2 · sideload-only.

## How it works

```
+----------------------+         +-------------------------+
| MainActivity         |         | OverlayService (FGS)    |
|  game library + FAB  |  tap →  |  type=mediaProjection   |
+----------------------+         |  floating control chip  |
                                 +------------+------------+
                                              |
        +-------------------------------------+-------------------------------------+
        |                                     |                                     |
+---------------+              +----------------------------+              +-----------------+
| ScreenCapture |              | AutopilotAccessibilitySvc  |              | LLM brain       |
| MediaProj +   |  ── frames ─►|  reads node tree +         |              | OpenAI/NVIDIA/  |
| ImageReader   |              |  dispatches gestures       |              | Gemini vision   |
+-------+-------+              +-------------+--------------+              +--------+--------+
        │                                    ▲                                      ▲
        │ Bitmap                             │ Action (tap/swipe/back/...)          │ image+text
        ▼                                    │                                      │
+-------+----------+ ──base64 jpeg + OCR + a11y nodes──────────────────────────────+│
| DecisionLoop     |                                                                │
| (capture→think   |◄── BrainDecision { thought, actions[], confidence } ──────────+
|  →act→wait)      |
+------------------+
```

Each tick the loop:

1. Pulls the latest frame from the `MediaProjection` VirtualDisplay.
2. Runs ML Kit Text Recognition over the bitmap.
3. Flattens the accessibility tree into bounded text/clickable nodes
   (capped at 80).
4. Builds a `BrainContext` (game prompt, screenshot base64, OCR lines,
   a11y lines, screen size, recent actions, persisted per-game memory).
5. Calls the configured provider — an OpenAI-compatible `/chat/completions`
   endpoint (OpenAI, NVIDIA NIM) or Gemini's `generateContent` REST API —
   with a strict-JSON response format. The brain must return:
   ```json
   { "thought": "...", "actions": [{"type":"tap","x":540,"y":1280}, ...],
     "confidence": 0.0-1.0, "memory": "optional persistent notes" }
   ```
6. Dispatches each action via `AccessibilityService.dispatchGesture()`
   (taps and swipes) or `GLOBAL_ACTION_BACK`. `wait` actions extend the
   delay before the next tick. Rate-limited to N actions/minute.
7. Updates the floating overlay status (idle / thinking / acting / error).

## Build

Open in Android Studio Hedgehog (or newer) and run, or:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You'll need a JDK 17 toolchain.

**No local toolchain? Use the GitHub Actions workflow.** `.github/workflows/build-apk.yml`
builds the debug APK on GitHub's runners — no Android Studio, no local
JDK/SDK needed:

1. Go to the repo's **Actions** tab → **Build APK** in the left sidebar.
2. Click **Run workflow**, pick the branch, run it.
3. Once it finishes, open the run → **Artifacts** → download
   `game-autopilot-debug-apk` (a zip containing `app-debug.apk`).
4. Sideload it: `adb install -r app-debug.apk`, or copy it to the phone
   and open it with "Install unknown apps" allowed for that source.

It also runs automatically on every push to `main` or
`claude/android-game-autopilot-7mc6mx`.

## Permissions

The app needs three things, granted from the in-app **Permissions** screen
(toolbar overflow → Permissions):

1. **Accessibility service** — for reading the running game's node tree
   and dispatching synthetic taps/swipes. Opens *Settings → Accessibility
   → Installed apps → Game Autopilot* — turn it on.
2. **Display over other apps** — for the floating control chip. Opens
   *Settings → Special app access → Display over other apps*.
3. **Post notifications** (Android 13+) — for the ongoing service
   notification while the autopilot is running.

`MediaProjection` consent (the "Start recording or casting" dialog) is
requested fresh **each time** you tap *Launch & Autopilot* — the system
does not let apps store this token across sessions.

## API key setup

Settings → **API provider** (radio group, three options):

- **OpenAI (default).** Base URL `https://api.openai.com/v1`,
  default model `gpt-4o-mini` (vision-capable). Paste your `sk-...` key.
- **NVIDIA NIM.** Base URL `https://integrate.api.nvidia.com/v1`,
  default model `meta/llama-3.2-90b-vision-instruct`. Paste your `nvapi-...`
  key from build.nvidia.com.
- **Google Gemini.** Base URL `https://generativelanguage.googleapis.com/v1beta`,
  default model `gemini-3.5-flash` (GA, vision-capable, supports Computer
  Use). Paste your Gemini API key from Google AI Studio. If you want a
  different Gemini model (e.g. a newer "Pro"-tier release once one ships),
  just type its model id into the **Model** field — no code change needed.

Any OpenAI-compatible base URL + vision-capable model also works under the
OpenAI/NVIDIA options (e.g. LM Studio, vLLM, Together AI's
OpenAI-compatible endpoints). Anthropic is the remaining major provider
that's neither OpenAI-shaped nor Gemini-shaped and would need its own
`Brain` implementation.

**API key storage:** plain `SharedPreferences` at
`/data/data/com.gameautopilot.app/shared_prefs/autopilot_settings.xml`.
This is per-user app-private storage, but it is *not* encrypted at rest.
The file is excluded from cloud backup and device transfer. Use the
**Clear API key** button in Settings to wipe it.

**What leaves your device:** every tick, the screenshot, OCR'd on-screen
text, a flattened accessibility tree, and the per-game memory notes are
sent to whichever provider you've configured (OpenAI / NVIDIA / Google).
If the game you're automating shows an account name, balance, or other
personal info on screen, that content leaves your device on every tick
while the autopilot is running — this is inherent to a vision-LLM-driven
loop, not a bug, but worth knowing before pointing it at anything beyond
casual single-player games.

## Per-game memory

Each game keeps a small persistent free-text "memory" (current goal,
progress milestones, what to try next) written by the brain itself. The
brain's JSON response has an optional `memory` field; whenever it's
present, `DecisionLoop` replaces the stored memory and `AutopilotController`
persists it to `filesDir/memory/{gameId}.txt` (capped at 4000 characters).
The next tick — and the next time you launch the game, even after an app
restart — includes that memory in the prompt, so the brain doesn't have to
rediscover "I'm on level 12 with 3 lives" from scratch every session.

Edit a game → **Reset memory** clears its stored notes (separate from
**Delete**, which removes the whole game). Deleting a game also clears its
memory file.

## Adding a game

1. Tap the **+** floating button.
2. **Pick installed app** — choose the game from your launchable apps.
3. Give it a clear **name** and a focused **system prompt** describing
   the game and your goal. The brain's per-game knowledge lives here.
   Example for a builder game:
   > You are playing Township. Goal: collect ready buildings, fulfill
   > orders that match what we have in storage, and avoid spending
   > diamonds. If a popup appears asking to buy gems, close it. The
   > "collect" buttons appear as yellow circles above buildings.
4. **Tick interval (ms)** — minimum time between brain calls. 1500ms is
   a sane default; lower = faster but more API spend; higher = cheaper.
5. **Save.**

## Running

From the game card, tap **Launch & Autopilot**:

1. App checks API key + permissions, surfaces what's missing.
2. `OverlayService` starts as a foreground service with type
   `mediaProjection`. You'll see an ongoing notification.
3. The MediaProjection consent dialog appears — tap *Start now*.
4. The target game launches, and the floating chip appears on top.
5. Tap **▶ Start** to begin the decision loop. **■ Stop** pauses
   without releasing the projection. **✕ Quit** tears everything down.

Drag the chip anywhere on screen with a touch-and-drag.

## Safety

- **Target package guard**: with *Only act when target game is foreground*
  on (default), the loop skips ticks when your game isn't the foreground
  app. Prevents the bot from acting on your launcher or settings.
- **Action rate limit**: configurable max actions per rolling minute
  (default 30).
- **Out-of-bounds dropping**: any tap/swipe with coords outside the
  current screen size is logged and skipped — never dispatched.
- **Quit kills everything**: the overlay × button tears down the
  decision loop, releases `MediaProjection`, and stops the foreground
  service in one shot. The Accessibility service stays enabled until
  you disable it from system Settings.

## Limitations

- One game per autopilot session (the controller is a singleton).
- Rotation while running is not handled (the VirtualDisplay isn't
  recreated). Lock orientation or Quit/restart on rotate.
- ML Kit Latin text-only OCR — non-Latin scripts will OCR as garbage,
  but the brain still sees the screenshot directly.
- `TemplateMatcher` from the early design is **not** included — the
  vision model handles icon recognition directly from the screenshot.
- API spend is real. With `gpt-4o-mini` at 1.5s tick and ~5KB
  base64 image you'll typically see ~$0.01–$0.05 per minute of play.
- This app uses `QUERY_ALL_PACKAGES` so the App Picker can show all
  launchable installed apps. That permission is restricted on Play
  Store but unrestricted for sideloaded debug builds.

## Terms-of-service warning

**Botting in multiplayer or competitive games can get your account
banned.** This tool exists for solo/idle games, accessibility use cases,
and learning. You are responsible for understanding the ToS of any game
you point it at. The author of this tool takes no responsibility for
bans, lost progress, lost premium currency, or accounts terminated as a
consequence of using it.

## Repo / project log

`PROGRESS.md` at repo root tracks build batches and decisions across
sessions — read it before continuing development. Code is on branch
`claude/android-game-autopilot-7mc6mx` of `viktorhedklin/androidapp`;
`main` is fast-forwarded from it on demand.

## File layout

```
app/src/main/
├── AndroidManifest.xml
├── res/                                  -- layouts, drawables, themes, etc.
└── java/com/gameautopilot/app/
    ├── App.kt                            Application + notif channel
    ├── MainActivity.kt                   game library entry
    ├── ui/                               activities + adapters
    ├── data/                             Game + Settings repos, per-game memory
    ├── brain/                            OpenAI-compatible + Gemini brains
    ├── core/                             Action, ScreenSnapshot, DecisionLoop,
    │                                     AutopilotController, RateLimiter
    ├── accessibility/                    a11y service + node reader + gestures
    ├── capture/                          MediaProjection + ScreenshotEncoder
    ├── vision/                           ML Kit OCR wrapper
    ├── overlay/                          floating control foreground service
    └── util/                             Logger, BitmapUtils, PermissionsUtil
```

Built incrementally across batches A–E (see `PROGRESS.md` for the
commit-by-commit record).
