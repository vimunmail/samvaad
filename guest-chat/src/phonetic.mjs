const tokensBySpec = new WeakMap();
function convertWord(word, spec) {
  const key = word.toLowerCase();
  const common = spec.words && Object.hasOwn(spec.words, key) ? spec.words[key] : undefined;
  if (common) return common;
  let value = word;
  if (word === word.toUpperCase() || /^[A-Z][a-z]+$/.test(word)) value = word.toLowerCase();
  let tokens = tokensBySpec.get(spec);
  if (!tokens) { tokens = [...new Set([...Object.keys(spec.vowels), ...Object.keys(spec.consonants), ...Object.keys(spec.signs)])].sort((a, b) => b.length - a.length); tokensBySpec.set(spec, tokens); }
  let result = '', consonant = false;
  for (let offset = 0; offset < value.length;) {
    const token = tokens.find(candidate => value.startsWith(candidate, offset));
    if (!token) { if (consonant) result += spec.virama; result += value[offset++]; consonant = false; continue; }
    if (Object.hasOwn(spec.vowels, token)) { result += spec.vowels[token][consonant ? 1 : 0]; consonant = false; }
    else if (Object.hasOwn(spec.consonants, token)) { if (consonant) result += spec.virama; result += spec.consonants[token]; consonant = true; }
    else { result += spec.signs[token]; consonant = false; }
    offset += token.length;
  }
  if (consonant) result += spec.virama;
  for (const [ending, replacement] of Object.entries(spec.terminal)) {
    if (ending && result.endsWith(ending)) { result = result.slice(0, -ending.length) + replacement; break; }
  }
  return result;
}
// Preserve native text, punctuation, numbers, URLs, emails and [[literal English]].
export function phoneticText(text, language, data) {
  const spec = data?.languages?.[language];
  if (!spec || language === 'en') return text;
  return text.replace(/\[\[[\s\S]*?\]\]|(?:https?:\/\/|www\.)\S+|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}|[A-Za-z]+(?:[~^][A-Za-z]+)*/g, token => {
    if (token.startsWith('[[')) return token.slice(2, -2);
    if (/^(https?:\/\/|www\.)/.test(token) || token.includes('@')) return token;
    return convertWord(token, spec);
  });
}
