package com.youxiang8727.screenshothelper.presentation.main

sealed class MainUiEvent {
    object RequestOverlayPermission : MainUiEvent()
    object RequestAccessibilityPermission : MainUiEvent()
    object ToggleFloatingWindow : MainUiEvent()
    object RefreshPermissionStatus : MainUiEvent()
    object RunBatchImageRecognition : MainUiEvent()
}
