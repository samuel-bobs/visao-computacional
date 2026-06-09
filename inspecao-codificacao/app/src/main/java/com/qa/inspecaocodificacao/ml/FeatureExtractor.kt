package com.qa.inspecaocodificacao.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Extrai um vetor de features (embedding) da ROI usando MobileNetV3-Small
 * como "feature vector" (saída do Global Average Pooling, sem a cabeça de
 * classificação). Modelo exportado por tools/export_tflite_model.py.
 *
 * O embedding é L2-normalizado na saída, o que torna a distância de cosseno
 * um simples produto escalar (ver AnomalyModel).
 */
class FeatureExtractor(context: Context) : AutoCloseable {

    companion object {
        private const val MODEL_ASSET = "mobilenet_v3_embedder.tflite"
        const val INPUT_SIZE = 224
    }

    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null

    /** Buffer de entrada reutilizado: zero alocação por frame (evita GC na thread de análise). */
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
        .order(ByteOrder.nativeOrder())

    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)

    val embeddingDim: Int

    init {
        val options = Interpreter.Options().apply {
            val compat = CompatibilityList()
            if (compat.isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate(compat.bestOptionsForThisDevice)
                addDelegate(gpuDelegate)
            } else {
                numThreads = 4
            }
        }
        interpreter = Interpreter(loadModel(context), options)
        embeddingDim = interpreter.getOutputTensor(0).shape().last()
    }

    private fun loadModel(context: Context): ByteBuffer {
        context.assets.openFd(MODEL_ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    /**
     * @param roiBitmap recorte da ROI já feito (qualquer tamanho; será
     *        redimensionado para 224x224 aqui).
     * @return embedding L2-normalizado.
     */
    fun extract(roiBitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(roiBitmap, INPUT_SIZE, INPUT_SIZE, true)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== roiBitmap) scaled.recycle()

        inputBuffer.rewind()
        for (pixel in pixels) {
            // Normalização [0, 1] — igual à usada na exportação do modelo.
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            inputBuffer.putFloat((pixel and 0xFF) / 255f)
        }

        val output = Array(1) { FloatArray(embeddingDim) }
        inputBuffer.rewind()
        interpreter.run(inputBuffer, output)

        return l2Normalize(output[0])
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm > 1e-9f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}
