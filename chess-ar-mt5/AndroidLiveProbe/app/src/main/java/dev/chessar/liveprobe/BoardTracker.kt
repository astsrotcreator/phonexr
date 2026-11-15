package dev.chessar.liveprobe

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Perspective map: board coordinates (0..1,0..1) to original camera pixels. */
class BoardHomography private constructor(private val h: DoubleArray) {
    fun map(u: Double, v: Double): PointF {
        val k = h[6] * u + h[7] * v + 1.0
        return PointF(((h[0] * u + h[1] * v + h[2]) / k).toFloat(),
            ((h[3] * u + h[4] * v + h[5]) / k).toFloat())
    }

    companion object {
        fun fromCorners(corners: List<PointF>, w: Int, height: Int): BoardHomography? {
            if (corners.size != 4 || w < 64 || height < 64) return null
            val pts = corners.map { PointF(it.x * w, it.y * height) }
            var twiceArea = 0.0
            for (i in 0..3) {
                val a = pts[i]; val b = pts[(i + 1) % 4]
                twiceArea += a.x * b.y - a.y * b.x
            }
            if (abs(twiceArea) < w * height * 0.035) return null
            val uv = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)
            val a = Array(8) { DoubleArray(9) }
            for (i in 0..3) {
                val (u, v) = uv[i]
                val x = pts[i].x.toDouble(); val y = pts[i].y.toDouble()
                val row = i * 2
                a[row][0] = u; a[row][1] = v; a[row][2] = 1.0
                a[row][6] = -u * x; a[row][7] = -v * x; a[row][8] = x
                a[row + 1][3] = u; a[row + 1][4] = v; a[row + 1][5] = 1.0
                a[row + 1][6] = -u * y; a[row + 1][7] = -v * y; a[row + 1][8] = y
            }
            for (j in 0..7) {
                var pivot = j
                for (i in (j + 1)..7) if (abs(a[i][j]) > abs(a[pivot][j])) pivot = i
                if (abs(a[pivot][j]) < 1e-9) return null
                val temp = a[j]; a[j] = a[pivot]; a[pivot] = temp
                val scale = a[j][j]
                for (k in j..8) a[j][k] /= scale
                for (i in 0..7) if (i != j) {
                    val m = a[i][j]
                    for (k in j..8) a[i][k] -= m * a[j][k]
                }
            }
            return BoardHomography(DoubleArray(8) { a[it][8] })
        }
    }
}

data class BoardDifference(
    val changed: List<Int>,
    val scores: DoubleArray,
    val shiftX: Int,
    val shiftY: Int,
    val registrationError: Double,
    val noise: Double,
    val message: String,
    val reliable: Boolean,
    val aligned: Boolean = reliable,
    val updatedCorners: List<PointF> = emptyList(),
    val inliers: Int = 0,
    val featureCount: Int = 0
)

/**
 * Offline, low-resolution test for whether two photos show changes in board cells.
 * This is NOT piece recognition. Perspective is calibrated by 4 manual taps.
 * Small head translations are compensated; rotations/perspective shifts are rejected.
 */
class BoardTracker {
    private var reference: IntArray? = null
    private var width = 0
    private var height = 0
    private var map: BoardHomography? = null
    private var referenceSamples = IntArray(64 * 16)
    private var anchorPositions = ArrayList<PointF>()
    private var anchorValues = IntArray(0)

    val calibrated: Boolean get() = reference != null

    fun reset() {
        reference = null
        map = null
        anchorPositions.clear()
        anchorValues = IntArray(0)
    }

    fun remember(photo: Bitmap, corners: List<PointF>): Boolean {
        val h = BoardHomography.fromCorners(corners, photo.width, photo.height) ?: return false
        val raw = pixelLuma(photo)
        val baseSamples = IntArray(1024)
        val positions = ArrayList<PointF>(256)
        val values = ArrayList<Int>(256)
        for (row in 0..7) for (col in 0..7) {
            val cell = row * 8 + col
            for (sy in 0..3) for (sx in 0..3) {
                val p = h.map((col + (sx + 0.5) / 4.0) / 8.0,
                    (row + (sy + 0.5) / 4.0) / 8.0)
                val value = sample(raw, photo.width, photo.height, p.x, p.y)
                baseSamples[cell * 16 + sy * 4 + sx] = value
            }
            for (sy in 0..1) for (sx in 0..1) {
                val p = h.map((col + (sx + 0.5) / 2.0) / 8.0,
                    (row + (sy + 0.5) / 2.0) / 8.0)
                positions.add(p)
                values.add(sample(raw, photo.width, photo.height, p.x, p.y))
            }
        }
        reference = raw
        width = photo.width; height = photo.height; map = h
        referenceSamples = baseSamples
        anchorPositions = positions
        anchorValues = values.toIntArray()
        return true
    }

