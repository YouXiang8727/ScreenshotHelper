package com.youxiang8727.screenshothelper

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader

@HiltAndroidApp
class ScreenshotHelperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (OpenCVLoader.initDebug()) {
            Log.d("ScreenshotHelperApp", "OpenCV initialized successfully")
        } else {
            Log.e("ScreenshotHelperApp", "OpenCV initialization failed")
        }
    }
}
