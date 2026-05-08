package com.youxiang8727.screenshothelper.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.youxiang8727.screenshothelper.service.ScreenshotService
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

object OpenCvHelper {
    private const val TAG = "OpenCvHelper"

    /**
     * 辨識滑動距離並儲存調試圖片 (透過 AccessibilityNodeInfo)
     */
    fun identifySliderOffset(
        context: Context,
        bgNode: AccessibilityNodeInfo, 
        sliderNode: AccessibilityNodeInfo, 
        callback: (Int) -> Unit
    ) {
        ScreenshotService.captureCurrentFrame { fullBitmap ->
            if (fullBitmap == null) {
                Log.e(TAG, "Failed to capture screen")
                callback(-1)
                return@captureCurrentFrame
            }

            val bgRect = android.graphics.Rect().apply { bgNode.getBoundsInScreen(this) }
            val sliderRect = android.graphics.Rect().apply { sliderNode.getBoundsInScreen(this) }

            val bgBitmap = cropBitmap(fullBitmap, bgRect)
            val sliderBitmap = cropBitmap(fullBitmap, sliderRect)
            fullBitmap.recycle()

            if (bgBitmap == null || sliderBitmap == null) {
                bgBitmap?.recycle()
                sliderBitmap?.recycle()
                callback(-1)
                return@captureCurrentFrame
            }

            // 計算相對座標
            val relativeSliderX = sliderRect.left - bgRect.left
            val relativeSliderY = sliderRect.top - bgRect.top

            // 核心演算法：模板匹配 (預設存檔在根目錄)
            val distance = calculateAndDebug(context, bgBitmap, sliderBitmap, relativeSliderX, relativeSliderY, subDir = "identifySliderOffset")
            
            bgBitmap.recycle()
            sliderBitmap.recycle()
            callback(distance)
        }
    }

    /**
     * 自動尋找滑動缺口 (混合方案：邊緣檢測找拼圖 -> 模板匹配找缺口)
     */
    fun autoFindSlideGap(
        context: Context,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        callback: (Int) -> Unit
    ) {
        ScreenshotService.captureCurrentFrame { fullBitmap ->
            if (fullBitmap == null) {
                Log.e(TAG, "Failed to capture screen")
                callback(-1)
                return@captureCurrentFrame
            }

            val bitmap = cropBitmap(fullBitmap, android.graphics.Rect(left, top, right, bottom))
            if (bitmap == null) {
                callback(-1)
                return@captureCurrentFrame
            }
            val dirName = "${System.currentTimeMillis()}"

            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            saveDebugImage(context, gray, dirName, "灰階.jpg")

            val binary = Mat()
            Imgproc.adaptiveThreshold(
                gray, binary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, 11, 2.0
            )
            saveDebugImage(context, binary, dirName, "自適應二值化.jpg")

            // 3. 垂直特徵投影
            val leftQuart = binary.cols() / 4
            val verticalProjection = IntArray(leftQuart)
            for (x in 0 until leftQuart) {
                var count = 0
                // 掃描中段區域
                for (y in (binary.rows() / 4)..(3 * binary.rows() / 4)) {
                    if (binary.get(y, x)[0] > 0) count++
                }
                verticalProjection[x] = count
            }

            // 4. 尋找最佳匹配 X 位置 (滑動視窗評分)
            var bestX = -1
            var maxScore = -1
            val targetWidth = 45

            for (x in 5 until leftQuart - targetWidth - 5) {
                val leftEdgeDensity = (verticalProjection[x - 1] + verticalProjection[x] + verticalProjection[x + 1]) / 3
                val rightEdgeDensity = (verticalProjection[x + targetWidth - 1] + verticalProjection[x + targetWidth] + verticalProjection[x + targetWidth + 1]) / 3

                var innerContent = 0
                for (i in x + 5 until x + targetWidth - 5) {
                    innerContent += verticalProjection[i]
                }

                val score = (leftEdgeDensity + rightEdgeDensity) * 2 + (innerContent / (targetWidth - 10))

                if (score > maxScore) {
                    maxScore = score
                    bestX = x
                }
            }

            if (bestX != -1) {
                // --- 核心改進：從內圈邊緣向左回溯到外圈邊緣 ---
                var outerX = bestX
                // 往左尋找，如果左邊的密度依然很高，代表邊緣還沒結束
                while (outerX > 2 && verticalProjection[outerX - 1] > verticalProjection[bestX] * 0.6) {
                    outerX--
                    // 防止無限回溯
                    if (bestX - outerX > 8) break
                }

                // 5. 自動尋找最佳 Y 軸位置
                val horizontalProjection = IntArray(binary.rows())
                for (y in 0 until binary.rows()) {
                    var count = 0
                    // 掃描從 outerX 開始的區間
                    for (x in outerX until (outerX + targetWidth + 5).coerceAtMost(binary.cols())) {
                        if (binary.get(y, x)[0] > 0) count++
                    }
                    horizontalProjection[y] = count
                }

                var bestY = (binary.rows() / 2) - 40
                var maxYSum = -1
                val scanHeight = 80
                for (y in 5 until binary.rows() - scanHeight - 5) {
                    var currentSum = 0
                    for (i in 0 until scanHeight) currentSum += horizontalProjection[y + i]
                    if (currentSum > maxYSum) {
                        maxYSum = currentSum
                        bestY = y
                    }
                }

                // 最終寬度補償：因為起點往左移了，寬度要加回來，並多包 2 像素確保包住外圈
                val finalWidth = targetWidth + (bestX - outerX) + 2
                val puzzleRect = Rect(outerX, bestY, finalWidth, scanHeight)

                // 繪製與儲存
                Imgproc.rectangle(mat, puzzleRect, Scalar(255.0, 0.0, 0.0), 2)
                saveDebugImage(context, mat, dirName, "抓取拼圖位置.jpg")

                val sliderBmp = Bitmap.createBitmap(bitmap, puzzleRect.x, puzzleRect.y, puzzleRect.width, puzzleRect.height)
                val distance = calculateAndDebug(context, bitmap, sliderBmp, puzzleRect.x, puzzleRect.y, dirName, "result.png")

                sliderBmp.recycle()
                callback(distance)
            } else {
                callback(-1)
            }

            releaseAll(mat, gray, binary)
        }
    }

