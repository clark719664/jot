# Jot

Native Android voice notes for Samsung Galaxy A36. Say **Hey Jot**, dictate, confirm, then choose an alarm, calendar event, reminder, or message draft. Voice recognition is local. The app has no Internet permission, account, ads, subscription, or automatic message sending.

## Install

Transfer `Jot-0.1.0.apk` to the phone and open it in My Files. Follow Android's installation prompt. Open Jot and allow microphone access when using voice. Allow notifications for hands-free listening and reminders. Turn on **Hey Jot** to start listening; the ongoing notification has **Stop**. Precisely timed reminders ask for Android's Alarms and reminders special access; approximate timing is available explicitly.

Notes remain on the phone. This release disables cloud backup and device transfer; uninstalling deletes its notes. Keep the app installed when updating with a newer APK signed by the same key.

## Behavior

- The pen creates a typed note; microphone starts a voice note immediately.
- Confirming preserves the note before any other action. Each selected action is stored independently.
- Messages are editable drafts. Open Messages to select a recipient and send yourself.
- Clock and Calendar open Android's own screens. Their rows report that the app opened, not that an item was created.
- When locked, external actions remain pending. Tap the notification and unlock to continue.
- Stopping or rebooting leaves Hey Jot off until you enable it again.
- Spoken readback requires an installed offline English Android text-to-speech voice.

## Build

Use JDK 21, Android SDK platform/build tools 35, and the checked-in Gradle 8.9 wrapper. Output bytecode targets Java 17; the app supports Android 8 and newer and targets Android 15.

Set `JAVA_HOME` to the JDK and put the Android SDK path in an ignored `local.properties`. Restore the pinned speech runtime and model assets with `scripts/fetch-voice-assets.ps1`; see the provenance document in `docs` for hashes and licenses.

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew.bat :app:assembleRelease
```

The personal release signing key and properties are in ignored `.local/`. Keep that directory private and backed up: it is needed to update the installed app without removing its notes. It is never included in the APK or source commits.

Android instrumentation tests use the bundled production speech models, confirm draft recovery through Room, and exercise dismissal during reminder saving. Test waveforms are in `app/src/androidTest/assets` and are excluded from the release APK.

## Verification boundary

This first release is verified on an Android 36 emulator and recorded speech fixtures. It still needs real Galaxy A36 testing for microphone acoustics, wake sensitivity, Samsung background behavior, TalkBack, and battery consumption. The emulator's Calendar provider has a missing activity class, so Calendar completion must be checked on the phone. Jot preserves the handoff and subsequent pending actions when that provider fails.

The older pinned Android lint tool misclassifies `SCHEDULE_EXACT_ALARM` as system-only. Only that diagnostic is suppressed on that permission: it is the documented user-granted special access, checked with `canScheduleExactAlarms()` before use. See https://developer.android.com/develop/background-work/services/alarms . Other lint checks remain enabled.
