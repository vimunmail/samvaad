import { before, after, beforeEach, test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, collection, getDoc, getDocs, setDoc, updateDoc, deleteDoc, writeBatch, Timestamp, serverTimestamp, arrayUnion, arrayRemove } from 'firebase/firestore';
let environment;
const token = 'a'.repeat(64);
const now = () => Date.now();
const room = (hostUid = 'host') => ({ hostUid, guestUid: null, status: 'waiting', createdAt: serverTimestamp(), expiresAt: Timestamp.fromMillis(now() + 7_200_000) });
const message = (uid = 'host', overrides = {}) => ({ senderUid: uid, kind: 'text', text: 'Hello', translation: '', imageAsset: '', createdAt: serverTimestamp(), clientTime: now(), ...overrides });
const database = uid => uid ? environment.authenticatedContext(uid).firestore() : environment.unauthenticatedContext().firestore();
before(async () => {
  environment = await initializeTestEnvironment({ projectId: 'demo-gestalk', firestore: { rules: await readFile('firebase/firestore.rules', 'utf8') } });
});
after(async () => { await environment.cleanup(); });
beforeEach(async () => {
  await environment.clearFirestore();
  const host = database('host');
  const batch = writeBatch(host);
  batch.set(doc(host, 'rooms/r1'), room());
  batch.set(doc(host, 'invites', token), { roomId: 'r1', expiresAt: Timestamp.fromMillis(now() + 600_000) });
  await batch.commit();
});
async function request(uid = 'guest') {
  const guest = database(uid);
  return setDoc(doc(guest, 'rooms/r1/requests', uid), { name: 'Guest', token, status: 'pending', createdAt: serverTimestamp() });
}
async function approve(uid = 'guest') {
  const host = database('host');
  const batch = writeBatch(host);
  batch.update(doc(host, 'rooms/r1'), { guestUid: uid, status: 'active' });
  batch.update(doc(host, 'rooms/r1/requests', uid), { status: 'approved' });
  return batch.commit();
}
async function groupRoom() {
  const host = database('host');
  const batch = writeBatch(host);
  batch.set(doc(host, 'rooms/group'), { ...room(), guestUids: [] });
  batch.set(doc(host, 'invites', 'c'.repeat(64)), { roomId: 'group', expiresAt: Timestamp.fromMillis(now() + 600_000) });
  await batch.commit();
}
async function groupRequest(uid) {
  return setDoc(doc(database(uid), 'rooms/group/requests', uid), { name: uid, token: 'c'.repeat(64), status: 'pending', createdAt: serverTimestamp() });
}
async function groupApprove(uid) {
  const host = database('host'), batch = writeBatch(host);
  batch.update(doc(host, 'rooms/group'), { guestUid: uid, guestUids: arrayUnion(uid), status: 'active' });
  batch.update(doc(host, 'rooms/group/requests', uid), { status: 'approved' });
  return batch.commit();
}
test('group admits several approved guests and every member sees the same conversation', async () => {
  await groupRoom();
  for (const uid of ['alice', 'bob', 'cara']) { await assertSucceeds(groupRequest(uid)); await assertSucceeds(groupApprove(uid)); }
  for (const uid of ['host', 'alice', 'bob', 'cara']) {
    await assertSucceeds(setDoc(doc(database(uid), 'rooms/group/messages', uid), message(uid, { senderName: uid === 'host' ? 'Gestalk' : uid })));
    await assertSucceeds(getDocs(collection(database(uid), 'rooms/group/messages')));
  }
  await groupRequest('pending');
  await assertFails(getDoc(doc(database('pending'), 'rooms/group')));
  await assertFails(getDocs(collection(database('pending'), 'rooms/group/messages')));
  await assertFails(setDoc(doc(database('alice'), 'rooms/group/messages/spoof'), message('alice', { senderName: 'bob' })));
});
test('group approvals are atomic, host-only and stop at eight guests', async () => {
  await groupRoom(); await groupRequest('alice');
  await assertFails(updateDoc(doc(database('host'), 'rooms/group'), { guestUid: 'alice', guestUids: ['alice'], status: 'active' }));
  await assertFails(updateDoc(doc(database('host'), 'rooms/group/requests/alice'), { status: 'approved' }));
  await assertFails(updateDoc(doc(database('alice'), 'rooms/group'), { guestUid: 'alice', guestUids: ['alice'], status: 'active' }));
  await groupApprove('alice');
  await groupRequest('overflow');
  for (let i = 1; i <= 7; i++) { await groupRequest(`guest${i}`); await groupApprove(`guest${i}`); }
  await assertFails(groupApprove('overflow'));
  await assertFails(groupRequest('ninth'));
});
test('each group receiver can acknowledge independently but cannot alter another receipt', async () => {
  await groupRoom();
  for (const uid of ['alice', 'bob']) { await groupRequest(uid); await groupApprove(uid); }
  await setDoc(doc(database('host'), 'rooms/group/messages/sign'), message('host', { kind: 'sign' }));
  for (const uid of ['alice', 'bob']) await assertSucceeds(setDoc(doc(database(uid), `rooms/group/receipts/sign_${uid}`), {
    messageId: 'sign', receiverUid: uid, deliveredAt: serverTimestamp(), readAt: serverTimestamp()
  }));
  await assertFails(updateDoc(doc(database('alice'), 'rooms/group/receipts/sign_bob'), { readAt: serverTimestamp() }));
  await assertFails(setDoc(doc(database('alice'), 'rooms/group/receipts/sign'), { receiverUid: 'alice', deliveredAt: serverTimestamp(), readAt: null }));
  await assertFails(setDoc(doc(database('host'), 'rooms/group/receipts/sign_host'), { messageId: 'sign', receiverUid: 'host', deliveredAt: serverTimestamp(), readAt: null }));
});
test('removing a group guest immediately revokes their transcript and send access', async () => {
  await groupRoom();
  for (const uid of ['alice', 'bob']) { await groupRequest(uid); await groupApprove(uid); }
  const host = database('host'), batch = writeBatch(host);
  batch.update(doc(host, 'rooms/group'), { guestUids: arrayRemove('alice') });
  batch.update(doc(host, 'rooms/group/requests/alice'), { status: 'removed' });
  await assertSucceeds(batch.commit());
  await assertFails(getDocs(collection(database('alice'), 'rooms/group/messages')));
  await assertFails(setDoc(doc(database('alice'), 'rooms/group/messages/after'), message('alice')));
  await assertSucceeds(getDocs(collection(database('bob'), 'rooms/group/messages')));
  await assertFails(groupApprove('alice'));
});
test('host can refresh a group QR and a pending guest can renew only their invite token', async () => {
  await groupRoom(); await groupRequest('alice'); await groupApprove('alice');
  const host = database('host'), fresh = 'd'.repeat(64);
  await assertSucceeds(setDoc(doc(host, 'invites', fresh), { roomId: 'group', expiresAt: Timestamp.fromMillis(now() + 600_000) }));
  await groupRequest('bob');
  await assertSucceeds(updateDoc(doc(database('bob'), 'rooms/group/requests/bob'), { token: fresh }));
  await assertFails(updateDoc(doc(database('bob'), 'rooms/group/requests/bob'), { status: 'approved' }));
  await assertFails(updateDoc(doc(database('bob'), 'rooms/group/requests/bob'), { name: 'alice' }));
  await assertSucceeds(groupApprove('bob'));
});
test('anonymous and unrelated users cannot read rooms or messages', async () => {
  await assertFails(getDoc(doc(database(), 'rooms/r1')));
  await assertFails(getDoc(doc(database('stranger'), 'rooms/r1')));
  await assertFails(getDocs(collection(database('stranger'), 'rooms/r1/messages')));
  await assertFails(getDocs(collection(database('host'), 'rooms')));
});

