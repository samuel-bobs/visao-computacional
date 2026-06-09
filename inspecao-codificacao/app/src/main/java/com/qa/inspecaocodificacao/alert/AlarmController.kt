package com.qa.inspecaocodificacao.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.camera.core.CameraControl
import com.qa.inspecaocodificacao.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Alarme industrial: apito contínuo (SoundPool, pré-carregado em memória
 * para latência ~zero) + tela vermelha pulsante (na UI) e, opcionalmente,
 * flash LED piscando.
 *
 * NOTA 18k gph (torch desligado por padrão): a 5 garrafas/s, OUTRAS
 * garrafas são inspecionadas DURANTE o alarme. O torch piscando muda a
 * iluminação da ROI e contaminaria presença e anomaly score dessas
 * garrafas — por isso useTorch=false no M31; o alerta visual é a tela.
 *
 * AUTO-RESET: a máquina por item registra o defeito na saída da ROI
 * (Estado D); o ViewModel mantém o alarme por um tempo mínimo perceptível
 * (alarmMinDurationMs) e então chama stop() — nenhum dado se perde.
 */
class AlarmController(
    context: Context,
    private val scope: CoroutineScope,
    private val useTorch: Boolean,
) {

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val alarmSoundId: Int = soundPool.load(context, R.raw.alarm_beep, 1)
    @Volatile private var soundLoaded = false
    private var activeStreamId = 0

    /** Controle da câmera (torch). Injetado pela Activity após o bind do CameraX. */
    @Volatile var cameraControl: CameraControl? = null

    private var torchJob: Job? = null
    @Volatile private var running = false

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (sampleId == alarmSoundId && status == 0) soundLoaded = true
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true

        // loop = -1: apito CONTÍNUO até stop(). Sem decodificação no disparo.
        if (soundLoaded) {
            activeStreamId = soundPool.play(alarmSoundId, 1f, 1f, 1, -1, 1f)
        }

        if (useTorch) {
            torchJob = scope.launch {
                var on = false
                while (isActive) {
                    on = !on
                    runCatching { cameraControl?.enableTorch(on) }
                    delay(250L) // 2 Hz: bem visível no chão de fábrica
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false

        if (activeStreamId != 0) {
            soundPool.stop(activeStreamId)
            activeStreamId = 0
        }
        torchJob?.cancel()
        torchJob = null
        if (useTorch) {
            runCatching { cameraControl?.enableTorch(false) }
        }
    }

    fun release() {
        stop()
        soundPool.release()
    }
}
