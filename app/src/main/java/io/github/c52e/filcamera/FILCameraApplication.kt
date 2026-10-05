package io.github.c52e.filcamera

import android.app.Application
import io.github.c52e.filcamera.strings.Strings

class FILCameraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.initialize(this)
    }
}
