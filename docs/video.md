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

Measured on an Android 9 TV following a live show: drift bounded 274–384 ms, rate ~1.012, **zero
seeks over 110 seconds**.

### Known limitation

The residual drift **does not converge to zero** — it holds steady at roughly 300 ms rather than
decaying away. Rate is a velocity input against a position error, so a constant residual means either
a constant disturbance or a constant measurement offset. The two candidates need opposite fixes:

- `MediaPlayer.getCurrentPosition()` reporting *behind* the displayed picture, in which case the
  video is already in sync and correcting it would push the picture early; or
- genuine pipeline latency, which would want an integral term.

Until it is known which, the loop is left as it is. A steady 300 ms offset can also simply be dialled
out with `remoteOffsetMs` if it is visible on your panel.

The measurement above also came from a short sequence on repeat rather than a full song. Treat the
numbers as indicative.

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
