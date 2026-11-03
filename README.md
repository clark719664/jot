# Jot

Voice notes that become alarms, events, reminders and messages.

**[Download Jot.apk](https://github.com/clark719664/jot/releases/latest/download/Jot.apk)** · [clark719664.github.io/jot](https://clark719664.github.io/jot/)

Say **Hey Jot**, or tap the pen and type. Jot works out what you meant, fills in the details, and does it once you confirm: sets the alarm, adds the event, schedules the reminder, or opens your message ready to send. Ask about your notes and it answers. It talks back in a natural voice that runs on the phone.

## Install

Android 8 or newer. Open `Jot.apk` on the phone and allow the install. Newer versions install over the old one and keep your notes.

Allow the microphone for voice, notifications for **Hey Jot** and reminders, and the calendar to add events directly. Without calendar access, Calendar opens filled in for you to save. Precise reminders ask for Alarms and reminders access; approximate timing works without it.

## Companion

Tap ✦ to download the companion: [Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm), 2.6 GB over Wi-Fi, running on the phone with [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM). With it, Jot:

- fixes misheard words and reads the fixed note back,
- picks the actions a note asks for,
- titles events and reminders, and drafts messages for you to send,
- answers questions about your notes, spoken or typed: *"What's the wifi password?"*

Without it, Jot still suggests actions from plain cues such as "remind me" or "wake me". Tap ✦ again to remove it. How it works: [docs/companion.md](docs/companion.md).

## Privacy

Notes, audio and the companion stay on the phone. The only network request is the companion download from Hugging Face, checked against a pinned SHA-256. No accounts, ads or analytics, and no cloud backup: uninstalling deletes your notes.

Messages are drafts. You pick the recipient and send them yourself. An alarm, event or message made from a new note doesn't clutter your notes; a reminder stays listed until it fires.

## Build

JDK 21 and Android SDK 35. Restore the speech runtime and models (hashes and licenses in [docs/voice-assets.md](docs/voice-assets.md)), then build:

```sh
pwsh scripts/fetch-voice-assets.ps1
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Release builds are signed when `.local/signing.properties` exists; keep that key, since updates must use it.

## Test

`testDebugUnitTest` covers parsing, time handling and the companion's rules on the JVM. Device tests run the bundled speech models, draft recovery, reminder saving and, when the companion model is on the device, the companion itself:

```sh
./gradlew :app:connectedDebugAndroidTest
```

## Limits

Verified on an Android 16 emulator with recorded and synthesized speech. Wake sensitivity on your voice, background limits, battery use and GPU speed still need real phones.

## License

MIT. Third-party components and models keep their own licenses, listed in [docs](docs).
