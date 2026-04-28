package com.youxiang8727.screenshothelper.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import com.youxiang8727.screenshothelper.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenshotService : Service() {

    companion object {
        private const val TAG = "ScreenshotService"
        private const val CHANNEL_ID = "screenshot_service_channel"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        var instance: ScreenshotService? = null
        
        private val _isAuthorized = MutableStateFlow(false)
        val isAuthorized = _isAuthorized.asStateFlow()

        fun takeScreenshot() {
            instance?.captureScreen() ?: Log.w(TAG, "ScreenshotService instance is null")
        }

        /** 提供給 OpenCvHelper 使用的同步/回調截圖 */
        fun captureCurrentFrame(callback: (Bitmap?) -> Unit) {
            instance?.captureScreenToBitmap(callback) ?: callback(null)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        resolveScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            initMediaProjection(resultCode, data)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        releaseProjection()
        stopSelf()
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        mediaProjection?.stop()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)?.apply {
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    _isAuthorized.value = false
                    releaseProjection()
                }
            }, handler)
        }
        _isAuthorized.value = mediaProjection != null
    }

    override fun onDestroy() {
        super.onDestroy()
        _isAuthorized.value = false
        releaseProjection()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 截圖核心 ──────────────────────────────────────────────────────────────

    fun captureScreen() {
        captureScreenToBitmap { bitmap ->
            bitmap?.let { persistScreenshot(it) }
        }
    }

    fun captureScreenToBitmap(callback: (Bitmap?) -> Unit) {
        val projection = mediaProjection ?: run {
            callback(null)
            return
        }

        releaseVirtualDisplay()
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, handler
        )

        handler.postDelayed({
            val reader = imageReader
            if (reader == null) {
                callback(null)
                return@postDelayed
            }
            try {
                reader.acquireLatestImage()?.use { image ->
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowPadding = plane.rowStride - pixelStride * screenWidth
                    
                    val rawBitmap = createBitmap(screenWidth + rowPadding / pixelStride, screenHeight)
                    rawBitmap.copyPixelsFromBuffer(buffer)
                    
                    val finalBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, screenWidth, screenHeight)
                    rawBitmap.recycle()
                    callback(finalBitmap)
                } ?: callback(null)
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing bitmap", e)
                callback(null)
            } finally {
                releaseVirtualDisplay()
            }
        }, 300)
    }

    private fun persistScreenshot(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "screenshot_$timestamp.png"
        val outputDir = getExternalFilesDir(null) ?: return
        val outputFile = File(outputDir, fileName)
        FileOutputStream(outputFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        Log.d(TAG, "Screenshot saved → ${outputFile.absolutePath}")
    }

    private fun resolveScreenMetrics() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
            screenDensity = dm.densityDpi
        }
        Log.d("David", "screenWidth: $screenWidth, screenHeight: $screenHeight, screenDensity: $screenDensity")
    }

    private fun releaseVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    private fun releaseProjection() {
        releaseVirtualDisplay()
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Screenshot Service", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("截圖服務")
            .setContentText("權限已就緒")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
}
