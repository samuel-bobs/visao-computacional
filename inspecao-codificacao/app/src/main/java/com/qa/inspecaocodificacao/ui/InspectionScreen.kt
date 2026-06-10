package com.qa.inspecaocodificacao.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.qa.inspecaocodificacao.InspectionViewModel
import com.qa.inspecaocodificacao.PipelineDiagnostics
import com.qa.inspecaocodificacao.domain.AppState
import com.qa.inspecaocodificacao.domain.RoiFractions
import java.util.Locale

private val GreenOk = Color(0xFF1B8A3A)
private val RedAlarm = Color(0xFFC62828)
private val BlueCalib = Color(0xFF1565C0)
private val OrangeWarn = Color(0xFFE65100)
private val DarkBg = Color(0xFF101418)
private val PanelBg = Color(0xCC1A2026)

/**
 * Tela única, à prova de operador. Mudanças do feedback de campo:
 *  - Treino de FUNDO e PADRÃO em botões separados, com status de cada um;
 *  - Janela de medição (ROI) ajustável por arrasto (QA Admin > Ajustar ROI);
 *  - Overlay de diagnóstico com scores ao vivo (presença/anomalia/estado/fps);
 *  - Mensagens operacionais visíveis (falha de treino não é mais silenciosa);
 *  - Sensibilidade de presença ajustável sem retreino.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InspectionScreen(
    viewModel: InspectionViewModel,
    onPreviewReady: (PreviewView) -> Unit,
) {
    val state by viewModel.appState.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val training by viewModel.trainingStatus.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val message by viewModel.uiMessage.collectAsState()

    var showBackgroundDialog by remember { mutableStateOf(false) }
    var showProductDialog by remember { mutableStateOf(false) }
    var showAdminDialog by remember { mutableStateOf(false) }
    var metricsVisible by remember { mutableStateOf(true) }

    // ROI em edição (só no modo RoiSetup); inicia na ROI persistida.
    var editRoi by remember { mutableStateOf(settings.roi) }

    Box(modifier = Modifier.fillMaxSize().background(DarkBg)) {

        // ------- Preview 4:3 letterboxed: tela corresponde 1:1 à análise -------
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(4f / 3f)
                .align(Alignment.Center),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PreviewView(context).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        onPreviewReady(this)
                    }
                },
            )

            if (state is AppState.RoiSetup) {
                RoiEditor(
                    roi = editRoi,
                    onChange = { editRoi = it },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                RoiFrame(roi = settings.roi, modifier = Modifier.fillMaxSize())
            }
        }

        // ----------------------- Overlay de ALARME --------------------------
        if (state is AppState.Alarm) {
            val pulse by rememberInfiniteTransition(label = "alarm")
                .animateFloat(
                    initialValue = 0.35f,
                    targetValue = 0.85f,
                    animationSpec = infiniteRepeatable(tween(300), RepeatMode.Reverse),
                    label = "alarmPulse",
                )
            Box(Modifier.fillMaxSize().alpha(pulse).background(RedAlarm))
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
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "INSPEÇÃO DE CODIFICAÇÃO",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.combinedClickable(
                    onClick = { metricsVisible = !metricsVisible },
                    onLongClick = { showAdminDialog = true }, // modo QA Admin
                ),
            )
            Spacer(Modifier.height(6.dp))
            StatusBanner(state)
        }

        // --------------------------- Conteúdo por estado -----------------------
        when (val s = state) {
            is AppState.Idle -> IdleContent(
                hasBackground = training.hasBackground,
                hasProduct = training.hasProduct,
                onTrainBackground = { showBackgroundDialog = true },
                onTrainProduct = { showProductDialog = true },
                onMonitor = { viewModel.startMonitoring() },
                modifier = Modifier.align(Alignment.Center),
            )

            is AppState.CalibratingBackground -> TrainingProgress(
                title = "MANTENHA A ROI VAZIA (aprendendo o fundo)",
                progress = s.progress,
                detail = null,
                modifier = Modifier.align(Alignment.Center),
            )

            is AppState.CalibratingProduct -> TrainingProgress(
                title = "PRODUÇÃO NORMAL RODANDO (aprendendo o padrão)",
                progress = s.progress,
                detail = "amostras com garrafa: ${s.samples}",
                modifier = Modifier.align(Alignment.Center),
            )

            is AppState.Monitoring -> {
                TextButton(
                    onClick = { viewModel.stopMonitoring() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) {
                    Text("PARAR", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            is AppState.RoiSetup -> RoiSetupControls(
                onSave = { viewModel.saveRoi(editRoi) },
                onCancel = {
                    editRoi = settings.roi
                    viewModel.cancelRoiSetup()
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )

            is AppState.Alarm -> Unit
        }

        // ------------------------ Card de métricas QA -----------------------
        if (metricsVisible && state !is AppState.RoiSetup) {
            MetricsCard(
                metrics = metrics,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }

        // --------------------- Overlay de diagnóstico -----------------------
        if (settings.showDiagnostics && state !is AppState.RoiSetup) {
            DiagnosticsPanel(
                diagnostics = diagnostics,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            )
        }

        // ------------------- Mensagem operacional (banner) -------------------
        message?.let { msg ->
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp)
                    .fillMaxWidth(0.8f),
                colors = CardDefaults.cardColors(containerColor = OrangeWarn),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = msg,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.dismissMessage() }) {
                        Text("OK", color = Color.White, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }

    // ------------------------------ Diálogos --------------------------------
    if (showBackgroundDialog) {
        ConfirmDialog(
            title = "Treinar FUNDO",
            text = "Garanta que a janela de medição esteja VAZIA (sem garrafas) " +
                "durante o treino (~13 s). Isso invalida o padrão atual. Confirmar?",
            onConfirm = {
                showBackgroundDialog = false
                viewModel.startBackgroundTraining()
            },
            onDismiss = { showBackgroundDialog = false },
        )
    }

    if (showProductDialog) {
        ConfirmDialog(
            title = "Treinar PADRÃO",
            text = "Garanta que a produção esteja rodando NORMAL (somente garrafas " +
                "boas) durante o treino (~45 s). Isso reseta as métricas do turno. Confirmar?",
            onConfirm = {
                showProductDialog = false
                viewModel.startProductTraining()
            },
            onDismiss = { showProductDialog = false },
        )
    }

    if (showAdminDialog) {
        AdminDialog(
            sensitivity = settings.presenceSensitivity,
            showDiagnostics = settings.showDiagnostics,
            onSensitivity = { viewModel.setPresenceSensitivity(it) },
            onShowDiagnostics = { viewModel.setShowDiagnostics(it) },
            onAdjustRoi = {
                showAdminDialog = false
                editRoi = settings.roi
                viewModel.enterRoiSetup()
            },
            onResetShift = {
                showAdminDialog = false
                viewModel.resetShiftMetrics()
            },
            onDismiss = { showAdminDialog = false },
        )
    }
}

// ============================ Componentes ============================

@Composable
private fun StatusBanner(state: AppState) {
    val (label, color) = when (state) {
        is AppState.Idle -> "OCIOSO" to Color.Gray
        is AppState.CalibratingBackground -> "TREINANDO FUNDO" to BlueCalib
        is AppState.CalibratingProduct -> "TREINANDO PADRÃO" to BlueCalib
        is AppState.Monitoring -> "MONITORANDO" to GreenOk
        is AppState.Alarm -> "ALARME" to RedAlarm
        is AppState.RoiSetup -> "AJUSTE DA JANELA DE MEDIÇÃO" to OrangeWarn
    }
    val animated by animateColorAsState(color, label = "statusColor")
    Box(
        modifier = Modifier
            .background(animated, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 6.dp),
    ) {
        Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun IdleContent(
    hasBackground: Boolean,
    hasProduct: Boolean,
    onTrainBackground: () -> Unit,
    onTrainProduct: () -> Unit,
    onMonitor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrainButton(
                label = "1 · TREINAR FUNDO",
                sub = "ROI vazia",
                done = hasBackground,
                enabled = true,
                onClick = onTrainBackground,
            )
            Spacer(Modifier.width(20.dp))
            TrainButton(
                label = "2 · TREINAR PADRÃO",
                sub = "produção normal",
                done = hasProduct,
                enabled = hasBackground,
                onClick = onTrainProduct,
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onMonitor,
            enabled = hasBackground,
            colors = ButtonDefaults.buttonColors(containerColor = GreenOk),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.size(width = 440.dp, height = 88.dp),
        ) {
            Text(
                text = if (hasProduct) "▶ MONITORAR" else "▶ MONITORAR (só contagem)",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun TrainButton(
    label: String,
    sub: String,
    done: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = BlueCalib),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.size(width = 280.dp, height = 96.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(sub, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (done) "✔ treinado" else "— pendente",
            color = if (done) GreenOk else Color.LightGray,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TrainingProgress(
    title: String,
    progress: Float,
    detail: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(0.7f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(16.dp),
            color = BlueCalib,
        )
        Text(
            text = "${(progress * 100).toInt()}%",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
        )
        detail?.let {
            Text(it, color = Color.Yellow, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MetricsCard(
    metrics: com.qa.inspecaocodificacao.data.ShiftMetrics,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = PanelBg),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Metric("INSPECIONADAS", metrics.totalInspected.toString(), Color.White)
            Metric("DEFEITOS", metrics.totalDefects.toString(), RedAlarm)
            Metric(
                "TAXA",
                String.format(Locale.US, "%.2f%%", metrics.defectRatePercent),
                if (metrics.defectRatePercent > 1f) RedAlarm else GreenOk,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.LightGray, fontSize = 11.sp)
        Text(value, color = valueColor, fontSize = 26.sp, fontWeight = FontWeight.Black)
    }
}

/** Telemetria ao vivo: a ferramenta de comissionamento que faltou em campo. */
@Composable
private fun DiagnosticsPanel(
    diagnostics: PipelineDiagnostics,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = PanelBg),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            DiagLine("presença", diagnostics.presenceScore, diagnostics.presenceEnter)
            DiagLine("anomalia", diagnostics.lastAnomalyScore, diagnostics.defectThreshold)
            Text(
                text = "item: ${diagnostics.itemState}   fps: ${diagnostics.fps}",
                color = Color.LightGray,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun DiagLine(label: String, value: Float, threshold: Float) {
    val over = threshold > 0f && value > threshold
    Text(
        text = String.format(Locale.US, "%-8s %.4f / %.4f", label, value, threshold),
        color = if (over) Color.Yellow else Color.White,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
    )
}

/** Moldura estática da ROI (fora do modo de edição). */
@Composable
private fun RoiFrame(roi: RoiFractions, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        Box(
            modifier = Modifier
                .padding(start = maxWidth * roi.left, top = maxHeight * roi.top)
                .width(maxWidth * roi.width())
                .height(maxHeight * roi.height())
                .border(3.dp, Color.Yellow, RoundedCornerShape(8.dp)),
        )
    }
}

