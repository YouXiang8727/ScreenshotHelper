package com.youxiang8727.screenshothelper.presentation.main

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youxiang8727.screenshothelper.R
import com.youxiang8727.screenshothelper.service.ScreenshotService
import kotlinx.coroutines.flow.collectLatest

private const val TAG = "MainScreen"

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissionStatus()
    }

    var isMediaProjectionAuthorized by remember { mutableStateOf(false) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, ScreenshotService::class.java).apply {
                putExtra(ScreenshotService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenshotService.EXTRA_DATA, result.data)
            }
            context.startForegroundService(serviceIntent)
            isMediaProjectionAuthorized = true
        } else {
            Toast.makeText(context, "截圖授權未完成", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collectLatest { effect ->
            when (effect) {
                is MainUiEffect.LaunchIntent -> settingsLauncher.launch(effect.intent)
                is MainUiEffect.ShowToast ->
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    MainScreenContent(
        uiState = uiState,
        isMediaProjectionAuthorized = isMediaProjectionAuthorized,
        onRequestOverlay = { viewModel.onEvent(MainUiEvent.RequestOverlayPermission) },
        onRequestAccessibility = { viewModel.onEvent(MainUiEvent.RequestAccessibilityPermission) },
        onToggleFloatWindow = { viewModel.onEvent(MainUiEvent.ToggleFloatingWindow) },
        onRequestMediaProjection = {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
        },
        onRunBatchRecognition = { viewModel.onEvent(MainUiEvent.RunBatchImageRecognition) }
    )
}

@Composable
fun MainScreenContent(
    uiState: MainUiState,
    isMediaProjectionAuthorized: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onToggleFloatWindow: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onRunBatchRecognition: () -> Unit
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            PermissionRow(
                label = stringResource(
                    if (uiState.isOverlayPermissionGranted) R.string.overlay_permission_granted
                    else R.string.overlay_permission_denied
                ),
                isGranted = uiState.isOverlayPermissionGranted,
                buttonText = stringResource(R.string.btn_request_overlay_permission),
                onButtonClick = onRequestOverlay
            )

            PermissionRow(
                label = stringResource(
                    if (uiState.isAccessibilityServiceEnabled) R.string.accessibility_granted
                    else R.string.accessibility_denied
                ),
                isGranted = uiState.isAccessibilityServiceEnabled,
                buttonText = stringResource(R.string.btn_request_accessibility_permission),
                onButtonClick = onRequestAccessibility
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isMediaProjectionAuthorized) "截圖服務已授權 ✓" else "截圖服務未授權",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isMediaProjectionAuthorized)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                if (!isMediaProjectionAuthorized) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onRequestMediaProjection) {
                        Text(text = "授權截圖", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onToggleFloatWindow,
                enabled = uiState.isOverlayPermissionGranted,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isFloatingWindowRunning)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (uiState.isFloatingWindowRunning) "停止懸浮視窗"
                    else stringResource(R.string.btn_start_float_window)
                )
            }

            // ── 批次影像辨識 ──────────────────────────────────────
            Button(
                onClick = onRunBatchRecognition,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text(text = "影像辨識 (批次處理)")
            }

            if (!uiState.isOverlayPermissionGranted) {
                Text(
                    text = "請先授予懸浮視窗權限才能啟動",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    isGranted: Boolean,
    buttonText: String,
    onButtonClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isGranted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        if (!isGranted) {
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onButtonClick) {
                Text(text = buttonText, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
