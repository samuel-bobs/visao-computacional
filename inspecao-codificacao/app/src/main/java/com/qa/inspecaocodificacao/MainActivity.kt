package com.qa.inspecaocodificacao

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Range
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.qa.inspecaocodificacao.camera.FrameAnalyzer
import com.qa.inspecaocodificacao.ui.InspectionScreen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val viewModel: InspectionViewModel by viewModels()

    /**
     * Executor de THREAD ÚNICA para análise de frames: garante ordem dos
     * frames e elimina condições de corrida na ItemStateMachine.
     */
    private lateinit var analysisExecutor: ExecutorService

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) bindCamera()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dispositivo fixo na linha: a tela nunca pode apagar.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        analysisExecutor = Executors.newSingleThreadExecutor()

        setContent {
            InspectionScreen(
                viewModel = viewModel,
                onPreviewReady = { previewView -> this.previewView = previewView },
            )
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            bindCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private var previewView: PreviewView? = null

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also { p ->
                previewView?.let { p.setSurfaceProvider(it.surfaceProvider) }
            }

            // 640x480 basta: a ROI vira 160x160 no modelo e a grade de luma
            // usa ~1k pixels. Resoluções maiores (o M31 entrega até 4k)
            // só aumentariam o custo de cópia sem ganho de detecção.
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                )
                .build()

            val analysisBuilder = ImageAnalysis.Builder()
                // Descarta frames atrasados em vez de enfileirar: latência constante.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(resolutionSelector)

            // FPS FIXO em 30/30: a 18k garrafas/h (6 frames por ciclo) o AE
            // não pode reduzir o frame rate em cena escura — frames a menos
            // quebrariam o debounce da contagem.
            Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(viewModel.config.targetFps, viewModel.config.targetFps),
            )

            val analysis = analysisBuilder.build()
                .also { it.setAnalyzer(analysisExecutor, FrameAnalyzer(viewModel::onFrame)) }

            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )

            // Câmera fixa: foco/exposição travados evitam que o autofoco
            // "cace" quando a garrafa passa, o que mudaria a baseline.
            camera.cameraControl.cancelFocusAndMetering()

            // Lock de AE/AWB entregue ao ViewModel: travado após o início da
            // calibração (cena assentada) e mantido o turno inteiro.
            viewModel.exposureLocker = { locked -> setExposureLock(camera, locked) }

            // Entrega o controle do torch ao alarme (se habilitado na config).
            viewModel.alarmController.cameraControl = camera.cameraControl
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun setExposureLock(camera: Camera, locked: Boolean) {
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, locked)
            .build()
        Camera2CameraControl.from(camera.cameraControl).addCaptureRequestOptions(options)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}
