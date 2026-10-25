import { useEffect, useRef, useState } from 'react';
import { languages } from './accessibility.mjs';
import { gestureVoiceText, newGloveMessages, selectVoice, listeningLanguages } from './gestureVoice.mjs';

type Message = { id: string; senderUid: string; kind: string; text: string; translations?: Record<string, string>; pending?: boolean };
const autoAudioPreference = 'samvaad-guest-auto-audio';
export function useGloveVoice(active: boolean, hostUid: string, messages: Message[],
  labels: Record<string, Record<string, string>>, paused: boolean, visible: boolean, volume: number) {
  // Automatic audio is on unless this guest turned it off.
  const [enabled, setEnabled] = useState(() => { try { return localStorage.getItem(autoAudioPreference) !== 'off'; } catch { return true; } });
  const [language, setLanguageState] = useState(() => {
    try { const value = localStorage.getItem('gestalk-glove-voice-language'); return languages.some(l => l[0] === value) ? value! : 'en'; } catch { return 'en'; }
  });
  const [info, setInfo] = useState('');
  const [playing, setPlaying] = useState(false);
  // Browsers refuse speech until the page has been touched once.
  const [blocked, setBlocked] = useState(false);
  const [voices, setVoices] = useState<SpeechSynthesisVoice[]>([]);
  const [voiceChoices, setVoiceChoices] = useState<Record<string, string>>(() => {
    try {
      const value = JSON.parse(localStorage.getItem('gestalk-listening-voices') || '{}');
      return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
    } catch { return {}; }
  });
  const seen = useRef(new Set<string>());
  const wasActive = useRef(false);
  const unlocked = useRef(false);
  const queue = useRef<Message[]>([]);
  const retry = useRef<{ text: string; target: string } | undefined>(undefined);
  const utterance = useRef<SpeechSynthesisUtterance | undefined>(undefined);
  const current = useRef({ active, enabled, language, labels, paused, visible, volume, voiceChoices });
  current.current = { active, enabled, language, labels, paused, visible, volume, voiceChoices };
  useEffect(() => {
    if (volume > 0) setInfo(value => value.startsWith('Guest audio is muted.') ? '' : value);
  }, [volume]);
  function cancelSpeech() {
    setPlaying(false);
    if (utterance.current) { utterance.current.onend = null; utterance.current.onerror = null; window.speechSynthesis?.cancel(); utterance.current = undefined; }
  }
  function stop() { queue.current = []; retry.current = undefined; cancelSpeech(); }
  function play(text: string, target: string) {
    if (current.current.volume <= 0) { setInfo('Guest audio is muted. Raise Audio volume in Listening language to hear messages.'); return; }
    const available = window.speechSynthesis.getVoices();
    const voice = available.find(voice => voice.voiceURI === current.current.voiceChoices[target]
      && voice.lang.toLowerCase().replaceAll('_', '-').split('-')[0] === target) || selectVoice(available, target);
    // An empty list means voices are still loading; the language tag lets the browser choose.
    if (!voice && available.length) { setInfo(`No ${languages.find(l => l[0] === target)?.[1] || target} voice is available in this browser. Open Listening language for voice setup. The message text is still available.`); return; }
    const next = new SpeechSynthesisUtterance(text);
    if (voice) next.voice = voice;
    next.lang = voice?.lang || languages.find(l => l[0] === target)?.[2] || 'en-US';
    next.volume = current.current.volume;
    next.onend = () => { if (utterance.current === next) { utterance.current = undefined; setPlaying(false); drain(); } };
    next.onerror = event => {
      if (utterance.current === next) { utterance.current = undefined; setPlaying(false); }
      if (event.error === 'not-allowed') { unlocked.current = false; retry.current = { text, target }; setBlocked(true); return; }
      if (!['canceled', 'interrupted'].includes(event.error)) setInfo('Voice playback was unavailable. Use the Speak button on a message to try again.');
      drain();
    };
    utterance.current = next; setPlaying(true); window.speechSynthesis.speak(next);
  }
  function drain() {
    const value = current.current;
    if (!unlocked.current || !value.active || !value.enabled || !value.visible || value.paused || utterance.current || !('speechSynthesis' in window)) return;
    if (retry.current) { const pending = retry.current; retry.current = undefined; play(pending.text, pending.target); return; }
    const message = queue.current.shift(); if (!message) return;
    const spoken = gestureVoiceText(message.text, value.language, value.labels, message.translations);
    if (spoken.language !== value.language) { setInfo('No translation for this sentence in your listening language. The original text is shown; audio was skipped.'); drain(); return; }
    setInfo('');
    play(spoken.text, spoken.language);
  }
  /** Must run inside a tap or key press; afterwards speech can start without one. */
  function unlock() {
    if (!('speechSynthesis' in window)) return;
    if (!unlocked.current) {
      // A real utterance inside Join's gesture reliably activates mobile speech.
      // Whitespace-only, zero-volume utterances can be discarded by browsers.
      const confirmation = new SpeechSynthesisUtterance('Audio enabled');
      confirmation.lang = 'en-US';
      confirmation.volume = current.current.volume;
      confirmation.onerror = event => { if (event.error === 'not-allowed') { unlocked.current = false; setBlocked(true); } };
      window.speechSynthesis.speak(confirmation);
      unlocked.current = true;
    }
    setBlocked(false);
    drain();
  }
  function toggle(value: boolean) {
    stop(); current.current.enabled = value; setEnabled(value); setInfo('');
    seen.current = new Set(messages.filter(m => !m.pending).map(m => m.id));
    try { localStorage.setItem(autoAudioPreference, value ? 'on' : 'off'); } catch { /* Optional preference. */ }
    if (value && 'speechSynthesis' in window) {
      unlock();

    }
  }
  function setLanguage(value: string) {
    if (!languages.some(row => row[0] === value)) return;
    stop(); setLanguageState(value); setInfo('');
    try { localStorage.setItem('gestalk-glove-voice-language', value); } catch { /* Optional preference. */ }
  }
  function setVoice(uri: string) {
    stop();
    const next = { ...voiceChoices, [language]: uri }; setVoiceChoices(next);
    try { localStorage.setItem('gestalk-listening-voices', JSON.stringify(next)); } catch { /* Optional preference. */ }
  }
  function speakMessage(text: string, translations?: Record<string, string>) {
    stop(); unlock();
    const spoken = gestureVoiceText(text, language, labels, translations);
    if (spoken.language !== language) { setInfo('No translation for this sentence in your listening language. The original text is shown; audio was skipped.'); return; }
    setInfo('');
    play(spoken.text, spoken.language);
  }
  useEffect(() => {
    if (!('speechSynthesis' in window)) return;
    const refresh = () => { setVoices(window.speechSynthesis.getVoices()); drain(); }; refresh();
    // The guest's first tap anywhere (for example "Join conversation") unlocks audio.
    const firstTouch = () => { if (current.current.active && current.current.enabled && !unlocked.current) unlock(); };
    window.speechSynthesis.addEventListener('voiceschanged', refresh);
    window.addEventListener('focus', refresh);
    document.addEventListener('pointerdown', firstTouch, true);
    document.addEventListener('keydown', firstTouch, true);
    return () => { stop(); window.speechSynthesis.removeEventListener('voiceschanged', refresh); window.removeEventListener('focus', refresh);
      document.removeEventListener('pointerdown', firstTouch, true); document.removeEventListener('keydown', firstTouch, true); };
  }, []);
  useEffect(() => {
    const settled = messages.filter(m => !m.pending).map(m => m.id);
    // History loaded when the chat opens, or arriving while silenced, is never replayed.
    if (!active || !enabled || !visible || !wasActive.current) {
      wasActive.current = active;
      if (!active || !visible) stop();
      settled.forEach(id => seen.current.add(id));
      return;
    }
    const incoming = newGloveMessages(messages, seen.current, hostUid);
    settled.forEach(id => seen.current.add(id));
    queue.current.push(...incoming); queue.current = queue.current.slice(-20);
    drain();
  }, [active, enabled, visible, hostUid, messages]);
  // Fallback in case a browser never reports the end of an utterance.
  useEffect(() => {
    if (!active || !enabled) return;
    const timer = window.setInterval(() => { if (!window.speechSynthesis?.speaking && utterance.current) utterance.current = undefined; drain(); }, 1000);
    return () => window.clearInterval(timer);
  }, [active, enabled]);
  useEffect(() => { if (!active) { stop(); seen.current.clear(); } }, [active]);
  // Pause (guest microphone or manual playback) defers queued messages instead of dropping them.
  useEffect(() => { if (paused) cancelSpeech(); else drain(); }, [paused]);
  useEffect(() => { if (volume > 0) drain(); }, [volume]);
  useEffect(() => { if (!visible) stop(); }, [visible]);
  const matchingVoices = voices.filter(voice => voice.lang.toLowerCase().replaceAll('_', '-').split('-')[0] === language);
  const selectedVoice = matchingVoices.find(voice => voice.voiceURI === voiceChoices[language]) || selectVoice(voices, language);
  return { enabled, toggle, language, setLanguage, speakMessage, stop, info, playing, matchingVoices, selectedVoice, setVoice,
    blocked, unlock,
    languageOptions: listeningLanguages(voices) as { code: string; label: string; available: boolean }[],
    refreshVoices: () => setVoices(window.speechSynthesis?.getVoices() || []),
    supported: 'speechSynthesis' in window };
}
