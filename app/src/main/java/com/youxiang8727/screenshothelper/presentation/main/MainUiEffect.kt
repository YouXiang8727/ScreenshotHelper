package com.youxiang8727.screenshothelper.presentation.main

import android.content.Intent

sealed class MainUiEffect {
    data class LaunchIntent(val intent: Intent) : MainUiEffect()
    data class ShowToast(val message: String) : MainUiEffect()
}
