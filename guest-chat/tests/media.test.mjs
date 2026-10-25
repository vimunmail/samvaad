import { test } from 'node:test';
import assert from 'node:assert/strict';
import { findVideo, safeImagePath, inviteToken } from '../src/media.mjs';
const videos = [{ phrases: ['Thank you', 'Thanks'], signLanguage: 'ISL', path: 'videos/thank-you.mp4' }];
test('reviewed phrase aliases match, arbitrary sentences do not', () => {
  assert.equal(findVideo('  THANK   YOU! ', videos), videos[0]);
  assert.equal(findVideo('Thanks', videos), videos[0]);
  assert.equal(findVideo('Thank you for the medicine', videos), undefined);
  assert.equal(findVideo('Thanks', videos, 'ASL'), undefined);
});
test('missing or unsafe videos fall back to text', () => {
  assert.equal(findVideo('Thanks', []), undefined);
  assert.equal(findVideo('Thanks', [{ ...videos[0], path: 'videos/../private.mp4' }]), undefined);
});
test('only catalogued local images can be rendered', () => {
  const catalogue = [{ path: 'images/emergency/NEED HELP.png' }];
  assert.equal(safeImagePath(catalogue[0].path, catalogue), '/images/emergency/NEED%20HELP.png');
  assert.equal(safeImagePath('https://tracker.example/a.png', catalogue), null);
});
test('invitation parser rejects malformed and short tokens', () => {
  assert.equal(inviteToken('#invite=' + 'a'.repeat(64)), 'a'.repeat(64));
  assert.equal(inviteToken('#invite=1234'), null);
  assert.equal(inviteToken('#room=123'), null);
});
