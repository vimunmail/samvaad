import { useEffect, useRef, useState } from 'react';
import { collection, doc, limitToLast, onSnapshot, orderBy, query, runTransaction, serverTimestamp, setDoc } from 'firebase/firestore';
import type { Firestore, Timestamp } from 'firebase/firestore';
import { freshPresence, languages, summarizeReceipts } from './accessibility.mjs';

type Presence = { id: string; language: string; typing: boolean; caption: string; updatedAt?: Timestamp };
export type Receipt = { deliveredAt?: Timestamp; readAt?: Timestamp | null; deliveredCount: number; readCount: number };
type ReceivedMessage = { id: string; senderUid: string; pending?: boolean };
type Recognition = {
  lang: string; continuous: boolean; interimResults: boolean; start(): void; stop(): void; abort(): void;
  onresult: ((event: { results: ArrayLike<{ isFinal: boolean; 0: { transcript: string } }> }) => void) | null;
  onerror: ((event: { error: string }) => void) | null; onend: (() => void) | null;
};
type RecognitionConstructor = new () => Recognition;
const speechWindow = window as typeof window & { SpeechRecognition?: RecognitionConstructor; webkitSpeechRecognition?: RecognitionConstructor };
const SpeechRecognition = speechWindow.SpeechRecognition ?? speechWindow.webkitSpeechRecognition;

