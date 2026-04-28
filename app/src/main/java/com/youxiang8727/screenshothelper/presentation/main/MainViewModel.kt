package com.youxiang8727.screenshothelper.presentation.main

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youxiang8727.screenshothelper.domain.usecase.CheckPermissionUseCase
import com.youxiang8727.screenshothelper.domain.usecase.ManageFloatingWindowUseCase
import com.youxiang8727.screenshothelper.util.OpenCvHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checkPermissionUseCase: CheckPermissionUseCase,
    private val manageFloatingWindowUseCase: ManageFloatingWindowUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _uiEffect = Channel<MainUiEffect>(Channel.BUFFERED)
    val uiEffect = _uiEffect.receiveAsFlow()

    init {
        refreshPermissionStatus()
    }

    fun onEvent(event: MainUiEvent) {
        val newState = MainReducer.reduce(_uiState.value, event)
        _uiState.value = newState

        viewModelScope.launch {
            when (event) {
                is MainUiEvent.RequestOverlayPermission -> {
                    _uiEffect.send(
                        MainUiEffect.LaunchIntent(checkPermissionUseCase.buildOverlaySettingsIntent())
                    )
                }
                is MainUiEvent.RequestAccessibilityPermission -> {
                    _uiEffect.send(
                        MainUiEffect.LaunchIntent(checkPermissionUseCase.buildAccessibilitySettingsIntent())
                    )
                }
                is MainUiEvent.ToggleFloatingWindow -> {
                    if (newState.isFloatingWindowRunning) {
                        manageFloatingWindowUseCase.start()
                    } else {
                        manageFloatingWindowUseCase.stop()
                    }
                }
                is MainUiEvent.RefreshPermissionStatus -> {
                    refreshPermissionStatus()
                }
                is MainUiEvent.RunBatchImageRecognition -> {
                    runBatchRecognition()
                }
            }
        }
    }

    private fun runBatchRecognition() {
        viewModelScope.launch(Dispatchers.IO) {
            val baseDir = context.getExternalFilesDir(null) ?: return@launch
            val imageDir = File(baseDir, "image")
            
            if (!imageDir.exists() || !imageDir.isDirectory) {
                _uiEffect.send(MainUiEffect.ShowToast("找不到 files/image 目錄"))
                return@launch
            }

            // 取得所有背景圖 (排除檔名包含 slider 的圖片)
            val bgFiles = imageDir.listFiles { _, name -> 
                name.endsWith(".png", ignoreCase = true) && !name.contains("slider", ignoreCase = true) 
            }
            
            // 檢查是否有通用滑塊 slider.png
            val globalSliderFile = File(imageDir, "slider.png")
            val hasGlobalSlider = globalSliderFile.exists()

            if (bgFiles.isNullOrEmpty()) {
                _uiEffect.send(MainUiEffect.ShowToast("沒有找到背景圖片 (.png)"))
                return@launch
            }

            _uiEffect.send(MainUiEffect.ShowToast("開始處理 ${bgFiles.size} 張背景圖..."))

            bgFiles.forEach { bgFile ->
                try {
                    val bgBitmap = BitmapFactory.decodeFile(bgFile.absolutePath) ?: return@forEach
                    // 呼叫 OpenCV 辨識邏輯 (這會觸發 saveDebugImage 並建立資料夾)
                    OpenCvHelper.identifySliderOffsetByBitmaps(
                        context = context,
                        bgBitmap = bgBitmap,
                        sliderBitmap = bgBitmap,
                        sliderX = 0, // 假設滑塊起始於左側 0 (調試用)
                        sliderY = 0,
                        fileName = bgFile.name
                    )

                    bgBitmap.recycle()

                    bgBitmap.recycle()
                } catch (e: Exception) {
                    Log.e("BatchCV", "Error processing ${bgFile.name}", e)
                }
            }
            _uiEffect.send(MainUiEffect.ShowToast("批次處理完成！請查看 identifySliderOffsetByBitmaps 資料夾"))
        }
    }

    fun refreshPermissionStatus() {
        _uiState.value = _uiState.value.copy(
            isOverlayPermissionGranted = checkPermissionUseCase.isOverlayPermissionGranted(),
            isAccessibilityServiceEnabled = checkPermissionUseCase.isAccessibilityServiceEnabled()
        )
    }
}
