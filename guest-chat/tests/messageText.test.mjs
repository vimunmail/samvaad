import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { outgoingText, displayedMessageText } from '../src/messageText.mjs';

const data = JSON.parse(readFileSync(new URL('../../app/src/main/assets/translations/phonetic-input.json', import.meta.url), 'utf8'));
const labels = { HELLO: { ml: 'നമസ്കാരം' } };

test('a locally translated English message cannot pretend it was sent in Malayalam', () => {
  assert.equal(displayedMessageText('Hello', true, 'ml', labels), 'Hello');
  assert.equal(displayedMessageText('Hello', false, 'ml', labels), 'നമസ്കാരം');
});

test('Manglish preview, stored text, and sent bubble use the same Malayalam text', () => {
  const sent = outgoingText('sughamano? enikku vellam venam.', 'ml', true, data);
  assert.equal(sent, 'സുഖമാണോ? എനിക്ക് വെള്ളം വേണം.');
  assert.equal(displayedMessageText(sent, true, 'en', labels), sent);
  assert.equal(displayedMessageText(sent, true, 'ml', labels), sent);
});

test('corrected preview is sent only until its draft or language changes', () => {
  const correction = { draft: 'sughamano', language: 'ml', text: 'സുഖമാണോ? നന്ദി!' };
  assert.equal(outgoingText('sughamano', 'ml', true, data, correction), correction.text);
  assert.equal(outgoingText('nanni', 'ml', true, data, correction), 'നന്ദി');
  assert.equal(outgoingText('namaste', 'hi', true, data, correction), 'नमस्ते');
  assert.equal(outgoingText('sughamano', 'ml', false, data, correction), 'sughamano');
});
