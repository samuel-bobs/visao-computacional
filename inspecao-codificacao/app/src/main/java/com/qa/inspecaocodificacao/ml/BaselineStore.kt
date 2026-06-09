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

    private companion object {
        const val FORMAT_VERSION = 2
    }

    private val file = File(context.filesDir, "baseline.bin")
    private val tmpFile = File(context.filesDir, "baseline.bin.tmp")

    fun save(model: InspectionModel) {
        // Escrita atômica: nunca deixa baseline corrompida se faltar energia.
        DataOutputStream(tmpFile.outputStream().buffered()).use { out ->
            out.writeInt(FORMAT_VERSION)
            out.writeInt(model.backgroundGrid.size)
            model.backgroundGrid.forEach(out::writeFloat)
            out.writeFloat(model.presenceEnterThreshold)
            out.writeFloat(model.presenceExitThreshold)
            out.writeInt(model.productCentroid.size)
            model.productCentroid.forEach(out::writeFloat)
            out.writeFloat(model.defectThreshold)
        }
        tmpFile.renameTo(file)
    }

    fun load(): InspectionModel? {
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                check(input.readInt() == FORMAT_VERSION) { "versão de baseline incompatível" }
                val background = FloatArray(input.readInt()) { input.readFloat() }
                val enter = input.readFloat()
                val exit = input.readFloat()
                val centroid = FloatArray(input.readInt()) { input.readFloat() }
                InspectionModel(background, enter, exit, centroid, input.readFloat())
            }
        }.getOrNull()
    }

    fun clear() {
        file.delete()
    }
}
