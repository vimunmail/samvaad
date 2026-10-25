import test from 'node:test';
import assert from 'node:assert/strict';
import { validateRequest, chargeUsage, languages, DAILY_PROJECT_LIMIT, DAILY_PHONE_LIMIT } from '../translation-policy.js';

test('all app regional languages accept complete English sentences, including negation', () => {
  for (const target of languages) {
    assert.deepEqual(validateRequest({ texts: ['I do not need an ambulance.'], target }),
      { texts: ['I do not need an ambulance.'], target });
  }
});
test('reject malformed, oversized and placeholder requests before billing', () => {
  for (const data of [{ texts: [], target: 'ml' }, { texts: ['Hello'], target: 'xx' },
    { texts: ['<ctrl42>'], target: 'ml' }, { texts: ['a'.repeat(301)], target: 'ml' },
    { texts: Array(31).fill('Hello'), target: 'ml' }, { texts: [17], target: 'ml' }]) {
    assert.throws(() => validateRequest(data));
  }
});
test('enforce project and phone budgets and reset at the next UTC day', () => {
  const now = Date.UTC(2026, 9, 11);
  const initial = chargeUsage({}, {}, 100, now);
  assert.throws(() => chargeUsage({ ...initial.project, characters: DAILY_PROJECT_LIMIT }, initial.phone, 1, now));
  assert.throws(() => chargeUsage(initial.project, { ...initial.phone, characters: DAILY_PHONE_LIMIT }, 1, now));
  assert.equal(chargeUsage(initial.project, initial.phone, 10, now + 86_400_000).phone.characters, 10);
});
test('limit bursts without breaking a 12-language generation run', () => {
  const now = Date.UTC(2026, 9, 11);
  let usage = { project: {}, phone: {} };
  for (let index = 0; index < 20; index++) usage = chargeUsage(usage.project, usage.phone, 10, now);
  assert.throws(() => chargeUsage(usage.project, usage.phone, 10, now));
  assert.equal(chargeUsage(usage.project, usage.phone, 10, now + 60_000).phone.calls, 1);
});