/**
 * Editor da janela de medição: arrastar pelo MEIO move; arrastar pelas
 * ALÇAS dos cantos redimensiona. Frações persistidas ao salvar.
 */
@Composable
private fun RoiEditor(
    roi: RoiFractions,
    onChange: (RoiFractions) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(roi)
    val onChangeState by rememberUpdatedState(onChange)
    val handleRadiusPx = with(LocalDensity.current) { 36.dp.toPx() }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier.pointerInput(Unit) {
            // 0 = nada, 1..4 = cantos (TL, TR, BL, BR), 5 = mover
            var mode = 0
            detectDragGestures(
                onDragStart = { pos ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val r = current
                    val corners = listOf(
                        Offset(r.left * w, r.top * h),
                        Offset(r.right * w, r.top * h),
                        Offset(r.left * w, r.bottom * h),
                        Offset(r.right * w, r.bottom * h),
                    )
                    val cornerIdx = corners.indexOfFirst { (it - pos).getDistance() < handleRadiusPx }
                    mode = when {
                        cornerIdx >= 0 -> cornerIdx + 1
                        pos.x in (r.left * w)..(r.right * w) &&
                            pos.y in (r.top * h)..(r.bottom * h) -> 5
                        else -> 0
                    }
                },
                onDrag = { change, drag ->
                    change.consume()
                    if (mode == 0) return@detectDragGestures
                    val dx = drag.x / size.width
                    val dy = drag.y / size.height
                    val r = current
                    val updated = when (mode) {
                        1 -> r.copy(left = r.left + dx, top = r.top + dy)
                        2 -> r.copy(right = r.right + dx, top = r.top + dy)
                        3 -> r.copy(left = r.left + dx, bottom = r.bottom + dy)
                        4 -> r.copy(right = r.right + dx, bottom = r.bottom + dy)
                        else -> {
                            // mover mantendo o tamanho, sem sair do frame
                            val mdx = dx.coerceIn(-r.left, 1f - r.right)
                            val mdy = dy.coerceIn(-r.top, 1f - r.bottom)
                            RoiFractions(r.left + mdx, r.top + mdy, r.right + mdx, r.bottom + mdy)
                        }
                    }
                    onChangeState(updated.clamped())
                },
                onDragEnd = { mode = 0 },
            )
        },
    ) {
        val w = maxWidth
        val h = maxHeight

        Box(
            modifier = Modifier
                .padding(start = w * roi.left, top = h * roi.top)
                .width(w * roi.width())
                .height(h * roi.height())
                .background(Color.Yellow.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .border(4.dp, Color.Yellow, RoundedCornerShape(8.dp)),
        )

        // Alças dos cantos
        listOf(
            Offset(roi.left, roi.top),
            Offset(roi.right, roi.top),
            Offset(roi.left, roi.bottom),
            Offset(roi.right, roi.bottom),
        ).forEach { c ->
            Box(
                modifier = Modifier
                    .offset(x = w * c.x - 14.dp, y = h * c.y - 14.dp)
                    .size(28.dp)
                    .background(Color.Yellow, CircleShape)
                    .border(2.dp, Color.Black, CircleShape),
            )
        }
    }
}

