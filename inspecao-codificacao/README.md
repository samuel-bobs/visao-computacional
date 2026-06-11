# Inspeção de Codificação — Android Edge AI

Aplicativo Android (Kotlin + Jetpack Compose + CameraX + TFLite) para inspeção
de qualidade em linha de envase: detecção de **codificação despadronizada em
garrafas** com câmera fixa, contagem precisa por item e métricas de QA
persistidas localmente.

**Dimensionamento do piloto: Samsung Galaxy M31 · 18.000 garrafas/hora.**

## A conta que dirige o design (18.000 gph no M31)

```
18.000 garrafas/h = 5 garrafas/s  →  ciclo de 200 ms por garrafa
Câmera a 30 fps   →  ~6 frames por ciclo (~3 com garrafa na ROI, ~3 de vão)
```

Rodar o TFLite em todo frame (~10–15 ms no Exynos 9611) não cabe nesse
orçamento. A solução é um **pipeline de dois estágios**:

| Estágio | O quê | Custo | Quando roda |
|---|---|---|---|
| 1 — Presença | Grade de luma 16×16 da ROI lida direto do buffer RGBA vs. referência do fundo | <0,5 ms | **Todo frame (30 fps)** — dirige a contagem |
| 2 — Anomalia | Crop da ROI → MobileNetV3-Small 160×160 (TFLite/XNNPACK) → distância de cosseno | ~10–15 ms | **Só com garrafa presente** (~15 inferências/s) |

A contagem (que não pode perder frame) fica no estágio barato; a inferência
cara roda na metade dos frames, com folga de CPU.

Ajustes específicos do **Galaxy M31** (Exynos 9611: 4×A73 + 4×A53, Mali-G72 MP3):

- **XNNPACK na CPU com 4 threads** (padrão). O delegate GPU no Mali-G72 MP3
  perde para a CPU em modelos pequenos (overhead de upload/dispatch); NNAPI
  no Exynos 9611 é instável. `useGpuDelegate` existe na config para benchmark.
- **Modelo exportado a 160×160** (~2× mais rápido que 224, perda irrelevante
  para anomalia em ROI fixa). O app lê o tamanho do próprio `.tflite`.
- **Análise a 640×480 com FPS travado em 30/30** via Camera2Interop — o AE
  não pode derrubar o frame rate em cena escura, ou o debounce quebra.
- **AE/AWB travados** após o início da calibração: exposição fixa = baseline
  estável o turno inteiro.
- **Sem rotação de frame**: a ROI é mapeada para as coordenadas do sensor uma
  única vez (`RoiMapper`); calibração e inferência usam a mesma orientação.
- **Zero alocação por frame**: grade de luma, IntArray do crop, Bitmap da ROI
  e buffers do TFLite são todos reutilizados (sem pressão de GC na thread de
  análise).

## Fluxo de operação (máquina de estados do app)

Treinamento em **duas etapas independentes** (revisão pós-teste de campo: a
calibração monolítica de 60 s era inoperável com a linha rodando):

```
IDLE ──"1·TREINAR FUNDO" (ROI vazia, ~13 s)──> TREINANDO FUNDO ──> IDLE (fundo ✓)
IDLE ──"2·TREINAR PADRÃO" (produção, ~45 s)──> TREINANDO PADRÃO ──> MONITORANDO
IDLE ──"MONITORAR"──> MONITORANDO ──"■ PARAR"──> IDLE
MONITORANDO <──auto-reset com retenção──> ALARME
IDLE/QA Admin ──"TROCAR PRODUTO"──> apaga fundo+padrão+métricas ──> IDLE (passo 1)
QA Admin ──"AJUSTAR ROI"──> ROI_SETUP (arrastar/redimensionar) ──> IDLE (retreino)
```

| Estado | UI | Comportamento |
|---|---|---|
| `Idle` | 2 botões de treino + botão MONITORAR + status ✔/— | Estágio 1 segue alimentando o diagnóstico |
| `CalibratingBackground` | Progresso "MANTENHA A ROI VAZIA" | Fundo + thresholds de presença (k-sigma) |
| `CalibratingProduct` | Progresso + nº de amostras com garrafa | Centroide do produto + threshold de defeito |
| `Monitoring` | Faixa verde + ROI amarela + botão PARAR | Estágio 1 em todo frame, estágio 2 sob demanda |
| `Alarm` | Tela pulsando vermelho + apito | Retenção mínima de 2 s após a saída do item |
| `RoiSetup` | ROI arrastável com alças nos cantos | Salvar invalida fundo+padrão (exige retreino) |

Sem padrão treinado, "MONITORAR (só contagem)" opera apenas a contagem — útil
para validar a contagem contra o contador da linha antes de treinar o padrão.

Falhas de treino agora geram **mensagem visível** (antes o app voltava a IDLE
em silêncio — causa raiz do "não contou nada" no teste de campo).

