/// <reference types="vite/client" />
import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { initializeApp } from 'firebase/app';
import { browserSessionPersistence, connectAuthEmulator, initializeAuth, signInAnonymously, signOut } from 'firebase/auth';
import type { Auth } from 'firebase/auth';
import { collection, connectFirestoreEmulator, doc, getDocFromServer as getDoc, initializeFirestore, memoryLocalCache, limitToLast, onSnapshot, orderBy, query, runTransaction, serverTimestamp, setDoc, updateDoc } from 'firebase/firestore';
import type { Firestore, Timestamp, Unsubscribe } from 'firebase/firestore';
import { findVideo, inviteToken, safeImagePath } from './media.mjs';
import { clarifyPhrases, languages, receiptLabel } from './accessibility.mjs';
import { useChatAccess } from './useChatAccess';
import { displayedMessageText, outgoingText } from './messageText.mjs';
import { PhoneticPreview } from './PhoneticPreview';
import { ChatTools } from './ChatTools';
import { useGloveVoice } from './useGloveVoice';
import { gestureVoiceText } from './gestureVoice.mjs';
import { useAudioVolume } from './useAudioVolume';
import type { PhoneticData } from './PhoneticPreview';
import './style.css';

type ImageEntry = { path: string; label: string };
type VideoEntry = { phrases: string[]; signLanguage: string; path: string };
type ChatMessage = { id: string; senderUid: string; senderName?: string; text: string; translation: string; translations?: Record<string, string>; imageAsset: string; kind: string; createdAt?: Timestamp; clientTime: number; pending?: boolean };
type Connection = { db: Firestore; uid: string };
type Stage = 'loading' | 'setup' | 'invite' | 'waiting' | 'chat' | 'ended';
const token = inviteToken(location.hash);
const isEmulator = import.meta.env.VITE_FIREBASE_EMULATORS === 'true' && ['localhost', '127.0.0.1'].includes(location.hostname);
let bootstrap: Promise<Connection> | undefined;
let guestAuth: Auth | undefined;
async function connect(): Promise<Connection> {
  if (!bootstrap) bootstrap = (async () => {
    const config = isEmulator ? { enabled: true, projectId: 'demo-gestalk', apiKey: 'demo-api-key', appId: 'demo-app' }
      : await fetch('/firebase-config.json', { cache: 'no-store' }).then(response => response.json());
    if (!config.enabled || !config.projectId || !config.apiKey) throw new Error('SETUP_REQUIRED');
    const app = initializeApp(config);
    const auth = initializeAuth(app, { persistence: browserSessionPersistence }); guestAuth = auth;
    const db = initializeFirestore(app, { localCache: memoryLocalCache() });
    if (isEmulator) { connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true }); connectFirestoreEmulator(db, '127.0.0.1', 8080); }
    // Preserve reloads in this tab, without leaving an approved identity on a shared phone.
    await auth.authStateReady();
    const user = auth.currentUser ?? (await signInAnonymously(auth)).user;
    return { db, uid: user.uid };
  })();
  return bootstrap;
}

