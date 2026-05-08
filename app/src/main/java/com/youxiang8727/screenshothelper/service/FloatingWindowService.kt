package com.youxiang8727.screenshothelper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import android.view.accessibility.AccessibilityNodeInfo
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.text.input.KeyboardType
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

enum class SliderSearchType {
    TEXT, ID, CONTENT_DESCRIPTION
}

class FloatingWindowService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val CHANNEL_ID = "floating_window_channel"
        private const val NOTIFICATION_ID = 1002
        private const val PREFS_NAME = "slider_prefs"

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        floatingComposeView = createComposeView {
            var showSliderConfig by remember { mutableStateOf(false) }
            var showSliderPositionConfig by remember { mutableStateOf(false) }
            var isRecognizing by remember { mutableStateOf(false) }

            // 節點配置狀態
            var bigKey by remember { mutableStateOf(prefs.getString("bigKey", "验证码背景") ?: "验证码背景") }
            var bigType by remember { mutableStateOf(SliderSearchType.valueOf(prefs.getString("bigType", SliderSearchType.CONTENT_DESCRIPTION.name) ?: SliderSearchType.CONTENT_DESCRIPTION.name)) }
            var smallKey by remember { mutableStateOf(prefs.getString("smallKey", "验证码滑块") ?: "验证码滑块") }
            var smallType by remember { mutableStateOf(SliderSearchType.valueOf(prefs.getString("smallType", SliderSearchType.CONTENT_DESCRIPTION.name) ?: SliderSearchType.CONTENT_DESCRIPTION.name)) }

            // 位置配置狀態 (LTRB)
            var leftStr by remember { mutableStateOf(prefs.getString("leftStr", "530") ?: "530") }
            var topStr by remember { mutableStateOf(prefs.getString("topStr", "953") ?: "953") }
            var rightStr by remember { mutableStateOf(prefs.getString("rightStr", "820") ?: "820") }
            var bottomStr by remember { mutableStateOf(prefs.getString("bottomStr", "1098") ?: "1098") }

            // 儲存 Helper
            val updateBigKey: (String) -> Unit = { it -> bigKey = it; prefs.edit().putString("bigKey", it).apply() }
            val updateBigType: (SliderSearchType) -> Unit = { it -> bigType = it; prefs.edit().putString("bigType", it.name).apply() }
            val updateSmallKey: (String) -> Unit = { it -> smallKey = it; prefs.edit().putString("smallKey", it).apply() }
            val updateSmallType: (SliderSearchType) -> Unit = { it -> smallType = it; prefs.edit().putString("smallType", it.name).apply() }

            val updatePos: (String, String) -> Unit = { key, value ->
                prefs.edit().putString(key, value).apply()
            }

            val onDrag: (Int, Int) -> Unit = { dx, dy ->
                floatingParams.x += dx
                floatingParams.y += dy
                windowManager?.updateViewLayout(floatingComposeView, floatingParams)
                savedX = floatingParams.x
                savedY = floatingParams.y
            }

            if (isRecognizing) {
                RecognizingContent(onDrag = onDrag)
            } else if (showSliderConfig) {
                SliderConfigContent(
                    bigKey = bigKey,
                    onBigKeyChange = updateBigKey,
                    bigType = bigType,
                    onBigTypeChange = updateBigType,
                    smallKey = smallKey,
                    onSmallKeyChange = updateSmallKey,
                    smallType = smallType,
                    onSmallTypeChange = updateSmallType,
                    onDrag = onDrag,
                    onBack = { 
                        showSliderConfig = false
                        updateFloatingWindowFocus(false)
                    },
                    onRecognize = {
                        handleCalculateSlider(bigKey, bigType, smallKey, smallType) { recognizing ->
                            isRecognizing = recognizing
                            updateFloatingWindowFocus(!recognizing)
                        }
                    }
                )
            } else if (showSliderPositionConfig) {
                SliderPositionConfigContent(
                    left = leftStr,
                    onLeftChange = { leftStr = it; updatePos("leftStr", it) },
                    top = topStr,
                    onTopChange = { topStr = it; updatePos("topStr", it) },
                    right = rightStr,
                    onRightChange = { rightStr = it; updatePos("rightStr", it) },
                    bottom = bottomStr,
                    onBottomChange = { bottomStr = it; updatePos("bottomStr", it) },
                    onDrag = onDrag,
                    onBack = { 
                        showSliderPositionConfig = false
                        updateFloatingWindowFocus(false)
                    },
                    onRecognize = {
                        isRecognizing = true
                        updateFloatingWindowFocus(false)
                        serviceScope.launch(Dispatchers.Main) {
                            // 給 UI 變換時間並收合鍵盤
                            delay(400)
                            OpenCvHelper.autoFindSlideGap(
                                this@FloatingWindowService,
                                leftStr.toIntOrNull() ?: 0,
                                topStr.toIntOrNull() ?: 0,
                                rightStr.toIntOrNull() ?: 0,
                                bottomStr.toIntOrNull() ?: 0
                            ) { resultX ->
                                serviceScope.launch(Dispatchers.Main) {
                                    isRecognizing = false
                                    updateFloatingWindowFocus(true)
                                    Toast.makeText(this@FloatingWindowService, "偵測結果 X: $resultX", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                )
            } else {
                FloatingBubbleContent(
                    onDrag = onDrag,
                    onScreenshotClick = {
                        ScreenshotService.takeScreenshot()
                        NodeAccessibilityService.instance?.performNodeDump()
                    },
                    onOcrClick = { showSelectionOverlay() },
                    onSliderClick = { 
                        showSliderConfig = true
                        updateFloatingWindowFocus(true)
                    },
                    onSliderPositionClick = {
                        showSliderPositionConfig = true
                        updateFloatingWindowFocus(true)
                    }
                )
            }
        }

        windowManager?.addView(floatingComposeView, floatingParams)
    }

    private fun updateFloatingWindowFocus(focusable: Boolean) {
        if (focusable) {
            floatingParams.flags = floatingParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            floatingParams.flags = floatingParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager?.updateViewLayout(floatingComposeView, floatingParams)
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

    private fun handleCalculateSlider(
        bigKey: String, 
        bigType: SliderSearchType, 
        smallKey: String, 
        smallType: SliderSearchType,
        onStateChange: (Boolean) -> Unit
    ) {
        val accessibilityService = NodeAccessibilityService.instance ?: return
        
        fun findNodes(key: String, type: SliderSearchType): List<AccessibilityNodeInfo> {
            Log.d(TAG, "findNodes($key, $type)")
            return when (type) {
                SliderSearchType.TEXT -> accessibilityService.findNodeByText(key)
                SliderSearchType.ID -> accessibilityService.findNodeById(key)
                SliderSearchType.CONTENT_DESCRIPTION -> accessibilityService.findNodeByContentDescription(key)
            }
        }

        val targetNode = findNodes(bigKey, bigType).getOrNull(0)
        val puzzle = findNodes(smallKey, smallType).getOrNull(0)

        if (targetNode != null && puzzle != null) {
            onStateChange(true) // 開始辨識，切換 UI 並縮小
            serviceScope.launch(Dispatchers.Main) {
                // 給 Compose 渲染時間並確保懸浮窗已縮小且鍵盤已收合，再執行截圖
                delay(400)
                OpenCvHelper.identifySliderOffset(this@FloatingWindowService, targetNode, puzzle) { offset ->
                    serviceScope.launch(Dispatchers.Main) {
                        onStateChange(false) // 恢復原本畫面
                        Toast.makeText(this@FloatingWindowService, "偏移量: $offset", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            serviceScope.launch(Dispatchers.Main) {
                val msg = "找不到節點: 大圖=${targetNode != null}, 小圖=${puzzle != null}"
                Toast.makeText(this@FloatingWindowService, msg, Toast.LENGTH_SHORT).show()
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
fun RecognizingContent(onDrag: (Int, Int) -> Unit) {
    Box(
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC333333))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
            .padding(12.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x.toInt(), dragAmount.y.toInt())
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text("辨識中...", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SliderConfigContent(
    bigKey: String,
    onBigKeyChange: (String) -> Unit,
    bigType: SliderSearchType,
    onBigTypeChange: (SliderSearchType) -> Unit,
    smallKey: String,
    onSmallKeyChange: (String) -> Unit,
    smallType: SliderSearchType,
    onSmallTypeChange: (SliderSearchType) -> Unit,
    onDrag: (Int, Int) -> Unit,
    onBack: () -> Unit,
    onRecognize: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC333333))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x.toInt(), dragAmount.y.toInt())
                    }
                }
        ) {
            Text(
                "⬅",
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(4.dp)
            )
            Text(
                "滑動驗證配置(節點)",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("大圖配置:", color = Color.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = bigKey,
                onValueChange = onBigKeyChange,
                label = { Text("大圖關鍵字", fontSize = 10.sp, color = Color.LightGray) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp)
            )
            SearchTypeSelector(selectedType = bigType, onTypeSelected = onBigTypeChange)

            Text("小圖配置:", color = Color.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = smallKey,
                onValueChange = onSmallKeyChange,
                label = { Text("小圖關鍵字", fontSize = 10.sp, color = Color.LightGray) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp)
            )
            SearchTypeSelector(selectedType = smallType, onTypeSelected = onSmallTypeChange)

            ActionButton("辨識", Color.Yellow) {
                onRecognize()
            }
        }
    }
}

@Composable
fun SliderPositionConfigContent(
    left: String,
    onLeftChange: (String) -> Unit,
    top: String,
    onTopChange: (String) -> Unit,
    right: String,
    onRightChange: (String) -> Unit,
    bottom: String,
    onBottomChange: (String) -> Unit,
    onDrag: (Int, Int) -> Unit,
    onBack: () -> Unit,
    onRecognize: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC333333))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x.toInt(), dragAmount.y.toInt())
                    }
                }
        ) {
            Text(
                "⬅",
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(4.dp)
            )
            Text(
                "滑動驗證配置(位置)",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("範圍配置 (LTRB):", color = Color.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = left,
                    onValueChange = onLeftChange,
                    label = { Text("Left", fontSize = 10.sp, color = Color.LightGray) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp)
                )
                OutlinedTextField(
                    value = top,
                    onValueChange = onTopChange,
                    label = { Text("Top", fontSize = 10.sp, color = Color.LightGray) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = right,
                    onValueChange = onRightChange,
                    label = { Text("Right", fontSize = 10.sp, color = Color.LightGray) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp)
                )
                OutlinedTextField(
                    value = bottom,
                    onValueChange = onBottomChange,
                    label = { Text("Bottom", fontSize = 10.sp, color = Color.LightGray) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp)
                )
            }

            ActionButton("辨識", Color.Yellow) {
                onRecognize()
            }
        }
    }
}

@Composable
fun SearchTypeSelector(selectedType: SliderSearchType, onTypeSelected: (SliderSearchType) -> Unit) {
    Column {
        SliderSearchType.values().forEach { type ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTypeSelected(type) }
                    .padding(vertical = 2.dp)
            ) {
                RadioButton(
                    selected = selectedType == type,
                    onClick = { onTypeSelected(type) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = Color.Yellow,
                        unselectedColor = Color.Gray
                    ),
                    modifier = Modifier.size(32.dp)
                )
                Text(type.name, color = Color.White, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun FloatingBubbleContent(
    onDrag: (Int, Int) -> Unit,
    onScreenshotClick: () -> Unit,
    onOcrClick: () -> Unit,
    onSliderClick: () -> Unit,
    onSliderPositionClick: () -> Unit
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
                    ActionButton("滑動驗證碼辨識(位置)", Color.Yellow, onSliderPositionClick)
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
