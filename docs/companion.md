# Companion

The companion is an optional on-device language model. It cleans up what was heard, suggests actions, writes titles and message drafts, and answers questions about saved notes.

## Model

- **Gemma 4 E2B instruction-tuned**, LiteRT-LM format, from [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) (Apache-2.0).
- Pinned to revision `c658da437244f072bf2f1cabc916b60d66d97663`, file `gemma-4-E2B-it.litertlm`, 2,588,147,712 bytes, SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`.
- Runtime: [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) `0.19.0` (Apache-2.0). GPU first, CPU fallback; emulators use CPU.

Chosen over Qwen3 0.6B/1.7B and Qwen3.5 0.8B/2B after scoring all five on the same note set: Gemma got 9 of 10 right, the next best 8.

## Download

Android's DownloadManager fetches the file over unmetered networks only, into the app's external files folder, with a system notification. Jot checks size and SHA-256, then marks it ready. A failed check deletes the file. Turning the companion off cancels the download or deletes the model.

## How a note is read

1. The note goes to the model with a short instruction and five worked examples. It replies with `{"note","question","actions","title","message"}`.
2. `CompanionText.settle` merges that reply with plain rules:
   - alarm, calendar and reminder need a time in the note or an explicit cue ("remind me", "wake me");
   - a message needs someone to tell, text or ask;
   - obvious cues add actions the model missed;
   - a question needs a question shape ("what", "where did I").
3. Times come from `TimeParser`, never from the model.

For spoken notes, `note` is the text as the user meant it: misheard words fixed, punctuation added. `CompanionText.fix` rejects a rewrite that strays too far from what was heard. Jot reads the fixed note back, so a bad fix is caught before anything happens. A cold start can take half a minute, so Jot says "One sec." after two seconds and "Almost there." ten seconds later, and after 45 seconds it reads back what was heard. The voice service stays up while Jot thinks.

## Doing the actions

On confirm, each selected action runs if its details are complete, and only a gap opens a form: an alarm goes straight to Clock, an event straight into the primary writable calendar, a reminder into Android's alarm scheduler, and a message opens in Messages ready for you to pick a recipient and send. Without calendar access, Calendar opens filled in.

A new note used for actions is marked `actionOnly`. It leaves the notes list once nothing is waiting on it, and stays searchable by the companion.

## Answers

A spoken or typed question is matched against saved notes by keyword. The best matches go to the model with an instruction to answer in one sentence from those notes only, or say it has no note about that. The matching notes show under the answer. **Save as note** keeps the question as a note instead.

## Speed

On an Android 16 x86_64 emulator (4 cores, CPU): load 1.5–2.6 s, reading a note 2–6 s, answering about 1 s. The engine unloads after two idle minutes.

## Debugging

```sh
adb shell setprop log.tag.JotCompanion DEBUG   # log every prompt and reply
adb logcat -s JotCompanion
```

To skip the download on a test device, push the model and give it to the app (rooted emulator):

```sh
adb root
adb shell mkdir -p /storage/emulated/0/Android/data/com.clark.jot/files/companion
adb push gemma-4-E2B-it.litertlm /storage/emulated/0/Android/data/com.clark.jot/files/companion/
adb shell chown -R $(adb shell stat -c %U /storage/emulated/0/Android/data/com.clark.jot):ext_data_rw /storage/emulated/0/Android/data/com.clark.jot
```

`CompanionModelTest` and `CompanionFlowTest` run when the file is present.
