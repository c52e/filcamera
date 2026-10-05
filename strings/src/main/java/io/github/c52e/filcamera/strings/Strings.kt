package io.github.c52e.filcamera.strings

import android.content.Context

/** Application resources follow the system configuration, including on worker threads. */
object Strings {
    @Volatile private var context: Context? = null

    fun initialize(application: Context) {
        context = application.applicationContext
    }

    fun get(id: Int, vararg args: Any?): String {
        val resources = checkNotNull(context) { "Strings must be initialized by Application" }.resources
        return if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)
    }
}

fun tr(id: Int, vararg args: Any?): String = Strings.get(id, *args)
