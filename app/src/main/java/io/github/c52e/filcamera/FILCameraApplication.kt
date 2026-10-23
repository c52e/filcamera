package io.github.c52e.filcamera

import android.app.Application
import io.github.c52e.filcamera.strings.Strings

class FILCameraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.initialize(this)
        val diagnostics=io.github.c52e.filcamera.camera.LocalDiagnostics
        diagnostics.initialize(this,"${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) / ${BuildConfig.DIAGNOSTIC_BUILD}")
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler {thread,error ->
            try {diagnostics.failure("APP-001","uncaught", "Unexpected application termination",error,
                "thread=${thread.name}",synchronous=true)} catch(_: Throwable) { /* Preserve Android crash handling. */ }
            previous?.uncaughtException(thread,error)
        }
        if(android.os.Build.VERSION.SDK_INT>=30) runCatching {
            getSystemService(android.app.ActivityManager::class.java).getHistoricalProcessExitReasons(packageName,0,3).forEach {
                diagnostics.event("APP.previousExit","time=${it.timestamp} reason=${it.reason} status=${it.status}")
            }
        }
    }
}
