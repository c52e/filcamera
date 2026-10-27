package io.github.c52e.filcamera

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Paired CPU-oracle/GPU preparation timings at editing resolution only. */
class BitmapGpuPerformanceTest {
    @Test fun previewPreparation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("bitmapGpuPerformance")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val task=FutureTask(Callable {
            val rows=JSONArray();val report=JSONObject().put("model",android.os.Build.MODEL).put("runs",rows)
            val output=File(app.cacheDir,"bitmap-gpu-performance.json")
            for(name in listOf("jpeg-performance-input.jpg","heic-input.heic")) {
                val file=File(app.cacheDir,name);check(file.isFile)
                repeat(3) {iteration->
                    val cpu=CaptureTiming()
                    BitmapCpuReference.prepare(file,false,{false},cpu).first.close()
                    val cpuResult=cpu.json()
                    val gpu=CaptureTiming()
                    val image=BitmapEditInput.prepareGpu(file,false,{false},gpu)
                    val gpuResult=gpu.json()
                    try {
                        rows.put(JSONObject().put("file",name).put("iteration",iteration).put("width",image.width).put("height",image.height).put("cpu",cpuResult).put("gpu",gpuResult))
                    } finally {image.close()}
                    check(FilmEngine.liveGpuImages()==0L)
                    output.writeText(report.toString(2))
                }
            }
        })
        FilmWorker.execute {task.run()};task.get(120,TimeUnit.SECONDS)
    }
}
