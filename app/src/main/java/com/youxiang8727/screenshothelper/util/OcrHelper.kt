package com.youxiang8727.screenshothelper.util

import android.content.Context
import android.graphics.*
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OcrHelper {
    private const val TAG = "OcrHelper"
    
    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    fun recognizeText(bitmap: Bitmap, onResult: (Text?) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                onResult(visionText)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                onResult(null)
            }
    }

    /**
     * 儲存結果，包含畫有框線與座標的截圖，以及帶座標的文字檔
     * @param selectionRect 原始圈選的區域，用於將相對座標轉換為全屏座標
     */
    fun saveResult(context: Context, bitmap: Bitmap, visionText: Text, selectionRect: Rect) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = File(context.getExternalFilesDir(null), "ocr_results")
        if (!dir.exists()) dir.mkdirs()

        // 建立一個可編輯的 Bitmap 複本用來畫框
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        
        val rectPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 3f // 紅色細線
        }
        
        val textPaint = Paint().apply {
            color = Color.RED
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            // 加上陰影背景讓字體更清晰
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }

        val sb = StringBuilder()
        
        for (block in visionText.textBlocks) {
            val box = block.boundingBox
            if (box != null) {
                // 1. 計算全屏絕對座標
                val absL = box.left + selectionRect.left
                val absT = box.top + selectionRect.top
                val absR = box.right + selectionRect.left
                val absB = box.bottom + selectionRect.top
                
                // 2. 在圖片上畫紅色矩形 (使用 box 相對座標)
                canvas.drawRect(box, rectPaint)
                
                // 3. 在圖片上標注 LRTB 座標
                val label = "[$absL,$absT,$absR,$absB]"
                // 標注在框的上方，如果太靠頂部則標注在框內
                val textY = if (box.top > 30) (box.top - 10).toFloat() else (box.top + 25).toFloat()
                canvas.drawText(label, box.left.toFloat(), textY, textPaint)

                // 4. 記錄到文字檔格式
                sb.append("${block.text}[$absL,$absT,$absR,$absB]\n")
            } else {
                sb.append("${block.text}[]\n")
            }
        }

        // 儲存畫好的圖片
        val imageFile = File(dir, "ocr_$timestamp.png")
        try {
            FileOutputStream(imageFile).use { out ->
                mutableBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image", e)
        } finally {
            mutableBitmap.recycle()
        }

        // 儲存文字檔
        val textFile = File(dir, "ocr_$timestamp.txt")
        try {
            textFile.writeText(sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save text", e)
        }
        
        Log.d(TAG, "Result saved to: ${dir.absolutePath}")
    }
}
