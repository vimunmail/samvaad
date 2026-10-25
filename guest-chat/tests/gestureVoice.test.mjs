import test from 'node:test';
import assert from 'node:assert/strict';
import { gestureVoiceText, newGloveMessages, selectVoice, listeningLanguages } from '../src/gestureVoice.mjs';
import { readFileSync } from 'node:fs';
import { summarizeReceipts, receiptLabel } from '../src/accessibility.mjs';
const labels = { HELLO: { ml: 'നമസ്കാരം', hi: 'नमस्ते' } };
test('each listener receives their chosen translated sign voice text', () => {
  assert.deepEqual(gestureVoiceText('Hello', 'ml', labels), { text: 'നമസ്കാരം', language: 'ml', translated: true });
  assert.deepEqual(gestureVoiceText('Hello', 'hi', labels), { text: 'नमस्ते', language: 'hi', translated: true });
  assert.equal(gestureVoiceText('Hello', 'en', labels).text, 'Hello');
});
test('phone-prepared gesture meanings use each guest language even for labels absent from the website', () => {
  const prepared = { ml: 'എനിക്ക് ഡോക്ടറെ കാണണം', hi: 'मुझे डॉक्टर को दिखाना है', ta: 'நான் மருத்துவரைப் பார்க்க வேண்டும்' };
  assert.deepEqual(gestureVoiceText('I need to see a doctor.', 'ml', {}, prepared),
    { text: prepared.ml, language: 'ml', translated: true });
  assert.deepEqual(gestureVoiceText('I need to see a doctor.', 'hi', {}, prepared),
    { text: prepared.hi, language: 'hi', translated: true });
  assert.deepEqual(gestureVoiceText('I need to see a doctor.', 'ta', {}, prepared),
    { text: prepared.ta, language: 'ta', translated: true });
  assert.equal(gestureVoiceText('I need to see a doctor.', 'en', {}, prepared).text, 'I need to see a doctor.');
  assert.deepEqual(gestureVoiceText('I need to see a doctor.', 'bn', {}, prepared),
    { text: 'I need to see a doctor.', language: 'en', translated: false });
});
test('reviewed phone meaning overrides bundled meaning, but invalid text cannot be spoken', () => {
  assert.equal(gestureVoiceText('Hello', 'ml', labels, { ml: 'സുഖമാണോ?' }).text, 'സുഖമാണോ?');
  assert.equal(gestureVoiceText('Hello', 'ml', labels, { ml: '<unk>' }).text, labels.HELLO.ml);
  assert.equal(gestureVoiceText('Hello', 'ml', labels, { ml: 123 }).text, labels.HELLO.ml);
});
test('unknown phrases use their original language without inventing a translation', () => {
  assert.deepEqual(gestureVoiceText('Custom sign phrase', 'ml', labels), { text: 'Custom sign phrase', language: 'en', translated: false });
  assert.equal(gestureVoiceText('എനിക്ക് വെള്ളം വേണം', 'ml', labels).language, 'ml');
});

test('Malayalam and Hindi source phrases convert to the guest language rather than being read in the source language', () => {
  assert.deepEqual(gestureVoiceText('നമസ്കാരം!', 'hi', labels), { text: 'नमस्ते', language: 'hi', translated: true });
  assert.deepEqual(gestureVoiceText('नमस्ते', 'ml', labels), { text: 'നമസ്കാരം', language: 'ml', translated: true });
  assert.equal(gestureVoiceText('നമസ്കാരം', 'en', labels).text, 'Hello');
});

test('ambiguous meanings and unknown negated sentences never get guessed', () => {
  const ambiguous = { TOMORROW: { hi: 'कल', ml: 'നാളെ' }, YESTERDAY: { hi: 'कल', ml: 'ഇന്നലെ' } };
  assert.equal(gestureVoiceText('कल', 'ml', ambiguous).translated, false);
  assert.equal(gestureVoiceText('I do not need water', 'hi', labels).translated, false);
});

test('each listed target language receives its catalogue phrase from a Malayalam source', () => {
  const catalogue = JSON.parse(readFileSync(new URL('../../app/src/main/assets/translations/gesture_labels.json', import.meta.url), 'utf8')).labels;
  for (const { code } of listeningLanguages([])) {
    const received = gestureVoiceText(catalogue.HELLO.ml, code, catalogue);
    assert.equal(received.language, code);
    assert.equal(received.translated, true);
    assert.equal(received.text, code === 'en' ? 'Hello' : catalogue.HELLO[code]);
  }
});
test('automatic audio includes new host gestures and typed replies, and ignores history and guest messages', () => {
  const messages = [{ id: 'old', senderUid: 'host', kind: 'sign' }, { id: 'new', senderUid: 'host', kind: 'sign' },
    { id: 'pending', senderUid: 'host', kind: 'sign', pending: true }, { id: 'guest', senderUid: 'guest', kind: 'sign' },
    { id: 'text', senderUid: 'host', kind: 'text' }];
  assert.deepEqual(newGloveMessages(messages, new Set(['old']), 'host').map(m => m.id), ['new', 'text']);
  assert.deepEqual(newGloveMessages(messages, new Set(['old', 'new', 'text']), 'host'), []);
});

