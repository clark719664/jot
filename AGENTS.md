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
- `core/` – pure Kotlin, JVM-tested: `TimeParser`, `ConversationCommands`, `CompanionText` (prompts, parsing, rules), `ContactMatch` (who a sentence is for), `SpeechText` (hesitations), `JotLines` (everything Jot says), `MicGain`.
- `companion/CompanionModel.kt` – model download, SHA-256 check, LiteRT-LM engine, idle unload.
- `capture/` – wake word and dictation (`OfflineAudio`), Jot's voice (`NaturalVoice`, with `OfflineSpeech` as the fallback), the foreground service.
- `platform/AndroidActions.kt` – alarms, calendar events, sending texts, reminder scheduling, and the views Jot lands in afterwards.
- `platform/Contacts.kt` – one phone number per contact.
- `data/JotStore.kt` – Room notes and actions.
- `ui/` – Compose screens. `Screenshots` (androidTest, `-e screens 1`) captures every screen.

## Rules

- The model proposes; `CompanionText.settle` decides. Times come from `TimeParser` on the confirmed note, never from the model directly.
- Jot only speaks lines from `JotLines` or the note itself. Keep them short and warm; never read out a list of commands.
- A message is sent only after the user confirms the person and the words Jot read back. What was read back is what gets sent.
- Every companion path must work when the model is absent, slow or wrong. A cold reply can take over 30 seconds, so nothing the rules can settle waits for it.
- Nothing leaves the phone except the pinned model download and texts the user confirmed.
- No taglines, helper text or secondary labels. If a screen needs one, fix the layout.
- Check 320dp at 2× text and dark mode before changing a screen.
- Logic changes get a JVM test in `app/src/test`.
