package app.fppvm.tv.panel

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Makes the screen read as a physical LED product rather than as a grid of screen pixels.
 *
 * FPP's Virtual Matrix draws one channel triplet per framebuffer pixel — correct, and nothing like
 * what the audience sees. A real P10 cabinet is 5 mm emitters on a 10 mm pitch over black
 * substrate; a bullet-pixel string is 12 mm bulbs on a 12-50 mm pitch. Both are mostly *dark*, and
 * that dark fraction is the look.
 *
 * So the look is specified in millimetres and the grid is solved for, not the other way round.
 * Panels differ between installs; the intent ("this should look like P10") is the thing worth
 * pinning down.
 */
enum class EmitterShape {
    /** Round bulb — bullet/seed pixels, and the apertures of a perforated panel. */
    ROUND,

    /** Square SMD package — what P2.5 through P10 cabinets actually look like up close. */
    SQUARE
}

/** How the grid is chosen. */
enum class PanelMode {
    /** No simulation: one show pixel per matrix cell, as FPP's Virtual Matrix does. */
    OFF,

    /** Pitch wins. The grid is however many cells fit the screen; the show is resampled onto it. */
    FIT_PHYSICAL,

    /** The show's own dimensions win. Pitch becomes whatever falls out, and is reported. */
    MATCH_SOURCE
}

/** How a show is resampled when its resolution differs from the solved grid. */
enum class Downsample {
    /**
     * A lit cell anywhere in the block lights the cell. Correct for light shows: content is sparse
     * and high contrast, and averaging one lit pixel with five hundred dark ones erases it.
     */
    MAX,

    /** Block mean. Right for image-like content, wrong for pixel-art chases. */
    MEAN
}

/** Named products, so the common cases are one setting rather than four. */
enum class PanelPreset(
    val label: String,
    val pitchMm: Float,
    val emitterMm: Float,
    val shape: EmitterShape
) {
    CUSTOM("Custom", 25.4f, 12.0f, EmitterShape.ROUND),

    // SMD cabinets. Emitter figures are the visible emitting area including the package's black
    // mask, not the die — that is what sets the apparent fill.
    P2_5("P2.5", 2.5f, 1.6f, EmitterShape.SQUARE),
    P3("P3", 3.0f, 1.8f, EmitterShape.SQUARE),
    P4("P4", 4.0f, 2.2f, EmitterShape.SQUARE),
    P5("P5", 5.0f, 2.8f, EmitterShape.SQUARE),
    P6("P6", 6.0f, 3.2f, EmitterShape.SQUARE),
    P8("P8", 8.0f, 3.6f, EmitterShape.SQUARE),
    P10("P10", 10.0f, 5.0f, EmitterShape.SQUARE),

    // 12 mm bullet/seed pixels at the usual spacings.
    BULLET_12("Bullet 12mm @ 12mm", 12.0f, 12.0f, EmitterShape.ROUND),
    BULLET_19("Bullet 12mm @ 19mm", 19.05f, 12.0f, EmitterShape.ROUND),
    BULLET_25("Bullet 12mm @ 25mm", 25.4f, 12.0f, EmitterShape.ROUND),
    BULLET_38("Bullet 12mm @ 38mm", 38.1f, 12.0f, EmitterShape.ROUND),
    BULLET_50("Bullet 12mm @ 50mm", 50.0f, 12.0f, EmitterShape.ROUND);

    val isCustom: Boolean get() = this == CUSTOM
}

/**
 * A solved layout. Everything the renderer needs, plus what was actually achieved so the UI can be
 * honest about it — "you asked for 25.4 mm, this panel gives 24.9 mm at 27x15" is the difference
 * between a tool and a guess.
 */
data class PanelGeometry(
    val cols: Int,
    val rows: Int,
    /** Cell pitch in surface pixels. Deliberately fractional: snapping to integers drifts. */
    val cellPx: Float,
    /** Emitter size in surface pixels — diameter for [EmitterShape.ROUND], side for SQUARE. */
    val emitterPx: Float,
    val shape: EmitterShape,
    val dpi: Float,
    val achievedPitchMm: Float,
    val achievedEmitterMm: Float,
    /** Width and height of the lit area in surface pixels; the remainder reads as bezel. */
    val widthPx: Int,
    val heightPx: Int,
    /** True when cells are too small to shape and the mask would eat the image, not sculpt it. */
    val degraded: Boolean,
    val note: String
) {
    val cellCount: Int get() = cols * rows

    /** Fraction of a cell that is lit — the number the open-area maths runs on. */
    val fillRatio: Float get() = if (cellPx > 0f) emitterPx / cellPx else 1f

    /** Lit fraction of the whole surface. Square lattice: (pi/4)(d/p)^2 for round emitters. */
    val openAreaPercent: Float
        get() {
            val r = fillRatio.coerceIn(0f, 1f)
            val a = if (shape == EmitterShape.ROUND) (Math.PI.toFloat() / 4f) * r * r else r * r
            return a * 100f
        }

    fun describe(): String =
        "%d x %d cells · pitch %.1f mm · emitter %.1f mm · %.0f dpi · %.0f%% lit%s".format(
            cols, rows, achievedPitchMm, achievedEmitterMm, dpi, openAreaPercent,
            if (note.isEmpty()) "" else " · $note"
        )
}