Código: `domain/AppState.kt`, orquestração em `InspectionViewModel.kt`.

## Ajustes de campo (QA Admin: toque longo no título)

- **Ajustar janela de medição** com TRÊS ferramentas (salvas juntas;
  alterar qualquer uma exige retreino):
  - **JANELA**: arraste o meio para mover, os cantos para redimensionar;
  - **MÁSCARA**: pinte células (grade 16×16) a IGNORAR dentro da janela —
    reflexos, partes móveis da máquina, respingos. Excluídas da presença e
    neutralizadas (cinza) no recorte enviado ao modelo;
  - **ZOOM**: slider 1x..máx da câmera para aproximar a região da
    codificação sem mover o suporte físico; persistido e reaplicado no boot.
  O preview é 4:3 com escala FIT — a moldura corresponde 1:1 ao recorte
  analisado.
- **Sensibilidade de presença** (0,25x–4x): multiplicador sobre os thresholds
  auto-calibrados, aplicado SEM retreinar — corrige contagem zerada em campo.
- **Diagnóstico na tela** (ligado por padrão): presença e anomalia ao vivo
  contra seus thresholds, estado da máquina por item (VAZIO/AVALIANDO/...)
  e FPS medido. É a ferramenta de comissionamento.
- **Zerar Turno**.

## FPS e slow-motion (avaliação para o M31)

O app consulta os ranges de FPS do sensor e escolhe o **maior range FIXO até
60 fps** (fallback 30/30). A 18k gph, 60 fps = 12 frames/ciclo — dobra a
resolução temporal para capturar o vão entre garrafas.

O slow-motion real do M31 (120–480 fps) **não é utilizável para inferência**:
o Android implementa esses modos com "constrained high-speed session", que
alimenta exclusivamente o encoder de vídeo, sem callback por frame para o
ImageAnalysis. O ganho equivalente vem de 60 fps + exposição travada curta
(AE lock), que também elimina o borrão de movimento — borrão é tempo de
exposição, não taxa de quadros.

## Contagem inteligente — "Histerese por Item"

Câmera fixa ⇒ uma garrafa gera vários frames. A contagem por frame
supercontaria. A solução (`domain/ItemStateMachine.kt`):

```
VAZIO ──presença > T_enter (1 frame)──> AVALIANDO (registra PICO, roda TFLite)
  ^                                          │
  └──presença < T_exit (2 frames):           │
     COMMIT do item ────────────── SAINDO ◄──┘
```

- **Histerese**: `T_enter > T_exit`, ambos **auto-calibrados** a partir do
  ruído do próprio fundo (`T_enter = ruído + 8σ`, `T_exit = ruído + 4σ`,
  com pisos de segurança) — nada de número mágico que quebra com outra
  iluminação.
- **Debounce dimensionado para 5 garrafas/s a 30 fps**: 1 frame confirma
  entrada, 2 confirmam saída (1 + ~2 de avaliação + 2 = cabe nos 6 frames
  do ciclo). Ruído de 1 frame em estado ENTRANDO ainda é descartado.
- **Commit único**: `totalInspecionado++` só na transição `SAINDO → VAZIO`;
  se `pico > threshold`, `totalDefeitos++`.
- **TFLite sob demanda**: o anomaly score entra na máquina como *supplier*,
  invocado só nos estados de avaliação — frames de esteira vazia custam
  apenas o estágio 1.

⚠️ Limite físico: a contagem exige um VÃO entre garrafas de ≥ 2 frames
(~70 ms ≈ 1/3 de garrafa a 18k gph). Garrafas encostadas sem vão se fundem
em um item — garanta o espaçamento do transportador no ponto da câmera.

## Matemática dos scores

**Estágio 1 — presença v2: fração de células alteradas**
(`ml/InspectionModel.kt`):

```
desvio_i = luma_i − fundo_i                     (grade 16×16, blocos 4×4)
shift    = mediana(desvios)                     (compensa deriva de iluminação)
mudou_i  = |desvio_i − shift| > max(6·σ_i, 8)   (ruído POR CÉLULA, do treino)
presença = células que mudaram / células ativas (máscara excluída)
```

Robusta à fração da ROI ocupada: garrafa cobrindo 20% das células ⇒
presença ≈ 20%, independente da intensidade — a média global da v1 diluía
o sinal quando a janela era maior que a garrafa e zerava a contagem.
Thresholds interpretáveis: entrar ≈ 12% das células, sair ≈ 6% (auto-
calibrados pelo ruído do fundo, com pisos).

**Estágio 2 — anomalia** (`ml/InspectionModel.kt`): embedding `f`
(MobileNetV3-Small, Global Average Pooling, L2-normalizado) contra o
centroide do produto bom:

```
anomalia(f) = 1 − (f · μ_produto) / ‖μ_produto‖        (‖f‖ = 1)
```