    fun compare(photo: Bitmap): BoardDifference {
        if (!calibrated || photo.width != width || photo.height != height) {
            return failure("Нет эталона либо изменился размер кадра. Разметь доску снова.")
        }
        val raw = pixelLuma(photo)
        // Align a small camera translation. A moved piece affects only a minority of pixels.
        var bestCost = Double.POSITIVE_INFINITY
        var dxBest = 0; var dyBest = 0
        for (dy in -10..10) for (dx in -10..10) {
            var sum = 0.0
            for (i in anchorPositions.indices) {
                val p = anchorPositions[i]
                val value = sample(raw, width, height, p.x + dx, p.y + dy)
                sum += min(42, abs(value - anchorValues[i])).toDouble()
            }
            val cost = sum / anchorPositions.size + (abs(dx) + abs(dy)) * 0.18
            if (cost < bestCost) {
                bestCost = cost; dxBest = dx; dyBest = dy
            }
        }
        val proj = map ?: return failure("Нет проекции доски.")
        val base = referenceSamples
        val delta = DoubleArray(64)
        val newSamples = IntArray(1024)
        for (row in 0..7) for (col in 0..7) {
            val idx = row * 8 + col
            var mean = 0.0
            for (sy in 0..3) for (sx in 0..3) {
                val k = idx * 16 + sy * 4 + sx
                val p = proj.map((col + (sx + 0.5) / 4.0) / 8.0,
                    (row + (sy + 0.5) / 4.0) / 8.0)
                val v = sample(raw, width, height, p.x + dxBest, p.y + dyBest)
                newSamples[k] = v
                mean += v - base[k]
            }
            delta[idx] = mean / 16.0
        }
        val lighting = median(delta)
        val scores = DoubleArray(64)
        for (idx in 0 until 64) {
            var sum = 0.0
            for (s in 0..15) {
                val k = idx * 16 + s
                sum += abs(newSamples[k] - base[k] - lighting)
            }
            scores[idx] = sum / 16.0
        }
        val noise = median(scores)
        val threshold = max(20.0, noise * 2.3 + 9.0)
        val changed = (0 until 64).filter { scores[it] >= threshold }
            .sortedByDescending { scores[it] }
        if (bestCost > 32.0 || changed.size > 12) {
            return BoardDifference(changed.take(8), scores, dxBest, dyBest, bestCost, noise,
                "Сильное смещение камеры или много изменений. Держи голову ровно; при необходимости разметь доску заново.",
                false)
        }
        val text = when {
            changed.isEmpty() -> "Заметных изменений клеток нет."
            changed.size == 1 -> "Изменилась 1 клетка: возможно, рука/тень или неточный кадр."
            changed.size == 2 -> "Изменились 2 клетки — проверяем возможный ход."
            changed.size <= 4 -> "Есть ${changed.size} изменившихся клетки: проверь подсветку вручную."
            else -> "Изменилось слишком много клеток (${changed.size}); ход не подтверждён."
        }
        return BoardDifference(changed.take(8), scores, dxBest, dyBest, bestCost, noise, text,
            changed.size <= 4)
    }

    private fun failure(message: String) = BoardDifference(emptyList(), DoubleArray(64),
        0, 0, 999.0, 0.0, message, false)

    private fun pixelLuma(bitmap: Bitmap): IntArray {
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return IntArray(argb.size) { i ->
            val px = argb[i]
            (((px shr 16 and 255) * 77) + ((px shr 8 and 255) * 150) +
                ((px and 255) * 29)) shr 8
        }
    }
    private fun sample(gray: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val xx = x.roundToInt().coerceIn(0, w - 1)
        val yy = y.roundToInt().coerceIn(0, h - 1)
        return gray[yy * w + xx]
    }
    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
