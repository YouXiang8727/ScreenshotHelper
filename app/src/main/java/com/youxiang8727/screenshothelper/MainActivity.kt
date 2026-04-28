package com.youxiang8727.screenshothelper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.youxiang8727.screenshothelper.presentation.main.MainScreen
import com.youxiang8727.screenshothelper.ui.theme.ScreenshotHelperTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScreenshotHelperTheme {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次從設定頁返回時重新檢查權限狀態
    }
}