test('rotated invitations deny new joins and pending approvals but keep existing members connected', async () => {
  await groupRoom(); await groupRequest('alice'); await groupApprove('alice'); await groupRequest('pending');
  const host = database('host'), old = 'c'.repeat(64), fresh = 'f'.repeat(64), batch = writeBatch(host);
  batch.set(doc(host, 'invites', fresh), { roomId: 'group', expiresAt: Timestamp.fromMillis(now() + 600_000) });
  batch.update(doc(host, 'invites', old), { revoked: true });
  await assertSucceeds(batch.commit());
  await assertFails(getDoc(doc(database('stranger'), 'invites', old)));
  await assertFails(groupRequest('stranger'));
  await assertFails(groupApprove('pending'));
  await assertSucceeds(getDoc(doc(database('alice'), 'invites', old)));
  await assertSucceeds(getDocs(collection(database('alice'), 'rooms/group/messages')));
  await assertSucceeds(updateDoc(doc(database('pending'), 'rooms/group/requests/pending'), { token: fresh }));
  await assertSucceeds(groupApprove('pending'));
});

test('guests cannot revoke invitations and hosts cannot un-revoke or rewrite them', async () => {
  await assertFails(updateDoc(doc(database('guest'), 'invites', token), { revoked: true }));
  await assertFails(updateDoc(doc(database('host'), 'invites', token), { expiresAt: Timestamp.fromMillis(now() + 900_000) }));
  await assertSucceeds(updateDoc(doc(database('host'), 'invites', token), { revoked: true }));
  await assertFails(updateDoc(doc(database('host'), 'invites', token), { revoked: false }));
});

