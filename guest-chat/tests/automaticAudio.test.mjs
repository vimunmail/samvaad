import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';
import * as voiceHelpers from '../src/gestureVoice.mjs';
import { languages } from '../src/accessibility.mjs';

// Exercise the actual hook with a speech engine that records playback requests.
function guest() {
  const slots = [], effects = [], spoken = [], saved = new Map();
  let cursor = 0, canceled = 0;
  const react = {
    useState(initial) {
      const index = cursor++;
      if (!(index in slots)) slots[index] = typeof initial === 'function' ? initial() : initial;
      return [slots[index], value => { slots[index] = typeof value === 'function' ? value(slots[index]) : value; }];
    },
    useRef(initial) { const index = cursor++; return slots[index] ??= { current: initial }; },
    useEffect(callback, dependencies) {
      const index = cursor++, old = slots[index];
      if (!old || dependencies.some((value, i) => !Object.is(value, old.dependencies[i]))) {
        slots[index] = { dependencies, cleanup: old?.cleanup };
        effects.push(() => { slots[index].cleanup?.(); slots[index].cleanup = callback(); });
      }
    },
  };
  const synth = {
    getVoices: () => [{ voiceURI: 'english', lang: 'en-US', name: 'English' }],
    speak: utterance => spoken.push(utterance), cancel: () => { canceled++; },
    addEventListener() {}, removeEventListener() {}, speaking: true,
  };
  const context = {
    exports: {}, require: name => name === 'react' ? react : name.includes('gestureVoice') ? voiceHelpers : { languages },
    window: { speechSynthesis: synth, addEventListener() {}, removeEventListener() {}, setInterval: () => 1, clearInterval() {} },
    document: { addEventListener() {}, removeEventListener() {} },
    localStorage: { getItem: key => saved.get(key) ?? null, setItem: (key, value) => saved.set(key, value) },
    SpeechSynthesisUtterance: class { constructor(text) { this.text = text; } },
  };
  const source = ts.transpileModule(readFileSync(new URL('../src/useGloveVoice.ts', import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021 },
  }).outputText;
  vm.runInNewContext(source, context);
  const labels = {};
  return {
    spoken, saved, get canceled() { return canceled; },
    render(active, messages = [], paused = false) {
      cursor = 0;
      const hook = context.exports.useGloveVoice(active, 'host', messages, labels, paused, true, 1);
      while (effects.length) effects.shift()();
      return hook;
    },
  };
}
const message = (id, text) => ({ id, text, senderUid: 'host', kind: 'text' });

test('joining unlocks audio and new messages speak immediately in order without a message tap', () => {
  const page = guest();
  page.render(false).unlock();
  assert.equal(page.spoken[0].text, 'Audio enabled');
  const history = [message('old', 'Old message')];
  page.render(true, history);
  page.render(true, [...history, message('one', 'First'), message('two', 'Second')]);
  assert.equal(page.spoken.at(-1).text, 'First');
  page.spoken.at(-1).onend();
  assert.equal(page.spoken.at(-1).text, 'Second');
  assert.ok(!page.spoken.some(value => value.text === 'Old message'));
});

test('mute stops speech, persists, skips muted messages and resumes future messages', () => {
  const page = guest();
  page.render(false).unlock();
  page.render(true);
  page.render(true, [message('one', 'First')]).toggle(false);
  assert.ok(page.canceled > 0);
  assert.equal(page.saved.get('samvaad-guest-auto-audio'), 'off');
  const muted = [message('one', 'First'), message('two', 'Muted')];
  page.render(true, muted).toggle(true);
  page.render(true, muted);
  page.render(true, [...muted, message('three', 'Next')]);
  assert.equal(page.spoken.at(-1).text, 'Next');
  assert.ok(!page.spoken.some(value => value.text === 'Muted'));
});

test('browser blocking retains the message for the resume-audio gesture', () => {
  const page = guest();
  page.render(false).unlock();
  page.render(true);
  const messages = [message('one', 'Please retry')];
  page.render(true, messages);
  page.spoken.at(-1).onerror({ error: 'not-allowed' });
  const hook = page.render(true, messages);
  assert.equal(hook.blocked, true);
  hook.unlock();
  assert.equal(page.spoken.at(-1).text, 'Please retry');
});
