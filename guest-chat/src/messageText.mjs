import { knownTranslation } from './accessibility.mjs';
import { phoneticText } from './phonetic.mjs';

// A correction only belongs to the exact draft and writing language it edited.
// The returned text is used by both the preview and the Firestore send payload.
export function outgoingText(draft, language, phoneticEnabled, data, correction) {
  if (!phoneticEnabled || !data?.languages?.[language]) return draft;
  if (correction?.draft === draft && correction.language === language) return correction.text;
  return phoneticText(draft, language, data);
}

// A sent bubble must show the transmitted text, never a local-only translation.
export function displayedMessageText(text, own, language, labels) {
  return own ? text : knownTranslation(text, language, labels) || text;
}
