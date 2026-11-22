# Jot

Voice notes that become alarms, events, reminders and messages.

**[Download Jot.apk](https://github.com/clark719664/jot/releases/latest/download/Jot.apk)** · [clark719664.github.io/jot](https://clark719664.github.io/jot/)

Say **Hey Jot** and keep talking, or tap the pen and type. Jot fills in the details and acts once you confirm. It can set an alarm, add an event, schedule a reminder or send a text. Then it opens the alarm, event or conversation it made, unless you uncheck **Open when done**. Its voice runs on the phone. The optional companion can answer questions about your notes.

## Install

Android 8 or newer. Open `Jot.apk` on the phone and allow the install. Newer versions install over the old one and keep your notes.

Allow the microphone for voice, notifications for **Hey Jot** and reminders, and the calendar to add events directly. Without calendar access, Calendar opens filled in for you to save. Precise reminders ask for Alarms and reminders access; approximate timing works without it.

Allow contacts so Jot can find who you mean, and SMS so it can send. Android may refuse SMS to an app installed outside a store. If it does, open Jot's App info, tap ⋮, choose **Allow restricted settings**, then allow SMS under Permissions. Until then, Messages opens with the person and the words filled in.

## Companion

Writing, dictation and local actions are free and ad-free. To use the optional AI companion, tap ✦, create an account or sign in with a passkey in your browser, then return to Jot. The companion can

- fix misheard words and read the corrected note back,
- suggest actions, title events and reminders, and draft messages for you to confirm,
- answer spoken or typed questions such as *“What's the Wi-Fi password?”*

The free account includes five AI calls per day across all your devices, resetting at midnight UTC. When billing is available, you can subscribe in Jot for $4.99/month to raise the daily allowance to 100 calls. You can cancel or resume renewal in the app. Both plans share the service’s $70 monthly AI budget and are subject to abuse controls. Typing, dictation and action cues such as “remind me” work offline. [Companion details](docs/companion.md)

## Privacy

Jot stores notes on your phone and processes microphone audio there. When you use the companion, Jot sends note text through its relay to OpenAI. Questions can include excerpts and dates from matching notes. The relay tracks your account, devices, plan and usage. If you subscribe, Stripe collects billing details in its payment form inside Jot. Uninstalling deletes your local notes.

Jot sends a text only after you confirm the person and the words, and your carrier delivers it. Contacts are read on the phone to find who you mean. An alarm, event or message made from a new note doesn't clutter your notes; a reminder stays listed until it fires.

## Build

JDK 21 and Android SDK 35. Restore the speech runtime and models (hashes and licenses in [docs/voice-assets.md](docs/voice-assets.md)), then build:

```sh
pwsh scripts/fetch-voice-assets.ps1
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Release builds are signed when `.local/signing.properties` exists; keep that key, since updates must use it.

## Test

`testDebugUnitTest` covers parsing, time handling and the companion's rules on the JVM. Device tests cover the bundled speech models, draft recovery and reminder saving.

```sh
./gradlew :app:connectedDebugAndroidTest
```

## Limits

Verified on an Android 16 emulator with recorded and synthesized speech. Wake sensitivity on your voice, background limits and battery use still need real phones.

## License

MIT. Third-party components and models keep their own licenses, listed in [docs](docs).
