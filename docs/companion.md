# Companion

The optional AI companion cleans up dictation, suggests actions, drafts titles and messages, and answers questions about saved notes. Writing, offline speech recognition and local actions are free and ad-free, with no account required.

## Connect

Tap ✦ to open Jot account signup or sign-in in your browser. Use a passkey, then return to Jot. Each signed-in device has its own revocable credential, encrypted with a key in Android Keystore. The relay calls OpenAI’s `gpt-6-luna` on the standard tier with no tools. The OpenAI API key stays on the relay.

## What gets sent

- To interpret a note, Jot sends its text, including any names or numbers you typed or dictated.
- To answer a question about saved notes, Jot checks for keyword matches on the phone. If it finds any, the relay sends the question with excerpts and dates from matching notes to OpenAI.
- Audio processing and address book lookups happen on the phone. Jot handles alarms, events, reminders and texts locally after you confirm.
- The relay tracks account and device access, plan status and usage. If you subscribe, Stripe collects billing details through its native PaymentSheet inside Jot and reports subscription status to the relay.

## Plans and limits

The free plan includes five AI calls per day. All devices signed in to the same account share that allowance, which resets at midnight UTC. The $4.99/month plan includes 100 calls per day. Once billing is configured, you can subscribe in Jot. Canceling renewal in the app keeps paid access until the end of the billing period. You can resume renewal before then. Your bank may ask you to verify a payment outside the app.

Both plans are subject to abuse controls and the same $70 ceiling for all OpenAI requests sent through Jot’s relay in a UTC calendar month. Before sending a request, the relay reserves its maximum possible charge and releases the unused amount when OpenAI returns usage. A request OpenAI rejects releases its reservation. An uncertain charge stays reserved through the following month. If the next request would exceed the available budget, Jot’s local features still work.

## How a note is read

The model can propose a corrected note, actions, a title and a message. `CompanionText.settle` checks the proposals against local rules. An alarm, event or reminder needs a time or an explicit cue such as “wake me.” A message needs someone to tell, text or ask. `TimeParser` extracts times from the confirmed note.

For spoken notes, `CompanionText.fix` rejects a rewrite that strays too far from what was heard. Jot reads the note back for confirmation. A timed alarm or reminder, or a text to one known contact, can be offered from local rules.

## Doing the actions

A spoken request with an action skips the note review and goes straight to the offer, which already says what Jot heard. Yes acts on it, no starts the sentence again, and cancel or closing it throws it away.

When you confirm, each selected action runs if its details are complete. An alarm goes to Clock, an event into the primary writable calendar, a reminder into Android's alarm scheduler, and a message to the contact as a text. Jot then opens the alarm list, event or conversation unless you uncheck **Open when done**. If two contacts fit, Jot asks which one. Without SMS or calendar access, Jot opens Messages or Calendar with the details filled in.

A new note used for actions is marked `actionOnly`. It leaves the notes list once nothing is waiting on it, and stays searchable by the companion.

## Answers

The model is asked to answer in one sentence using the supplied notes. Matching notes appear under the answer. If nothing matches, Jot says so locally. **Save as note** keeps the question as a note instead.
