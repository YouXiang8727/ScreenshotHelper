package com.youxiang8727.screenshothelper.presentation.main

data class MainUiState(
    val isOverlayPermissionGranted: Boolean = false,
    val isAccessibilityServiceEnabled: Boolean = false,
    val isFloatingWindowRunning: Boolean = false,
    val statusMessage: String = ""
)
