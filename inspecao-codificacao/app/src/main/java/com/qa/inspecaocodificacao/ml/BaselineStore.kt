package com.qa.inspecaocodificacao.ml

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Persiste a baseline em arquivo binário interno: se o app reiniciar
 * acidentalmente durante o turno, ele volta direto para MONITORANDO
 * sem exigir nova calibração.
 */
class BaselineStore(context: Context) {

    private val file = File(context.filesDir, "baseline.bin")
    private val tmpFile = File(context.filesDir, "baseline.bin.tmp")

    fun save(model: AnomalyModel) {
        // Escrita atômica: nunca deixa baseline corrompida se faltar energia.
        DataOutputStream(tmpFile.outputStream().buffered()).use { out ->
            out.writeInt(model.emptyCentroid.size)
            model.emptyCentroid.forEach(out::writeFloat)
            out.writeInt(model.productCentroid.size)
            model.productCentroid.forEach(out::writeFloat)
            out.writeFloat(model.defectThreshold)
        }
        tmpFile.renameTo(file)
    }

    fun load(): AnomalyModel? {
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val empty = FloatArray(input.readInt()) { input.readFloat() }
                val product = FloatArray(input.readInt()) { input.readFloat() }
                AnomalyModel(empty, product, input.readFloat())
            }
        }.getOrNull()
    }

    fun clear() {
        file.delete()
    }
}