test('invitations cannot outlive the room or be created after it is closed', async () => {
  const host = database('host');
  await environment.withSecurityRulesDisabled(async context => updateDoc(doc(context.firestore(), 'rooms/r1'), { expiresAt: Timestamp.fromMillis(now() + 60_000) }));
  await assertFails(setDoc(doc(host, 'invites', 'd'.repeat(64)), { roomId: 'r1', expiresAt: Timestamp.fromMillis(now() + 600_000) }));
  await updateDoc(doc(host, 'rooms/r1'), { status: 'closed' });
  await assertFails(setDoc(doc(host, 'invites', 'e'.repeat(64)), { roomId: 'r1', expiresAt: Timestamp.fromMillis(now() + 30_000) }));
});

test('default-deny covers unknown collections and cross-room requests cannot grant access', async () => {
  await assertFails(setDoc(doc(database('host'), 'users/private'), { secret: 'private' }));
  await assertFails(getDoc(doc(database('host'), 'users/private')));
  await groupRoom(); await groupRequest('alice'); await groupApprove('alice');
  await assertFails(getDocs(collection(database('alice'), 'rooms/r1/messages')));
  await assertFails(setDoc(doc(database('alice'), 'rooms/r1/messages/m'), message('alice')));
  await assertFails(deleteDoc(doc(database('alice'), 'rooms/group')));
});
test('invitation permits requesting but not reading the transcript', async () => {
  const guest = database('guest');
  await assertSucceeds(getDoc(doc(guest, 'invites', token)));
  await assertSucceeds(request());
  await assertFails(getDocs(collection(guest, 'rooms/r1/messages')));
  await assertFails(getDocs(collection(guest, 'invites')));
});
test('invalid invitation and impersonated join requests fail', async () => {
  const guest = database('guest');
  await assertFails(setDoc(doc(guest, 'rooms/r1/requests/guest'), { name: 'Guest', token: 'b'.repeat(64), status: 'pending', createdAt: serverTimestamp() }));
  await assertFails(setDoc(doc(guest, 'rooms/r1/requests/other'), { name: 'Guest', token, status: 'pending', createdAt: serverTimestamp() }));
  await assertFails(setDoc(doc(guest, 'rooms/r1/requests/guest'), { name: 'Guest', token, status: 'approved', createdAt: serverTimestamp() }));
});
test('only host can approve and approval must be atomic', async () => {
  await request();
  await assertFails(updateDoc(doc(database('guest'), 'rooms/r1'), { guestUid: 'guest', status: 'active' }));
  await assertFails(updateDoc(doc(database('host'), 'rooms/r1'), { guestUid: 'guest', status: 'active' }));
  await assertFails(updateDoc(doc(database('host'), 'rooms/r1/requests/guest'), { status: 'approved' }));
  await assertSucceeds(approve());
});
test('approved guest can exchange text and known image paths', async () => {
  await request(); await approve();
  const guest = database('guest');
  await assertSucceeds(setDoc(doc(guest, 'rooms/r1/messages/m1'), message('guest')));
  await assertSucceeds(setDoc(doc(guest, 'rooms/r1/messages/m2'), message('guest', { kind: 'image', text: 'A', imageAsset: 'images/alphabets/A.png' })));
  await assertSucceeds(getDocs(collection(database('host'), 'rooms/r1/messages')));
  await assertSucceeds(getDocs(collection(guest, 'rooms/r1/messages')));
});
test('only host signs can attach bounded translations for guest listening languages', async () => {
  await request(); await approve();
  const host = database('host'), guest = database('guest');
  await assertSucceeds(setDoc(doc(host, 'rooms/r1/messages/s1'), message('host', {
    kind: 'sign', text: 'I need a doctor', translations: { ml: 'എനിക്ക് ഒരു ഡോക്ടറെ വേണം', hi: 'मुझे डॉक्टर चाहिए' }
  })));
  await assertSucceeds(getDoc(doc(guest, 'rooms/r1/messages/s1')));
  await assertSucceeds(setDoc(doc(host, 'rooms/r1/messages/legacy'), message('host', { kind: 'sign' })));
  await assertFails(setDoc(doc(guest, 'rooms/r1/messages/guest'), message('guest', { translations: { ml: 'മലയാളം' } })));
  await assertFails(setDoc(doc(host, 'rooms/r1/messages/text'), message('host', { translations: { ml: 'മലയാളം' } })));
  await assertFails(setDoc(doc(host, 'rooms/r1/messages/lang'), message('host', { kind: 'sign', translations: { zz: 'Hello' } })));
  await assertFails(setDoc(doc(host, 'rooms/r1/messages/size'), message('host', { kind: 'sign', translations: { ml: 'ക'.repeat(301) } })));
  await assertFails(setDoc(doc(host, 'rooms/r1/messages/type'), message('host', { kind: 'sign', translations: { ml: 7 } })));
});
test('sender spoofing, excessive text and arbitrary media URLs fail', async () => {
  await request(); await approve();
  const guest = database('guest');
  await assertFails(setDoc(doc(guest, 'rooms/r1/messages/m1'), message('host')));
  await assertFails(setDoc(doc(guest, 'rooms/r1/messages/m2'), message('guest', { text: 'x'.repeat(2001) })));
  await assertFails(setDoc(doc(guest, 'rooms/r1/messages/m3'), message('guest', { imageAsset: 'https://tracker.example/image.png' })));
  await assertFails(setDoc(doc(guest, 'rooms/r1/messages/m4'), message('guest', { imageAsset: 'images/../private.png' })));
  await assertFails(setDoc(doc(guest, 'rooms/r1/messages/m5'), message('guest', { kind: 'sign' })));
});
test('messages are immutable and server timestamps are required', async () => {
  const host = database('host');
  await assertSucceeds(setDoc(doc(host, 'rooms/r1/messages/m1'), message()));
  await assertFails(updateDoc(doc(host, 'rooms/r1/messages/m1'), { text: 'Changed' }));
  await assertFails(setDoc(doc(host, 'rooms/r1/messages/m2'), message('host', { createdAt: Timestamp.fromMillis(0) })));
});
test('unapproved requests stay private from other guests', async () => {
  await request();
  await assertFails(getDoc(doc(database('stranger'), 'rooms/r1/requests/guest')));
  await assertFails(getDocs(collection(database('guest'), 'rooms/r1/requests')));
  await assertSucceeds(getDoc(doc(database('guest'), 'rooms/r1/requests/guest')));
});
test('host cannot substitute a second guest or extend expiry', async () => {
  await request('guest'); await request('second'); await approve('guest');
  await assertFails(approve('second'));
  await assertFails(updateDoc(doc(database('host'), 'rooms/r1'), { expiresAt: Timestamp.fromMillis(now() + 9_000_000) }));
});
test('closing revokes message access and prevents reopening', async () => {
  await request(); await approve();
  await assertSucceeds(updateDoc(doc(database('host'), 'rooms/r1'), { status: 'closed' }));
  await assertSucceeds(getDoc(doc(database('guest'), 'rooms/r1')));
  await assertFails(setDoc(doc(database('guest'), 'rooms/r1/messages/m1'), message('guest')));
  await assertFails(getDocs(collection(database('guest'), 'rooms/r1/messages')));
  await assertFails(updateDoc(doc(database('host'), 'rooms/r1'), { status: 'active' }));
});
test('expired invitation blocks new join and approval', async () => {
  await request();
  await environment.withSecurityRulesDisabled(async context => updateDoc(doc(context.firestore(), 'invites', token), { expiresAt: Timestamp.fromMillis(0) }));
  await assertFails(request('second'));
  await assertFails(approve());
});
test('session expiry revokes access even without a host closing it', async () => {
  await request(); await approve();
  await environment.withSecurityRulesDisabled(async context => updateDoc(doc(context.firestore(), 'rooms/r1'), { expiresAt: Timestamp.fromMillis(0) }));
  await assertFails(getDocs(collection(database('guest'), 'rooms/r1/messages')));
  await assertFails(setDoc(doc(database('host'), 'rooms/r1/messages/m1'), message()));
});
test('the existing guest can reopen its invitation after invitation expiry', async () => {
  await request(); await approve();
  await environment.withSecurityRulesDisabled(async context => updateDoc(doc(context.firestore(), 'invites', token), { expiresAt: Timestamp.fromMillis(0) }));
  await assertSucceeds(getDoc(doc(database('guest'), 'invites', token)));
  await assertFails(getDoc(doc(database('second'), 'invites', token)));
});
test('room ownership cannot be forged, lifetime capped, other collections denied', async () => {
  const guest = database('guest');
  await assertFails(setDoc(doc(guest, 'rooms/forged'), room('host')));
  await assertFails(setDoc(doc(guest, 'rooms/long'), { ...room('guest'), expiresAt: Timestamp.fromMillis(now() + 86_400_000) }));
  await assertFails(setDoc(doc(guest, 'users/arbitrary'), { public: true }));
});