test('English gesture and typed-message text use the listener language independently of typing settings', () => {
  const catalogue = JSON.parse(readFileSync(new URL('../../app/src/main/assets/translations/gesture_labels.json', import.meta.url), 'utf8')).labels;
  for (const { code } of listeningLanguages([]).filter(row => row.code !== 'en')) {
    const received = gestureVoiceText('Hello', code, catalogue);
    assert.equal(received.language, code);
    assert.equal(received.text, catalogue.HELLO[code]);
    assert.equal(received.translated, true);
  }
  const messages = [{ id: 'typed', senderUid: 'host', kind: 'text', text: 'Hello' },
    { id: 'spoken', senderUid: 'host', kind: 'speech', text: 'Hello' },
    { id: 'guest-text', senderUid: 'guest', kind: 'text', text: 'Hello' }];
  assert.deepEqual(newGloveMessages(messages, new Set(), 'host').map(m => m.id), ['typed', 'spoken']);
  assert.equal(gestureVoiceText(messages[0].text, 'ml', catalogue).text, 'നമസ്കാരം');
  assert.equal(gestureVoiceText('Hello!', 'ml', catalogue).text, 'നമസ്കാരം');
  assert.equal(gestureVoiceText('I need water.', 'ml', catalogue).text, catalogue['I NEED WATER'].ml);
});

test('all twelve regional languages remain selectable even if the browser has only English voices', () => {
  const choices = listeningLanguages([{ lang: 'en_US' }]);
  assert.equal(choices.length, 13);
  assert.deepEqual(choices.filter(row => row.available).map(row => row.code), ['en']);
  assert.equal(choices.find(row => row.code === 'ml').available, false);
});
test('voice selection uses installed voices and does not substitute a different language', () => {
  const voices = [{ lang: 'en-US' }, { lang: 'ml-IN' }, { lang: 'hi-IN' }];
  assert.equal(selectVoice(voices, 'ml'), voices[1]);
  assert.equal(selectVoice(voices, 'ta'), undefined);
  assert.equal(selectVoice([{ lang: 'en-GB' }], 'en').lang, 'en-GB');
});

test('voice selection prefers an available natural voice in the selected language', () => {
  const voices = [{ lang: 'ml-IN', name: 'Basic' }, { lang: 'en-US', name: 'Natural' }, { lang: 'ml-IN', name: 'Malayalam Natural' }];
  assert.equal(selectVoice(voices, 'ml'), voices[2]);
});

test('the bundled catalogue contains no model placeholders or mixed Indic scripts', () => {
  const catalogue = JSON.parse(readFileSync(new URL('../../app/src/main/assets/translations/gesture_labels.json', import.meta.url), 'utf8')).labels;
  const blocks = { hi: [0x900,0x97f], mr: [0x900,0x97f], bn: [0x980,0x9ff], as: [0x980,0x9ff],
    gu: [0xa80,0xaff], pa: [0xa00,0xa7f], or: [0xb00,0xb7f], ta: [0xb80,0xbff], te: [0xc00,0xc7f],
    kn: [0xc80,0xcff], ml: [0xd00,0xd7f], ur: [0x600,0x6ff] };
  for (const [phrase, translations] of Object.entries(catalogue)) for (const [language, value] of Object.entries(translations)) {
    assert.doesNotMatch(value, /<ctrl\d+>|<unk>/i, `${phrase}/${language}`);
    const [lo, hi] = blocks[language];
    for (const char of value) if (/\p{L}/u.test(char) && char.codePointAt(0) >= 0x900 && char.codePointAt(0) <= 0xd7f) {
      assert.ok(char.codePointAt(0) >= lo && char.codePointAt(0) <= hi, `${phrase}/${language} contains ${char}`);
    }
  }
  assert.equal(catalogue.TOILET.ml, 'ശൗചാലയം');
  assert.equal(catalogue.KERALA.ml, 'കേരളം');
  assert.equal(catalogue.RIGHT.hi, 'दायाँ');
});
test('group receipts count independent people and deduplicate legacy receipts', () => {
  const receipts = summarizeReceipts([{ id: 'm', receiverUid: 'alice', deliveredAt: {}, readAt: {} },
    { id: 'm_alice', messageId: 'm', receiverUid: 'alice', deliveredAt: {} },
    { id: 'm_bob', messageId: 'm', receiverUid: 'bob', deliveredAt: {}, readAt: {} }]);
  assert.equal(receipts.m.deliveredCount, 2); assert.equal(receipts.m.readCount, 2);
  assert.equal(receiptLabel(false, receipts.m, true), 'Read by 2');
  assert.equal(receiptLabel(false, { deliveredCount: 2 }, true), 'Delivered to 2');
});