object PanelSolver {

    /**
     * Below this the aperture stops shaping the image and starts eating it. FPP-VM's own mask
     * builder already bails at 3 px for the same reason; a hole cannot be smaller than a pixel and
     * the app should say so rather than render mush.
     */
    const val MIN_CELL_PX = 3f
    const val MIN_EMITTER_PX = 1.5f

    /** Fallback when the display reports a dpi that cannot be true. */
    const val FALLBACK_DPI = 46f

    /**
     * Vendor-supplied `xdpi`/`ydpi` is a constant in a config file, not a measurement, and Android
     * devices get it wrong routinely. Cross-check it against the implied diagonal before trusting
     * physical units to it.
     */
    fun isPlausibleDpi(dpi: Float, widthPx: Int, heightPx: Int): Boolean {
        if (dpi <= 1f || dpi.isNaN() || dpi.isInfinite()) return false
        val diagonalInches = hypot(widthPx / dpi, heightPx / dpi)
        // Wide on purpose. This is here to reject nonsense — a reported 2 dpi implies a 60 ft
        // screen — not to have an opinion about form factors. A phone is ~5-7 in and a video
        // wall can be 150.
        return diagonalInches in 2f..200f
    }

    /**
     * Solves the layout for [surfaceWidth] x [surfaceHeight].
     *
     * [sourceCols]/[sourceRows] are the show's own matrix dimensions, used by
     * [PanelMode.MATCH_SOURCE] and reported against for resampling.
     */
    fun solve(
        mode: PanelMode,
        pitchMm: Float,
        emitterMm: Float,
        shape: EmitterShape,
        surfaceWidth: Int,
        surfaceHeight: Int,
        sourceCols: Int,
        sourceRows: Int,
        reportedDpi: Float,
        dpiOverride: Float = 0f
    ): PanelGeometry {
        var note = ""
        val dpi = when {
            dpiOverride > 1f -> dpiOverride
            isPlausibleDpi(reportedDpi, surfaceWidth, surfaceHeight) -> reportedDpi
            else -> {
                note = "reported dpi ${"%.0f".format(reportedDpi)} implausible, assuming $FALLBACK_DPI"
                FALLBACK_DPI
            }
        }
        val pxPerMm = dpi / 25.4f

        val safePitch = pitchMm.coerceAtLeast(0.1f)
        val safeEmitter = emitterMm.coerceIn(0.05f, safePitch)

        var cellPx: Float
        var cols: Int
        var rows: Int

        when (mode) {
            PanelMode.MATCH_SOURCE, PanelMode.OFF -> {
                cols = sourceCols.coerceAtLeast(1)
                rows = sourceRows.coerceAtLeast(1)
                // The show dictates the grid; the pitch is then whatever the screen allows.
                cellPx = minOf(surfaceWidth.toFloat() / cols, surfaceHeight.toFloat() / rows)
            }
            PanelMode.FIT_PHYSICAL -> {
                cellPx = safePitch * pxPerMm
                cols = floor(surfaceWidth / cellPx).toInt().coerceAtLeast(1)
                rows = floor(surfaceHeight / cellPx).toInt().coerceAtLeast(1)
            }
        }

        val emitterPx = if (mode == PanelMode.FIT_PHYSICAL) {
            safeEmitter * pxPerMm
        } else {
            // Keep the requested fill ratio when the pitch was not ours to choose.
            cellPx * (safeEmitter / safePitch)
        }

        val degraded = cellPx < MIN_CELL_PX || emitterPx < MIN_EMITTER_PX
        if (degraded) {
            val extra = "cell %.1f px is below the %.0f px floor; apertures cannot be drawn"
                .format(cellPx, MIN_CELL_PX)
            note = if (note.isEmpty()) extra else "$note; $extra"
        }

        return PanelGeometry(
            cols = cols,
            rows = rows,
            cellPx = cellPx,
            emitterPx = emitterPx,
            shape = shape,
            dpi = dpi,
            achievedPitchMm = cellPx / pxPerMm,
            achievedEmitterMm = emitterPx / pxPerMm,
            widthPx = (cols * cellPx).roundToInt(),
            heightPx = (rows * cellPx).roundToInt(),
            degraded = degraded,
            note = note
        )
    }
}
