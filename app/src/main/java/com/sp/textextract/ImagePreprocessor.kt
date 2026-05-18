package com.sp.textextract

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * Preprocessing pipeline applied to every captured photo before OCR:
 *
 *   1. Downscale if huge   – keeps processing fast; ML Kit works best ≤ 4 MP
 *   2. Auto-rotate         – corrects EXIF orientation so the image is upright
 *   3. Document crop       – finds the largest quadrilateral contour and applies
 *                            a perspective warp ("scan" effect); falls back to the
 *                            full frame if no good quad is found
 *   4. Greyscale           – removes colour noise that confuses character segmentation
 *   5. Denoise             – light Gaussian blur removes camera sensor noise before
 *                            edge-sensitive steps; better than no blur for binarisation
 *   6. Contrast / CLAHE    – Contrast-Limited Adaptive Histogram Equalisation boosts
 *                            local contrast without blowing out highlights; far better
 *                            than a global stretch for uneven lighting
 *   7. Sharpen             – unsharp-mask brings back edge detail softened by the blur
 *   8. Adaptive threshold  – Sauvola-style local binarisation (OpenCV's ADAPTIVE_THRESH
 *                            _GAUSSIAN_C) handles shadows and gradients that a global
 *                            Otsu threshold fails on; outputs a clean black-on-white image
 *   9. Deskew              – small in-plane rotation correction using the dominant line
 *                            angle from a Hough transform (±15 ° range)
 *  10. Border pad           – adds a thin white margin so characters at the edge aren't
 *                            clipped by ML Kit's internal padding
 */
object ImagePreprocessor {

    // ── tuneable constants ─────────────────────────────────────────────────────

    /** Max side length before downscaling.  4 MP ≈ 2000 × 2000 px. */
    private const val MAX_SIDE_PX = 2000

    /** Minimum quad area as a fraction of the total image area to accept a crop. */
    private const val MIN_QUAD_AREA_FRAC = 0.10

    /** Adaptive threshold block size (must be odd). */
    private const val THRESH_BLOCK = 31

    /** Adaptive threshold constant subtracted from the mean. */
    private const val THRESH_C = 10.0

    /** Maximum deskew correction angle in degrees. */
    private const val MAX_DESKEW_DEG = 15.0

    /** White border added on each side (px, after all transforms). */
    private const val BORDER_PX = 20

    // ── public entry point ─────────────────────────────────────────────────────

    /**
     * Runs the full pipeline and returns a processed [Bitmap] ready for ML Kit.
     * The [exifDegrees] parameter should be the rotation angle read from the
     * photo's EXIF data (0, 90, 180, or 270).
     */
    fun process(src: Bitmap, exifDegrees: Int = 0): Bitmap {
        val mat = bitmapToMat(src)

        val downscaled  = downscale(mat)
        val rotated     = rotateExif(downscaled, exifDegrees)
        val cropped     = cropDocument(rotated)
        val grey        = toGrey(cropped)
        val denoised    = denoise(grey)
        val clahe       = applyClahe(denoised)
        val sharpened   = sharpen(clahe)
        val binary      = adaptiveThreshold(sharpened)
        val deskewed    = deskew(binary)
        val padded      = addBorder(deskewed)

        return matToBitmap(padded)
    }

    // ── step implementations ───────────────────────────────────────────────────

    /** Step 1 – downscale to MAX_SIDE_PX on the longer dimension */
    private fun downscale(src: Mat): Mat {
        val maxSide = maxOf(src.rows(), src.cols())
        if (maxSide <= MAX_SIDE_PX) return src
        val scale = MAX_SIDE_PX.toDouble() / maxSide
        val dst = Mat()
        Imgproc.resize(src, dst, Size(), scale, scale, Imgproc.INTER_AREA)
        return dst
    }

