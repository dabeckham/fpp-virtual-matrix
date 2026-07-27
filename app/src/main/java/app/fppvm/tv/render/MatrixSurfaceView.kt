package app.fppvm.tv.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import app.fppvm.tv.config.MatrixConfig

/**
 * Presents the matrix on the TV panel.
 *
 * The matrix bitmap is tiny (a 64x32 show is 2048 pixels) and the panel is 720p or 1080p, so the
 * whole job is a nearest-neighbour upscale. Letting the 2D blitter do that — one `drawBitmap`
 * with filtering off — is both the fastest path and the only one that keeps pixel edges hard;
 * bilinear filtering turns a pixel matrix into a blurry mess.
 *
 * `present` is called from the playback thread, not the UI thread. That is deliberate: a show
 * frame must not queue behind whatever else the main looper is doing.
 */
class MatrixSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val lock = Any()

    @Volatile
    private var surfaceReady = false

    private var bitmap: Bitmap? = null
    private var bitmapW = 0
    private var bitmapH = 0
    private var bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888

    private val srcRect = Rect()
    private val dstRect = Rect()
    private val bitmapPaint = Paint().apply {
        isFilterBitmap = false // nearest neighbour: keep the pixels square-edged
        isAntiAlias = false
        isDither = false
    }
    private val maskPaint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = false
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        textSize = 28f
    }

    private var config: MatrixConfig = MatrixConfig.DEFAULT

    /** Cached gap/dot overlay, sized to the destination rect. Rebuilt only when geometry changes. */
    private var mask: Bitmap? = null
    private var maskKey: String = ""

    @Volatile
    var statusText: String? = null

    init {
        holder.addCallback(this)
    }

    fun setConfig(next: MatrixConfig) {
        synchronized(lock) {
            config = next
            ensureBitmap(next.width, next.height)
            dstRect.setEmpty() // force a geometry recompute on the next present
            mask?.recycle()
            mask = null
            maskKey = ""
        }
    }

    private fun ensureBitmap(w: Int, h: Int, cfg: Bitmap.Config = bitmapConfig) {
        if (bitmapW == w && bitmapH == h && bitmapConfig == cfg && bitmap != null) return
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(w, h, cfg)
        bitmapW = w
        bitmapH = h
        bitmapConfig = cfg
        srcRect.set(0, 0, w, h)
    }

    /**
     * Uploads RGB565 [pixels] and blits them.
     *
     * Paired with a 565 surface this is the cheapest path there is: half the bytes of ARGB_8888
     * through the upload, and a blit with no format conversion. On a panel that composites at 16
     * bits anyway it is visually identical.
     */
    fun present565(pixels: ShortArray, w: Int, h: Int): Boolean {
        if (!surfaceReady) return false
        synchronized(lock) {
            ensureBitmap(w, h, Bitmap.Config.RGB_565)
            val bmp = bitmap ?: return false
            if (pixels.size < w * h) return false
            val buf = shortBuffer(pixels, w * h)
            bmp.copyPixelsFromBuffer(buf)

            val canvas = try {
                holder.lockCanvas()
            } catch (t: Throwable) {
                null
            } ?: return false
            try {
                drawFrame(canvas, bmp)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: Throwable) {
                }
            }
            return true
        }
    }

    private var shortBuf: java.nio.ShortBuffer? = null
    private var shortBufBacking: ShortArray? = null

    /** Caches the wrapper by array identity — a resize hands us a different array of any size. */
    private fun shortBuffer(pixels: ShortArray, count: Int): java.nio.ShortBuffer {
        var b = shortBuf
        if (b == null || shortBufBacking !== pixels) {
            b = java.nio.ShortBuffer.wrap(pixels)
            shortBuf = b
            shortBufBacking = pixels
        }
        b.clear()
        b.limit(count)
        return b
    }

    /**
     * Asks for a 16-bit surface so the blit is a straight copy rather than a per-pixel conversion.
     * Only worth doing for the 565 path; the compositor is free to ignore it.
     */
    fun requestLowColorSurface(enable: Boolean) {
        try {
            holder.setFormat(
                if (enable) android.graphics.PixelFormat.RGB_565 else android.graphics.PixelFormat.RGBA_8888
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * Uploads [pixels] (ARGB_8888, `w * h`) and blits them. Returns false when the surface is not
     * available, so the caller can count dropped frames instead of guessing.
     */
    fun present(pixels: IntArray, w: Int, h: Int): Boolean {
        if (!surfaceReady) return false
        synchronized(lock) {
            ensureBitmap(w, h)
            val bmp = bitmap ?: return false
            if (pixels.size < w * h) return false
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)

            val canvas = try {
                holder.lockCanvas()
            } catch (t: Throwable) {
                null
            } ?: return false
            try {
                drawFrame(canvas, bmp)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: Throwable) {
                }
            }
            return true
        }
    }

    /** Fills the panel with black and, if set, draws [statusText]. */
    fun presentBlank(): Boolean {
        if (!surfaceReady) return false
        synchronized(lock) {
            val canvas = try {
                holder.lockCanvas()
            } catch (t: Throwable) {
                null
            } ?: return false
            try {
                canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
                drawStatus(canvas)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: Throwable) {
                }
            }
            return true
        }
    }

    private fun drawFrame(canvas: Canvas, bmp: Bitmap) {
        canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
        computeDest(canvas.width, canvas.height, bmp.width, bmp.height)
        canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
        drawMask(canvas, bmp.width, bmp.height)
        drawStatus(canvas)
    }

    private fun computeDest(viewW: Int, viewH: Int, mw: Int, mh: Int) {
        if (!dstRect.isEmpty && dstRect.right <= viewW && dstRect.bottom <= viewH) return
        when (config.scaleMode) {
            MatrixConfig.ScaleMode.STRETCH -> dstRect.set(0, 0, viewW, viewH)
            MatrixConfig.ScaleMode.FIT, MatrixConfig.ScaleMode.FILL -> {
                val sx = viewW.toDouble() / mw
                val sy = viewH.toDouble() / mh
                val s = if (config.scaleMode == MatrixConfig.ScaleMode.FIT) minOf(sx, sy) else maxOf(sx, sy)
                val w = (mw * s).toInt()
                val h = (mh * s).toInt()
                val left = (viewW - w) / 2
                val top = (viewH - h) / 2
                dstRect.set(left, top, left + w, top + h)
            }
        }
    }

    /**
     * Draws the inter-pixel gaps. The mask is an ALPHA_8 bitmap generated once per geometry, so
     * the per-frame cost is a single blit no matter how many cells the matrix has — a 256x128
     * matrix would otherwise mean tens of thousands of draw calls per frame.
     */
    private fun drawMask(canvas: Canvas, mw: Int, mh: Int) {
        if (config.pixelStyle == MatrixConfig.PixelStyle.SOLID || config.pixelGapPercent <= 0) return
        val cellW = dstRect.width().toFloat() / mw
        val cellH = dstRect.height().toFloat() / mh
        // Below ~3 px a cell the gap would eat the image rather than shape it.
        if (cellW < 3f || cellH < 3f) return

        val key = "${config.pixelStyle}:${config.pixelGapPercent}:${dstRect.width()}x${dstRect.height()}:${mw}x$mh"
        var m = mask
        if (m == null || key != maskKey) {
            m?.recycle()
            m = buildMask(dstRect.width(), dstRect.height(), mw, mh)
            mask = m
            maskKey = key
        }
        if (m != null) canvas.drawBitmap(m, dstRect.left.toFloat(), dstRect.top.toFloat(), maskPaint)
    }

    private fun buildMask(w: Int, h: Int, mw: Int, mh: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val c = Canvas(bmp)
            val opaque = Paint().apply { color = Color.BLACK; isAntiAlias = true }
            val clear = Paint().apply {
                color = Color.TRANSPARENT
                xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                isAntiAlias = true
            }
            c.drawColor(Color.BLACK) // start fully masked, then punch the lit area out
            val cellW = w.toFloat() / mw
            val cellH = h.toFloat() / mh
            val inset = config.pixelGapPercent / 200f // half the gap on each side
            val r = RectF()
            for (y in 0 until mh) {
                for (x in 0 until mw) {
                    val l = x * cellW
                    val t = y * cellH
                    r.set(l + cellW * inset, t + cellH * inset, l + cellW * (1 - inset), t + cellH * (1 - inset))
                    if (config.pixelStyle == MatrixConfig.PixelStyle.DOTS) {
                        c.drawOval(r, clear)
                    } else {
                        c.drawRect(r, clear)
                    }
                }
            }
            bmp
        } catch (t: OutOfMemoryError) {
            null
        }
    }

    // ---------------------------------------------------------------- panel simulation

    private var panelMaskBitmap: Bitmap? = null
    private var panelMaskKey: String = ""

    /**
     * Draws the show as a grid of physical emitters: one flat block of colour per cell, with the
     * cached aperture/bloom mask over it. See [PanelMask] for why the mask carries the bloom.
     */
    fun presentPanel(
        pixels: IntArray,
        geometry: app.fppvm.tv.panel.PanelGeometry,
        bloomPercent: Int
    ): Boolean {
        if (!surfaceReady) return false
        synchronized(lock) {
            val cols = geometry.cols
            val rows = geometry.rows
            if (cols <= 0 || rows <= 0 || pixels.size < cols * rows) return false
            ensureBitmap(cols, rows, Bitmap.Config.ARGB_8888)
            val bmp = bitmap ?: return false
            bmp.setPixels(pixels, 0, cols, 0, 0, cols, rows)

            val canvas = try {
                holder.lockCanvas()
            } catch (t: Throwable) {
                null
            } ?: return false
            try {
                canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
                val left = (canvas.width - geometry.widthPx) / 2
                val top = (canvas.height - geometry.heightPx) / 2
                dstRect.set(left, top, left + geometry.widthPx, top + geometry.heightPx)
                // Nearest neighbour: each cell must be a hard-edged block of one colour, because
                // the mask carves the emitter shape out of it.
                canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)

                if (!geometry.degraded) {
                    val mask = panelMaskFor(geometry, bloomPercent)
                    if (mask != null) {
                        canvas.drawBitmap(mask, dstRect.left.toFloat(), dstRect.top.toFloat(), maskPaint)
                    }
                }
                drawStatus(canvas)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: Throwable) {
                }
            }
            return true
        }
    }

    private fun panelMaskFor(g: app.fppvm.tv.panel.PanelGeometry, bloomPercent: Int): Bitmap? {
        val key = PanelMask.keyFor(g, bloomPercent)
        val cached = panelMaskBitmap
        if (cached != null && key == panelMaskKey) return cached
        cached?.recycle()
        val built = PanelMask.build(g, bloomPercent)
        panelMaskBitmap = built
        panelMaskKey = if (built != null) key else ""
        return built
    }

    private fun drawStatus(canvas: Canvas) {
        val text = statusText ?: return
        var y = 40f
        for (line in text.split('\n')) {
            canvas.drawText(line, 24f, y, textPaint)
            y += textPaint.textSize + 6f
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(lock) { dstRect.setEmpty() }
        surfaceReady = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }
}
