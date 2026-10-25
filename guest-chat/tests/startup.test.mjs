import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';
import * as media from '../src/media.mjs';
import * as accessibility from '../src/accessibility.mjs';

function startup(status) {
  const effects = [], reads = [], downloads = [], states = [];
  let anonymousSignIns = 0, persistence;
  const auth = { currentUser: null, async authStateReady() { this.currentUser = { uid: 'guest' }; } };
  const react = {
    useState(initial) {
      const slot = states.length;
      states.push(typeof initial === 'function' ? initial() : initial);
      return [states[slot], value => { states[slot] = typeof value === 'function' ? value(states[slot]) : value; }];
    },
    useRef: current => ({ current }), useEffect: callback => effects.push(callback),
  };
  const dependencies = {
    react, 'react-dom/client': { createRoot: () => ({ render() {} }) },
    'react/jsx-runtime': { jsx: (type, props) => ({ type, props }), jsxs: (type, props) => ({ type, props }) },
    'firebase/app': { initializeApp: () => ({}) },
    'firebase/auth': {
      browserSessionPersistence: 'session',
      initializeAuth: (_, options) => { persistence = options.persistence; return auth; },
      signInAnonymously: async () => { anonymousSignIns++; return { user: { uid: 'new' } }; },
    },
    'firebase/firestore': {
      initializeFirestore: () => ({}), memoryLocalCache: () => ({}),
      doc: (_, ...parts) => parts.join('/'),
      getDocFromServer: async path => {
        reads.push(path);
        return path.startsWith('invites/')
          ? { exists: () => true, data: () => ({ roomId: 'room', expiresAt: { toMillis: () => 123456 } }) }
          : { exists: () => !!status, data: () => ({ status, name: 'Guest' }) };
      },
    },
    './media.mjs': media, './accessibility.mjs': accessibility,
    './messageText.mjs': { outgoingText: () => '' },
    './useChatAccess': { useChatAccess: () => ({ peers: [], language: 'en', visible: true }) },
    './useGloveVoice': { useGloveVoice: () => ({ language: 'en' }) },
    './useAudioVolume': { useAudioVolume: () => ({ volume: 1 }) },
  };
  const context = {
    exports: {}, require: name => dependencies[name] || {},
    location: { hash: '#invite=' + 'a'.repeat(64), hostname: 'example.test' },
    navigator: { onLine: true }, localStorage: { getItem: () => null },
    document: { getElementById() {} },
    window: { addEventListener() {}, removeEventListener() {} },
    fetch: async path => { downloads.push(path); return { json: async () => ({ enabled: true, projectId: 'test', apiKey: 'test' }) }; },
  };
  const source = readFileSync(new URL('../src/main.tsx', import.meta.url), 'utf8')
    .replace('import.meta.env.VITE_FIREBASE_EMULATORS', 'undefined');
  const compiled = ts.transpileModule(source + '\nexports.testApp = App;', {
    compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2021 },
  }).outputText;
  vm.runInNewContext(compiled, context);
  const page = context.exports.testApp();
  return { page, states, reads, downloads, effects, get anonymousSignIns() { return anonymousSignIns; }, get persistence() { return persistence; } };
}

test('QR startup shows the name form before network checks finish and prioritizes connection data', async () => {
  const guest = startup();
  assert.equal(guest.states[0], 'invite');
  assert.ok(JSON.stringify(guest.page).includes('Connecting…'));
  assert.ok(JSON.stringify(guest.page).includes('Your first name or nickname'));
  guest.effects.forEach(effect => effect());
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(guest.downloads, ['/firebase-config.json']);
  assert.equal(guest.reads.length, 2);
  assert.equal(guest.anonymousSignIns, 0, 'restore the existing identity before creating another anonymous guest');
  assert.equal(guest.persistence, 'session');
});

test('approved guests rely on the live room listener instead of an extra startup room read', async () => {
  const guest = startup('approved');
  guest.effects.forEach(effect => effect());
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(guest.reads.length, 2);
  assert.ok(!guest.reads.includes('rooms/room'));
  assert.equal(guest.states[0], 'waiting', 'approval is still verified by the server listener');
});
