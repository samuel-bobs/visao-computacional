package com.qa.inspecaocodificacao.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Analyzer do CameraX. Roda no executor de fundo de thread única.
 *
 * O processamento é SÍNCRONO dentro de analyze(): o ImageProxy é entregue
 * ao pipeline (que lê o buffer direto, sem cópia do frame inteiro) e
 * fechado em seguida. Com STRATEGY_KEEP_ONLY_LATEST, frames que chegarem
 * durante o processamento são descartados, nunca enfileirados — latência
 * constante mesmo se uma inferência atrasar.
 */
class FrameAnalyzer(
    private val onFrame: (ImageProxy) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        try {
            onFrame(imageProxy)
        } finally {
            // Sem close() o pipeline trava: CameraX só entrega o próximo
            // frame quando o atual é liberado.
            imageProxy.close()
        }
    }
}
