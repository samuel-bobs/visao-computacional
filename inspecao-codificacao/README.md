# Inspeção de Codificação — Android Edge AI

Aplicativo Android (Kotlin + Jetpack Compose + CameraX + TFLite) para inspeção
de qualidade em linha de envase: detecção de **codificação despadronizada em
garrafas** com câmera fixa, contagem precisa por item e métricas de QA
persistidas localmente.

## Fluxo de operação (máquina de estados do app)

```
IDLE ──"INICIAR CALIBRAÇÃO" + confirmação──> CALIBRANDO (60 s)
                                                  │
              ┌──── baseline salva ───────────────┘
              v
         MONITORANDO ──score > threshold (garrafa na ROI)──> ALARME
              ^                                                 │
              └────── garrafa sai da ROI (auto-reset) ──────────┘
```

| Estado | UI | Comportamento |
|---|---|---|
| `Idle` | Botão gigante azul + card de métricas | Pipeline ML desligado (economia de CPU) |
| `Calibrating` | Barra de progresso + instrução por fase | Aprende fundo (10 s) e produto (50 s) |
| `Monitoring` | Faixa verde "MONITORANDO" + ROI amarela | Compara cada frame com a baseline |
| `Alarm` | Tela pulsando vermelho + apito + flash | Auto-reset quando a garrafa sai |

Código: `domain/AppState.kt`, orquestração em `InspectionViewModel.kt`.

## Contagem inteligente — "Histerese por Item"

Câmera fixa ⇒ uma garrafa parada gera centenas de frames. A contagem por frame
supercontaria. A solução (`domain/ItemStateMachine.kt`):

```
VAZIO ──presença > T_enter (N frames)──> ENTRANDO ──> AVALIANDO (registra PICO)
  ^                                                        │
  └──presença < T_exit (M frames): COMMIT do item ── SAINDO┘
```

- **Histerese**: `T_enter (0.18) > T_exit (0.10)` — ruído de iluminação não
  oscila o estado.
- **Debounce**: entrada confirmada com 2 frames, saída com 3.
- **Commit único**: `totalInspecionado++` só na transição `SAINDO → VAZIO`;
  se `pico > threshold`, `totalDefeitos++`. Uma garrafa = um incremento,
  independente de quantos frames ela ocupou.
- **Alarme ao vivo**: se durante `AVALIANDO` o score passa do threshold, o
  alarme dispara imediatamente (não espera a garrafa sair) e se **auto-reseta**
  no commit — o defeito já foi registrado.

## Matemática do Anomaly Score

1. **Features**: MobileNetV3-Small sem cabeça de classificação, saída do
   Global Average Pooling (~576 dims), exportado para TFLite float16
   (`tools/export_tflite_model.py`). Cada ROI vira um embedding `f`,
   L2-normalizado.

2. **Baseline (calibração)**: dois centroides são aprendidos:
   - `μ_vazio` — média dos embeddings com esteira vazia (primeiros 10 s);
   - `μ_produto` — média dos embeddings com garrafa BOA na ROI (50 s restantes;
     frames sem garrafa são filtrados pela própria distância a `μ_vazio`).

3. **Scores por frame** (distância de cosseno; com `‖f‖ = 1`):

   ```
   presença(f) = 1 − (f · μ_vazio)   / ‖μ_vazio‖     → dirige a máquina por item
   anomalia(f) = 1 − (f · μ_produto) / ‖μ_produto‖   → dirige a detecção de defeito
   ```

   Codificação ausente, borrada ou deslocada muda a textura local da ROI ⇒ o
   embedding se afasta de `μ_produto` ⇒ score sobe.

4. **Threshold automático** ("k-sigma"): ao final da calibração, cada embedding
   de produto é pontuado contra `μ_produto`:

   ```
   T = média(scores) + k·desvio(scores),  k = 4  (piso de segurança 0.08)
   ```

   O threshold se adapta à variabilidade natural de cada produto/iluminação.

## Estrutura do código

```
app/src/main/java/com/qa/inspecaocodificacao/
├── MainActivity.kt              # bind CameraX (Preview + ImageAnalysis), permissão
├── InspectionViewModel.kt       # orquestrador: pipeline por frame + estados
├── domain/
│   ├── AppState.kt              # máquina de estados do APP
│   ├── ItemStateMachine.kt      # máquina de estados por ITEM (histerese)
│   └── InspectionConfig.kt      # ROI, thresholds, durações
├── ml/
│   ├── FeatureExtractor.kt      # TFLite MobileNetV3 (GPU delegate, zero-alloc)
│   ├── AnomalyModel.kt          # centroides + distância de cosseno
│   ├── CalibrationSession.kt    # acúmulo de baseline + threshold k-sigma
│   └── BaselineStore.kt         # persistência atômica da baseline
├── camera/
│   ├── FrameAnalyzer.kt         # ImageAnalysis.Analyzer (KEEP_ONLY_LATEST)
│   └── RoiCropper.kt            # crop da ROI (frações do frame)
├── alert/
│   └── AlarmController.kt       # SoundPool (loop) + torch piscando
├── data/
│   └── MetricsRepository.kt     # DataStore: total, defeitos, taxa (%)
└── ui/
    └── InspectionScreen.kt      # Compose: tela única à prova de operador
```

## Robustez industrial

- **Persistência**: métricas em DataStore e baseline em arquivo binário com
  escrita atômica — reinício acidental volta direto para MONITORANDO com os
  contadores intactos.
- **Latência**: executor de thread única + `STRATEGY_KEEP_ONLY_LATEST`
  (frames atrasados são descartados, nunca enfileirados); buffers TFLite
  reutilizados (sem pressão de GC); GPU delegate quando disponível.
- **Operador**: tela única, um botão, confirmação antes de resetar; "Zerar
  Turno" escondido atrás de toque longo no título (modo QA Admin).
- **Tela sempre ligada** (`FLAG_KEEP_SCREEN_ON`) e orientação travada.

## Build

1. Gere o modelo: `cd tools && python export_tflite_model.py` e copie o
   `.tflite` para `app/src/main/assets/`.
2. Abra `inspecao-codificacao/` no Android Studio e rode no dispositivo
   (minSdk 26, câmera traseira obrigatória, flash recomendado).

## Comissionamento na linha

1. Fixe o dispositivo e ajuste a ROI (`InspectionConfig`) usando a moldura
   amarela do preview até cobrir só a faixa da codificação.
2. Inicie a calibração com a esteira vazia; libere a produção quando a UI
   pedir (após 10 s).
3. Valide com garrafas-padrão defeituosas conhecidas e ajuste
   `defectThresholdK` se necessário (menor = mais sensível).
