package io.github.c52e.filcamera.camera

enum class WhiteBalanceSource { AUTO, NATIVE_CCT, CALIBRATED }

/** Frozen per-request values. Calibrated controls are computed before submitting the request. */
data class WhiteBalance(val kelvin: Int? = null,
    val source: WhiteBalanceSource = if(kelvin==null) WhiteBalanceSource.AUTO else WhiteBalanceSource.NATIVE_CCT,
    val calibrationHash: String? = null, val gains: List<Float> = emptyList(),val transform: List<Float> = emptyList()) {
    val manual: Boolean get() = kelvin != null
    init {
        require(kelvin == null || kelvin > 0)
        require((kelvin==null)==(source==WhiteBalanceSource.AUTO))
        if(source==WhiteBalanceSource.CALIBRATED) {
            require(!calibrationHash.isNullOrBlank())
            require(gains.size==4 && gains.all {it.isFinite() && it in 1f..16f})
            require(transform.size==9 && transform.all(Float::isFinite))
        }
    }
}
data class TemperatureRange(val minimum: Int, val maximum: Int) {
    init { require(minimum > 0 && maximum > minimum) }
    val default: Int get() = 5500.coerceIn(minimum, maximum)
    fun contains(kelvin: Int) = kelvin in minimum..maximum
}
data class WhiteBalanceStatus(val selection: WhiteBalance = WhiteBalance(),
    val range: TemperatureRange? = null, val pending: Boolean = false, val error: String = "",
    val controlSource: WhiteBalanceSource = WhiteBalanceSource.AUTO) {
    val supported: Boolean get() = range != null
}
