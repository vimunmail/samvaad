import test from 'node:test';
import assert from 'node:assert/strict';
import { runTranslation, TranslationError } from '../translation-service.js';

test('forward complete English meanings to Google and return requested Malayalam text', async () => {
  let charged = 0;
  const result = await runTranslation({ auth: { uid: 'phone' }, data: { texts: ['I do not need help'], target: 'ml' } }, {
    reserve: async (uid, count) => { assert.equal(uid, 'phone'); charged = count; },
    translate: async input => {
      assert.deepEqual(input, { contents: ['I do not need help'], sourceLanguageCode: 'en', targetLanguageCode: 'ml', mimeType: 'text/plain' });
      return [{ translations: [{ translatedText: 'എനിക്ക് സഹായം ആവശ്യമില്ല' }] }];
    },
  });
  assert.equal(charged, 18);
  assert.deepEqual(result, { translations: ['എനിക്ക് സഹായം ആവശ്യമില്ല'], target: 'ml', source: 'google-cloud' });
});
test('reject unauthenticated and invalid requests before reserving usage or contacting Google', async () => {
  const deps = { reserve: async () => assert.fail('No reservation expected'), translate: async () => assert.fail('No Google request expected') };
  await assert.rejects(runTranslation({ data: { texts: ['Hello'], target: 'ml' } }, deps), { code: 'unauthenticated' });
  await assert.rejects(runTranslation({ auth: { uid: 'phone' }, data: { texts: ['<unk>'], target: 'ml' } }, deps), { code: 'invalid-argument' });
});
test('budget failures prevent the Google request', async () => {
  await assert.rejects(runTranslation({ auth: { uid: 'phone' }, data: { texts: ['Hello'], target: 'ml' } }, {
    reserve: async () => { throw new TranslationError('resource-exhausted', 'Daily limit'); },
    translate: async () => assert.fail('No Google request expected'),
  }), { code: 'resource-exhausted' });
});
test('incomplete, corrupt and failed responses never return fabricated translations', async () => {
  const request = { auth: { uid: 'phone' }, data: { texts: ['Hello'], target: 'ml' } };
  for (const translations of [[], [{ translatedText: '<ctrl42>' }], [{ translatedText: '' }]]) {
    await assert.rejects(runTranslation(request, { reserve: async () => {}, translate: async () => [{ translations }] }), { code: 'unavailable' });
  }
  await assert.rejects(runTranslation(request, { reserve: async () => {}, translate: async () => { throw new Error('Sensitive upstream response'); } }),
    error => error.code === 'unavailable' && !error.message.includes('Sensitive'));
});
