package com.youxiang8727.screenshothelper.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.youxiang8727.screenshothelper.MainActivity
import com.youxiang8727.screenshothelper.R
import com.youxiang8727.screenshothelper.util.OcrHelper
import com.youxiang8727.screenshothelper.util.OpenCvHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val CHANNEL_ID = "floating_window_channel"
        private const val NOTIFICATION_ID = 1002

        private var savedX = 100
        private var savedY = 200
    }

    private var windowManager: WindowManager? = null
    private var floatingView: DraggableContainer? = null
    private var selectionOverlay: SelectionOverlayView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var isMinimized = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupFloatingWindow()
        observePermissionStates()
    }

    private fun observePermissionStates() {
        serviceScope.launch {
            combine(
                ScreenshotService.isAuthorized,
                NodeAccessibilityService.isConnected
            ) { screenshot, accessibility ->
                screenshot to accessibility
            }.collect {
                floatingView?.refreshUI()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        removeFloatingWindow()
        removeSelectionOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setupFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }
        floatingView = DraggableContainer(this).apply { refreshUI() }
        windowManager?.addView(floatingView, layoutParams)
    }

    private fun showSelectionOverlay() {
        if (selectionOverlay != null) return
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        
        selectionOverlay = SelectionOverlayView(this) { rect ->
            handleSelectionResult(rect)
        }
        windowManager?.addView(selectionOverlay, params)
        floatingView?.visibility = View.GONE
    }

    private fun removeSelectionOverlay() {
        selectionOverlay?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
        }
        selectionOverlay = null
        floatingView?.visibility = View.VISIBLE
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
                        Log.d(TAG, "OCR Result Saved: ${visionText.text}")
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

    private fun toggleMinimize() {
        isMinimized = !isMinimized
        floatingView?.refreshUI()
        windowManager?.updateViewLayout(floatingView, layoutParams)
    }

    private fun removeFloatingWindow() {
        floatingView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { }
        }
        floatingView = null
    }

    @SuppressLint("ViewConstructor")
    inner class SelectionOverlayView(context: Context, val onSelected: (Rect) -> Unit) : View(context) {
        private var startX = 0f
        private var startY = 0f
        private var currentX = 0f
        private var currentY = 0f
        private var isSelecting = false
        
        private val paint = Paint().apply {
            color = Color.parseColor("#80000000")
            style = Paint.Style.FILL
        }
        
        private val strokePaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5f
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }
        
        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            if (isSelecting) {
                val location = IntArray(2)
                getLocationOnScreen(location)
                val localStartX = startX - location[0]
                val localStartY = startY - location[1]
                val localCurrentX = currentX - location[0]
                val localCurrentY = currentY - location[1]

                val left = min(localStartX, localCurrentX)
                val top = min(localStartY, localCurrentY)
                val right = max(localStartX, localCurrentX)
                val bottom = max(localStartY, localCurrentY)
                canvas.drawRect(left, top, right, bottom, clearPaint)
                canvas.drawRect(left, top, right, bottom, strokePaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    currentX = event.rawX
                    currentY = event.rawY
                    isSelecting = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    currentX = event.rawX
                    currentY = event.rawY
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    isSelecting = false
                    val rect = Rect(
                        min(startX, event.rawX).toInt(),
                        min(startY, event.rawY).toInt(),
                        max(startX, event.rawX).toInt(),
                        max(startY, event.rawY).toInt()
                    )
                    onSelected(rect)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    inner class DraggableContainer(context: Context) : FrameLayout(context) {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var isDragging = false

        fun refreshUI() {
            removeAllViews()
            if (isMinimized) setupMinimizedView() else setupExpandedView()
        }

        private fun setupExpandedView() {
            val isScreenshotReady = ScreenshotService.isAuthorized.value
            val isAccessibilityReady = NodeAccessibilityService.isConnected.value

            val mainLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(12))
                background = GradientDrawable().apply {
                    setColor(0xCC333333.toInt()) 
                    cornerRadius = dpToPx(16).toFloat()
                    setStroke(dpToPx(1), 0x33FFFFFF.toInt()) 
                }
                elevation = dpToPx(8).toFloat()
            }

            val header = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }

            header.addView(TextView(context).apply {
                text = "Helper"
                textSize = 11f
                setTextColor(0xAAFFFFFF.toInt()) 
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })

            header.addView(TextView(context).apply {
                text = " — "
                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                textSize = 18f
                setTextColor(Color.WHITE)
                setOnClickListener { toggleMinimize() }
            })

            mainLayout.addView(header)

            val btnParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpToPx(10) }

            if (!isScreenshotReady || !isAccessibilityReady) {
                mainLayout.addView(TextView(context).apply {
                    text = "⚠️ 請開啟授權與無障礙"
                    textSize = 11f
                    setTextColor(0xFFFF5252.toInt()) 
                    setPadding(0, dpToPx(8), 0, dpToPx(8))
                    gravity = Gravity.CENTER
                })
            } else {
                mainLayout.addView(Button(context).apply {
                    text = getString(R.string.btn_screenshot)
                    layoutParams = btnParams
                    transformationMethod = null
                    background = createOutlinedDrawable(0xFFBB86FC.toInt())
                    setTextColor(0xFFBB86FC.toInt())
                    setOnClickListener { ScreenshotService.takeScreenshot() }
                })

                mainLayout.addView(Button(context).apply {
                    text = getString(R.string.btn_get_nodes)
                    layoutParams = btnParams
                    transformationMethod = null
                    background = createOutlinedDrawable(0xFF03DAC5.toInt())
                    setTextColor(0xFF03DAC5.toInt())
                    setOnClickListener { NodeAccessibilityService.instance?.performNodeDump() }
                })

                mainLayout.addView(Button(context).apply {
                    text = "辨識文字"
                    layoutParams = btnParams
                    transformationMethod = null
                    background = createOutlinedDrawable(0xFF4CAF50.toInt())
                    setTextColor(0xFF4CAF50.toInt())
                    setOnClickListener { showSelectionOverlay() }
                })

                mainLayout.addView(Button(context).apply {
                    text = "計算滑動距離"
                    layoutParams = btnParams
                    transformationMethod = null
                    background = createOutlinedDrawable(Color.YELLOW)
                    setTextColor(Color.YELLOW)
                    setOnClickListener { handleCalculateSlider() }
                })
            }
            addView(mainLayout)
        }

        private fun handleCalculateSlider() {
            val accessibilityService = NodeAccessibilityService.instance ?: return
            val rootNode = accessibilityService.rootInActiveWindow ?: return

            val targetNode = accessibilityService.findNodeById("puzzle_backimg").getOrNull(0)
            val puzzle = accessibilityService.findNodeById("puzzle_slot").getOrNull(0)
            val slider = accessibilityService.findNodeByText("请拖动滑块完成拼图").getOrNull(0)?.parent?.getChild(2)

            if (targetNode != null && puzzle != null && slider != null) {
                val sliderRect = Rect().apply { slider.getBoundsInScreen(this) }
                OpenCvHelper.identifySliderOffset(this@FloatingWindowService, targetNode, puzzle) { offset ->
                    serviceScope.launch(Dispatchers.Main) {
                        if (offset > 0) {
                            accessibilityService.dispatchSwipe(
                                sliderRect.centerX(), sliderRect.centerY(),
                                sliderRect.centerX() + offset, sliderRect.centerY(), 1000
                            )
                        }
                    }
                }
            }
            rootNode.recycle()
        }

        private fun setupMinimizedView() {
            val miniCircle = FrameLayout(context).apply {
                layoutParams = LayoutParams(dpToPx(52), dpToPx(52))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xCC333333.toInt()) 
                    setStroke(dpToPx(2), Color.WHITE)
                }
                elevation = dpToPx(8).toFloat()
                addView(TextView(context).apply {
                    text = "📸"
                    gravity = Gravity.CENTER
                    layoutParams = LayoutParams(-1, -1)
                })
                setOnClickListener { toggleMinimize() }
            }
            addView(miniCircle)
        }

        private fun createOutlinedDrawable(color: Int) = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dpToPx(10).toFloat()
            setStroke(dpToPx(2), color)
        }

        private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> updateInitialTouch(ev)
                MotionEvent.ACTION_MOVE -> if (checkTouchSlop(ev)) { isDragging = true; return true }
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { updateInitialTouch(event); return true }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging && checkTouchSlop(event)) isDragging = true
                    if (isDragging) {
                        this@FloatingWindowService.layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        this@FloatingWindowService.layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(this, this@FloatingWindowService.layoutParams)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) { 
                        savedX = this@FloatingWindowService.layoutParams.x
                        savedY = this@FloatingWindowService.layoutParams.y 
                    } else if (event.action == MotionEvent.ACTION_UP) performClick()
                    isDragging = false
                }
            }
            return true
        }

        private fun updateInitialTouch(ev: MotionEvent) {
            initialX = this@FloatingWindowService.layoutParams.x
            initialY = this@FloatingWindowService.layoutParams.y
            initialTouchX = ev.rawX
            initialTouchY = ev.rawY
            isDragging = false
        }

        private fun checkTouchSlop(ev: MotionEvent): Boolean {
            val dx = abs(ev.rawX - initialTouchX); val dy = abs(ev.rawY - initialTouchY)
            return dx > touchSlop || dy > touchSlop
        }

        override fun performClick(): Boolean = super.performClick()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Floating Window", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("懸浮助手")
            .setContentText("服務運行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
}
