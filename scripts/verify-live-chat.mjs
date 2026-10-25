import { readFile } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import assert from 'node:assert/strict';
import { initializeApp, deleteApp } from 'firebase/app';
import { getAuth, inMemoryPersistence, setPersistence, signInAnonymously } from 'firebase/auth';
import { getFirestore, doc, collection, getDoc, getDocs, writeBatch, setDoc, updateDoc, serverTimestamp, Timestamp, terminate, arrayRemove } from 'firebase/firestore';
import { outgoingText } from '../guest-chat/src/messageText.mjs';

// Opt-in smoke test: creates one temporary live room, then closes it.
// No personal transcripts, admin credentials, or invitation tokens are logged.
const config = JSON.parse(await readFile(new URL('../app/src/main/assets/firebase-config.json', import.meta.url), 'utf8'));
const phoneticData = JSON.parse(await readFile(new URL('../app/src/main/assets/translations/phonetic-input.json', import.meta.url), 'utf8'));
if (process.argv[2] !== config.projectId) throw new Error('Pass the configured project ID to explicitly run the live smoke test.');
const clients = [];
let room;
const timeout = setTimeout(() => { console.error('Live chat verification timed out.'); process.exit(1); }, 90_000);
async function client(label) {
  const app = initializeApp(config, `verification-${label}-${Date.now()}`);
  const auth = getAuth(app);
  await setPersistence(auth, inMemoryPersistence);
  const { user } = await signInAnonymously(auth);
  const db = getFirestore(app);
  clients.push({ app, db });
  return { db, uid: user.uid };
}
async function denied(operation) {
  await assert.rejects(operation, error => error.code === 'permission-denied');
}
try {
  const host = await client('host');
  const guest = await client('guest');
  const secondGuest = await client('second-guest');
  const token = randomBytes(32).toString('hex');
  room = doc(collection(host.db, 'rooms'));
  const batch = writeBatch(host.db);
  batch.set(room, { hostUid: host.uid, guestUid: null, guestUids: [], status: 'waiting', createdAt: serverTimestamp(), expiresAt: Timestamp.fromMillis(Date.now() + 7_200_000) });
  batch.set(doc(host.db, 'invites', token), { roomId: room.id, expiresAt: Timestamp.fromMillis(Date.now() + 600_000) });
  await batch.commit();
  console.log('PASS: Android-style room and QR invitation creation');
  assert.equal((await getDoc(doc(guest.db, 'invites', token))).data().roomId, room.id);
  const guestRoom = doc(guest.db, 'rooms', room.id);
  await denied(getDoc(guestRoom));
  await denied(getDocs(collection(guestRoom, 'messages')));
  await denied(getDocs(collection(guestRoom, 'participants')));
  const request = doc(guestRoom, 'requests', guest.uid);
  await setDoc(request, { name: 'QR connection verification', token, status: 'pending', createdAt: serverTimestamp() });
  assert.equal((await getDocs(collection(room, 'requests'))).size, 1);
  console.log('PASS: Guest can request access; unapproved messages remain private');
  const approval = writeBatch(host.db);
  approval.update(room, { guestUid: guest.uid, guestUids: [guest.uid], status: 'active' });
  approval.update(doc(room, 'requests', guest.uid), { status: 'approved' });
  await approval.commit();
  assert.equal((await getDoc(doc(secondGuest.db, 'invites', token))).data().roomId, room.id);
  await setDoc(doc(secondGuest.db, 'rooms', room.id, 'requests', secondGuest.uid), { name: 'Second synthetic guest', token, status: 'pending', createdAt: serverTimestamp() });
  const secondApproval = writeBatch(host.db);
  secondApproval.update(room, { guestUid: secondGuest.uid, guestUids: [guest.uid, secondGuest.uid], status: 'active' });
  secondApproval.update(doc(room, 'requests', secondGuest.uid), { status: 'approved' });
  await secondApproval.commit();
  assert.equal((await getDoc(doc(secondGuest.db, 'rooms', room.id))).data().guestUids.length, 2);
  console.log('PASS: Two independent guests join the same QR group with host approval');
  const malayalam = outgoingText('sughamano? enikku vellam venam.', 'ml', true, phoneticData);
  assert.equal(malayalam, 'സുഖമാണോ? എനിക്ക് വെള്ളം വേണം.');
  for (const [person, text, kind] of [[host, 'Hello', 'sign'], [guest, malayalam, 'text']]) {
    await setDoc(doc(collection(person.db, 'rooms', room.id, 'messages')), { senderUid: person.uid, text, kind, translation: '', imageAsset: '', createdAt: serverTimestamp(), clientTime: Date.now() });
  }
  assert.equal((await getDocs(collection(guestRoom, 'messages'))).size, 2);
  assert.equal((await getDocs(collection(room, 'messages'))).size, 2);
  assert.ok((await getDocs(collection(room, 'messages'))).docs.some(message => message.data().text === malayalam));
  assert.ok((await getDocs(collection(secondGuest.db, 'rooms', room.id, 'messages'))).docs.some(message => message.data().text === malayalam));
  await setDoc(doc(collection(secondGuest.db, 'rooms', room.id, 'messages')), { senderUid: secondGuest.uid, senderName: 'Second synthetic guest', text: 'Second guest can join the conversation', kind: 'text', translation: '', imageAsset: '', createdAt: serverTimestamp(), clientTime: Date.now() });
  assert.equal((await getDocs(collection(guestRoom, 'messages'))).size, 3);
  console.log('PASS: Guest Manglish is converted locally and received as Malayalam');
  await setDoc(doc(collection(guestRoom, 'messages')), { senderUid: guest.uid, text: 'A', kind: 'image', translation: '', imageAsset: 'images/alphabets/A.png', createdAt: serverTimestamp(), clientTime: Date.now() });
  console.log('PASS: Approved two-way sign/text messages and sign picture');
  const ownMessage = (await getDocs(collection(room, 'messages'))).docs.find(d => d.data().senderUid === host.uid);
  const receipt = doc(guestRoom, 'receipts', `${ownMessage.id}_${guest.uid}`);
  await setDoc(receipt, { messageId: ownMessage.id, receiverUid: guest.uid, deliveredAt: serverTimestamp(), readAt: null });
  assert.equal((await getDoc(doc(room, 'receipts', receipt.id))).data().readAt, null);
  await updateDoc(receipt, { readAt: serverTimestamp() });
  assert.ok((await getDoc(doc(room, 'receipts', receipt.id))).data().readAt);
  await denied(updateDoc(doc(room, 'receipts', receipt.id), { readAt: null }));
  const secondReceipt = doc(secondGuest.db, 'rooms', room.id, 'receipts', `${ownMessage.id}_${secondGuest.uid}`);
  await setDoc(secondReceipt, { messageId: ownMessage.id, receiverUid: secondGuest.uid, deliveredAt: serverTimestamp(), readAt: serverTimestamp() });
  assert.equal((await getDocs(collection(room, 'receipts'))).size, 2);
  await denied(updateDoc(receipt, { receiverUid: secondGuest.uid }));
  console.log('PASS: Each listener acknowledges the glove message independently');
  for (const person of [host, guest]) await setDoc(doc(person.db, 'rooms', room.id, 'participants', person.uid), {
    language: person === host ? 'en' : 'ml', typing: true, caption: 'Synthetic live caption', updatedAt: serverTimestamp()
  });
  assert.equal((await getDocs(collection(guestRoom, 'participants'))).size, 2);
  await setDoc(doc(collection(guestRoom, 'messages')), { senderUid: guest.uid, text: 'Synthetic speech transcript', kind: 'speech', translation: '', imageAsset: '', createdAt: serverTimestamp(), clientTime: Date.now() });
  console.log('PASS: Live captions, language preferences, speech transcripts and receiver-only delivered/read receipts');
  const removal = writeBatch(host.db);
  removal.update(room, { guestUids: arrayRemove(secondGuest.uid) });
  removal.update(doc(room, 'requests', secondGuest.uid), { status: 'removed' });
  await removal.commit();
  await denied(getDocs(collection(secondGuest.db, 'rooms', room.id, 'messages')));
  assert.ok((await getDocs(collection(guestRoom, 'messages'))).size > 0);
  console.log('PASS: Removing one guest revokes their access while the remaining guest stays connected');
  await updateDoc(room, { status: 'closed' });
  await denied(getDocs(collection(guestRoom, 'messages')));
  await denied(getDocs(collection(guestRoom, 'participants')));
  await denied(getDocs(collection(guestRoom, 'receipts')));
  console.log('PASS: Ending the conversation revokes transcript access');
} catch (error) {
  console.error(`Live chat verification failed: ${error.code ?? error.message}`);
  process.exitCode = 1;
} finally {
  if (room) await updateDoc(room, { status: 'closed' }).catch(() => {});
  await Promise.all(clients.map(async ({ app, db }) => { await terminate(db); await deleteApp(app); }));
  clearTimeout(timeout);
}
