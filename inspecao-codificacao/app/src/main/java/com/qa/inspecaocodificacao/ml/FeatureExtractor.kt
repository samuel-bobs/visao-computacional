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
 * sem a cabeça de classificação (tools/export_tflite_model.py).
 *
 * Tuning para o Galaxy M31 (Exynos 9611):
 *  - Padrão: XNNPACK na CPU com 4 threads — casa com os 4x Cortex-A73.
 *    Para modelos pequenos, o delegate GPU no Mali-G72 MP3 perde para a
 *    CPU (overhead de upload/dispatch domina); habilite useGpu apenas se
 *    o benchmark no dispositivo final disser o contrário.
 *  - Entrada 160x160 (definida na exportação): ~2x mais rápida que 224
 *    com perda de acurácia irrelevante para anomalia em ROI fixa. O
 *    tamanho é lido do próprio modelo — trocar o .tflite basta.
 *  - Buffers reutilizados: zero alocação por inferência (sem pressão de
 *    GC na thread de análise).
 */
class FeatureExtractor(context: Context, useGpu: Boolean = false) : AutoCloseable {

    companion object {
        private const val MODEL_ASSET = "mobilenet_v3_embedder.tflite"
    }

    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null

    val inputSize: Int
    val embeddingDim: Int

    private val inputBuffer: ByteBuffer
    private val pixels: IntArray
    private val output: Array<FloatArray>

    init {
        val options = Interpreter.Options().apply {
            val compat = CompatibilityList()
            if (useGpu && compat.isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate(compat.bestOptionsForThisDevice)
                addDelegate(gpuDelegate)
            } else {
                numThreads = 4 // 4x A73; XNNPACK ativo por padrão no TFLite 2.x
            }
        }
        interpreter = Interpreter(loadModel(context), options)

        inputSize = interpreter.getInputTensor(0).shape()[1] // [1, H, W, 3]
        embeddingDim = interpreter.getOutputTensor(0).shape().last()

        inputBuffer = ByteBuffer
            .allocateDirect(inputSize * inputSize * 3 * 4)
            .order(ByteOrder.nativeOrder())
        pixels = IntArray(inputSize * inputSize)
        output = Array(1) { FloatArray(embeddingDim) }
    }

    private fun loadModel(context: Context): ByteBuffer {
        context.assets.openFd(MODEL_ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    /**
     * @param roiBitmap recorte da ROI (redimensionado para o input aqui).
     * @return embedding L2-normalizado. O array retornado é uma CÓPIA —
     *         seguro para guardar na baseline de calibração.
     */
    fun extract(roiBitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(roiBitmap, inputSize, inputSize, true)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        if (scaled !== roiBitmap) scaled.recycle()

        inputBuffer.rewind()
        for (pixel in pixels) {
            // Normalização [0, 1] — igual à usada na exportação do modelo.
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            inputBuffer.putFloat((pixel and 0xFF) / 255f)
        }

        inputBuffer.rewind()
        interpreter.run(inputBuffer, output)

        return l2Normalize(output[0]).copyOf()
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
