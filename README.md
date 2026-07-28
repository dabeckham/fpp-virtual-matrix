# FPP Virtual Matrix for Android TV

Turns an Android TV into a **Falcon Player virtual matrix** — a panel that joins an FPP show as a
MultiSync remote, follows the player's timing, and renders its slice of the channel data as a pixel
matrix on the screen.

It also takes live **DDP** from xLights, simulates the *look* of a real LED panel rather than drawing
flat squares, and can play **full-resolution video underneath the matrix** so sequence effects run on
top of footage — something FPP itself cannot do, because it plays a video *or* a sequence, never both
in one song.

It is a port of four pieces of [FPP](https://github.com/FalconChristmas/fpp):

| FPP | here |
|---|---|
| `src/channeloutput/FBMatrix.cpp` — the **Virtual Matrix** channel output | `render/MatrixRaster.kt` + `render/MatrixSurfaceView.kt` |
| `src/MultiSync.cpp` — the **MultiSync** control protocol | `proto/FppProtocol.kt` + `proto/MultiSyncClient.kt` |
| `src/fseq/FSEQFile.cpp` — the **FSEQ** sequence reader | `fseq/FseqHeader.kt` + `fseq/FseqReader.kt` |
| `channeloutputthread.cpp` — remote timing | `sync/SyncClock.kt` |

## What it does

- **Joins the show.** Listens on UDP 32320, both for broadcast and on the MultiSync **multicast**
  group `239.70.80.80` (which is 239.F.P.P), joined on every usable interface. It announces itself
  with a ping v3 packet and answers discovery, so it appears in the player's MultiSync list like any
  other remote. Either transport works, so it does not matter which the player is set to send.
- **Follows the player.** Sequence open / start / stop / sync packets drive playback. Between sync
  packets the position free-runs off the local monotonic clock; each packet applies a proportional
  correction, with a hard re-anchor only for a real seek.
- **Takes live DDP.** UDP 4048, so xLights can push straight at the TV while you sequence. It answers
  the DDP discovery query, which is how it fills in the Vendor and Model columns on the xLights
  Controllers tab. A running MultiSync sequence always wins over DDP.
- **Looks like a panel, not a grid of squares.** The physical appearance is specified in
  **millimetres** — pitch, emitter size, shape, substrate — and the pixel grid is solved from the
  display's own geometry. Fourteen built-in profiles from P2.5 indoor up to 12 mm bullets on a 2 inch
  pitch, and any of them can be edited and saved as your own.
- **Plays video under the matrix.** Drop `song.mp4` next to `song.fseq` and both play, blended: video
  full screen underneath, sequence effects on top, unlit emitters fully transparent so there is no
  screen-door over the footage. See [docs/video.md](docs/video.md).
- **Configures from a browser.** A small web server on port 8080 serves a config page, a file
  manager, and enough of FPP's own HTTP API that FPP-side tooling recognises the device.
- **Fetches its own sequences.** When a sync packet names an `.fseq` this device does not have, it
  pulls it from the master's file API (`GET /api/file/sequences/<name>`). No upload step, no
  per-remote file copy to configure.
- **Uses a USB stick if one is present.** Detected automatically, no permission needed on any API
  level. Reads span every volume; writes go to the preferred one, so pulling the stick loses only
  what was on it.

## What it deliberately does not do

- **No audio.** Media sync packets are counted and ignored. The show's audio belongs to the player.
- **No FPP command execution.** `CTRL_PKT_FPPCOMMAND` packets are logged for diagnostics but not
  acted on. This device renders the channel data it is told to render; inventing behaviour here
  would put it out of step with every other remote in the show.
- **No controls on the video layer.** No brightness, blackout, crop, pan, zoom or picture-in-picture.
  Blacking the video under an effect, or insetting it with effects around the edge, is authored in a
  video editor — the tool that is good at it. The device plays the file full screen, on the show
  clock, and nothing else.
- **No flip or rotate on the panel tab.** The xLights model's *Starting Location* already owns pixel
  order; a second place to mirror would silently fight the first.
- **It is not an FPP instance.** There is no scheduler, no playlist engine, no channel outputs. It
  advertises system type `FPP` in ping packets purely because FPP only offers unicast MultiSync to
  systems reporting a type below `0x80` in remote mode; the model string says what it really is.

## Configuring it

### From a browser — the normal way

`http://<tv-address>:8080`. Three flat tabs:

| tab | what lives there |
|---|---|
| **Panel** | the physical look — profile, pitch, emitter size and shape, substrate, bloom, downsample |
| **Show** | the channel geometry — width, height, start channel, colour order, brightness, gamma, MultiSync, DDP, idle mode |
| **File Manager** | sequences and videos on every volume, upload, play/stop, bulk move, delete |

Optional password, off by default, matching how the rest of a show network is normally run.

**Width and height belong to the show, not the look.** They must equal the model in xLights Layout or
the channel data will not line up. To change how the panel *reads* — coarser dots, wider spacing —
change pitch and emitter size on the Panel tab and leave the model dense. Every effect still lands
where it should.

### On the TV

**MENU** opens settings, **INFO** opens diagnostics, **OK** toggles the stats overlay. A long **BACK**
press stands the focus guard down for five minutes.

### From a laptop

Any subset of the config can be pushed as JSON, merged over what is stored:

```bash
adb shell "am start -n app.fppvm.tv/.MainActivity --es config '{\"width\":96,\"height\":48,\"startChannel\":4097}'"
```

The same JSON works as a `POST /api/config` body. `{"profileId":"p10","applyProfile":true}` loads a
panel profile.

| key | meaning |
|---|---|
| `width`, `height` | matrix size in pixels — **must match the xLights model** |
| `startChannel` | first channel, **1-based**, exactly as FPP's channel-output page shows it |
| `colorOrder` | `RGB` (default), `RBG`, `GRB`, `GBR`, `BRG`, `BGR` |
| `flipHorizontal`, `flipVertical`, `transpose` | orientation; `flipVertical` is FPP's *invert* |
| `brightness`, `gamma` | `out = 255 * (in/255)^gamma * brightness/100` |
| `scaleMode` | `FIT`, `FILL`, `STRETCH` |
| `pixelStyle`, `pixelGapPercent` | `SOLID`, `GRID`, `DOTS` — the simple look, used when `panelMode` is `OFF` |
| `panelMode` | `OFF`, or a panel simulation mode |
| `profileId` | a built-in id (`p2_5`, `p3`, `p4`, `p5`, `p6`, `p8`, `p10`, `p10_dip`, `bullet_12`, `bullet_19`, `bullet_25`, `bullet_38`, `bullet_50`) or one of your own |
| `pitchMm`, `emitterMm` | centre-to-centre spacing and emitter size, millimetres |
| `emitterShape` | `ROUND`, `SQUARE` |
| `substrateColor` | colour of the unlit surface between emitters |
| `bloomPercent` | 0 = hard-edged apertures; higher spreads glow towards the cell corner |
| `panelDpi` | 0 trusts the display's reported dpi |
| `downsample` | how source pixels combine into one emitter |
| `multiSyncEnabled` | listen for a player |
| `ddpEnabled` | accept live DDP and answer DDP discovery |
| `hostname` | name advertised in ping packets; blank uses the device's own |
| `remoteOffsetMs` | FPP's `remoteOffset`; positive renders later |
| `autoFetchSequences`, `masterHost` | sequence download |
| `idleMode` | `BLACK`, `TEST_PATTERN`, `STATUS` |
| `loopPlayback` | repeat a file started from the file manager |
| `preferRemovableStorage` | write to USB when a stick is mounted |
| `holdFocus` | take the screen back if something steals focus mid-show |
| `webServerEnabled`, `webPort`, `webPassword` | the config server |
| `colorDepth` | `AUTO`, `HIGH`, `FAST` — see the performance notes |
| `keepScreenOn`, `showOverlay` | screen and stats overlay |

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

For xLights specifically — including the one model setting that will mirror your image if it is
wrong — see [docs/xlights.md](docs/xlights.md).

## Video

Pairing is by filename, the same way FPP pairs audio with a sequence. There is no mode switch: the
presence of the file *is* the instruction.

| files present, same basename | what plays | substrate |
|---|---|---|
| `song.fseq` + `song.mp4` | both, blended | fully transparent |
| `song.fseq` only | sequence only | substrate rules apply |
| `song.mp4` only | video only | fully transparent |

Encode at the panel's own size and frame rate with a short GOP — each panel in a show can therefore
carry its own media, cut to its own dimensions. Full guidance, and how the video is kept on the show
clock without seeking, is in [docs/video.md](docs/video.md).

## Performance notes

- The panel path uploads a bitmap of only `cols * rows` pixels, so a 16-bit surface saves nothing
  there and costs a conversion at composition time. `colorDepth = FAST` still forces RGB565 for the
  **full-screen** path, where the bitmap is the whole matrix and halving the bytes does help.
- Bloom is baked into the cached aperture mask as an alpha ramp, not drawn as a per-cell glow sprite.
  `lockCanvas` gives a software canvas, so per-cell blits would be millions of blended pixels every
  frame.
- Emitter alpha over video comes from the **strongest colour channel**, not perceptual luminance. A
  lit LED is a physical object that blocks what is behind it whatever its hue; weighting it the way
  an eye weights brightness leaves full-power blue roughly half see-through.

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

`app/debug.keystore` is committed so `adb install -r` can update in place — without a stable key,
Gradle mints a random one per build machine and every CI build is signed differently. **That key
belongs to this app alone.** Do not copy a debug keystore in from another project: Android's update
signature check is per package name, so sharing one buys nothing and widens the blast radius to every
app carrying it. Generate a fresh one per repo:

```bash
keytool -genkeypair -keystore app/debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10950 -dname 'CN=Android Debug,O=Android,C=US'
```

## Licence

The FPP sources this is ported from are GPL v2 (`FBMatrix.cpp`) and LGPL v2.1 (`MultiSync.*`,
`FSEQFile.*`). This project is released under the **GPL v2** to stay compatible with the strongest
of those.