function App() {
  const [stage, setStage] = useState<Stage>('invite');
  const [name, setName] = useState('');
  const [text, setText] = useState('');
  const [roomId, setRoomId] = useState('');
  const [connection, setConnection] = useState<Connection>();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [hostUid, setHostUid] = useState('');
  const [groupGuests, setGroupGuests] = useState<string[] | undefined>();
  const [historyReady, setHistoryReady] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [online, setOnline] = useState(navigator.onLine);
  const [images, setImages] = useState<ImageEntry[]>([]);
  const [videos, setVideos] = useState<VideoEntry[]>([]);
  const [showImages, setShowImages] = useState(false);
  const [showTools, setShowTools] = useState(false);
  const [showListening, setShowListening] = useState(false);
  const [imageSearch, setImageSearch] = useState('');
  const [playing, setPlaying] = useState<VideoEntry>();
  const [failedMessage, setFailedMessage] = useState<{ id: string; text: string; imageAsset: string; kind: string; draft?: string }>();
  const [phoneticData, setPhoneticData] = useState<PhoneticData>();
  const [phoneticEnabled, setPhoneticEnabled] = useState(() => { try { return localStorage.getItem('gestalk-phonetic-input') === 'on'; } catch { return false; } });
  const [correction, setCorrection] = useState<{ draft: string; language: string; text: string }>();
  const [labels, setLabels] = useState<Record<string, Record<string, string>>>({});
  const [expiry, setExpiry] = useState(0);
  const bottom = useRef<HTMLDivElement>(null);
  const busyRef = useRef(false);
  const sessionEnded = useRef(false);
  const audio = useAudioVolume();
  const access = useChatAccess(connection?.db, connection?.uid, roomId, stage === 'chat', text, messages,
    transcript => { if (busyRef.current || !online) { setText(transcript); setError('Voice text is ready. Tap Send when connected.'); } else void send(transcript, '', undefined, 'speech'); }, audio.volume);
  const gloveVoice = useGloveVoice(stage === 'chat' && historyReady && !!hostUid, hostUid, messages, labels,
    access.listening || access.speaking, access.visible, audio.volume);
  function endConversation(reason: string) {
    sessionEnded.current = true;
    access.abortMicrophone(); access.stopSpeaking(); gloveVoice.stop();
    setStage('ended'); setMessages([]); setText(''); setName(''); setCorrection(undefined);
    setFailedMessage(undefined); setHistoryReady(false); setPlaying(undefined);
    setShowImages(false); setShowTools(false); setShowListening(false); setImageSearch('');
    setHostUid(''); setGroupGuests(undefined); setRoomId(''); setConnection(undefined); setError(reason);
    history.replaceState(null, '', location.pathname + location.search);
    if (guestAuth) void signOut(guestAuth).catch(() => {});
  }
  function changeAudioVolume(value: number) {
    audio.setVolume(value);
    if (value <= 0) { gloveVoice.stop(); access.stopSpeaking(); }
  }
  const peers = access.peers.filter(peer => peer.id === hostUid || groupGuests?.includes(peer.id));
  const guestName = (uid: string) => messages.find(message => message.senderUid === uid && message.senderName)?.senderName || `Guest ${uid.slice(-4)}`;
  const phoneticReady = !!phoneticData?.languages[access.language];
  const convertedDraft = outgoingText(text, access.language, phoneticEnabled, phoneticData, correction);
  const draftReady = !!convertedDraft.trim() && convertedDraft.length <= 2000 && (!phoneticEnabled || phoneticReady);
  const languageNames: Record<string, string> = { en: 'English', hi: 'Hindi', bn: 'Bengali', te: 'Telugu', mr: 'Marathi', ta: 'Tamil', ur: 'Urdu', gu: 'Gujarati', kn: 'Kannada', ml: 'Malayalam', pa: 'Punjabi', or: 'Odia', as: 'Assamese' };
  const listeningName = languageNames[gloveVoice.language];
  function togglePhonetic(enabled: boolean) {
    if (enabled && access.language === 'en') access.setLanguage('ml');
    setPhoneticEnabled(enabled); setCorrection(undefined);
    try { localStorage.setItem('gestalk-phonetic-input', enabled ? 'on' : 'off'); } catch { /* Preferences are optional. */ }
  }
  function selectWritingLanguage(language: string) {
    access.setLanguage(language);
    // Selecting a native writing script also selects English-letter input.
    // The checkbox can still be turned off for an existing native keyboard.
    const enabled = language !== 'en';
    setPhoneticEnabled(enabled); setCorrection(undefined);
    try { localStorage.setItem('gestalk-phonetic-input', enabled ? 'on' : 'off'); } catch { /* Preferences are optional. */ }
  }

  useEffect(() => {
    const up = () => setOnline(true), down = () => setOnline(false);
    const changeInvite = () => { if (inviteToken(location.hash) !== token) location.reload(); };
    window.addEventListener('online', up); window.addEventListener('offline', down);
    window.addEventListener('hashchange', changeInvite);
    return () => { window.removeEventListener('online', up); window.removeEventListener('offline', down); window.removeEventListener('hashchange', changeInvite); };
  }, []);
  useEffect(() => {
    let disposed = false;
    if (!token) { setStage('invite'); return; }
    connect().then(async current => {
      const invite = await getDoc(doc(current.db, 'invites', token));
      if (!invite.exists()) throw new Error('This invitation is unavailable. Ask for a new QR code.');
      if (!disposed) {
        const request = await getDoc(doc(current.db, 'rooms', invite.data().roomId, 'requests', current.uid));
        if (request.data()?.status === 'pending' && request.data()?.token !== token) {
          await updateDoc(request.ref, { token });
        }
        if (request.exists()) setName(value => value || request.data().name);
        const expiry = request.data()?.status === 'approved'
          ? null
          : invite.data().expiresAt;
        if (disposed || sessionEnded.current) return;
        if (['rejected', 'removed'].includes(request.data()?.status)) { endConversation('The Samvaad user ended your access.'); return; }
        setConnection(current); setRoomId(invite.data().roomId);
        setExpiry(expiry?.toMillis() ?? 0);
        setStage(request.exists() ? 'waiting' : 'invite');
      }
    }).catch(reason => {
      if (disposed) return;
      if (reason.message === 'SETUP_REQUIRED') setStage('setup');
      else endConversation('This QR has expired, the group is full, or it cannot connect. Ask the Samvaad user to refresh the QR.');
    });
    return () => { disposed = true; };
  }, []);

  // Give sign-in and invitation checks the network before optional chat assets.
  useEffect(() => {
    if (!connection) return;
    let disposed = false;
    fetch('/translations.json').then(r => r.json()).then(value => { if (!disposed) setLabels(value.labels ?? {}); }).catch(() => {});
    return () => { disposed = true; };
  }, [connection]);
  useEffect(() => {
    if (stage !== 'chat') return;
    let disposed = false;
    fetch('/sign-images.json').then(r => r.json()).then(value => { if (!disposed) setImages(value); }).catch(() => {});
    fetch('/sign-media.json').then(r => r.json()).then(value => { if (!disposed) setVideos(value.videos ?? []); }).catch(() => {});
    fetch('/phonetic-input.json').then(r => r.json()).then(value => { if (!disposed) setPhoneticData(value); }).catch(() => {});
    return () => { disposed = true; };
  }, [stage]);

  const chatSessionActive = ['waiting', 'chat'].includes(stage);
  useEffect(() => {
    if (!connection || !roomId || !chatSessionActive) return;
    const { db, uid } = connection;
    const unsubscribers: Unsubscribe[] = [];
    let stopped = false;
    let attached = false;
    const attachChat = () => {
      if (attached || stopped || sessionEnded.current) return;
      attached = true; setStage('chat'); setError('');
      unsubscribers.push(onSnapshot(doc(db, 'rooms', roomId), snapshot => {
        if (stopped || sessionEnded.current) return;
        if (!snapshot.exists() || snapshot.data().status === 'closed'
          || (Array.isArray(snapshot.data().guestUids) && !snapshot.data().guestUids.includes(uid))) endConversation('Conversation access ended.');
        else { setExpiry(snapshot.data().expiresAt.toMillis()); setHostUid(snapshot.data().hostUid); setGroupGuests(snapshot.data().guestUids); }
      }, () => { if (!stopped) endConversation('Conversation access ended.'); }));
      unsubscribers.push(onSnapshot(query(collection(db, 'rooms', roomId, 'messages'), orderBy('createdAt'), limitToLast(200)), { includeMetadataChanges: true }, snapshot => {
        if (stopped || sessionEnded.current) return;
        setMessages(snapshot.docs.map(message => ({ ...message.data(), id: message.id, pending: message.metadata.hasPendingWrites } as ChatMessage)));
        setHistoryReady(true);
      }, () => { if (!stopped) endConversation('Message access ended. Ask for a fresh QR to reconnect.'); }));
    };
    unsubscribers.push(onSnapshot(doc(db, 'rooms', roomId, 'requests', uid), { includeMetadataChanges: true }, snapshot => {
      if (stopped || sessionEnded.current) return;
      if (snapshot.data()?.status === 'approved' && !snapshot.metadata.fromCache) attachChat();
      else if (['rejected', 'removed'].includes(snapshot.data()?.status)) endConversation('The Samvaad user declined or ended your access.');
    }, () => { if (!stopped) endConversation('Unable to verify conversation access. Scan a fresh QR.'); }));
    return () => { stopped = true; unsubscribers.forEach(unsubscribe => unsubscribe()); };
  }, [connection, roomId, chatSessionActive]);
  useEffect(() => { bottom.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages.length]);
  useEffect(() => {
    if (!expiry || !['invite', 'waiting', 'chat'].includes(stage)) return;
    const timeout = window.setTimeout(() => endConversation('This invitation or conversation has expired. Ask for a new QR.'), Math.max(0, expiry - Date.now()));
    return () => window.clearTimeout(timeout);
  }, [expiry, stage]);

  async function join(event: React.FormEvent) {
    event.preventDefault();
    if (!connection || !token || !name.trim() || busy) return;
    if (gloveVoice.enabled && audio.volume > 0) gloveVoice.unlock();
    setBusy(true); setError('');
    try {
      const request = doc(connection.db, 'rooms', roomId, 'requests', connection.uid);
      if (!(await getDoc(request)).exists()) await setDoc(request, { name: name.trim(), token, status: 'pending', createdAt: serverTimestamp() });
      if (!sessionEnded.current) setStage('waiting');
    } catch { if (!sessionEnded.current) setError('Could not join. Check internet and ask for a fresh QR if necessary.'); }
    finally { setBusy(false); }
  }
  async function send(messageText = text.trim(), imageAsset = '', retryId?: string, kind = imageAsset ? 'image' : 'text', draft?: string) {
    if (!connection || stage !== 'chat' || !messageText || messageText.length > 2000 || busyRef.current || !online) return;
    const payload = { id: retryId ?? crypto.randomUUID(), text: messageText, imageAsset, kind, draft };
    busyRef.current = true;
    setBusy(true); setError('');
    try {
      const reference = doc(connection.db, 'rooms', roomId, 'messages', payload.id);
      await runTransaction(connection.db, async transaction => {
        const existing = await transaction.get(reference);
        if (!existing.exists()) transaction.set(reference, { senderUid: connection.uid,
          senderName: name.trim(), kind, text: messageText, translation: '', imageAsset,
          createdAt: serverTimestamp(), clientTime: Date.now() });
      });
      if (sessionEnded.current) return;
      if (draft !== undefined) setText(current => current === draft ? '' : current);
      setShowImages(false); setFailedMessage(undefined);
    } catch { if (!sessionEnded.current) { setFailedMessage(payload); setError('Message was not confirmed. Retry when connected.'); } }
    finally { busyRef.current = false; setBusy(false); }
  }
  const status = !online ? 'Offline · reconnect to send' : stage === 'chat' ? 'Connected to Samvaad' : stage === 'waiting' ? 'Waiting for approval' : 'Private guest conversation';
  return <main className="app">
    <header><div className="brand">s<span>↔</span></div><div><h1>Samvaad</h1><p><span className={`dot ${stage === 'chat' && online ? 'connected' : ''}`} />{stage === 'chat' && groupGuests && online ? `Group chat · ${groupGuests.length + 1} people` : status}</p></div>{stage === 'chat' ? <><button className="listen-language" onClick={() => setShowListening(true)} aria-label="Choose listening language" title="Language for incoming Samvaad text and audio">{listeningName} ▾</button><button className="voice-toggle" disabled={!gloveVoice.supported || !historyReady} onClick={() => {
      if (gloveVoice.enabled) gloveVoice.toggle(false); else { if (audio.volume === 0) changeAudioVolume(1); gloveVoice.toggle(true); }
    }} aria-label={gloveVoice.enabled ? 'Disable automatic audio' : 'Enable automatic audio'} aria-pressed={gloveVoice.enabled} title={`Hear new Samvaad gestures and typed replies · volume ${Math.round(audio.volume * 100)}%`}>{gloveVoice.enabled && audio.volume > 0 ? '🔊 Audio on' : '🔇 Audio off'}</button></> : <span className="guest-label">Guest chat</span>}</header>
    {isEmulator && <div className="notice">Local test · no real Firebase data</div>}
    <section className="conversation" aria-label="Conversation">
      {stage === 'loading' && <div className="welcome"><div className="symbol">↔</div><h2>Connecting your conversation…</h2><p>Opening a private link to the Samvaad user.</p></div>}
      {stage === 'setup' && <div className="welcome"><div className="symbol">↔</div><h2>Firebase setup needed</h2><p>This guest chat is built, but its Firebase project has not been connected yet.</p></div>}
      {stage === 'invite' && <div className="welcome"><div className="symbol">↔</div><p className="eyebrow">A CONVERSATION WITHOUT BARRIERS</p><h2>Say hello, your way.</h2><p>Send text and sign pictures. Read your partner’s gesture messages in the same conversation.</p>
        {token ? <form onSubmit={join}><label htmlFor="name">Your first name or nickname</label><input id="name" value={name} onChange={e => setName(e.target.value)} maxLength={40} autoComplete="off" placeholder="What should we call you?"/><button disabled={!connection || busy || !online || !name.trim()}>{!connection ? 'Connecting…' : busy ? 'Requesting…' : 'Join conversation →'}</button><small>The Samvaad user approves your request before messages are shared.</small></form>
        : <div className="notice">Scan the QR shown on the Samvaad phone to join a conversation.</div>}
      </div>}
      {stage === 'waiting' && <div className="welcome"><div className="symbol">⌛</div><h2>One moment, {name || 'guest'}.</h2><p>Ask the Samvaad user to tap <strong>Allow guest</strong> on their phone.</p><small>This screen opens the chat automatically after approval.</small></div>}
      {stage === 'ended' && <div className="welcome"><div className="symbol">✓</div><h2>Conversation unavailable</h2><p>The session may have ended or its invitation expired. Scan a new QR to start again.</p></div>}
      {stage === 'chat' && <>{gloveVoice.blocked && gloveVoice.enabled && <div className="notice" role="status">Your browser paused audio. <button onClick={gloveVoice.unlock}>Resume automatic audio</button></div>}<div className="chat-note">Text, signs, and pictures · one conversation</div>
        {messages.length === 0 && <div className="empty"><h2>You’re connected.</h2><p>Type a greeting to start. Your partner’s signs appear here as messages.</p></div>}
        {messages.map(message => {
          const own = message.senderUid === connection?.uid;
          const image = safeImagePath(message.imageAsset, images);
          const video = findVideo(message.text, videos) as VideoEntry | undefined;
          const fromHost = message.senderUid === hostUid;
          const received = fromHost ? gestureVoiceText(message.text, gloveVoice.language, labels,
            message.kind === 'sign' ? message.translations : undefined) : undefined;
          const displayedText = received ? received.text
            : displayedMessageText(message.text, own, access.language, labels);
          const translated = displayedText !== message.text;
          return <article key={message.id} className={`bubble ${own ? 'own' : 'partner'} ${message.senderUid === hostUid ? 'host-message' : 'guest-message'}`}>
            <span className="speaker">{own ? 'You' : message.senderUid === hostUid ? 'Samvaad' : message.senderName || guestName(message.senderUid)}{message.kind === 'sign' ? ' · sign' : message.kind === 'speech' ? ' · voice text' : ''}{received && !received.translated ? ` · Original · ${listeningName} translation unavailable` : ''}</span>
            {image && <img src={image} alt={message.text} loading="lazy"/>}
            <p dir="auto">{displayedText}</p>{translated && <p className="translation" dir="auto">{message.text}</p>}
            {!fromHost && !translated && message.translation && <p className="translation" dir="auto">{message.translation}</p>}
            <div className="bubble-actions"><button className="video-link" disabled={!access.voiceSupported} onClick={() => {
              if (fromHost) { access.abortMicrophone(); access.stopSpeaking(); gloveVoice.speakMessage(message.text,
                message.kind === 'sign' ? message.translations : undefined); }
              else { gloveVoice.stop(); access.speak(displayedText); }
            }} aria-label={`Speak message: ${message.text}`}>◖)) Speak</button>
            {video && <button className="video-link" onClick={() => setPlaying(video)}>▶ Play sign video · {video.signLanguage}</button>}
            <time>{new Date(message.createdAt?.toMillis() ?? message.clientTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}{own && ` · ${receiptLabel(message.pending, access.receipts[message.id], !!groupGuests)}`}</time></div>
          </article>;
        })}{peers.filter(peer => peer.caption).map(peer => <div key={peer.id} className="live-caption" role="status"><strong>{peer.id === hostUid ? 'Samvaad' : guestName(peer.id)} is speaking…</strong><p dir="auto">{peer.caption}</p><small>Live words may change.</small></div>)}
        {peers.some(peer => peer.typing) && <p className="typing" role="status">{peers.filter(peer => peer.typing).map(peer => peer.id === hostUid ? 'Samvaad' : guestName(peer.id)).join(', ')} typing…</p>}<div ref={bottom}/></>}
    </section>
    {error && <div className="error" role="alert">{error}{failedMessage && stage === 'chat' && <button disabled={busy || !online} onClick={() => send(failedMessage.text, failedMessage.imageAsset, failedMessage.id, failedMessage.kind, failedMessage.draft)}>Retry message</button>}</div>}
    {stage === 'chat' && <>
      <ChatTools open={showTools} title="Chat tools" onClose={() => setShowTools(false)}>
      <button className="picture-tool" onClick={() => endConversation('You left the conversation. This tab’s messages and draft have been cleared.')}>Leave conversation</button>
      <button className="picture-tool" onClick={() => { setShowTools(false); setShowListening(true); }}>Listening language · {languages.find(row => row[0] === gloveVoice.language)?.[1]}</button>
      <section className="chat-options"><h3>Language & accessibility</h3><div>
        <label>Writing & microphone language <select value={access.language} onChange={e => selectWritingLanguage(e.target.value)}>{languages.map(([code, label]) => <option key={code} value={code}>{label}</option>)}</select></label>
        <small>Choosing a writing language enables English-letter typing. Listening language separately controls Samvaad's incoming text and audio. Sent messages show the exact text shared with the group.</small>
        <label className="check"><input type="checkbox" checked={access.vibrate} disabled={!access.vibrationSupported} onChange={e => access.setVibrate(e.target.checked)}/>Vibrate on new messages { !access.vibrationSupported && '(unavailable in this browser)' }</label>
        <small>Read means the conversation was open on the receiving phone.</small>
      </div></section>
      <div className="quick-replies" aria-label="Clarification replies">{clarifyPhrases.map((phrase, index) => <button key={phrase} disabled={busy || !online} onClick={() => { setShowTools(false); void send(phrase); }}>{index === 0 ? 'Please repeat' : 'I didn’t understand'}</button>)}</div>
      <button className="picture-tool" onClick={() => { setShowTools(false); setShowImages(true); }}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="8" cy="8" r="1.5"/><path d="m3 17 5-5 4 4 4-6 5 7"/></svg>Choose sign picture</button>
      <div className="voice-tools"><button className={access.listening ? 'recording' : ''} disabled={!access.speechSupported || (!access.listening && (busy || !online))} onClick={access.toggleMic} aria-pressed={access.listening}>{access.listening ? 'Stop microphone' : '🎤 Speak to chat'}</button>
        <button disabled={!access.voiceSupported || (!draftReady && !access.speaking)} onClick={() => {
          gloveVoice.stop(); if (access.speaking) access.stopSpeaking(); else access.speak(convertedDraft.trim());
        }}>{access.speaking ? 'Stop voice' : 'Speak my text'}</button></div>
      {!access.speechSupported && <small className="feature-info">Speech input is unavailable in this browser. Use the keyboard or a supported browser.</small>}
      {(access.listening || access.info) && <div className="feature-info" role="status">{access.caption || access.info}</div>}
      <div className="phonetic-controls"><label className="check"><input type="checkbox" checked={phoneticEnabled} disabled={!phoneticData && !phoneticEnabled} onChange={e => togglePhonetic(e.target.checked)}/>Type with English letters</label>
        <select aria-label="Writing language" value={access.language} onChange={e => selectWritingLanguage(e.target.value)}>{languages.map(([code, label]) => <option key={code} value={code}>{label}</option>)}</select>
      </div>
      {phoneticEnabled && phoneticReady && <small className="phonetic-help">Example: {phoneticData!.languages[access.language].example}. Keep English words inside [[double brackets]]. Check or edit the preview before sending.</small>}
      {phoneticEnabled && !phoneticReady && <small className="feature-info">Choose a writing language. If the converter is unavailable, reload or turn off phonetic typing to use your keyboard.</small>}
      {!phoneticEnabled && <small className="phonetic-help">Normal keyboard · messages are sent exactly as typed. For Manglish, turn on English-letter typing.</small>}
      </ChatTools>
      <ChatTools open={showListening} title="Language & voice" onClose={() => setShowListening(false)}>
        <section className="glove-voice-options"><h3>Hear Samvaad on this phone</h3>
          <p>Choose the language you want to hear. Samvaad's phone and your typing language can be different.</p>
          <label className="language-field">I want to hear<select aria-label="Listening language" value={gloveVoice.language} onChange={e => { access.abortMicrophone(); access.stopSpeaking(); gloveVoice.setLanguage(e.target.value); }}>{gloveVoice.languageOptions.map(({code, label}) => <option key={code} value={code}>{languageNames[code]}{code !== 'en' ? ` · ${label}` : ''}</option>)}</select></label>
          <div className={`voice-status ${gloveVoice.selectedVoice ? 'ready' : 'missing'}`} role="status"><strong>{gloveVoice.selectedVoice ? `✓ ${listeningName} voice ready` : `${listeningName} voice needs setup`}</strong><p>{gloveVoice.selectedVoice ? 'New messages play automatically. Use Audio on/off in the chat to mute or enable them.' : 'Your browser has not provided this voice. Install its voice data on your phone and refresh voices below.'}</p></div>
          {gloveVoice.matchingVoices.length > 1 && <label className="language-field">Voice on this phone<select aria-label="Listening voice" value={gloveVoice.selectedVoice?.voiceURI || ''} onChange={e => gloveVoice.setVoice(e.target.value)}>{gloveVoice.matchingVoices.map(voice => <option key={voice.voiceURI} value={voice.voiceURI}>{voice.name}</option>)}</select></label>}
          <div className="voice-actions"><button disabled={!gloveVoice.supported || !gloveVoice.selectedVoice || audio.volume === 0} onClick={() => { access.abortMicrophone(); access.stopSpeaking(); gloveVoice.speakMessage('Hello'); }}>▶ Test {listeningName} voice</button><button className="secondary" onClick={gloveVoice.refreshVoices}>Refresh voices</button></div>
          <div className="audio-volume">
            <label htmlFor="guest-audio-volume">Audio volume <output htmlFor="guest-audio-volume">{audio.volume === 0 ? 'Muted' : `${Math.round(audio.volume * 100)}%${audio.volume === 1 ? ' · Maximum' : ''}`}</output></label>
            <div className="volume-controls"><input id="guest-audio-volume" type="range" min="0" max="100" step="5" value={Math.round(audio.volume * 100)} aria-label="Guest audio volume" aria-valuetext={audio.volume === 0 ? 'Muted' : `${Math.round(audio.volume * 100)} percent`} onChange={e => changeAudioVolume(Number(e.target.value) / 100)}/><button onClick={() => changeAudioVolume(1)}>Maximum</button></div>
        <button disabled={!gloveVoice.supported || !gloveVoice.selectedVoice || audio.volume === 0} onClick={() => { access.abortMicrophone(); access.stopSpeaking(); gloveVoice.speakMessage('Hello'); }}>Test audio volume</button>
            <small>Controls automatic and manual speech on this phone. Starts at 100% and remembers your choice. Changes apply to the next playback; 0 mutes and stops speech. For louder sound, raise your phone's media volume with its volume buttons.</small>
          </div>
          <button disabled={!gloveVoice.supported || !historyReady || !gloveVoice.selectedVoice || audio.volume === 0} onClick={() => {
            access.abortMicrophone(); access.stopSpeaking(); gloveVoice.toggle(true);
            if (gloveVoice.languageOptions.find(row => row.code === gloveVoice.language)?.available) setShowListening(false);
          }}>{gloveVoice.enabled ? 'Automatic audio is on' : `Enable ${listeningName} audio`}</button>
          {gloveVoice.enabled && <button onClick={() => gloveVoice.toggle(false)}>Turn automatic audio off</button>}
          <small>Joining enables automatic audio unless you muted it. New gestures and typed replies play without tapping each message. Keep this page open. Old messages are not replayed.</small>
          <small>English + 12 Indian languages. Known gesture phrases convert from any catalogue language into your listening language. Other sentences keep their original text and are marked when translation is unavailable; they will not unexpectedly play in a different language.</small>
          <details><summary>Set up voices on your phone</summary><p>On Android, open Settings → Accessibility → Text-to-speech output → your engine's settings → Install voice data. Download the languages offered by that engine, return here and refresh voices. Names of settings vary by phone.</p>
            <p>The website can use voices exposed by your browser; it cannot install phone voices. All 12 languages have text support, but audio availability depends on your phone and speech engine.</p>
            <a href="https://support.google.com/accessibility/android/answer/6006983?hl=en" target="_blank" rel="noreferrer">Android voice setup guide</a>
            <ul>{gloveVoice.languageOptions.map(({code, label, available}) => <li key={code}>{label}: {available ? 'Voice available' : 'Voice unavailable in this browser'}</li>)}</ul>
            <button onClick={gloveVoice.refreshVoices}>Refresh phone voices</button>
          </details>
          {(gloveVoice.info || gloveVoice.playing) && <p role="status">{gloveVoice.playing ? 'Speaking Samvaad message…' : gloveVoice.info}</p>}
          {!gloveVoice.supported && <p role="status">This browser does not support voice playback. Messages remain readable.</p>}
        </section>
      </ChatTools>
      {(gloveVoice.info || gloveVoice.playing) && <div className="composer-status" role="status">{gloveVoice.playing ? 'Speaking Samvaad message…' : gloveVoice.info}</div>}
      {phoneticEnabled && phoneticReady && text && <PhoneticPreview value={convertedDraft} language={access.language} onChange={value => setCorrection({ draft: text, language: access.language, text: value })}/>}
      <ChatTools open={showImages} title="Sign pictures" onClose={() => setShowImages(false)}>
        <section className="image-picker" aria-label="Choose a sign picture"><input aria-label="Search sign pictures" placeholder="Search a word or number" value={imageSearch} onChange={e => setImageSearch(e.target.value)}/><div className="image-grid">{images.filter(image => image.label.toLowerCase().includes(imageSearch.toLowerCase())).map(image => <button key={image.path} disabled={busy || !online} onClick={() => send(image.label, image.path)}><img src={safeImagePath(image.path, images)!} loading="lazy" alt=""/><span>{image.label}</span></button>)}</div></section>
      </ChatTools>
      {(access.listening || access.info) && <div className="composer-status" role="status">{access.caption || access.info}</div>}
      <form className="composer" onSubmit={event => { event.preventDefault(); if (draftReady) void send(convertedDraft.trim(), '', undefined, 'text', text); }}><button type="button" className="attach" onClick={() => setShowTools(true)} aria-label="Open chat tools" aria-haspopup="dialog" title="Chat tools: language, pictures and quick replies"><svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/></svg></button><textarea aria-label="Your message" placeholder={phoneticEnabled ? 'Type Manglish / English letters…' : 'Type a message…'} value={text} onChange={event => { setText(event.target.value); setCorrection(undefined); }} maxLength={2000} rows={1}/><button type="button" className={`composer-mic ${access.listening ? 'recording' : ''}`} disabled={!access.speechSupported || (!access.listening && (busy || !online))} onClick={access.toggleMic} aria-label={access.listening ? 'Stop microphone' : 'Speak to chat'} aria-pressed={access.listening} title={access.listening ? 'Stop microphone' : 'Speak to chat'}>🎤</button><button className="send-button" disabled={busy || !online || !draftReady} aria-label="Send message">{busy ? '…' : 'Send'}</button></form>
    </>}
    {stage !== 'chat' && <footer>Private session · No app installation needed</footer>}
    {playing && <div className="modal" role="dialog" aria-modal="true" aria-label="Sign video"><div><button onClick={() => setPlaying(undefined)} autoFocus>Close video ×</button><video controls playsInline autoPlay src={`/${playing.path}`} onError={() => { setPlaying(undefined); setError('This sign video is unavailable. The original text is still visible.'); }}/><p>{playing.signLanguage} · {playing.phrases[0]}</p></div></div>}
  </main>;
}
createRoot(document.getElementById('root')!).render(<App/>);