    private fun releaseAll(vararg objs: Any?) {
        for (obj in objs) {
            when (obj) {
                is Mat -> obj.release()
                is Bitmap -> if (!obj.isRecycled) obj.recycle()
            }
        }
    }

    /**
     * 辨識滑動距離並儲存調試圖片 (直接傳入圖片)
     */
    fun identifySliderOffsetByBitmaps(
        context: Context,
        bgBitmap: Bitmap,
        sliderBitmap: Bitmap,
        sliderX: Int,
        sliderY: Int,
        fileName: String
    ): Int {
        return calculateAndDebug(
            context, 
            bgBitmap, 
            sliderBitmap, 
            sliderX, 
            sliderY, 
            subDir = "identifySliderOffsetByBitmaps", 
            fileName = fileName
        )
    }

    private fun calculateAndDebug(
        context: Context,
        bgBmp: Bitmap, 
        sliderBmp: Bitmap, 
        sliderX: Int, 
        sliderY: Int,
        subDir: String? = null,
        fileName: String = "${System.currentTimeMillis()}.png"
    ): Int {
        val bgSrc = Mat()
        val sliderSrc = Mat()
        Utils.bitmapToMat(bgBmp, bgSrc)
        Utils.bitmapToMat(sliderBmp, sliderSrc)

        // 1. 預處理 (使用邊緣特徵進行匹配最準確)
        val bgGray = Mat()
        val sliderGray = Mat()
        Imgproc.cvtColor(bgSrc, bgGray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(sliderSrc, sliderGray, Imgproc.COLOR_RGBA2GRAY)

        val bgEdges = Mat()
        val sliderEdges = Mat()
        Imgproc.Canny(bgGray, bgEdges, 100.0, 200.0)
        Imgproc.Canny(sliderGray, sliderEdges, 100.0, 200.0)

        // 2. 模板匹配
        val result = Mat()
        Imgproc.matchTemplate(bgEdges, sliderEdges, result, Imgproc.TM_CCOEFF_NORMED)

        var mmr = Core.minMaxLoc(result)
        var bestMatchX = mmr.maxLoc.x.toInt()
        var bestMatchY = mmr.maxLoc.y.toInt()
        
        // 排除原位 (避免匹配到拼圖塊本身)
        if (abs(bestMatchX - sliderX) < 15) {
            val mask = Mat.ones(result.size(), CvType.CV_8U)
            // 排除掉原位置及其右側的一小段範圍
            val excludeWidth = (sliderX + sliderSrc.width() / 2).coerceAtMost(result.cols())
            Imgproc.rectangle(mask, Rect(0, 0, excludeWidth, result.rows()), Scalar(0.0), -1)
            mmr = Core.minMaxLoc(result, mask)
            bestMatchX = mmr.maxLoc.x.toInt()
            bestMatchY = mmr.maxLoc.y.toInt()
            mask.release()
        }

        // 3. 繪製紅色調試框線
        val redColor = Scalar(255.0, 0.0, 0.0, 255.0)
        // 標註原位
        Imgproc.rectangle(
            bgSrc, 
            Rect(sliderX, sliderY, sliderSrc.cols(), sliderSrc.rows()), 
            redColor, 
            2
        )
        // 標註匹配到的位置
        Imgproc.rectangle(
            bgSrc, 
            Rect(bestMatchX, bestMatchY, sliderSrc.cols(), sliderSrc.rows()), 
            redColor, 
            2
        )

        // 4. 儲存結果圖
        saveDebugImage(context, bgSrc, subDir, fileName)

        val finalDistance = bestMatchX - sliderX
        Log.d(TAG, "calculateAndDebug -> MatchX: $bestMatchX, SliderX: $sliderX, Dist: $finalDistance, Conf: ${mmr.maxVal}")

        // 釋放資源
        bgSrc.release(); sliderSrc.release(); bgGray.release(); sliderGray.release()
        bgEdges.release(); sliderEdges.release(); result.release()

        return if (mmr.maxVal > 0.1) finalDistance else -1
    }

    private fun saveDebugImage(context: Context, mat: Mat, subDir: String?, fileName: String) {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)
        
        val baseDir = context.getExternalFilesDir(null) ?: return
        val targetDir = if (subDir != null) File(baseDir, subDir) else baseDir
        
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        
        val file = File(targetDir, fileName)
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                Log.d(TAG, "Debug image saved: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug image", e)
        } finally {
            bitmap.recycle()
        }
    }

    private fun cropBitmap(src: Bitmap, rect: android.graphics.Rect): Bitmap? {
        return try {
            Bitmap.createBitmap(
                src,
                rect.left.coerceAtLeast(0),
                rect.top.coerceAtLeast(0),
                rect.width().coerceAtMost(src.width - rect.left),
                rect.height().coerceAtMost(src.height - rect.top)
            )
        } catch (e: Exception) {
            null
        }
    }
}
