# FPP Virtual Matrix for Android TV

Turns an Android TV into a **Falcon Player virtual matrix** — a panel that joins an FPP show as a
MultiSync remote, follows the player's timing, and renders its slice of the channel data as a pixel
matrix on the screen.

It is a port of two pieces of [FPP](https://github.com/FalconChristmas/fpp):

| FPP | here |
|---|---|
| `src/channeloutput/FBMatrix.cpp` — the **Virtual Matrix** channel output | `render/MatrixRaster.kt` + `render/MatrixSurfaceView.kt` |
| `src/MultiSync.cpp` — the **MultiSync** control protocol | `proto/FppProtocol.kt` + `proto/MultiSyncClient.kt` |
| `src/fseq/FSEQFile.cpp` — the **FSEQ** sequence reader | `fseq/FseqHeader.kt` + `fseq/FseqReader.kt` |
| `channeloutputthread.cpp` — remote timing | `sync/SyncClock.kt` |

## What it does

- **Joins the show.** Listens on UDP 32320 and the `239.70.80.80` MultiSync group, announces itself
  with a ping v3 packet, and answers discovery, so it appears in the player's MultiSync list like
  any other remote.
- **Follows the player.** Sequence open / start / stop / sync packets drive playback. Between sync
  packets the position free-runs off the local monotonic clock; each packet applies a proportional
  correction, with a hard re-anchor only for a real seek.
- **Renders the matrix.** `width x height` pixels, three channels each, from a configurable start
  channel — the same mapping FPP's Virtual Matrix uses — upscaled to the panel with nearest-neighbour
  so pixel edges stay hard. Optional inter-pixel gaps or dots to look like a real matrix.
- **Fetches its own sequences.** When a sync packet names an `.fseq` this device does not have, it
  pulls it from the master's file API (`GET /api/file/sequences/<name>`). No upload step, no
  per-remote file copy to configure.

## What it deliberately does not do

- **No audio.** Media sync packets are counted and ignored. The show's audio belongs to the player.
- **No FPP command execution.** `CTRL_PKT_FPPCOMMAND` packets are logged for diagnostics but not
  acted on. This device renders the channel data it is told to render; inventing behaviour here
  would put it out of step with every other remote in the show.
- **It is not an FPP instance.** There is no scheduler, no playlist engine, no channel outputs. It
  advertises system type `FPP` in ping packets purely because FPP only offers unicast MultiSync to
  systems reporting a type below `0x80` in remote mode; the model string says what it really is.

## Configuration

On the TV: **MENU** opens settings, **INFO** opens diagnostics, **OK** toggles the stats overlay.

From a laptop, any subset of the config can be pushed as JSON (merged over what is stored):

```bash
adb shell "am start -n app.fppvm.tv/.MainActivity --es config '{\"width\":96,\"height\":48,\"startChannel\":4097}'"
```

| key | meaning |
|---|---|
| `width`, `height` | matrix size in pixels |
| `startChannel` | first channel, **1-based**, exactly as FPP's channel-output page shows it |
| `colorOrder` | `RGB` (default), `RBG`, `GRB`, `GBR`, `BRG`, `BGR` |
| `flipHorizontal`, `flipVertical`, `transpose` | orientation; `flipVertical` is FPP's *invert* |
| `brightness`, `gamma` | `out = 255 * (in/255)^gamma * brightness/100` |
| `scaleMode` | `FIT`, `FILL`, `STRETCH` |
| `pixelStyle`, `pixelGapPercent` | `SOLID`, `GRID`, `DOTS` |
| `multiSyncEnabled` | listen for a player |
| `remoteOffsetMs` | FPP's `remoteOffset`; positive renders later |
| `autoFetchSequences`, `masterHost` | sequence download |
| `idleMode` | `BLACK`, `TEST_PATTERN`, `STATUS` |

## Setting it up against a player

1. In FPP, add a **Virtual Matrix**-shaped block of channels to the show (any channel range works —
   this device reads a range, it does not need FPP to have an output configured for it).
2. Set the TV's `width`, `height` and `startChannel` to that block.
3. Make sure the player has **MultiSync enabled** and is sending. The TV will appear in
   *Status/Control → MultiSync* once it announces.
4. Start a sequence. The TV downloads it if needed and follows.

The **test pattern** idle mode is the fastest way to confirm geometry before a player is involved:
a white border marks the matrix edges, and a single red pixel marks channel 0, so a wrong flip or
transpose is obvious rather than merely looking different.

## Build

No local Android SDK is needed — CI builds the APK.

```bash
gradle :app:testDebugUnitTest
```

```bash
gradle :app:assembleDebug
```

Pushing to `main` or a `feat/**` branch runs the unit tests, builds a debug APK and publishes it to
the `ci-latest` prerelease.

## Licence

The FPP sources this is ported from are GPL v2 (`FBMatrix.cpp`) and LGPL v2.1 (`MultiSync.*`,
`FSEQFile.*`). This project is released under the **GPL v2** to stay compatible with the strongest
of those.
