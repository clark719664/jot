# Offline voice assets

Jot uses the CPU build of sherpa-onnx with an English keyword spotter and streaming English recognizer. The app bundles the model files and reads them through Android's asset manager. It captures audio into in-memory buffers and passes recognized text to the conversation controller. Android's installed offline English voice supplies spoken prompts and readback.

## Sources and licenses

- **sherpa-onnx 2.0.1 Android runtime** (Apache-2.0), release asset `sherpa-onnx-2.0.1.aar`. The upstream release publishes the matching SHA-256 below. [Release asset](https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar) · [License](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/LICENSE)
- **GigaSpeech English 3.3M keyword spotter** (Apache-2.0), release asset `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2`. Jot copies the three quantized ONNX graphs and vocabulary from the archive. The wake phrase is encoded as `▁HE Y ▁JO T` in Jot's own keywords file. [Release asset](https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2) · [Maintainer license confirmation](https://github.com/k2-fsa/sherpa-onnx/issues/3802)
- **LibriSpeech English streaming Zipformer 20M recognizer** (Apache-2.0), release asset `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2`. Jot copies the three quantized ONNX graphs and vocabulary. The archive README identifies the license and original checkpoint. [Release asset](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2) · [Training/export discussion](https://github.com/k2-fsa/icefall/pull/903)
- **ONNX Runtime 1.28.2** (MIT) ships inside the sherpa-onnx AAR. Jot packages the upstream license and third-party notices. [sherpa-onnx Android dependency pin](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/cmake/onnxruntime-android-aarch64.cmake) · [License](https://github.com/microsoft/onnxruntime/blob/v1.28.2/LICENSE) · [Notices](https://github.com/microsoft/onnxruntime/blob/v1.28.2/ThirdPartyNotices.txt)

## Restore assets

Run `pwsh -File scripts/fetch-voice-assets.ps1` from the repository root with PowerShell 7.4+, curl and tar. The optional `-UseGhApi` switch uses authenticated GitHub CLI to fetch the same release assets. The helper verifies SHA-256 before copying the runtime or extracting the selected model entries. It reuses verified downloads in `.local/voice`.

```text
a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7  sherpa-onnx-2.0.1.aar
f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a  sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9  sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2
```
