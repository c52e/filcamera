package io.github.c52e.filcamera.imaging

import kotlin.math.*

/** Optional positive display exposure, never a change to the saved RAW or its calibration.
 * Meters the 99th percentile of the brightest linear-sRGB channel, targeting 0.9.
 * A bounded log histogram avoids sorting/allocation proportional to image size.
 */
object DisplayExposure {
    const val ID = "raw-auto-display-v1"
    fun estimateEv(image: LinearImage): Float {
        val histogram=IntArray(1024)
        val m=ColorMath.rec2020ToSrgb
        var count=0
        for(i in image.rgb.indices step 3) {
            val x=image.rgb[i]; val y=image.rgb[i+1]; val z=image.rgb[i+2]
            val peak=max(m[0]*x+m[1]*y+m[2]*z,max(m[3]*x+m[4]*y+m[5]*z,m[6]*x+m[7]*y+m[8]*z))
            if(!peak.isFinite()) continue
            val bin=if(peak<=0f) 0 else (((log2(peak).coerceIn(-16f,8f)+16f)/24f)*1023).roundToInt()
            histogram[bin]++; count++
        }
        if(count==0) return 0f
        val threshold=ceil(count*.99).toInt()
        var sum=0
        for(bin in histogram.indices) {
            sum+=histogram[bin]
            if(sum>=threshold) {
                // Do not amplify black/near-black frames into visible noise.
                val peak=2f.pow(bin/1023f*24f-16f)
                if(peak < 1f/1024f) return 0f
                return log2(.9f/peak).coerceIn(0f,4f)
            }
        }
        return 0f
    }
    fun applyInPlace(image: LinearImage, ev: Float) {
        require(ev.isFinite() && ev in 0f..4f)
        val gain=2f.pow(ev)
        for(i in image.rgb.indices) image.rgb[i]*=gain
    }
}
