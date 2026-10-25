export function normalizePhrase(value) {
  return value.normalize('NFKC').toLocaleLowerCase('en-US').trim().replace(/[.!?,;:]+$/u, '').trim().replace(/\s+/gu, ' ');
}
export function findVideo(text, videos, language = 'ISL') {
  const phrase = normalizePhrase(text);
  return videos.find(video => video.signLanguage === language &&
    Array.isArray(video.phrases) && video.phrases.some(alias => normalizePhrase(alias) === phrase) &&
    /^videos\/[A-Za-z0-9_/-]+\.mp4$/.test(video.path) && !video.path.includes('..'));
}
export function safeImagePath(path, catalogue) {
  return catalogue.some(image => image.path === path) ? `/${path.split('/').map(encodeURIComponent).join('/')}` : null;
}
export function inviteToken(hash) {
  const token = new URLSearchParams(hash.replace(/^#/, '')).get('invite');
  return token && /^[a-f0-9]{64}$/.test(token) ? token : null;
}