    /** Step 2 – rotate according to EXIF orientation tag */
    private fun rotateExif(src: Mat, degrees: Int): Mat {
        if (degrees == 0) return src
        val dst = Mat()
        val code = when (degrees) {
            90  -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return src
        }
        Core.rotate(src, dst, code)
        return dst
    }

    /**
     * Step 3 – perspective-correct document crop.
     *
     * Algorithm:
     *   a) Work on a small thumbnail for speed.
     *   b) Convert to greyscale → blur → Canny edges.
     *   c) Dilate edges to close small gaps in document borders.
     *   d) Find all external contours; keep the largest one.
     *   e) Approximate to a polygon; accept only 4-vertex (quad) shapes
     *      whose area exceeds MIN_QUAD_AREA_FRAC of the image.
     *   f) Order corners (TL, TR, BR, BL) and warpPerspective.
     *   g) If no suitable quad is found, return the original unchanged.
     */
    private fun cropDocument(src: Mat): Mat {
        val thumbScale = 800.0 / maxOf(src.rows(), src.cols())
        val thumb = Mat()
        Imgproc.resize(src, thumb, Size(), thumbScale, thumbScale)

        val grey = Mat()
        Imgproc.cvtColor(thumb, grey, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(grey, grey, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(grey, edges, 50.0, 150.0)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        if (contours.isEmpty()) return src

        val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }
        val thumbArea = (thumb.rows() * thumb.cols()).toDouble()

        for (contour in sorted.take(5)) {
            val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.02 * peri, true)

            if (approx.rows() == 4 && Imgproc.contourArea(approx) > thumbArea * MIN_QUAD_AREA_FRAC) {
                // Scale corners back to original resolution
                val scale = 1.0 / thumbScale
                val srcPts = orderQuadCorners(approx.toArray()).map {
                    Point(it.x * scale, it.y * scale)
                }
                return perspectiveWarp(src, srcPts)
            }
        }
        return src   // fallback: no quad found
    }

    /** Orders quad corners as [TL, TR, BR, BL]. */
    private fun orderQuadCorners(pts: Array<Point>): List<Point> {
        val sorted = pts.sortedBy { it.x + it.y }   // TL has smallest sum, BR largest
        val tl = sorted[0]
        val br = sorted[3]
        val remaining = listOf(sorted[1], sorted[2]).sortedBy { it.y }
        val tr = remaining[0]   // smaller y → top
        val bl = remaining[1]
        return listOf(tl, tr, br, bl)
    }

