package dev.chessar.liveprobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ImageView
import kotlin.math.min

/** Touch four outer board corners, clockwise: TL, TR, BR, BL. */
class BoardImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ImageView(context, attrs) {
    var onCalibrationChanged: ((Int) -> Unit)? = null
    var onCalibrationDone: ((List<PointF>) -> Unit)? = null
    val corners = ArrayList<PointF>()
    var calibrating = false
        private set
    private var overlayValid = true
    fun setTrackedCorners(updated: List<PointF>) {
        if (updated.size == 4) {
            corners.clear()
            corners.addAll(updated.map { PointF(it.x, it.y) })
            displayShiftX = 0
            displayShiftY = 0
            overlayValid = true
        } else overlayValid = false
        invalidate()
    }
    fun hideTrackingGrid() {
        overlayValid = false
        highlighted = emptySet()
        invalidate()
    }
    var whiteBottom = true
    var highlighted: Set<Int> = emptySet()
        set(value) { field = value; invalidate() }
    private var displayShiftX = 0
    private var displayShiftY = 0
    private var bitmapWidth = 0
    private var bitmapHeight = 0
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 240, 215); strokeWidth = 2.1f; style = Paint.Style.STROKE
    }
    private val chosen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(104, 255, 186, 44); style = Paint.Style.FILL
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(245, 239, 75); style = Paint.Style.FILL
    }
    private val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 25f; isFakeBoldText = true
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    init {
        scaleType = ScaleType.FIT_CENTER
        adjustViewBounds = false
        setBackgroundColor(Color.rgb(235, 241, 236))
    }

    fun setPhoto(photo: Bitmap) {
        bitmapWidth = photo.width
        bitmapHeight = photo.height
        setImageBitmap(photo)
        invalidate()
    }

    fun setDisplayTranslation(dx: Int, dy: Int) {
        displayShiftX = dx; displayShiftY = dy; invalidate()
    }

    fun startCalibration() {
        displayShiftX = 0; displayShiftY = 0
        corners.clear()
        highlighted = emptySet()
        overlayValid = true
        calibrating = true
        onCalibrationChanged?.invoke(0)
        invalidate()
    }

    fun clearCalibration() {
        displayShiftX = 0; displayShiftY = 0
        calibrating = false
        overlayValid = true
        corners.clear()
        highlighted = emptySet()
        invalidate()
    }

    fun shiftCalibration(dx: Int, dy: Int) {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return
        displayShiftX = 0; displayShiftY = 0
        for (p in corners) {
            p.x = (p.x + dx.toFloat() / bitmapWidth).coerceIn(0f, 1f)
            p.y = (p.y + dy.toFloat() / bitmapHeight).coerceIn(0f, 1f)
        }
        invalidate()
    }

    private fun imageParams(): FloatArray? {
        if (bitmapWidth == 0 || bitmapHeight == 0 || width == 0 || height == 0) return null
        val contentW = width - paddingLeft - paddingRight
        val contentH = height - paddingTop - paddingBottom
        val scale = min(contentW.toFloat() / bitmapWidth, contentH.toFloat() / bitmapHeight)
        val x0 = paddingLeft + (contentW - bitmapWidth * scale) * 0.5f
        val y0 = paddingTop + (contentH - bitmapHeight * scale) * 0.5f
        return floatArrayOf(x0, y0, scale)
    }

    private fun toScreen(p: PointF, params: FloatArray) =
        PointF(params[0] + p.x * params[2], params[1] + p.y * params[2])

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!calibrating || bitmapWidth == 0) return super.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (event.action != MotionEvent.ACTION_UP) return true
        performClick()
        val p = imageParams() ?: return true
        val x = (event.x - p[0]) / (p[2] * bitmapWidth)
        val y = (event.y - p[1]) / (p[2] * bitmapHeight)
        if (x !in 0f..1f || y !in 0f..1f) return true
        corners.add(PointF(x, y))
        onCalibrationChanged?.invoke(corners.size)
        if (corners.size == 4) {
            calibrating = false
            onCalibrationDone?.invoke(corners.toList())
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val params = imageParams() ?: return
        for ((i, corner) in corners.withIndex()) {
            val c = toScreen(PointF(corner.x * bitmapWidth + displayShiftX, corner.y * bitmapHeight + displayShiftY), params)
            canvas.drawCircle(c.x, c.y, 8f, cornerPaint)
            canvas.drawText("${i + 1}", c.x + 10, c.y - 10, labels)
        }
        if (corners.size < 4 || !overlayValid) return
        val homography = BoardHomography.fromCorners(corners, bitmapWidth, bitmapHeight) ?: return
        fun screen(u: Double, v: Double): PointF {
            val p = homography.map(u, v)
            return toScreen(PointF(p.x + displayShiftX, p.y + displayShiftY), params)
        }
        for (n in 0..8) {
            val p = n / 8.0
            val a = screen(p, 0.0); val b = screen(p, 1.0)
            canvas.drawLine(a.x, a.y, b.x, b.y, line)
            val c = screen(0.0, p); val d = screen(1.0, p)
            canvas.drawLine(c.x, c.y, d.x, d.y, line)
        }
        highlighted.forEach { index ->
            val row = index / 8
            val col = index % 8
            val x = col / 8.0; val y = row / 8.0
            val a = screen(x, y); val b = screen(x + 1.0 / 8, y)
            val c = screen(x + 1.0 / 8, y + 1.0 / 8)
            val d = screen(x, y + 1.0 / 8)
            val shape = Path().apply {
                moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y)
                lineTo(d.x, d.y); close()
            }
            canvas.drawPath(shape, chosen)
            val label = VisualChess.squareName(index, whiteBottom)
            val center = screen(x + 1.0 / 16, y + 1.0 / 16)
            canvas.drawText(label, center.x - 14, center.y + 8, labels)
        }
    }
}
