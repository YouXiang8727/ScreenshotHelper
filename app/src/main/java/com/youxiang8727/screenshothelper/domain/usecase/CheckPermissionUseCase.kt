package com.youxiang8727.screenshothelper.domain.usecase

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.youxiang8727.screenshothelper.service.NodeAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class CheckPermissionUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun isOverlayPermissionGranted(): Boolean =
        Settings.canDrawOverlays(context)

    fun isAccessibilityServiceEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val targetService = "${context.packageName}/${NodeAccessibilityService::class.java.name}"
        return enabledServices.any { info ->
            val si = info.resolveInfo.serviceInfo
            "${si.packageName}/${si.name}" == targetService
        }
    }

    fun buildOverlaySettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    fun buildAccessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
