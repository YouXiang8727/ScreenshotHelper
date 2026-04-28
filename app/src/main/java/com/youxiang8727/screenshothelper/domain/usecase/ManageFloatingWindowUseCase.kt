package com.youxiang8727.screenshothelper.domain.usecase

import android.content.Context
import android.content.Intent
import com.youxiang8727.screenshothelper.service.FloatingWindowService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ManageFloatingWindowUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun start() {
        val intent = Intent(context, FloatingWindowService::class.java)
        context.startService(intent)
    }

    fun stop() {
        val intent = Intent(context, FloatingWindowService::class.java)
        context.stopService(intent)
    }
}
