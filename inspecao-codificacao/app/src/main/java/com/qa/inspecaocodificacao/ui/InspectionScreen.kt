package com.qa.inspecaocodificacao.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.qa.inspecaocodificacao.InspectionViewModel
import com.qa.inspecaocodificacao.domain.AppState
import com.qa.inspecaocodificacao.domain.CalibrationPhase
import java.util.Locale

private val GreenOk = Color(0xFF1B8A3A)
private val RedAlarm = Color(0xFFC62828)
private val BlueCalib = Color(0xFF1565C0)
private val DarkBg = Color(0xFF101418)

/**
 * Tela única, à prova de operador:
 *  - IDLE: botão gigante "INICIAR CALIBRAÇÃO" + métricas do turno.
 *  - CALIBRANDO: barra de progresso e instrução da fase.
 *  - MONITORANDO: faixa verde "MONITORANDO" sobre o preview com a ROI desenhada.
 *  - ALARME: tela inteira pulsando em vermelho.
 *  - QA Admin: toque LONGO no título abre "Zerar Turno".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InspectionScreen(
    viewModel: InspectionViewModel,
    onPreviewReady: (PreviewView) -> Unit,
) {
    val state by viewModel.appState.collectAsState()
    val metrics by viewModel.metrics.collectAsState()

    var showConfirmDialog by remember { mutableStateOf(false) }
    var showAdminDialog by remember { mutableStateOf(false) }
    var metricsVisible by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize().background(DarkBg)) {

        // ---------------- Preview da câmera + moldura da ROI ----------------
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PreviewView(context).also(onPreviewReady)
            },
        )
        RoiOverlay(viewModel)

        // ----------------------- Overlay de ALARME --------------------------
        if (state is AppState.Alarm) {
            val pulse by rememberInfiniteTransition(label = "alarm")
                .animateFloat(
                    initialValue = 0.35f,
                    targetValue = 0.85f,
                    animationSpec = infiniteRepeatable(tween(300), RepeatMode.Reverse),
                    label = "alarmPulse",
                )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(pulse)
                    .background(RedAlarm)
            )
            Text(
                text = "⚠ CODIFICAÇÃO FORA DO PADRÃO",
                color = Color.White,
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // ------------------- Cabeçalho (status + QA Admin) ------------------
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "INSPEÇÃO DE CODIFICAÇÃO",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.combinedClickable(
                    onClick = { metricsVisible = !metricsVisible },
                    onLongClick = { showAdminDialog = true }, // modo QA Admin
                ),
            )
            Spacer(Modifier.height(8.dp))
            StatusBanner(state)
        }

        // --------------------------- Conteúdo central -----------------------
        when (val s = state) {
            is AppState.Idle -> {
                Button(
                    onClick = { showConfirmDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = BlueCalib),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(width = 420.dp, height = 140.dp),
                ) {
                    Text("INICIAR CALIBRAÇÃO", fontSize = 32.sp, fontWeight = FontWeight.Black)
                }
            }

            is AppState.Calibrating -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.7f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = when (s.phase) {
                            CalibrationPhase.EMPTY_BELT ->
                                "MANTENHA A ESTEIRA VAZIA (aprendendo o fundo)"
                            CalibrationPhase.PRODUCT ->
                                "LIBERE A PRODUÇÃO NORMAL (aprendendo o padrão)"
                        },
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth().height(16.dp),
                        color = BlueCalib,
                    )
                    Text(
                        text = "${(s.progress * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }

            is AppState.Monitoring, is AppState.Alarm -> Unit // só o banner/overlay
        }

        // ------------------------ Card de métricas QA -----------------------
        if (metricsVisible) {
            MetricsCard(
                metrics = metrics,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            )
        }
    }

    // ------------------------------ Diálogos --------------------------------
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Nova calibração") },
            text = {
                Text(
                    "Isso resetará o modelo e as métricas para o novo produto. " +
                        "Garanta que a esteira esteja VAZIA nos primeiros 10 segundos. Confirmar?"
                )
            },
            confirmButton = {
                Button(onClick = {
                    showConfirmDialog = false
                    viewModel.startCalibration()
                }) { Text("CONFIRMAR") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("Cancelar") }
            },
        )
    }

    if (showAdminDialog) {
        AlertDialog(
            onDismissRequest = { showAdminDialog = false },
            title = { Text("QA Admin") },
            text = { Text("Zerar as métricas do turno? A baseline do produto será mantida.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = RedAlarm),
                    onClick = {
                        showAdminDialog = false
                        viewModel.resetShiftMetrics()
                    },
                ) { Text("ZERAR TURNO") }
            },
            dismissButton = {
                TextButton(onClick = { showAdminDialog = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun StatusBanner(state: AppState) {
    val (label, color) = when (state) {
        is AppState.Idle -> "OCIOSO — calibre para iniciar" to Color.Gray
        is AppState.Calibrating -> "CALIBRANDO" to BlueCalib
        is AppState.Monitoring -> "MONITORANDO" to GreenOk
        is AppState.Alarm -> "ALARME" to RedAlarm
    }
    val animated by animateColorAsState(color, label = "statusColor")
    Box(
        modifier = Modifier
            .background(animated, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(label, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun MetricsCard(
    metrics: com.qa.inspecaocodificacao.data.ShiftMetrics,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xCC1A2026)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Metric("INSPECIONADAS", metrics.totalInspected.toString(), Color.White)
            Metric("DEFEITOS", metrics.totalDefects.toString(), RedAlarm)
            Metric(
                "TAXA DE DEFEITO",
                String.format(Locale.US, "%.2f%%", metrics.defectRatePercent),
                if (metrics.defectRatePercent > 1f) RedAlarm else GreenOk,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.LightGray, fontSize = 12.sp)
        Text(value, color = valueColor, fontSize = 30.sp, fontWeight = FontWeight.Black)
    }
}

/** Moldura visual da ROI sobre o preview, para o setup físico do suporte. */
@Composable
private fun RoiOverlay(viewModel: InspectionViewModel) {
    val cfg = viewModel.config
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(0.dp),
        ) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val w = maxWidth
                val h = maxHeight
                Box(
                    modifier = Modifier
                        .padding(start = w * cfg.roiLeft, top = h * cfg.roiTop)
                        .width(w * (cfg.roiRight - cfg.roiLeft))
                        .height(h * (cfg.roiBottom - cfg.roiTop))
                        .border(3.dp, Color.Yellow, RoundedCornerShape(8.dp)),
                )
            }
        }
    }
}
