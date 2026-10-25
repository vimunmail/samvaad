// Local-only host simulator for testing the real browser flow. Never production.
import { initializeApp } from 'firebase/app';
import { getAuth, connectAuthEmulator, signInAnonymously } from 'firebase/auth';
import { getFirestore, connectFirestoreEmulator, doc, collection, writeBatch, onSnapshot, serverTimestamp, Timestamp, addDoc, setDoc, updateDoc, runTransaction, arrayRemove } from 'firebase/firestore';
import { createInterface } from 'node:readline';
import { randomBytes } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
const app = initializeApp({ projectId: 'demo-gestalk', apiKey: 'demo-api-key', appId: 'demo-app' });
const auth = getAuth(app);
connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
const db = getFirestore(app);
connectFirestoreEmulator(db, '127.0.0.1', 8080);
const { user } = await signInAnonymously(auth);
const room = doc(collection(db, 'rooms'));
const token = randomBytes(32).toString('hex');
const batch = writeBatch(db);
batch.set(room, { hostUid: user.uid, guestUid: null, guestUids: [], status: 'waiting', createdAt: serverTimestamp(), expiresAt: Timestamp.fromMillis(Date.now() + 7_200_000) });
batch.set(doc(db, 'invites', token), { roomId: room.id, expiresAt: Timestamp.fromMillis(Date.now() + 600_000) });
await batch.commit();
await mkdir('.firebase', { recursive: true });
await writeFile('.firebase/demo-session.json', JSON.stringify({ token, roomId: room.id }));
console.log('Local test invitation prepared.');
const seenRequests = new Set();
onSnapshot(collection(room, 'requests'), async snapshot => {
  for (const request of snapshot.docs) {
    if (request.data().status !== 'pending' || seenRequests.has(request.id)) continue;
    seenRequests.add(request.id);
    await new Promise(resolve => setTimeout(resolve, 3000));
    await runTransaction(db, async transaction => {
      const current = await transaction.get(room);
      const pending = await transaction.get(request.ref);
      const guests = current.data().guestUids;
      if (pending.data().status !== 'pending' || guests.length >= 8) return;
      transaction.update(room, { guestUid: request.id, guestUids: [...guests, request.id], status: 'active' });
      transaction.update(request.ref, { status: 'approved' });
    });
    console.log('Guest approved.');
    await addDoc(collection(room, 'messages'), { senderUid: user.uid, kind: 'sign', text: 'Hello', translation: '', imageAsset: '', createdAt: serverTimestamp(), clientTime: Date.now() });
  }
});
const seenMessages = new Set();
onSnapshot(collection(room, 'messages'), async snapshot => {
  for (const message of snapshot.docs) {
    if (message.data().senderUid === user.uid || seenMessages.has(message.id)) continue;
    seenMessages.add(message.id);
    console.log(`Guest ${message.data().kind} received: ${message.data().text}`);
    const receipt = doc(room, 'receipts', `${message.id}_${user.uid}`);
    await setDoc(receipt, { messageId: message.id, receiverUid: user.uid, deliveredAt: serverTimestamp(), readAt: null });
    setTimeout(() => updateDoc(receipt, { readAt: serverTimestamp() }).catch(() => {}), 1_500);
    if (message.data().text === 'Hello from the guest') await addDoc(collection(room, 'messages'), {
      senderUid: user.uid, kind: 'sign', text: 'Yes, I can read your message.', translation: '', imageAsset: '', createdAt: serverTimestamp(), clientTime: Date.now()
    });
  }
});
onSnapshot(collection(room, 'participants'), snapshot => {
  for (const participant of snapshot.docs.filter(d => d.id !== user.uid)) {
    console.log(`Guest presence: ${participant.data().language}, typing=${participant.data().typing}, caption=${!!participant.data().caption}`);
  }
});
onSnapshot(collection(room, 'receipts'), snapshot => {
  for (const receipt of snapshot.docs.filter(d => d.data().receiverUid !== user.uid)) console.log(`Host message ${receipt.data().readAt ? 'read' : 'delivered'}.`);
});
// Optional synthetic host controls for browser QA, using this emulator client only.
createInterface({ input: process.stdin }).on('line', async line => {
  try {
    const command = JSON.parse(line);
    if (command.type === 'presence') await setDoc(doc(room, 'participants', user.uid), {
      language: command.language ?? 'en', typing: !!command.typing, caption: (command.caption ?? '').slice(0, 2000), updatedAt: serverTimestamp()
    });
    else if (command.type === 'message') await addDoc(collection(room, 'messages'), {
      senderUid: user.uid, kind: command.kind ?? 'text', text: command.text, translation: '', imageAsset: '', createdAt: serverTimestamp(), clientTime: Date.now()
    });
    else if (command.type === 'remove') {
      const removal = writeBatch(db);
      removal.update(room, { guestUids: arrayRemove(command.uid) });
      removal.update(doc(room, 'requests', command.uid), { status: 'removed' });
      await removal.commit(); console.log('Guest removed.');
    }
    else if (command.type === 'close') { await updateDoc(room, { status: 'closed' }); console.log('Local conversation closed.'); }
  } catch { console.log('Invalid local QA command.'); }
});
process.on('SIGINT', async () => {
  const closing = writeBatch(db); closing.update(room, { status: 'closed' });
  await closing.commit(); process.exit(0);
});
