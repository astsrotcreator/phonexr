package dev.chessar.liveprobe

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Camera-space homography tracking with sparse pyramidal optical flow + RANSAC.
 *
 * Two separate references:
 *  - last accepted camera frame: geometric tracking (updated every GOOD frame);
 *  - last confirmed chess position: board appearance (updated only by the user).
 *
 * On failed geometry, no position inference and no false "change-less" board.
 * All calculations run offline. AI camera still supplies only ~240x150 JPEGs.
 */
class StableBoardTracker {
    private var lastGray: Mat? = null
    private var lastCorners = emptyList<PointF>()    // absolute camera pixels
    private var baseline: ByteArray? = null          // 192x192 perspective warp
    private var frameWidth = 0
    private var frameHeight = 0
    private var rejectedFrames = 0

    val calibrated get() = baseline != null && lastGray != null && lastCorners.size == 4
    private val rectSize = 192
    private val cellSize = rectSize / 8

    fun reset() {
        lastGray?.release()
        lastGray = null
        lastCorners = emptyList()
        baseline = null
        frameWidth = 0
        frameHeight = 0
        rejectedFrames = 0
    }

    fun remember(photo: Bitmap, normalizedCorners: List<PointF>): Boolean {
        if (normalizedCorners.size != 4 || photo.width < 80 || photo.height < 80) return false
        val corners = normalizedCorners.map {
            PointF(it.x * photo.width, it.y * photo.height)
        }
        if (!validQuad(corners, photo.width, photo.height, null)) return false
        val gray = toGray(photo)
        val rect = rectify(gray, corners)
        if (rect == null) {
            gray.release()
            return false
        }
        lastGray?.release()
        lastGray = gray
        baseline = rect
        frameWidth = photo.width
        frameHeight = photo.height
        lastCorners = corners
        rejectedFrames = 0
        return true
    }

    fun compare(photo: Bitmap): BoardDifference {
        val last = lastGray ?: return fail("Разметь доску и сохрани эталон.")
        val ref = baseline ?: return fail("Сначала сохрани эталон.")
        if (photo.width != frameWidth || photo.height != frameHeight)
            return fail("Изменилось разрешение фото. Разметь доску повторно.")

        val now = toGray(photo)
        try {
            val matches = opticalRegistration(last, now, lastCorners)
                ?: return fail("Не удалось надёжно совместить кадры. Сетка скрыта: держи доску в кадре или разметь заново.")
            val corners = matches.corners
            val rectified = rectify(now, corners)
                ?: return fail("Не удалось выровнять перспективу. Попробуй изменить ракурс.")

            val meanShifts = DoubleArray(64)
            for (cell in 0..63) {
                val rr = cell / 8
                val cc = cell % 8
                var sum = 0.0
                for (dy in 7..16 step 3) for (dx in 7..16 step 3) {
                    val idx = (rr * cellSize + dy) * rectSize + cc * cellSize + dx
                    sum += (rectified[idx].toInt() and 255) - (ref[idx].toInt() and 255)
                }
                meanShifts[cell] = sum / 16.0
            }
            val exposure = median(meanShifts)
            val scores = DoubleArray(64)
            for (cell in 0..63) {
                val rr = cell / 8
                val cc = cell % 8
                var sum = 0.0
                var count = 0
                for (dy in 5..18 step 2) for (dx in 5..18 step 2) {
                    val idx = (rr * cellSize + dy) * rectSize + cc * cellSize + dx
                    sum += abs((rectified[idx].toInt() and 255) -
                        (ref[idx].toInt() and 255) - exposure)
                    count++
                }
                scores[cell] = sum / count
            }

            val noise = median(scores)
            val threshold = max(16.0, noise * 1.9 + 7.0)
            val changed = (0..63).filter { scores[it] > threshold }
                .sortedByDescending { scores[it] }

            // Advance *camera* reference on every trustworthy homography.
            // Chess reference is deliberately NOT changed until confirmed.
            lastGray?.release()
            lastGray = now.clone()
            lastCorners = corners
            rejectedFrames = 0

            val q = corners.map { PointF(it.x / frameWidth, it.y / frameHeight) }
            val prior = matches.priorCorners
            val dx = ((corners.map { it.x }.average() -
                prior.map { it.x }.average())).roundToInt()
            val dy = ((corners.map { it.y }.average() -
                prior.map { it.y }.average())).roundToInt()
            val alignmentGood = matches.inliers >= 12 && matches.inlierFraction >= 0.34
            val visuallyClean = changed.size <= 4
            val info = when {
                changed.isEmpty() -> "Доска совмещена; заметных изменений клеток нет."
                changed.size == 2 -> "Возможны две изменившиеся клетки; нужна проверка легальности."
                changed.size == 1 -> "Изменилась одна клетка: вероятно, тень или неполный кадр."
                changed.size <= 4 -> "Есть ${changed.size} изменившихся клетки — возможны взятие или движение руки."
                else -> "Изменилось ${changed.size} клеток. Ход не определён: блики, параллакс фигур или движение камеры."
            }
            return BoardDifference(
                changed.take(8), scores, dx, dy, matches.rms, noise,
                info + " Оптическая привязка: ${matches.inliers}/${matches.total}.",
                alignmentGood && visuallyClean, aligned = true, updatedCorners = q,
                inliers = matches.inliers, featureCount = matches.total
            )
        } catch (e: Exception) {
            return fail("Ошибка оптического совмещения (${e.javaClass.simpleName}). Разметь кадр заново.")
        } finally {
            now.release()
        }
    }