@Composable
private fun RoiSetupControls(
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Arraste o meio para mover · arraste os cantos para redimensionar",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Salvar exigirá NOVO treino de fundo e padrão",
            color = Color.Yellow,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row {
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(containerColor = GreenOk),
            ) { Text("SALVAR ROI", fontWeight = FontWeight.Black) }
            Spacer(Modifier.width(16.dp))
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
            ) { Text("CANCELAR", fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text("CONFIRMAR") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun AdminDialog(
    sensitivity: Float,
    showDiagnostics: Boolean,
    onSensitivity: (Float) -> Unit,
    onShowDiagnostics: (Boolean) -> Unit,
    onAdjustRoi: () -> Unit,
    onResetShift: () -> Unit,
    onDismiss: () -> Unit,
) {
    var sliderValue by remember { mutableStateOf(sensitivity) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("QA Admin") },
        text = {
            Column {
                Text(
                    "Sensibilidade de presença: ${String.format(Locale.US, "%.2fx", sliderValue)}",
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Maior = detecta a garrafa mais facilmente (corrige contagem zerada).",
                    fontSize = 12.sp,
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onSensitivity(sliderValue) },
                    valueRange = 0.25f..4f,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Diagnóstico na tela", modifier = Modifier.weight(1f))
                    Switch(checked = showDiagnostics, onCheckedChange = onShowDiagnostics)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onAdjustRoi, modifier = Modifier.fillMaxWidth()) {
                    Text("AJUSTAR JANELA DE MEDIÇÃO (ROI)")
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onResetShift,
                    colors = ButtonDefaults.buttonColors(containerColor = RedAlarm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("ZERAR TURNO")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
