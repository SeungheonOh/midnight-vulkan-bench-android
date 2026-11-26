package network.midnight.vulkanbench.core

fun interface ProgressListener {
    fun onStage(stage: String)
}

object NativeBridge {
    init {
        System.loadLibrary("midnight_bench_core")
    }

    external fun init(pipelineCachePath: String?, arithOverride: String?)
    external fun call(requestJson: String): String
    external fun cancel()
    external fun setProgressListener(listener: ProgressListener?)
}
