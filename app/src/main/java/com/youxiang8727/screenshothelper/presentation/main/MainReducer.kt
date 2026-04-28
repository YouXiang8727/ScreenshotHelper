package com.youxiang8727.screenshothelper.presentation.main

object MainReducer {
    fun reduce(state: MainUiState, event: MainUiEvent): MainUiState = when (event) {
        is MainUiEvent.RefreshPermissionStatus -> state // ViewModel 會另外更新狀態
        is MainUiEvent.ToggleFloatingWindow -> state.copy(
            isFloatingWindowRunning = !state.isFloatingWindowRunning
        )
        else -> state
    }
}