export function useChatAccess(db: Firestore | undefined, uid: string | undefined, roomId: string,
  active: boolean, draft: string, messages: ReceivedMessage[], sendSpeech: (text: string) => void, volume: number) {
  const [language, setLanguageState] = useState(() => {
    try { const saved = localStorage.getItem('gestalk-language'); return languages.some(l => l[0] === saved) ? saved! : 'en'; } catch { return 'en'; }
  });
  const [vibrate, setVibrateState] = useState(() => { try { return localStorage.getItem('gestalk-vibrate') !== 'off'; } catch { return true; } });
  const [peers, setPeers] = useState<Presence[]>([]);
  const [receipts, setReceipts] = useState<Record<string, Receipt>>({});
  const [caption, setCaption] = useState('');
  const [listening, setListening] = useState(false);
  const [speaking, setSpeaking] = useState(false);
  const [info, setInfo] = useState('');
  useEffect(() => {
    if (volume > 0) setInfo(value => value.startsWith('Guest audio is muted.') ? '' : value);
  }, [volume]);
  const [visible, setVisible] = useState(document.visibilityState === 'visible');
  const visibleRef = useRef(visible); visibleRef.current = visible;
  const [receiptRevision, setReceiptRevision] = useState(0);
  const [clock, setClock] = useState(Date.now());
  const recognition = useRef<Recognition | null>(null);
  const finalSpeech = useRef(sendSpeech); finalSpeech.current = sendSpeech;
  const currentPresence = useRef({ language, typing: !!draft.trim(), caption });
  currentPresence.current = { language, typing: !!draft.trim(), caption };
  const lastTyping = useRef(Date.now());
  useEffect(() => { lastTyping.current = Date.now(); }, [draft]);
  const seen = useRef<Set<string> | undefined>(undefined);
  const acknowledged = useRef(new Map<string, boolean>());
  const inFlight = useRef(new Set<string>());
  function abortMicrophone() {
    const current = recognition.current;
    if (current) { current.onresult = null; current.onerror = null; current.onend = null; current.abort(); }
    recognition.current = null; setListening(false); setCaption('');
  }

  useEffect(() => {
    const change = () => {
      const shown = document.visibilityState === 'visible'; setVisible(shown); setClock(Date.now());
      if (!shown) { abortMicrophone(); window.speechSynthesis?.cancel(); setSpeaking(false); }
    };
    document.addEventListener('visibilitychange', change);
    const ticker = setInterval(() => setClock(Date.now()), 1_000);
    return () => { document.removeEventListener('visibilitychange', change); clearInterval(ticker); };
  }, []);
  useEffect(() => {
    if (!active || !db || !uid) return;
    const participants = onSnapshot(collection(db, 'rooms', roomId, 'participants'), snapshot => {
      setPeers(snapshot.docs.filter(d => d.id !== uid).map(d => ({ ...d.data(), id: d.id } as Presence)));
    }, () => setInfo('Live typing and captions are temporarily unavailable.'));
    const receiptListener = onSnapshot(query(collection(db, 'rooms', roomId, 'receipts'), orderBy('deliveredAt'), limitToLast(1800)), snapshot => {
      setReceipts(current => ({ ...current, ...summarizeReceipts(snapshot.docs.map(d => ({ ...d.data(), id: d.id }))) }));
    }, () => setInfo('Delivery updates are temporarily unavailable.'));
    return () => { participants(); receiptListener(); setPeers([]); setReceipts({}); };
  }, [active, db, uid, roomId]);

  // A heartbeat while composing keeps the status alive; pauses disappear after three seconds.
  useEffect(() => {
    if (!active || !db || !uid) return;
    let stopped = false, timer: ReturnType<typeof setTimeout> | undefined, lastWrite = 0, previous = '';
    const write = (force = false) => {
      if (stopped) return;
      const now = Date.now();
      const value = { ...currentPresence.current, typing: visible && currentPresence.current.typing && now - lastTyping.current < 3_000,
        caption: visible ? currentPresence.current.caption : '' };
      const key = JSON.stringify(value);
      if (!force && key === previous && !value.typing && !value.caption) return;
      if (now - lastWrite < 1_000) { clearTimeout(timer); timer = setTimeout(() => write(), 1_000 - (now - lastWrite)); return; }
      lastWrite = now; previous = key;
      void setDoc(doc(db, 'rooms', roomId, 'participants', uid), { ...value, updatedAt: serverTimestamp() })
        .catch(() => { if (!stopped) setInfo('Live captions are not confirmed. Final messages can still be sent.'); });
    };
    write(); const heartbeat = setInterval(() => write(), 1_500);
    return () => { stopped = true; clearInterval(heartbeat); clearTimeout(timer);
      void setDoc(doc(db, 'rooms', roomId, 'participants', uid), { language: currentPresence.current.language, typing: false, caption: '', updatedAt: serverTimestamp() }).catch(() => {});
    };
  }, [active, db, uid, roomId, visible]);
  // Current presence is read by the rate-limited heartbeat above.

  useEffect(() => { seen.current = undefined; acknowledged.current.clear(); inFlight.current.clear(); }, [roomId]);
  useEffect(() => {
    if (!active || !db || !uid) return;
    const incoming = messages.filter(m => m.senderUid !== uid && !m.pending);
    if (seen.current && incoming.some(m => !seen.current!.has(m.id)) && vibrate && visible) navigator.vibrate?.([120, 60, 120]);
    seen.current = new Set(incoming.map(m => m.id));
    incoming.forEach(message => {
      if (inFlight.current.has(message.id) || acknowledged.current.get(message.id) === true || (!visible && acknowledged.current.has(message.id))) return;
      inFlight.current.add(message.id);
      const read = visible;
      const reference = doc(db, 'rooms', roomId, 'receipts', `${message.id}_${uid}`);
      void runTransaction(db, async transaction => {
        const existing = await transaction.get(reference);
        if (!existing.exists()) transaction.set(reference, { messageId: message.id, receiverUid: uid, deliveredAt: serverTimestamp(), readAt: read ? serverTimestamp() : null });
        else if (read && !existing.data().readAt) transaction.update(reference, { readAt: serverTimestamp() });
      }).then(() => {
        acknowledged.current.set(message.id, read);
      }).catch(() => setInfo('Some delivery updates are waiting for a connection.'))
        .finally(() => { inFlight.current.delete(message.id);
          if (!read && visibleRef.current && acknowledged.current.has(message.id)) setReceiptRevision(value => value + 1);
        });
    });
  }, [active, db, uid, roomId, messages, visible, vibrate, receiptRevision]);
  useEffect(() => {
    if (active) { if (vibrate) navigator.vibrate?.(100); }
    else { abortMicrophone(); window.speechSynthesis?.cancel(); setSpeaking(false); }
    return () => { abortMicrophone(); window.speechSynthesis?.cancel(); };
  }, [active]);

  function setLanguage(value: string) {
    setInfo('');
    abortMicrophone(); window.speechSynthesis?.cancel(); setSpeaking(false);
    setLanguageState(value); try { localStorage.setItem('gestalk-language', value); } catch { /* Preferences are optional. */ }
  }
  function setVibrate(value: boolean) {
    setVibrateState(value); try { localStorage.setItem('gestalk-vibrate', value ? 'on' : 'off'); } catch { /* Preferences are optional. */ }
    if (value) navigator.vibrate?.(100);
  }
  function speak(text: string) {
    setInfo('');
    if (volume <= 0) { setInfo('Guest audio is muted. Raise Audio volume in Listening language to hear messages.'); return; }
    if (!('speechSynthesis' in window)) { setInfo('Voice playback is unavailable in this browser.'); return; }
    abortMicrophone(); window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.volume = volume;
    utterance.lang = languages.find(l => l[0] === language)?.[2] ?? 'en-US';
    utterance.voice = window.speechSynthesis.getVoices().find(v => v.lang.toLowerCase() === utterance.lang.toLowerCase()) ?? null;
    utterance.onend = () => setSpeaking(false);
    utterance.onerror = () => { setSpeaking(false); setInfo('This voice is unavailable. Choose another language or install its voice on your phone.'); };
    setSpeaking(true); window.speechSynthesis.speak(utterance);
  }
  function toggleMic() {
    if (listening) { recognition.current?.stop(); return; }
    if (!SpeechRecognition) { setInfo('Speech input is unavailable in this browser. You can still type.'); return; }
    window.speechSynthesis?.cancel(); setSpeaking(false); setInfo('Listening… Your browser may use its speech service.');
    const recognizer = new SpeechRecognition(); recognition.current = recognizer;
    recognizer.lang = languages.find(l => l[0] === language)?.[2] ?? 'en-US';
    recognizer.continuous = false; recognizer.interimResults = true;
    let completed = false, speechFailed = false;
    recognizer.onresult = event => {
      let interim = '', final = '';
      for (const result of Array.from(event.results)) {
        if (result.isFinal) final += result[0].transcript + ' '; else interim += result[0].transcript + ' ';
      }
      setCaption(interim.trim().slice(0, 2000));
      if (final.trim() && !completed) { completed = true; finalSpeech.current(final.trim().slice(0, 2000)); setCaption(''); recognizer.stop(); }
    };
    recognizer.onerror = event => { speechFailed = true; setListening(false); setCaption(''); setInfo(event.error === 'not-allowed' ? 'Microphone access was declined. Allow it in your browser settings to use speech.' : `Speech input stopped (${event.error}). Try again or type your message.`); };
    recognizer.onend = () => { setListening(false); setCaption(''); if (completed) setInfo('Voice text captured. Check its sending status in the conversation.');
      else if (!speechFailed) setInfo('Speech stopped. Tap the microphone to speak again.'); };
    try { recognizer.start(); setListening(true); } catch { abortMicrophone(); setInfo('Could not start the microphone. Try again.'); }
  }
  return { language, setLanguage, vibrate, setVibrate, peers: peers.filter(peer => freshPresence(peer, clock)), receipts, visible,
    caption, listening, speaking, speak, stopSpeaking: () => { window.speechSynthesis?.cancel(); setSpeaking(false); },
    toggleMic, abortMicrophone, speechSupported: !!SpeechRecognition, voiceSupported: 'speechSynthesis' in window,
    vibrationSupported: typeof navigator.vibrate === 'function', info };
}
