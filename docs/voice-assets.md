# Offline voice assets

Jot uses the CPU build of sherpa-onnx with an English keyword spotter, Silero voice activity detection, the Moonshine Base English recognizer and Supertonic 3 for speech. The app bundles the model files and reads them through Android's asset manager. It captures audio into in-memory buffers and passes recognized text to the conversation controller. If Jot's voice cannot load, Android's installed offline English voice speaks instead.

## Tuning

The microphone stays open from "Hey Jot" through the note, with a 10-second buffer, so nothing is lost while the phone is busy and a sentence can follow the wake word in one breath. A short vibration confirms the wake word, because a tone would land in the recording.

Silero decides when the speaker has finished: 1.6 s of silence ends a recording, and a hesitation counts as speech. Moonshine then transcribes the whole recording at once. It writes hesitations out as words, which `SpeechText` removes, and it writes numbers as digits, sometimes hearing "for six fifty" as "4 6 50", which `TimeParser` reads back correctly.

Moonshine replaced a streaming Zipformer recognizer that turned hesitations into stray sounds and dropped the start of many recordings. On 36 clips of one real speaker from the DisfluencySpeech set, word errors were 36.7% for the Zipformer, 16.9% for Moonshine Tiny, 14.0% for Moonshine Base and 13.2% for Parakeet 110M. Base was chosen over Parakeet because its hesitations are plain words that can be removed, and over Tiny because it heard names and short replies better. Moonshine stays loaded for two minutes after use. A voice session with the companion loaded peaked at 2.8 GB on a 6 GB emulator, and a 2.5 GB emulator ran out of memory.

The microphone source has no automatic gain, so `MicGain` lifts quiet speech by up to 20× before the wake word and dictation. The wake word runs at threshold 0.1 and score 2. Those values came from scoring 32 synthesized "Hey Jot" clips and 44 negatives (sound-alikes such as "Hey John", plus everyday speech) at four microphone levels: at talking volume wakes rose from 19 to 27 of 32, at quiet volume from 14 to 25, with no false wakes. Those figures use synthesized voices, not real people.

Jot speaks with Supertonic speaker 1 at speed 1.05 and 5 steps. On the emulator it generates about five seconds of speech per second.

## Sources and licenses

- **sherpa-onnx 2.0.1 Android runtime** (Apache-2.0), release asset `sherpa-onnx-2.0.1.aar`. The upstream release publishes the matching SHA-256 below. [Release asset](https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar) · [License](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/LICENSE)
- **GigaSpeech English 3.3M keyword spotter** (Apache-2.0), release asset `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2`. Jot copies the three quantized ONNX graphs and vocabulary from the archive. The wake phrase is encoded as `▁HE Y ▁JO T` in Jot's own keywords file. [Release asset](https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2) · [Maintainer license confirmation](https://github.com/k2-fsa/sherpa-onnx/issues/3802)
- **Moonshine Base English** (MIT for the English models), release asset `sherpa-onnx-moonshine-base-en-quantized-2026-02-27.tar.bz2`. Jot copies the encoder, the merged decoder and the tokens, and packages the license as `LICENSE-moonshine.txt`. [Moonshine](https://github.com/moonshine-ai/moonshine)
- **Silero VAD** (MIT), release asset `silero_vad.onnx`. [Silero VAD](https://github.com/snakers4/silero-vad)
- **Supertonic 3 int8** (code MIT, weights BigScience OpenRAIL-M, sherpa-onnx conversion Apache-2.0), release asset `sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2`. Jot copies the four ONNX graphs, `tts.json`, `unicode_indexer.bin` and `voice.bin`. The OpenRAIL-M use restrictions apply to anyone using or redistributing the weights. [Release asset](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2) · [Model](https://huggingface.co/Supertone/supertonic-3)
- **ONNX Runtime 1.28.2** (MIT) ships inside the sherpa-onnx AAR. Jot packages the upstream license and third-party notices. [sherpa-onnx Android dependency pin](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/cmake/onnxruntime-android-aarch64.cmake) · [License](https://github.com/microsoft/onnxruntime/blob/v1.28.2/LICENSE) · [Notices](https://github.com/microsoft/onnxruntime/blob/v1.28.2/ThirdPartyNotices.txt)

## Restore assets

Run `pwsh -File scripts/fetch-voice-assets.ps1` from the repository root with PowerShell 7.4+, curl and tar. The optional `-UseGhApi` switch uses authenticated GitHub CLI to fetch the same release assets. The helper verifies SHA-256 before copying the runtime or extracting the selected model entries. It reuses verified downloads in `.local/voice`.

```text
a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7  sherpa-onnx-2.0.1.aar
f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a  sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
43232c1d13013d37317163baec3135bd771a186a4356f28c889bab453bb0e891  sherpa-onnx-moonshine-base-en-quantized-2026-02-27.tar.bz2
9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6  silero_vad.onnx
82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427  sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2
```
