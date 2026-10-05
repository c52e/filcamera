package io.github.c52e.filcamera

import java.util.concurrent.Executors

/** One owner for all lab native sessions and camera save work. Never cancel the executor. */
object FilmWorker {
    private val owner=Executors.newSingleThreadExecutor()
    fun execute(work: () -> Unit) { owner.execute(work) }
}
