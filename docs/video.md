# Video under the matrix

FPP plays a video **or** a sequence — never both in one song. This device plays both at once: the
video full screen underneath, the matrix rendered over the top of it, with unlit emitters fully
transparent so the footage shows through.

That is the whole point of the feature. Sampling video *down* into channel data buys nothing —
xLights already renders video to a matrix model, at matrix resolution. What you cannot otherwise have
is **full-resolution video with sequence effects sweeping across it**, and that is what this gives
you.

## Turning it on

There is no setting. **Pairing is by filename**, the same way FPP pairs audio with a sequence:

| files present, same basename | what plays | substrate |
|---|---|---|
| `song.fseq` + `song.mp4` | both, blended | **fully transparent** |
| `song.fseq` only | sequence only | substrate rules apply (black mask / PCB / none) |
| `song.mp4` only | video only | **fully transparent** |

The presence of the file *is* the instruction. Put both files in the same folder — internal storage
or a USB stick, it does not matter, reads span every volume.

A video paired with a sequence plays **muted and non-looping**: the show owns the audio and the
sequence owns the length. A video played on its own from the file manager honours the **Repeat**
toggle and its own soundtrack.

## There are no video controls, and that is deliberate

No brightness, blackout, crop, pan, zoom, positioning or picture-in-picture. The device plays the
file full screen, on the show clock, and nothing else.

Everything creative belongs upstream:

- **Want the video to go black under an effect?** Cut that to black in your editor.
- **Want picture-in-picture with effects around the edges?** Author the inset in your editor and let
  the matrix effects run in the surrounding area.

A video editor is enormously better at this than any control this app could offer, and the result is
deterministic — it is baked into the file rather than depending on device state at showtime.

## Encoding your media

Encode **for the panel**, not for a TV. The panel is the display.

| setting | value | why |
|---|---|---|
| **Resolution** | the panel's own `width` x `height` | anything larger is decode and bandwidth spent on pixels that get thrown away in the downscale |
| **Frame rate** | the panel's frame rate | no cadence conversion, no judder |
| **GOP** | **≈ 0.5 s or shorter** — a keyframe every ~15 frames at 30 fps | makes the rare hard reseek cheap; see below |
| **Codec** | H.264, baseline or main | what Android TV decoders handle best |

```bash
ffmpeg -i source.mp4 \
  -vf scale=96:48 -r 30 -g 15 -keyint_min 15 -sc_threshold 0 \
  -c:v libx264 -profile:v main -pix_fmt yuv420p \
  -c:a aac -b:a 128k \
  song.mp4
```

`-sc_threshold 0` stops the encoder inserting extra keyframes on scene changes, so the GOP really is
the length you asked for.

### Each panel can carry its own media

Because the media is cut to the panel's own dimensions, **different TVs in one show can run different
footage** — each encoded for its own size and its own slice of the display. That is a feature of the
approach, not a workaround for it.

## How it stays in sync — rate, not seeking

FPP uses VLC and *seeks* to match each sync packet. An exact seek has to decode from the previous
keyframe forward, so with a two second GOP every correction costs up to two seconds of decode work.
That is why it struggles to keep up.

This device does what `SyncClock` already does for sequences: **it corrects the rate, not the
position.**

```
rate = 1 + (errorMs / 1000) * 0.04     clamped to 0.98 .. 1.02
```

A small, permanent speed nudge absorbs drift continuously and inaudibly. A real seek is reserved for
a genuine jump — an error over **1.5 seconds**, which in practice means the operator moved the show
position, not that timing slipped.

This is why the short GOP matters: the rare seek that does happen is then cheap.

Measured on an Android 9 TV over a full 120 second pass, sampling every 4 seconds:

```
381 → 220 → 178 → 151 → 124 → 104 → 85 → 69 → 57 → 47 → 37 → 30 → 24 → 18 → 13 → 9 → 7 → 6 → 4 → 2 → 1 → -1 → -3 ms
```

Clean exponential decay, settling at **−8 ms** with **zero seeks**. The fitted time constant is about
19 seconds against the ~25 seconds the 0.04 gain predicts.

### What the target is, and why it is not the clock

The video is steered at **the frame the panel has just put on screen**, not at where the show clock
has reached.

Those are not the same instant. The follow loop runs after the sequence frame has been decoded and
painted, so the clock has already moved on by however long that work took, while the picture a viewer
is looking at is the frame chosen *before* it started. Aiming at the clock therefore asks the video to
be where the matrix will be one paint from now — and the video obliges.

Measured with a timecode burned into the video and a matching one rendered into the sequence, both
read out of a **single screen capture** so nothing depends on when the capture happened:

| target | on-screen offset, video vs effects | n |
|---|---|---|
| show clock | **−86 ms** (video ahead), median −80 | 7 |
| frame just painted | **+22 ms**, median +20 | 24 |

The spread either side (~45 ms) is the same in both and is the measurement's own floor: the panel
paints every ~97 ms, so a single capture can only place the sequence's frame to within half of that.

Matching picture to picture also stays correct when the panel gets cheaper or more expensive to draw,
which a constant tuned per device would not.

If a residual offset is still visible on your panel, `remoteOffsetMs` dials the whole device earlier
or later.

## How it is composited

The matrix is a translucent `SurfaceView` with `setZOrderMediaOverlay(true)`, sitting over a second
`SurfaceView` holding the video. Each emitter's alpha is driven by its own brightness:

```
outAlpha = apertureAlpha(x, y) * f(cellBrightness)
```

so an unlit emitter is fully transparent and the video runs through untouched, while a lit one is
opaque and reads as an LED. Bloom already lives in the alpha ramp, so it becomes a soft halo over the
video for free.

Two things worth knowing if you go reading the code:

- **Alpha comes from the strongest colour channel, not perceptual luminance.** An LED is not an eye:
  a lit emitter is a physical object that blocks what is behind it whatever its hue. Weighting blue
  the way vision weights it scores full-power blue 31 against red's 63 and leaves blue emitters half
  see-through.
- **Substrate colour and video are mutually exclusive.** A black mask or grey PCB is opaque by
  definition, so whenever a video is playing the substrate goes fully transparent.
