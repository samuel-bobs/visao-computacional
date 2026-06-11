package com.qa.inspecaocodificacao.ml

import android.content.Context
import com.qa.inspecaocodificacao.domain.InspectionConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Persiste fundo e padrão em arquivos SEPARADOS (treinos independentes):
 * reinício acidental volta direto a MONITORANDO com o que já foi treinado.
 * Escrita atômica (tmp + rename). Versão incompatível => retreino.
 */
class BaselineStore(context: Context, private val config: InspectionConfig) {

    private companion object {
        const val BG_VERSION = 2      // v2: + sigma por célula
        const val PRODUCT_VERSION = 2 // v2: + gate de centralização
    }

    private val bgFile = File(context.filesDir, "background.bin")
    private val productFile = File(context.filesDir, "product.bin")
    private val legacyFile = File(context.filesDir, "baseline.bin")

    fun saveBackground(model: BackgroundModel) = atomicWrite(bgFile) { out ->
        out.writeInt(BG_VERSION)
        out.writeInt(model.refGrid.size)
        model.refGrid.forEach(out::writeFloat)
        model.sigmaGrid.forEach(out::writeFloat)
        out.writeFloat(model.presenceEnterThreshold)
        out.writeFloat(model.presenceExitThreshold)
    }

    fun loadBackground(): BackgroundModel? {
        if (!bgFile.exists()) return null
        return runCatching {
            DataInputStream(bgFile.inputStream().buffered()).use { input ->
                check(input.readInt() == BG_VERSION)
                val size = input.readInt()
                val ref = FloatArray(size) { input.readFloat() }
                val sigma = FloatArray(size) { input.readFloat() }
                BackgroundModel(
                    refGrid = ref,
                    sigmaGrid = sigma,
                    presenceEnterThreshold = input.readFloat(),
                    presenceExitThreshold = input.readFloat(),
                    cellSigmaK = config.cellSigmaK,
                    minCellDelta = config.minCellDelta,
                )
            }
        }.getOrNull()
    }

    fun saveProduct(model: ProductModel) = atomicWrite(productFile) { out ->
        out.writeInt(PRODUCT_VERSION)
        out.writeInt(model.centroid.size)
        model.centroid.forEach(out::writeFloat)
        out.writeFloat(model.defectThreshold)
        out.writeFloat(model.presenceGate)
    }

    fun loadProduct(): ProductModel? {
        if (!productFile.exists()) return null
        return runCatching {
            DataInputStream(productFile.inputStream().buffered()).use { input ->
                check(input.readInt() == PRODUCT_VERSION)
                val centroid = FloatArray(input.readInt()) { input.readFloat() }
                ProductModel(centroid, input.readFloat(), input.readFloat())
            }
        }.getOrNull()
    }

    /** ROI/máscara/zoom alterados ou troca de produto: nada treinado vale. */
    fun clearAll() {
        bgFile.delete()
        productFile.delete()
        legacyFile.delete()
    }

    fun clearProduct() {
        productFile.delete()
    }

    private fun atomicWrite(target: File, block: (DataOutputStream) -> Unit) {
        val tmp = File(target.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use(block)
        tmp.renameTo(target)
    }
}