**Gate de centralização**: o treino aprende o percentil 60 das presenças
amostradas e só usa (no treino E na inferência) frames acima dele —
garrafas meio dentro/meio fora inflavam o desvio do treino e tornavam o
threshold `μ+4σ` inalcançável (defeitos nunca disparavam).

**Threshold automático** ("k-sigma", `ml/CalibrationSession.kt`): ao final
da calibração, cada embedding de produto é pontuado contra `μ_produto`:

```
T = média(scores) + 4·desvio(scores)     (piso de segurança 0.08)
```

A 18k gph, os 50 s da fase de produto veem ~250 garrafas — baseline
estatisticamente sólida em uma única calibração.

## Alarme a 5 garrafas/s

- **Retenção mínima** (`alarmMinDurationMs = 2 s`): a garrafa defeituosa sai
  da ROI em ~150 ms — um alarme que resetasse na saída seria imperceptível.
  O defeito é contado na saída (Estado D), mas o alarme segue por 2 s para o
  operador reagir. Itens bons saindo não estendem; novo defeito re-arma.
- **Torch desligado por padrão** (`useTorchOnAlarm = false`): outras garrafas
  são inspecionadas DURANTE o alarme; o flash piscando mudaria a iluminação
  da ROI e contaminaria os scores delas. O alerta visual é a tela vermelha
  pulsante (o apito SoundPool continua).

## Estrutura do código

```
app/src/main/java/com/qa/inspecaocodificacao/
├── MainActivity.kt              # bind CameraX, 640x480, FPS 30/30, lock AE/AWB
├── InspectionViewModel.kt       # orquestrador: pipeline 2 estágios + estados
├── domain/
│   ├── AppState.kt              # máquina de estados do APP + TrainingStatus
│   ├── ItemStateMachine.kt      # máquina por ITEM (histerese, TFLite sob demanda)
│   ├── RoiFractions.kt          # janela de medição ajustável (frações + clamp)
│   └── InspectionConfig.kt      # dimensionamento 18k gph / M31 documentado
├── ml/
│   ├── FeatureExtractor.kt      # TFLite MobileNetV3 160x160 (XNNPACK 4 threads)
│   ├── InspectionModel.kt       # BackgroundModel + ProductModel (independentes)
│   ├── Trainers.kt              # BackgroundTrainer e ProductTrainer (1 por botão)
│   └── BaselineStore.kt         # persistência atômica, fundo e padrão separados
├── camera/
│   ├── FrameAnalyzer.kt         # Analyzer (KEEP_ONLY_LATEST, síncrono)
│   ├── LumaGrid.kt              # ESTÁGIO 1: presença direto do buffer
│   ├── RoiMapper.kt             # ROI display -> coordenadas do sensor
│   └── RoiCropper.kt            # ESTÁGIO 2: crop reutilizável p/ TFLite
├── alert/
│   └── AlarmController.kt       # SoundPool em loop; torch opcional
├── data/
│   ├── MetricsRepository.kt     # contadores em memória + flush em lote
│   └── SettingsRepository.kt    # ROI, sensibilidade e diagnóstico persistidos
└── ui/
    └── InspectionScreen.kt      # Compose: tela única à prova de operador
```

## Robustez industrial

- **Persistência**: contadores em memória com flush em lote a cada 1,5 s
  (5 fsyncs/s por garrafa desgastariam o flash; janela máxima de perda:
  ~7 garrafas) e baseline em arquivo binário com escrita atômica — reinício
  acidental volta direto para MONITORANDO.
- **Latência**: executor de thread única + `STRATEGY_KEEP_ONLY_LATEST`
  (frames atrasados são descartados, nunca enfileirados).
- **Operador**: tela única, um botão, confirmação antes de resetar; "Zerar
  Turno" atrás de toque longo no título (modo QA Admin).
- **Tela sempre ligada** (`FLAG_KEEP_SCREEN_ON`) e orientação travada.

## Build

1. Gere o modelo: `cd tools && python export_tflite_model.py` e copie o
   `.tflite` para `app/src/main/assets/`.
2. Abra `inspecao-codificacao/` no Android Studio e rode no dispositivo
   (minSdk 26; o piloto é o Galaxy M31).

## Comissionamento na linha (M31, 18k gph)

1. Fixe o dispositivo e ajuste a ROI (`InspectionConfig`) usando a moldura
   amarela do preview até cobrir só a faixa da codificação. ROI estreita no
   sentido do movimento aumenta o vão efetivo entre garrafas.
2. Verifique o espaçamento: precisa haver ≥ 70 ms de esteira visível entre
   garrafas consecutivas no ponto da câmera.
3. Inicie a calibração com a esteira vazia; libere a produção quando a UI
   pedir (após 10 s).
4. Valide com garrafas-padrão defeituosas e ajuste `defectThresholdK`
   (menor = mais sensível). Se a contagem divergir do contador da linha,
   ajuste os sigmas de presença (`presenceEnterSigma`/`presenceExitSigma`).
