# Assets do modelo

Coloque aqui o arquivo `mobilenet_v3_embedder.tflite`, gerado por
`tools/export_tflite_model.py` (na raiz do módulo):

```bash
cd ../../../../../tools
pip install tensorflow
python export_tflite_model.py
cp mobilenet_v3_embedder.tflite ../app/src/main/assets/
```

O modelo não é versionado no git por ser um binário de ~3 MB regenerável.