    /** Applies a 4-point perspective warp. */
    private fun perspectiveWarp(src: Mat, corners: List<Point>): Mat {
        val (tl, tr, br, bl) = corners
        val widthTop    = hypot(br.x - bl.x, br.y - bl.y)
        val widthBottom = hypot(tr.x - tl.x, tr.y - tl.y)
        val w = maxOf(widthTop, widthBottom)

        val heightLeft  = hypot(tr.x - br.x, tr.y - br.y)
        val heightRight = hypot(tl.x - bl.x, tl.y - bl.y)
        val h = maxOf(heightLeft, heightRight)

        val srcMat = MatOfPoint2f(tl, tr, br, bl)
        val dstMat = MatOfPoint2f(
            Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h)
        )
        val M = Imgproc.getPerspectiveTransform(srcMat, dstMat)
        val warped = Mat()
        Imgproc.warpPerspective(src, warped, M, Size(w, h))
        return warped
    }

    /** Step 4 – convert BGR → greyscale */
    private fun toGrey(src: Mat): Mat {
        val dst = Mat()
        return when (src.channels()) {
            1    -> src
            4    -> { Imgproc.cvtColor(src, dst, Imgproc.COLOR_BGRA2GRAY); dst }
            else -> { Imgproc.cvtColor(src, dst, Imgproc.COLOR_BGR2GRAY);  dst }
        }
    }

    /** Step 5 – Gaussian denoise (light, σ = 1) */
    private fun denoise(src: Mat): Mat {
        val dst = Mat()
        Imgproc.GaussianBlur(src, dst, Size(3.0, 3.0), 1.0)
        return dst
    }

    /**
     * Step 6 – CLAHE (Contrast-Limited Adaptive Histogram Equalisation).
     * clipLimit=2 and tileSize=8×8 work well for typical document photos.
     */
    private fun applyClahe(src: Mat): Mat {
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val dst = Mat()
        clahe.apply(src, dst)
        return dst
    }

    /**
     * Step 7 – unsharp mask sharpen.
     * result = original * (1 + amount) − blurred * amount
     * amount = 1.5 gives a noticeable but not garish sharpening.
     */
    private fun sharpen(src: Mat): Mat {
        val blurred = Mat()
        Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), 3.0)
        val dst = Mat()
        Core.addWeighted(src, 2.5, blurred, -1.5, 0.0, dst)
        return dst
    }

    /**
     * Step 8 – adaptive (Gaussian-weighted) threshold.
     * THRESH_BINARY_INV + inversion gives black text on white — the ideal
     * input for most OCR engines.
     */
    private fun adaptiveThreshold(src: Mat): Mat {
        val dst = Mat()
        Imgproc.adaptiveThreshold(
            src, dst,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            THRESH_BLOCK,
            THRESH_C
        )
        return dst
    }

    /**
     * Step 9 – deskew using probabilistic Hough lines.
     *
     * Strategy:
     *   • Run HoughLinesP on the binary image.
     *   • Compute each line's angle; collect those within ±MAX_DESKEW_DEG.
     *   • Take the median angle and rotate the image to correct it.
     *   • If fewer than 5 lines are found the image is likely already straight —
     *     skip correction to avoid introducing artefacts.
     */
    private fun deskew(src: Mat): Mat {
        val lines = Mat()
        Imgproc.HoughLinesP(
            src, lines,
            1.0,              // rho resolution
            Math.PI / 180,    // theta resolution
            80,               // min votes
            src.cols() * 0.3, // min line length (30 % of width)
            10.0              // max gap
        )

        if (lines.rows() < 5) return src

        val angles = mutableListOf<Double>()
        for (i in 0 until lines.rows()) {
            val line = lines[i, 0]
            val angle = Math.toDegrees(atan2(line[3] - line[1], line[2] - line[0]))
            if (abs(angle) < MAX_DESKEW_DEG) angles.add(angle)
        }

        if (angles.isEmpty()) return src

        angles.sort()
        val median = angles[angles.size / 2]
        if (abs(median) < 0.5) return src   // negligible — skip rotation

        val centre = Point(src.cols() / 2.0, src.rows() / 2.0)
        val M = Imgproc.getRotationMatrix2D(centre, median, 1.0)
        val dst = Mat()
        Imgproc.warpAffine(
            src, dst, M, src.size(),
            Imgproc.INTER_LINEAR,
            Core.BORDER_CONSTANT,
            Scalar(255.0)   // fill with white
        )
        return dst
    }

    /** Step 10 – add a white border so edge characters aren't clipped */
    private fun addBorder(src: Mat): Mat {
        val dst = Mat()
        Core.copyMakeBorder(
            src, dst,
            BORDER_PX, BORDER_PX, BORDER_PX, BORDER_PX,
            Core.BORDER_CONSTANT,
            Scalar(255.0)
        )
        return dst
    }

    // ── bitmap ↔ Mat helpers ───────────────────────────────────────────────────

    private fun bitmapToMat(bmp: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bmp, mat)
        return mat
    }

    private fun matToBitmap(mat: Mat): Bitmap {
        // Ensure we always output an 8-bit greyscale-compatible bitmap
        val out = Mat()
        return when (mat.channels()) {
            1 -> {
                Imgproc.cvtColor(mat, out, Imgproc.COLOR_GRAY2RGBA)
                val bmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(out, bmp)
                bmp
            }
            else -> {
                val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(mat, bmp)
                bmp
            }
        }
    }
}
