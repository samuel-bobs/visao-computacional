"""Exporta o MobileNetV3-Small como extrator de embeddings TFLite.

Gera `mobilenet_v3_embedder.tflite` (entrada 224x224x3 float em [0,1],
saída: vetor de features do Global Average Pooling, ~576 dims).

Uso:
    pip install tensorflow
    python export_tflite_model.py
    cp mobilenet_v3_embedder.tflite ../app/src/main/assets/
"""

import tensorflow as tf

IMG_SIZE = 224

base = tf.keras.applications.MobileNetV3Small(
    input_shape=(IMG_SIZE, IMG_SIZE, 3),
    include_top=False,          # sem cabeça de classificação
    pooling="avg",              # Global Average Pooling -> embedding
    weights="imagenet",
    include_preprocessing=True, # aceita entrada em [0, 255] reescalada internamente
)

inputs = tf.keras.Input(shape=(IMG_SIZE, IMG_SIZE, 3))
# O app envia pixels em [0, 1]; o preprocess interno espera [0, 255].
embeddings = base(inputs * 255.0)
model = tf.keras.Model(inputs, embeddings)

converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.target_spec.supported_types = [tf.float16]  # ~2x menor, mesma acurácia
tflite_model = converter.convert()

with open("mobilenet_v3_embedder.tflite", "wb") as f:
    f.write(tflite_model)

print(f"Modelo exportado: {len(tflite_model) / 1e6:.1f} MB")
