import { knownTranslation, languages } from './accessibility.mjs';

const indexes = new WeakMap();
const normalize = text => text.normalize('NFKC').trim().replace(/[.!?,।؟]+$/u, '').replace(/[_-]+/gu, ' ').replace(/\s+/gu, ' ').toLocaleUpperCase('en-US');
const valid = value => typeof value === 'string' && value.trim() && !/<ctrl\d+>|<unk>/i.test(value);
// Match a known phrase in ANY source language before selecting the receiver's language.
// Ambiguous source phrases are translated only if all matching entries agree.
export function receiverTranslation(text, language, labels) {
  if (!labels) return '';
  let index = indexes.get(labels);
  if (!index) {
    index = new Map();
    for (const [key, translations] of Object.entries(labels)) {
      for (const source of [key, ...Object.values(translations).filter(valid)]) {
        const phrase = normalize(source);
        if (!index.has(phrase)) index.set(phrase, new Set());
        index.get(phrase).add(key);
      }
    }
    indexes.set(labels, index);
  }
  const keys = labels[normalize(text)] ? [normalize(text)] : [...(index.get(normalize(text)) || [])];
  const targets = keys.map(key => language === 'en'
    ? key.charAt(0) + key.slice(1).toLowerCase().replace(/\bi\b/g, 'I') : labels[key]?.[language]);
  if (!targets.length || targets.some(value => !valid(value))) return '';
  return new Set(targets.map(normalize)).size === 1 ? targets[0] : '';
}

export function gestureVoiceText(text, language, labels, messageTranslations) {
  // The wearer has already prepared these meanings on-device, including
  // reviewed corrections not present in the website's bundled catalogue.
  const prepared = messageTranslations?.[language];
  if (language !== 'en' && valid(prepared) && prepared.length <= 300) {
    return { text: prepared.trim(), language, translated: true };
  }
  const translated = receiverTranslation(text, language, labels) || knownTranslation(text, language, labels)
    || knownTranslation(text.trim().replace(/[.!?।؟]+$/u, ''), language, labels);
  if (translated) return { text: translated, language, translated: true };
  const scripts = [['ml', /[\u0D00-\u0D7F]/], ['ta', /[\u0B80-\u0BFF]/], ['te', /[\u0C00-\u0C7F]/],
    ['kn', /[\u0C80-\u0CFF]/], ['hi', /[\u0900-\u097F]/], ['bn', /[\u0980-\u09FF]/],
    ['gu', /[\u0A80-\u0AFF]/], ['pa', /[\u0A00-\u0A7F]/], ['or', /[\u0B00-\u0B7F]/], ['ur', /[\u0600-\u06FF]/]];
  let originalLanguage = scripts.find(([, pattern]) => pattern.test(text))?.[0] || 'en';
  if ((originalLanguage === 'hi' && language === 'mr') || (originalLanguage === 'bn' && language === 'as')) originalLanguage = language;
  return { text, language: originalLanguage, translated: originalLanguage === language };
}
export function selectVoice(voices, language) {
  const tag = languages.find(row => row[0] === language)?.[2] || 'en-US';
  const normalized = value => value.toLowerCase().replaceAll('_', '-');
  return voices.filter(voice => normalized(voice.lang).split('-')[0] === language)
    .sort((a, b) => {
      const score = voice => (normalized(voice.lang) === tag.toLowerCase() ? 10 : 0)
        + (/natural|neural/i.test(voice.name || '') ? 3 : /google|microsoft/i.test(voice.name || '') ? 2 : 0);
      return score(b) - score(a);
    })[0];
}
export function newGloveMessages(messages, seen, hostUid) {
  return messages.filter(message => message.senderUid === hostUid && ['sign', 'text', 'speech'].includes(message.kind)
    && !message.pending && !seen.has(message.id));
}

export function listeningLanguages(voices) {
  return languages.map(([code, label]) => ({ code, label, available: !!selectVoice(voices, code) }));
}
