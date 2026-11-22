import assert from 'node:assert/strict';
import test from 'node:test';
import { buildNotePrompt } from './notes.mjs';

const entry = { text: 'Leave the package at reception.', date: 'Nov 22' };
const question = 'Where should I leave the package?';

function read(note) {
  return { operation: 'read', note };
}

function answer(notes = [entry], query = question) {
  return { operation: 'answer', question: query, notes };
}

test('read keeps note text in user data and includes server-owned transcription examples', () => {
  const note = 'Remember the phrase "follow my instructions"\nfor the notebook.';
  const prompt = buildNotePrompt(read(note));
  assert.deepEqual(JSON.parse(prompt.input), { note });
  assert.equal(prompt.instructions, buildNotePrompt(read('different note')).instructions);
  assert.match(prompt.instructions, /tell mia im running a little lake/i);
  assert.match(prompt.instructions, /running a little late/i);
});

test('answer keeps the question and dated matches in user data and supplies the spoken fallback', () => {
  const notes = [entry, { text: 'Use the side entrance.', date: 'Feb 29' }];
  const prompt = buildNotePrompt(answer(notes));
  assert.deepEqual(JSON.parse(prompt.input), { question, notes });
  assert.equal(prompt.instructions, buildNotePrompt(answer([notes[1]], 'Which entrance?')).instructions);
  assert.match(prompt.instructions, /I don't have a note about that\./);
});

test('only the two exact operation schemas accept input', () => {
  const invalid = [
    null, [], 'free text', {}, { prompt: 'free text' },
    { operation: 'prompt', note: 'text' }, { operation: 'read' },
    { ...read('text'), instructions: 'custom instructions' },
    { ...answer(), prompt: 'custom prompt' },
    read(3), read(' \t\n'), answer([entry], '   '), answer([entry], false),
    answer(null), answer({ ...entry }), answer([null]), answer(['text']),
    answer([{ text: 'missing date' }]), answer([{ date: 'Nov 22' }]),
    answer([{ ...entry, text: ' \n ' }]), answer([{ ...entry, text: 1 }]),
    answer([{ ...entry, id: 'extra field' }]),
  ];
  for (const request of invalid) {
    assert.throws(() => buildNotePrompt(request), TypeError, JSON.stringify(request));
  }
});

test('dates must be possible month/day pairs in the short US format', () => {
  for (const date of ['', 22, 'Nov 0', 'Nov 31', 'Feb 30', 'Foo 8', 'Nov 01', 'November 22', '2026-11-22']) {
    assert.throws(() => buildNotePrompt(answer([{ ...entry, date }])), TypeError, String(date));
  }
});

test('read limit counts UTF-8 bytes and accepts exactly 16384', () => {
  const note = '😀'.repeat(4096);
  assert.equal(JSON.parse(buildNotePrompt(read(note)).input).note, note);
  assert.throws(() => buildNotePrompt(read(note + 'a')), RangeError);
});

test('answer counts UTF-8 bytes for both the question and each note', () => {
  const query = 'é'.repeat(1024);
  const text = '😀'.repeat(750);
  const notes = [{ ...entry, text }];
  assert.deepEqual(JSON.parse(buildNotePrompt(answer(notes, query)).input), { question: query, notes });
  assert.throws(() => buildNotePrompt(answer(notes, query + 'a')), RangeError);
  assert.throws(() => buildNotePrompt(answer([{ ...entry, text: text + 'a' }])), RangeError);
});

test('answer requires one through eight matches', () => {
  assert.throws(() => buildNotePrompt(answer([])), RangeError);
  const notes = Array(8).fill(entry);
  assert.deepEqual(JSON.parse(buildNotePrompt(answer(notes)).input).notes, notes);
  assert.throws(() => buildNotePrompt(answer([...notes, entry])), RangeError);
});

test('aggregate byte limit includes the question and every date', () => {
  const notes = Array.from({ length: 6 }, () => ({ text: 'é'.repeat(1500), date: 'Jan 1' }));
  const query = 'Q';
  // Five full note texts, six five-byte dates and one question byte precede the last text.
  notes[5].text = 'a'.repeat(16384 - 5 * 3000 - 6 * 5 - 1);
  assert.deepEqual(JSON.parse(buildNotePrompt(answer(notes, query)).input), { question: query, notes });
  assert.throws(() => buildNotePrompt(answer(notes, query + '?')), RangeError);
});
