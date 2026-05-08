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
import kotlin.math.max
import kotlin.math.min

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

            val rect = android.graphics.Rect(left, top, right, bottom)
            val bitmap = cropBitmap(fullBitmap, rect)
            fullBitmap.recycle()

            if (bitmap == null) {
                callback(-1)
                return@captureCurrentFrame
            }

            val subDir = "autoFindSlideGap"
            val fileName = "${System.currentTimeMillis()}.png"
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            
            // 1. 儲存原始剪裁圖
            saveDebugImage(context, mat, subDir, "1_original_$fileName")

            // 2. 灰階
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            saveDebugImage(context, gray, subDir, "2_gray_$fileName")

            // 3. 高斯模糊降噪
            val blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            saveDebugImage(context, blurred, subDir, "3_blurred_$fileName")

            // 4. 邊緣檢測
            val edges = Mat()
            Imgproc.Canny(blurred, edges, 50.0, 150.0)
            saveDebugImage(context, edges, subDir, "4_edges_$fileName")

            // 水平投影：每列邊緣像素總和
            val colSums = IntArray(edges.cols()) { x ->
                var sum = 0
                for (y in 0 until edges.rows()) {
                    if (edges.get(y, x)[0] > 0) sum++
                }
                sum
            }

            // --- 演算法邏輯：尋找兩個顯著邊緣 (參考排除原位邏輯) ---
            var maxDiff1 = -1
            var pos1 = -1
            var maxDiff2 = -1
            var pos2 = -1

            // 第一遍：找最強邊緣 (通常是拼圖)
            for (x in 1 until colSums.size) {
                val diff = colSums[x] - colSums[x - 1]
                if (diff > maxDiff1) {
                    maxDiff1 = diff
                    pos1 = x
                }
            }

            // 第二遍：找次強邊緣 (缺口)，但排除掉第一個點及其周邊區域 (增加排除範圍至 50)
            val excludeRange = 50
            for (x in 1 until colSums.size) {
                if (pos1 != -1 && abs(x - pos1) < excludeRange) continue
                
                val diff = colSums[x] - colSums[x - 1]
                if (diff > maxDiff2) {
                    maxDiff2 = diff
                    pos2 = x
                }
            }

            // 5. 繪製紅色結果框線並儲存
            val rectWidth = 50
            
            // 框出位置 1 (最強邊緣)
            if (pos1 != -1 && maxDiff1 > 0) {
                Imgproc.rectangle(
                    mat,
                    Rect(pos1, 0, rectWidth.coerceAtMost(mat.cols() - pos1), mat.rows()),
                    Scalar(255.0, 0.0, 0.0, 255.0),
                    2
                )
            }

            // 框出位置 2 (次強邊緣)
            if (pos2 != -1 && maxDiff2 > 0) {
                Imgproc.rectangle(
                    mat,
                    Rect(pos2, 0, rectWidth.coerceAtMost(mat.cols() - pos2), mat.rows()),
                    Scalar(255.0, 0.0, 0.0, 255.0),
                    2
                )
            }
            
            saveDebugImage(context, mat, subDir, "5_result_$fileName")

            // 釋放資源
            mat.release()
            gray.release()
            blurred.release()
            edges.release()
            bitmap.recycle()
            
            // 計算滑動距離：兩個顯著邊界點的間距 (abs(pos2 - pos1))
            val distance = if (pos1 != -1 && pos2 != -1) abs(pos2 - pos1) else -1
            Log.d(TAG, "autoFindSlideGap -> pos1: $pos1, pos2: $pos2, distance: $distance")
            
            callback(distance)
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

        // 1. 預處理
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
        
        // 排除原位
        if (abs(bestMatchX - sliderX) < 10) {
            val mask = Mat.ones(result.size(), CvType.CV_8U)
            val excludeWidth = (sliderX + sliderSrc.width() / 2).coerceAtMost(result.cols())
            Imgproc.rectangle(mask, Rect(0, 0, excludeWidth, result.rows()), Scalar(0.0), -1)
            mmr = Core.minMaxLoc(result, mask)
            bestMatchX = mmr.maxLoc.x.toInt()
            bestMatchY = mmr.maxLoc.y.toInt()
            mask.release()
        }

        // 3. 繪製調試框線
        Imgproc.rectangle(
            bgSrc, 
            Rect(sliderX, sliderY, sliderSrc.cols(), sliderSrc.rows()), 
            Scalar(0.0, 0.0, 255.0, 255.0), 
            2
        )
        Imgproc.rectangle(
            bgSrc, 
            Rect(bestMatchX, bestMatchY, sliderSrc.cols(), sliderSrc.rows()), 
            Scalar(0.0, 0.0, 0.0, 255.0), 
            2
        )

        // 4. 儲存圖片
        saveDebugImage(context, bgSrc, subDir, fileName)

        val finalDistance = bestMatchX - sliderX
        Log.d(TAG, "Result -> MatchX: $bestMatchX, SliderX: $sliderX, Dist: $finalDistance, Conf: ${mmr.maxVal}")

        // 釋放
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
