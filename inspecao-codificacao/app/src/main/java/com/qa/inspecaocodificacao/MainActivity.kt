package com.qa.inspecaocodificacao

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
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
import androidx.camera.camera2.interop.Camera2CameraInfo
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

    /** Thread única de análise: ordem dos frames e máquina por item sem locks. */
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
            val fpsRange = selectFpsRange(provider, viewModel.config.maxAnalysisFps)

            // 60 fps pode não ser suportado na combinação de tamanhos:
            // tenta o range escolhido e cai para 30/30 se o bind falhar.
            try {
                bindUseCases(provider, fpsRange)
            } catch (e: Exception) {
                bindUseCases(provider, Range(30, 30))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Maior range de FPS FIXO suportado pelo sensor até maxFps.
     * Range fixo (lower == upper): o AE não pode derrubar o frame rate em
     * cena escura — frames a menos quebrariam o debounce da contagem.
     *
     * Nota slow-motion: os modos 120-480 fps do M31 usam sessão high-speed
     * restrita que alimenta apenas o encoder de vídeo, sem callback por
     * frame — inutilizáveis para inferência. 60 fps é o teto utilizável.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun selectFpsRange(provider: ProcessCameraProvider, maxFps: Int): Range<Int> {
        val info = CameraSelector.DEFAULT_BACK_CAMERA
            .filter(provider.availableCameraInfos)
            .firstOrNull() ?: return Range(30, 30)

        val ranges = Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return Range(30, 30)

        return ranges
            .filter { it.lower == it.upper && it.upper <= maxFps }
            .maxByOrNull { it.upper }
            ?: Range(30, 30)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindUseCases(provider: ProcessCameraProvider, fpsRange: Range<Int>) {
        // Preview e análise na MESMA proporção 4:3 + FIT no PreviewView:
        // a moldura da ROI na tela corresponde 1:1 ao recorte analisado
        // (correção do desalinhamento observado em campo).
        val analysisResolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()

        val previewResolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 960), // 4:3 nítido para o ajuste da ROI
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(previewResolution)
            .build()
            .also { p ->
                previewView?.let { p.setSurfaceProvider(it.surfaceProvider) }
            }

        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(analysisResolution)

        Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            fpsRange,
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

        // Câmera fixa: foco travado evita que o autofoco "cace" a garrafa.
        camera.cameraControl.cancelFocusAndMetering()

        viewModel.exposureLocker = { locked -> setExposureLock(camera, locked) }
        viewModel.alarmController.cameraControl = camera.cameraControl
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
