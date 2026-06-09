package com.qa.inspecaocodificacao

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
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

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also { p ->
                previewView?.let { p.setSurfaceProvider(it.surfaceProvider) }
            }

            val analysis = ImageAnalysis.Builder()
                // Descarta frames atrasados em vez de enfileirar: latência constante.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
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

            // Entrega o controle do torch ao alarme (flash piscante).
            viewModel.alarmController.cameraControl = camera.cameraControl
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}
