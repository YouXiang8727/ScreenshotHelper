package com.youxiang8727.screenshothelper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.youxiang8727.screenshothelper.util.OcrHelper
import com.youxiang8727.screenshothelper.util.OpenCvHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class FloatingWindowService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val CHANNEL_ID = "floating_window_channel"
        private const val NOTIFICATION_ID = 1002

        private var savedX = 100
        private var savedY = 200
    }

    private var windowManager: WindowManager? = null
    private var floatingComposeView: ComposeView? = null
    private var selectionComposeView: ComposeView? = null
    
    private lateinit var floatingParams: WindowManager.LayoutParams
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Implement ViewModelStoreOwner
    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore = store

    // Implement SavedStateRegistryOwner
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupFloatingWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        removeFloatingWindow()
        removeSelectionOverlay()
        store.clear()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    private fun setupFloatingWindow() {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        floatingParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        floatingComposeView = createComposeView {
            FloatingBubbleContent(
                onDrag = { dx, dy ->
                    floatingParams.x += dx
                    floatingParams.y += dy
                    windowManager?.updateViewLayout(floatingComposeView, floatingParams)
                    savedX = floatingParams.x
                    savedY = floatingParams.y
                },
                onScreenshotClick = {
                    ScreenshotService.takeScreenshot()
                    NodeAccessibilityService.instance?.performNodeDump()
                },
                onOcrClick = { showSelectionOverlay() },
                onSliderClick = { handleCalculateSlider() }
            )
        }

        windowManager?.addView(floatingComposeView, floatingParams)
    }

    private fun showSelectionOverlay() {
        if (selectionComposeView != null) return

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        selectionComposeView = createComposeView {
            SelectionOverlayContent(
                onSelected = { rect ->
                    handleSelectionResult(rect)
                }
            )
        }

        windowManager?.addView(selectionComposeView, params)
        floatingComposeView?.visibility = View.GONE
    }

    private fun removeSelectionOverlay() {
        selectionComposeView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
        }
        selectionComposeView = null
        floatingComposeView?.visibility = View.VISIBLE
    }

    private fun removeFloatingWindow() {
        floatingComposeView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
        }
        floatingComposeView = null
    }

    private fun handleSelectionResult(rect: Rect) {
        removeSelectionOverlay()
        if (rect.width() < 10 || rect.height() < 10) return

        ScreenshotService.captureCurrentFrame { fullBitmap ->
            if (fullBitmap == null) return@captureCurrentFrame
            try {
                val cropLeft = rect.left.coerceIn(0, fullBitmap.width - 1)
                val cropTop = rect.top.coerceIn(0, fullBitmap.height - 1)
                val cropWidth = rect.width().coerceAtMost(fullBitmap.width - cropLeft)
                val cropHeight = rect.height().coerceAtMost(fullBitmap.height - cropTop)

                val cropped = Bitmap.createBitmap(fullBitmap, cropLeft, cropTop, cropWidth, cropHeight)
                OcrHelper.recognizeText(cropped) { visionText ->
                    if (visionText != null) {
                        OcrHelper.saveResult(this, cropped, visionText, rect)
                        serviceScope.launch(Dispatchers.Main) {
                            Toast.makeText(this@FloatingWindowService, "辨識完成並已存檔", Toast.LENGTH_SHORT).show()
                        }
                    }
                    cropped.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Crop or OCR failed", e)
            } finally {
                fullBitmap.recycle()
            }
        }
    }

    private fun handleCalculateSlider() {
        val accessibilityService = NodeAccessibilityService.instance ?: return
        val targetNode = accessibilityService.findNodeById("puzzle_backimg").getOrNull(0)
        val puzzle = accessibilityService.findNodeById("puzzle_slot").getOrNull(0)
        val slider = accessibilityService.findNodeByText("请拖动滑块完成拼图").getOrNull(0)?.parent?.getChild(2)

        if (targetNode != null && puzzle != null && slider != null) {
            val sliderRect = Rect().apply { slider.getBoundsInScreen(this) }
            OpenCvHelper.identifySliderOffset(this@FloatingWindowService, targetNode, puzzle) { offset ->
                serviceScope.launch(Dispatchers.Main) {
                    Toast.makeText(this@FloatingWindowService, "偏移量: $offset", Toast.LENGTH_SHORT).show()
                    if (offset > 0) {
//                        accessibilityService.dispatchSwipe(
//                            sliderRect.centerX(), sliderRect.centerY(),
//                            sliderRect.centerX() + offset, sliderRect.centerY(), 1000
//                        )
                    }
                }
            }
        }
    }

    private fun createComposeView(content: @Composable () -> Unit): ComposeView {
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowService)
            setViewTreeViewModelStoreOwner(this@FloatingWindowService)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowService)
            
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    content()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Floating Window", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("懸浮助手")
            .setContentText("服務運行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
}

@Composable
fun FloatingBubbleContent(
    onDrag: (Int, Int) -> Unit,
    onScreenshotClick: () -> Unit,
    onOcrClick: () -> Unit,
    onSliderClick: () -> Unit
) {
    var isMinimized by remember { mutableStateOf(false) }
    val isScreenshotAuthorized by ScreenshotService.isAuthorized.collectAsState()
    val isAccessibilityEnabled by NodeAccessibilityService.isConnected.collectAsState()

    Box(
        modifier = Modifier
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x.toInt(), dragAmount.y.toInt())
                }
            }
    ) {
        if (isMinimized) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC333333))
                    .border(2.dp, Color.White, CircleShape)
                    .clickable { isMinimized = false },
                contentAlignment = Alignment.Center
            ) {
                Text("📸", fontSize = 24.sp)
            }
        } else {
            Column(
                modifier = Modifier
                    .width(160.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC333333))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Helper",
                        color = Color(0xAAFFFFFF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        " — ",
                        color = Color.White,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clickable { isMinimized = true }
                            .padding(horizontal = 4.dp)
                    )
                }

                if (!isScreenshotAuthorized || !isAccessibilityEnabled) {
                    Text(
                        "⚠️ 請開啟授權與無障礙",
                        color = Color(0xFFFF5252),
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                } else {
                    ActionButton("截圖 & 節點", Color(0xFFBB86FC), onScreenshotClick)
                    ActionButton("辨識文字", Color(0xFF4CAF50), onOcrClick)
                    ActionButton("滑動驗證碼辨識", Color.Yellow, onSliderClick)
                }
            }
        }
    }
}

@Composable
fun ActionButton(text: String, color: Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, color),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = 12.sp)
    }
}

@Composable
fun SelectionOverlayContent(onSelected: (Rect) -> Unit) {
    var startPos by remember { mutableStateOf<Offset?>(null) }
    var currentPos by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        startPos = offset
                        currentPos = offset
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentPos = change.position
                    },
                    onDragEnd = {
                        val s = startPos
                        val c = currentPos
                        if (s != null && c != null) {
                            val rect = Rect(
                                min(s.x, c.x).toInt(),
                                min(s.y, c.y).toInt(),
                                max(s.x, c.x).toInt(),
                                max(s.y, c.y).toInt()
                            )
                            onSelected(rect)
                        }
                        startPos = null
                        currentPos = null
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val s = startPos
            val c = currentPos
            if (s != null && c != null) {
                val left = min(s.x, c.x)
                val top = min(s.y, c.y)
                val right = max(s.x, c.x)
                val bottom = max(s.y, c.y)

                drawRect(
                    color = Color.Transparent,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    blendMode = BlendMode.Clear
                )
                drawRect(
                    color = Color.White,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                )
            }
        }
    }
}
