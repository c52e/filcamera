package io.github.c52e.filcamera.camera

/** Controller-lifetime memory of rejected preview/RAW combinations; never blacklists a lens. */
internal class PreviewFallbacks {
    private data class Key(val route: LensRoute,val rawWidth: Int,val rawHeight: Int)
    private val rejected=mutableMapOf<Key,MutableList<PreviewStream>>()

    fun reject(route: LensRoute,rawWidth: Int,rawHeight: Int,stream: PreviewStream) {
        rejected.getOrPut(Key(route,rawWidth,rawHeight)) {mutableListOf()}.add(stream)
    }

    fun available(route: LensRoute,rawWidth: Int,rawHeight: Int,streams: List<PreviewStream>): List<PreviewStream> {
        val failures=rejected[Key(route,rawWidth,rawHeight)].orEmpty()
        // Once a combination fails, spend this run's bandwidth budget on strictly smaller buffers.
        return streams.filter {candidate->failures.all {failed->
            candidate.width<=failed.width && candidate.height<=failed.height && candidate.pixels<failed.pixels
        }}
    }
}