    private data class Registration(
        val corners: List<PointF>, val priorCorners: List<PointF>,
        val inliers: Int, val total: Int, val inlierFraction: Double, val rms: Double
    )

    private fun opticalRegistration(prev: Mat, next: Mat, corners: List<PointF>): Registration? {
        // Use the board plane and a narrow external margin; furniture background
        // at a different depth would create the wrong homography.
        val centerX = corners.map { it.x }.average().toFloat()
        val centerY = corners.map { it.y }.average().toFloat()
        val maskCorners = corners.map {
            Point(((it.x - centerX) * 1.10f + centerX).toDouble(),
                ((it.y - centerY) * 1.10f + centerY).toDouble())
        }
        val mask = Mat.zeros(frameHeight, frameWidth, CvType.CV_8UC1)
        val poly = MatOfPoint(*maskCorners.toTypedArray())
        Imgproc.fillConvexPoly(mask, poly, Scalar(255.0))
        val pts = MatOfPoint()
        Imgproc.goodFeaturesToTrack(prev, pts, 160, 0.01, 3.0, mask)
        val from = pts.toArray()
        mask.release(); poly.release(); pts.release()
        if (from.size < 18) return null

        val fromF = MatOfPoint2f(*from)
        val nextF = MatOfPoint2f()
        val flowStatus = MatOfByte()
        val flowError = MatOfFloat()
        Video.calcOpticalFlowPyrLK(prev, next, fromF, nextF, flowStatus,
            flowError, Size(29.0, 29.0), 3)
        val nextPts = nextF.toArray()
        val status = flowStatus.toArray()

        // Cross-check optical-flow matches in reverse: repeated dark/light
        // chessboard squares can otherwise yield an incorrect 1-rank jump.
        val backwards = MatOfPoint2f()
        val backStatus = MatOfByte()
        val backError = MatOfFloat()
        Video.calcOpticalFlowPyrLK(next, prev, nextF, backwards, backStatus,
            backError, Size(29.0, 29.0), 3)
        val prevAgain = backwards.toArray()
        val statusBack = backStatus.toArray()

        val validPrev = ArrayList<Point>()
        val validNext = ArrayList<Point>()
        for (i in from.indices) {
            if (i >= nextPts.size || i >= status.size || i >= statusBack.size ||
                status[i].toInt() == 0 || statusBack[i].toInt() == 0) continue
            val point = nextPts[i]
            val back = prevAgain[i]
            if (point.x < -3 || point.x > frameWidth + 3 ||
                point.y < -3 || point.y > frameHeight + 3) continue
            if (hypot(back.x - from[i].x, back.y - from[i].y) > 2.2) continue
            validPrev.add(from[i])
            validNext.add(point)
        }
        fromF.release(); nextF.release(); flowStatus.release()
        flowError.release(); backwards.release(); backStatus.release(); backError.release()
        if (validPrev.size < 14) return null

        val source = MatOfPoint2f(*validPrev.toTypedArray())
        val dest = MatOfPoint2f(*validNext.toTypedArray())
        val inlierMask = Mat()
        val h = Calib3d.findHomography(source, dest, Calib3d.RANSAC, 2.6, inlierMask)
        source.release(); dest.release()
        if (h.empty()) {
            h.release(); inlierMask.release()
            return null
        }
        val inlierCount = Core.countNonZero(inlierMask)
        val fraction = inlierCount.toDouble() / validPrev.size
        if (inlierCount < 12 || fraction < 0.34) {
            h.release(); inlierMask.release()
            return null
        }

        val previousCorners = MatOfPoint2f(*corners.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val newCornersMat = MatOfPoint2f()
        Core.perspectiveTransform(previousCorners, newCornersMat, h)
        val transformed = newCornersMat.toArray().map { PointF(it.x.toFloat(), it.y.toFloat()) }
        previousCorners.release(); newCornersMat.release()

        // Calculate reprojection error using only RANSAC inliers.
        var ss = 0.0
        var count = 0
        val weights = ByteArray(inlierMask.rows() * inlierMask.cols())
        inlierMask.get(0, 0, weights)
        for (i in validPrev.indices) {
            if (i >= weights.size || (weights[i].toInt() and 255) == 0) continue
            val xx = validPrev[i].x; val yy = validPrev[i].y
            val den = h.get(2, 0)[0] * xx + h.get(2, 1)[0] * yy + h.get(2, 2)[0]
            if (abs(den) < 1e-9) continue
            val u = (h.get(0, 0)[0] * xx + h.get(0, 1)[0] * yy + h.get(0, 2)[0]) / den
            val v = (h.get(1, 0)[0] * xx + h.get(1, 1)[0] * yy + h.get(1, 2)[0]) / den
            val e = hypot(u - validNext[i].x, v - validNext[i].y)
            ss += e * e; count++
        }
        val rms = if (count > 0) kotlin.math.sqrt(ss / count) else 100.0
        h.release(); inlierMask.release()

        if (rms > 2.7 ||
            !validQuad(transformed, frameWidth, frameHeight, corners)) return null
        return Registration(transformed, corners, inlierCount, validPrev.size, fraction, rms)
    }

    private fun toGray(photo: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(photo, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        return gray
    }

    private fun rectify(gray: Mat, corners: List<PointF>): ByteArray? {
        if (corners.size != 4) return null
        val source = MatOfPoint2f(*corners.map {
            Point(it.x.toDouble(), it.y.toDouble())
        }.toTypedArray())
        val size = (rectSize - 1).toDouble()
        val target = MatOfPoint2f(Point(0.0, 0.0), Point(size, 0.0),
            Point(size, size), Point(0.0, size))
        val h = Imgproc.getPerspectiveTransform(source, target)
        val rect = Mat()
        Imgproc.warpPerspective(gray, rect, h,
            Size(rectSize.toDouble(), rectSize.toDouble()))
        val result = if (rect.rows() == rectSize && rect.cols() == rectSize) {
            ByteArray(rectSize * rectSize).also { rect.get(0, 0, it) }
        } else null
        rect.release(); h.release(); source.release(); target.release()
        return result
    }

    private fun validQuad(q: List<PointF>, w: Int, h: Int, previous: List<PointF>?): Boolean {
        if (q.size != 4) return false
        if (q.any { !it.x.isFinite() || !it.y.isFinite() ||
                it.x < -9 || it.y < -9 || it.x > w + 9 || it.y > h + 9 }) return false
        val edges = (0..3).map { i ->
            hypot((q[i].x - q[(i+1) % 4].x).toDouble(),
                (q[i].y - q[(i+1) % 4].y).toDouble())
        }
        if (edges.any { it < 20 }) return false
        val area = polygonArea(q)
        if (area < 700) return false
        if (previous != null) {
            val ratio = area / polygonArea(previous)
            if (ratio !in 0.50..1.70) return false
            if ((0..3).any {
                hypot((q[it].x-previous[it].x).toDouble(),
                    (q[it].y-previous[it].y).toDouble()) > 65
                }) return false
        }
        return true
    }
    private fun polygonArea(p: List<PointF>): Double {
        var sum = 0.0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i+1)%p.size]
            sum += a.x.toDouble() * b.y - a.y.toDouble() * b.x
        }
        return abs(sum) * 0.5
    }
    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val array = values.clone()
        array.sort()
        return if (array.size % 2 == 1) array[array.size / 2]
            else (array[array.size / 2 - 1] + array[array.size / 2]) / 2.0
    }
    private fun fail(reason: String): BoardDifference {
        rejectedFrames++
        val message = if (rejectedFrames >= 2)
            "$reason После нескольких сбоев обязательно разметь доску заново."
            else reason
        return BoardDifference(emptyList(), DoubleArray(64), 0, 0, 999.0,
            0.0, message, false, aligned = false)
    }
}
