package io.github.c52e.filcamera

import android.app.Activity
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

internal fun foregroundActivityRule(activity: Class<out Activity>): ExternalResource = object : ExternalResource() {
    override fun before() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val component = "${instrumentation.targetContext.packageName}/${activity.name}"
        AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am start -W -n $component")).use {
            it.readBytes()
        }
    }
}