const presence = (overrides = {}) => ({ language: 'ml', typing: true, caption: 'Live words', updatedAt: serverTimestamp(), ...overrides });
const receipt = (uid = 'guest', read = false) => ({ receiverUid: uid, deliveredAt: serverTimestamp(), readAt: read ? serverTimestamp() : null });
test('only approved participants can read and publish their own live captions', async () => {
  const host = database('host'), guest = database('guest'), stranger = database('stranger');
  await assertSucceeds(setDoc(doc(host, 'rooms/r1/participants/host'), presence()));
  await assertFails(getDocs(collection(guest, 'rooms/r1/participants')));
  await assertFails(setDoc(doc(guest, 'rooms/r1/participants/guest'), presence()));
  await request(); await approve();
  await assertSucceeds(setDoc(doc(guest, 'rooms/r1/participants/guest'), presence()));
  await assertSucceeds(getDocs(collection(host, 'rooms/r1/participants')));
  await assertFails(setDoc(doc(guest, 'rooms/r1/participants/host'), presence()));
  await assertFails(getDocs(collection(stranger, 'rooms/r1/participants')));
  await assertFails(setDoc(doc(stranger, 'rooms/r1/participants/stranger'), presence()));
});
test('live state is bounded and cannot contain extra data or forged timestamps', async () => {
  const reference = doc(database('host'), 'rooms/r1/participants/host');
  for (const overrides of [{ caption: 'x'.repeat(2001) }, { typing: 'true' }, { language: 'xx' }, { updatedAt: Timestamp.fromMillis(0) }, { secret: 'extra' }]) {
    await assertFails(setDoc(reference, presence(overrides)));
  }
  await assertSucceeds(setDoc(reference, presence({ caption: '', typing: false })));
});
test('only the actual receiver can acknowledge an existing message', async () => {
  const host = database('host'), guest = database('guest');
  await setDoc(doc(host, 'rooms/r1/messages/m1'), message());
  await assertFails(setDoc(doc(guest, 'rooms/r1/receipts/m1'), receipt()));
  await request(); await approve();
  await assertFails(setDoc(doc(host, 'rooms/r1/receipts/m1'), receipt('host')));
  await assertFails(setDoc(doc(guest, 'rooms/r1/receipts/missing'), receipt()));
  await assertFails(setDoc(doc(guest, 'rooms/r1/receipts/m1'), receipt('host')));
  await assertSucceeds(setDoc(doc(guest, 'rooms/r1/receipts/m1'), receipt()));
  await assertSucceeds(getDocs(collection(host, 'rooms/r1/receipts')));
  await assertFails(getDocs(collection(database('stranger'), 'rooms/r1/receipts')));
});
test('delivery timestamps are immutable and read acknowledgement only advances once', async () => {
  await request(); await approve();
  const host = database('host'), guest = database('guest');
  await setDoc(doc(host, 'rooms/r1/messages/m1'), message());
  const reference = doc(guest, 'rooms/r1/receipts/m1');
  await assertFails(setDoc(reference, { ...receipt(), deliveredAt: Timestamp.fromMillis(0) }));
  await assertSucceeds(setDoc(reference, receipt()));
  await assertFails(updateDoc(reference, { deliveredAt: Timestamp.fromMillis(0) }));
  await assertFails(updateDoc(doc(host, 'rooms/r1/receipts/m1'), { readAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(reference, { readAt: serverTimestamp() }));
  await assertFails(updateDoc(reference, { readAt: null }));
  await assertFails(updateDoc(reference, { readAt: serverTimestamp() }));
});
test('speech transcripts work in both directions and closing revokes captions and receipts', async () => {
  await request(); await approve();
  const host = database('host'), guest = database('guest');
  await assertSucceeds(setDoc(doc(host, 'rooms/r1/messages/speech1'), message('host', { kind: 'speech' })));
  await assertSucceeds(setDoc(doc(guest, 'rooms/r1/messages/speech2'), message('guest', { kind: 'speech' })));
  await setDoc(doc(guest, 'rooms/r1/receipts/speech1'), receipt('guest', true));
  await setDoc(doc(host, 'rooms/r1/participants/host'), presence());
  await updateDoc(doc(host, 'rooms/r1'), { status: 'closed' });
  for (const name of ['participants', 'receipts']) await assertFails(getDocs(collection(guest, `rooms/r1/${name}`)));
  await assertFails(setDoc(doc(guest, 'rooms/r1/participants/guest'), presence()));
  await assertFails(setDoc(doc(host, 'rooms/r1/receipts/speech2'), receipt('host', true)));
});
