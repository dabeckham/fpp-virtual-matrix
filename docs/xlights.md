# Using it with xLights

Two independent paths reach this device, and they answer different questions:

- **MultiSync** — the TV joins a running FPP show as a remote and renders the sequence the player is
  playing. This is showtime.
- **DDP** — xLights pushes live channel data straight at the TV over UDP 4048 while you sequence.
  This is the workbench.

Both can be enabled at once. **A running MultiSync sequence always wins**, on the assumption that if
the master is playing a show, the show is what should be on screen.

## Discovery

Click **Discover** on the Controllers tab and the TV appears with its address, Vendor **FPP** and
Model **Virtual Matrix** — matching the entry xLights already ships in `fpp.xcontroller`.

Those two columns are filled from the **DDP** discovery reply, not from MultiSync. The MultiSync ping
does carry a model string, but xLights reads it into its internal `platform` / `platformModel` fields
rather than the grid columns. So if `ddpEnabled` is off, the device still discovers — the columns
just come up blank.

## ⚠️ Starting Location must be **Top Left**

If your image comes out mirrored left-to-right, **this is the setting**, not the device.

The model's *Starting Location* owns pixel order. This app consumes channels row-major from the
top-left, exactly as FPP's own Virtual Matrix output does. A model set to **Bottom Left** feeds the
rows in the opposite order and the picture mirrors.

Set the model to **Top Left**, horizontal, no zig-zag.

There is deliberately **no flip or rotate control on the panel tab** — a second place to mirror would
silently fight the first, and then neither setting means anything on its own.

## Width and height must equal the model

The TV's `width` and `height` are channel geometry, not a look. Get them wrong and the data does not
line up — a 640x360 frame sent to a device configured 45x25 fills only the first two rows of the
image, and the whole panel reads as one colour. That looks exactly like a rendering bug and is not
one.

**To change how the panel reads, do not re-cut the model.** Leave it dense and change **pitch** and
**emitter size** on the Panel tab. Those drive the aperture mask and the integration, so a dense model
plus a coarse pitch gives real LED spacing with every effect still landing where it should. That is
the intended workflow.

## Setting up the controller

1. **Controllers tab** → Discover, or add it manually with the TV's address.
2. Assign your matrix model to the controller's single virtual matrix port.
3. In **Layout**, confirm the model's Starting Location is **Top Left**.
4. On the TV's Show tab, set `width`, `height` and `startChannel` to match the model.

The TV's **test pattern** idle mode confirms geometry before any data flows: a white border marks the
matrix edges and a single red pixel marks channel 0.

## FPP Connect

**FPP Connect cannot reach this device directly, and the reason is a port number rather than a
missing feature.**

xLights talks HTTP on **port 80 only** — the URL is built as `"http://" + host + url` with no port
anywhere in the path. An unprivileged Android process cannot bind port 80, and the kernel on Android
9 devices predates the `ip_unprivileged_port_start` sysctl that would let that be relaxed. So the
device serves its FPP-compatible API on 8080, where FPP Connect will not look for it.

Everything else is in place: the API endpoints FPP Connect uses are implemented and answer correctly
on 8080.

**The workaround is FPP's own proxy.** FPP's proxy rewrite accepts a `host:port`, so adding
`<tv-address>:8080` as a Proxied Host on the player should let xLights reach the device at
`http://<player>/proxy/<tv-address>:8080/...`. *This path has not been tested end to end.*

## Sending DDP by hand

Useful for testing without xLights. Two traps that will cost you an afternoon:

1. **Build the frame from `GET /api/config`, never from memory.** See the width/height note above —
   a wrongly sized frame produces a symptom that looks nothing like its cause.
2. **Check nothing else is already sending.** Sample `ddpPackets` on `/api/status` twice a few seconds
   apart and confirm it is static before you start. A second sender on the network interleaves
   silently into the same buffer.

The end-of-frame DDP sync packet is broadcast to `255.255.255.255`, so this device treats *only* data
landing inside its own channel slice as live output. A broadcast push alone will not take the panel
away from its idle pattern — otherwise an unrelated show elsewhere on the network would blank it.

DDP output is dropped after **2 seconds** of quiet and the panel returns to idle.
