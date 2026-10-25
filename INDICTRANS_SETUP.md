# Offline IndicTrans2 setup

The app packages `ai4bharat/indictrans2-en-indic-dist-200M` from AI4Bharat. Accept the model publisher's access terms on Hugging Face before downloading. Keep the access token private.

1. Install Python 3.12 and run `local-translation/Setup translator.cmd` on Windows. This creates `.indictrans-venv`, installs CPU dependencies, and downloads the pinned official model into `local-translation/.state/model`.
2. Install the export dependencies into that environment:

```powershell
.\.indictrans-venv\Scripts\python.exe -m pip install onnx onnxruntime onnxruntime-extensions
```

3. Export each component and quantize the encoder and decoder:

```powershell
.\.indictrans-venv\Scripts\python.exe local-translation/export_android.py encoder
.\.indictrans-venv\Scripts\python.exe local-translation/export_android.py decoder
.\.indictrans-venv\Scripts\python.exe local-translation/export_android.py quantize-encoder
.\.indictrans-venv\Scripts\python.exe local-translation/export_android.py quantize-decoder
.\.indictrans-venv\Scripts\python.exe local-translation/export_android.py tokenizer
.\.indictrans-venv\Scripts\python.exe local-translation/verify_android_export.py
```

4. Confirm `encoder.onnx`, `decoder.onnx`, `tokenizer.onnx`, `vocab.json`, and `LICENSE` exist under `local-translation/.state/android-assets/indictrans2`. The Android build includes these generated assets automatically.

Exporting requires substantial RAM, disk space, and time. Generated models and local state are excluded from Git. Preserve the model and preprocessing licenses with redistributed artifacts.

The optional `local-translation/Start translator.cmd` runs the computer translation service. It is not required for inference once the Android model is bundled.
