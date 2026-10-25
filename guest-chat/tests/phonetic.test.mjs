import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { phoneticText } from '../src/phonetic.mjs';

const data = JSON.parse(readFileSync(new URL('../../app/src/main/assets/translations/phonetic-input.json', import.meta.url), 'utf8'));

test('common Manglish produces a complete Malayalam message', () => {
  assert.equal(phoneticText('Sughamano? enikku vellam venam. NANNI!', 'ml', data), 'സുഖമാണോ? എനിക്ക് വെള്ളം വേണം. നന്ദി!');
});

test('all twelve writing languages have usable native-script greetings', () => {
  const greetings = { hi: 'नमस्ते', bn: 'নমস্কার', te: 'నమస్కారం', mr: 'नमस्कार', ta: 'வணக்கம்', ur: 'سلام', gu: 'નમસ્તે', kn: 'ನಮಸ್ಕಾರ', ml: 'സുഖമാണോ', pa: 'ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ', or: 'ନମସ୍କାର', as: 'নমস্কাৰ' };
  assert.deepEqual(Object.keys(data.languages).sort(), Object.keys(greetings).sort());
  for (const [language, expected] of Object.entries(greetings)) assert.equal(phoneticText(data.languages[language].example, language, data), expected, language);
});

test('phonetic rules convert unfamiliar words without dictionary entries', () => {
  const scriptOnly = { languages: { ml: { ...data.languages.ml, words: {} } } };
  assert.equal(phoneticText('kavi amma kaaka kumar', 'ml', scriptOnly), 'കവി അമ്മ കാക കുമർ');
});

test('mixed native text, punctuation, emoji and multiline spacing survive', () => {
  assert.equal(phoneticText('ഞാൻ 123 🙂\nenikku vellam!', 'ml', data), 'ഞാൻ 123 🙂\nഎനിക്ക് വെള്ളം!');
});

test('literal English, links and email addresses remain readable', () => {
  const input = 'nanni [[Alex Smith]] https://example.com/chat?q=hello www.example.com alex@example.org';
  assert.equal(phoneticText(input, 'ml', data), 'നന്ദി Alex Smith https://example.com/chat?q=hello www.example.com alex@example.org');
});

test('English, unavailable converters and empty input remain unchanged', () => {
  for (const [text, language, fixture] of [['How are you?', 'en', data], ['hello', 'missing', data], ['hello', 'ml', undefined], ['', 'ml', data]]) {
    assert.equal(phoneticText(text, language, fixture), text);
  }
  assert.equal(typeof phoneticText('constructor', 'ml', data), 'string');
});
