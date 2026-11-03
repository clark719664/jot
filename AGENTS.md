# Jot for agents

Native Android app, Kotlin + Compose, one module (`app`). Package `com.jot.app`, application id `com.clark.jot`.

## Commands

```sh
pwsh scripts/fetch-voice-assets.ps1          # once: speech runtime + models
./gradlew :app:testDebugUnitTest             # JVM tests, seconds
./gradlew :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest     # emulator or phone
```

## Map

- `JotController.kt` – the whole conversation: capture, confirm, suggest, forms, answers.
- `core/` – pure Kotlin, JVM-tested: `TimeParser`, `ConversationCommands`, `CompanionText` (prompts, parsing, rules).
- `companion/CompanionModel.kt` – model download, SHA-256 check, LiteRT-LM engine, idle unload.
- `capture/` – wake word, dictation and spoken prompts (sherpa-onnx, foreground service).
- `platform/AndroidActions.kt` – alarms, calendar and message hand-offs; reminder scheduling.
- `data/JotStore.kt` – Room notes and actions.
- `ui/` – Compose screens. `Screenshots` (androidTest, `-e screens 1`) captures every screen.

## Rules

- The model proposes; `CompanionText.settle` decides. Times always come from `TimeParser`, never the model.
- Every companion path must work when the model is absent, slow or wrong.
- Nothing leaves the phone except the pinned model download.
- No taglines, helper text or secondary labels. If a screen needs one, fix the layout.
- Check 320dp at 2× text and dark mode before changing a screen.
- Logic changes get a JVM test in `app/src/test`.
