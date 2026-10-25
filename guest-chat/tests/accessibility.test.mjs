import { test } from 'node:test';
import assert from 'node:assert/strict';
import { freshPresence, knownTranslation, receiptLabel } from '../src/accessibility.mjs';
test('unknown sentences retain original text; placeholder translations are rejected', () => {
  const labels = { HELLO: { ml: 'നമസ്കാരം', kn: '<ctrl42>' } };
  assert.equal(knownTranslation(' hello ', 'ml', labels), 'നമസ്കാരം');
  assert.equal(knownTranslation('hello', 'kn', labels), '');
  assert.equal(knownTranslation('hello', 'en', labels), '');
  assert.equal(knownTranslation('hello my friend', 'ml', labels), '');
});
test('stale or implausibly future captions cannot keep showing after a disconnect', () => {
  const presence = { caption: 'Live', updatedAt: { toMillis: () => 10_000 } };
  assert.equal(freshPresence(presence, 17_999), presence);
  assert.equal(freshPresence(presence, 18_000), undefined);
  assert.equal(freshPresence(presence, -30_001), undefined);
  assert.equal(freshPresence(undefined, 20_000), undefined);
});
test('pending writes never claim delivery and receipts advance the status', () => {
  assert.equal(receiptLabel(true, { readAt: {} }), 'Sending…');
  assert.equal(receiptLabel(false, undefined), 'Sent');
  assert.equal(receiptLabel(false, { deliveredAt: {} }), 'Delivered');
  assert.equal(receiptLabel(false, { deliveredAt: {}, readAt: {} }), 'Read');
});
