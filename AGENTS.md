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
- `companion/CompanionModel.kt` – Android client for the opt-in companion relay.
- `relay/` – Cloudflare Worker for account and device access, quotas, billing and OpenAI requests.
- `capture/` – wake word and dictation (`OfflineAudio`), Jot's voice (`NaturalVoice`, with `OfflineSpeech` as the fallback), the foreground service.
- `platform/AndroidActions.kt` – alarms, calendar events, sending texts, reminder scheduling, and the views Jot lands in afterwards.
- `platform/Contacts.kt` – one phone number per contact.
- `data/JotStore.kt` – Room notes and actions.
- `ui/` – Compose screens. `Screenshots` (androidTest, `-e screens 1`) captures every screen.

## Rules

- The model proposes; `CompanionText.settle` decides. Times come from `TimeParser` on the confirmed note, never from the model directly.
- Jot only speaks lines from `JotLines` or the note itself. Keep them short and warm; never read out a list of commands.
- A message is sent only after the user confirms the person and the words Jot read back. What was read back is what gets sent.
- Keep non-AI features free, ad-free and usable without an account. Typing, dictation and local action rules must work when the relay is unavailable or out of budget.
- Send companion requests only after opt-in. Send the current note text or the question with matching note texts and dates. Keep raw audio and contact lookup on the phone.
- Keep the OpenAI API key on the Worker. Use browser passkey signup and sign-in, return to Jot, and issue a revocable device credential protected by Android Keystore.
- Enforce five free AI calls per account per UTC day across devices. The $4.99/month plan allows 100 calls per account per UTC day. Enforce abuse controls and the aggregate $70 OpenAI ceiling per UTC calendar month for all accounts. Keep free access independent of Stripe configuration. Use native Stripe PaymentSheet for subscriptions and handle canceling or resuming renewal inside Jot.
- No taglines, helper text or secondary labels. If a screen needs one, fix the layout.
- Check 320dp at 2× text and dark mode before changing a screen.
- Logic changes get a JVM test in `app/src/test`.
