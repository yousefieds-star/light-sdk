# Snake — the classic, for the Light Phone III

The snake game from the old handsets, drawn in the two colours of LightOS. Eat, grow, don't run into yourself.

## How it plays

| | |
|---|---|
| **Menu** | Pick a **LEVEL** (1 slow … 9 fast), **WALLS** on or off, and the **THEME**. Your **HIGH SCORE** for that level is right there. Then **PLAY**. |
| **Steering** | Swipe on the board in the direction you want to go. Or tap the left or right half of the board to turn that way. |
| **Scroll wheel** | Roll up to turn left, roll down to turn right. Press it to start, pause or resume. |
| **Scoring** | Each meal is worth the level number in points. Every fifth meal brings a bonus that blinks away after a while; catch it early and it's worth five meals, late and it's worth one. |
| **Walls** | On: the edge ends the game, like the original. Off: the snake comes back around the other side. The frame is dashed to remind you. |
| **Pause** | **PAUSE** in the bottom bar, the back arrow, or the wheel button. Leaving the tool pauses too. |
| **Game over** | Your score, whether it's a new best, and **PLAY AGAIN**. |

The game is over when you stop playing. There are no streaks, timers, reminders or notifications: open it when you feel like a round.

Snake needs no permissions. It never uses the internet, and nothing leaves the phone. The only things it remembers are your settings and your best score on each level.

Screenshots from the LightOS emulator are in [`screenshots/`](screenshots).

## Running in the LightOS emulator

Follow [docs/system_app](../docs/system_app/README.md) once to install the LightOS emulator app as a system app on an API 34 `test-keys` AVD (1080 × 1240, 420 dpi). Then, for each build:

1. In `lighttool.toml`, set `serverPackage = "com.thelightphone.sdk.emulator"` (the commented line).
2. Build and install with the alternate server package allowed:

```
./gradlew -DlightSdk.toolOnly=true -DlightSdk.allowAltServerPackage=true :tool:installDebug
```

3. Open Snake from the LightOS emulator. The emulator's volume keys stand in for the scroll wheel (stock Android keeps the wheel's own key codes for itself, so those can only be tried on a Light Phone).

Set `serverPackage` back to `"com.lightos"` before committing or submitting a build.

## Code map

| File | Role |
|---|---|
| `SnakeGame.kt` | The rules, in plain Kotlin: movement, growth, walls, bonus, scoring. No Android. |
| `GameScreen.kt` | The game screen and its view model: the tick loop, touch and wheel input, pause and game over, haptics |
| `Board.kt` | Draws the board on a Compose canvas and turns touches into swipes and taps |
| `MenuScreen.kt` | Start screen (`@InitialScreen`): level, walls, theme, high score, how to play |
| `HowToPlayScreen.kt` | A short explanation of the game |
| `Settings.kt` | Level, walls, theme and best scores, stored in DataStore |
| `Ui.kt` | Shared LightOS-styled pieces |

Run the engine tests with `./gradlew -DlightSdk.toolOnly=true :tool:testDebugUnitTest`.
