package io.github.c52e.filcamera

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import io.github.c52e.filcamera.strings.R as Texts

class ReleaseSmokeTest {
    @Test fun signedReleaseCapture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("releaseSmoke") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
            return null
        }
        fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            val deadline = SystemClock.elapsedRealtime() + 90000
            while (SystemClock.elapsedRealtime() < deadline) {
                val root = ui.rootInActiveWindow
                if (root?.packageName?.toString() == "io.github.c52e.filcamera") {
                    find(root, predicate)?.let { return it }
                }
                SystemClock.sleep(200)
            }
            error("Release UI condition timed out")
        }
        ui.executeShellCommand("am start -W -n io.github.c52e.filcamera/io.github.c52e.filcamera.CameraActivity").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        val capture = context.getString(Texts.string.capture)
        assertTrue(awaitNode { it.contentDescription?.toString() == capture && it.isEnabled }
            .performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val savedPrefix = context.getString(Texts.string.saved_1f_s).substringBefore('%')
        val saved = awaitNode { it.text?.toString()?.startsWith(savedPrefix) == true }
        android.util.Log.i("FILCameraReleaseSmoke", saved.text.toString())
        val library = context.getString(Texts.string.library)
        SystemClock.sleep(500)
        var opened = false
        repeat(10) {
            if (!opened) {
                var node: AccessibilityNodeInfo? = awaitNode { it.contentDescription?.toString() == library }
                while (node != null && !node.isClickable) node = node.parent
                opened = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                if (!opened) SystemClock.sleep(200)
            }
        }
        assertTrue(opened)
        val photoPrefix = context.getString(Texts.string.open_photo_value, "")
        awaitNode { it.contentDescription?.toString()?.startsWith(photoPrefix) == true }
    }
}


