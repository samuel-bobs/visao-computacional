package com.qa.inspecaocodificacao.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Analyzer do CameraX. Roda no executor de fundo de thread única passado
 * ao ImageAnalysis — nunca na main thread.
 *
 * Com STRATEGY_KEEP_ONLY_LATEST, se a inferência demorar mais que o
 * intervalo entre frames, frames intermediários são descartados em vez de
 * enfileirados: latência constante, sem backpressure (essencial na linha).
 */
class FrameAnalyzer(
    private val onFrame: (Bitmap) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            val upright = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
            onFrame(upright)
        } finally {
            // Sem close() o pipeline trava: CameraX só entrega o próximo
            // frame quando o atual é liberado.
            imageProxy.close()
        }
    }
}
