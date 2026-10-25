export const languages = new Set(['ml', 'hi', 'bn', 'te', 'mr', 'ta', 'ur', 'gu', 'kn', 'pa', 'or', 'as']);
export const DAILY_PROJECT_LIMIT = 100_000;
export const DAILY_PHONE_LIMIT = 25_000;

export function validateRequest(data) {
  if (!data || !languages.has(data.target) || !Array.isArray(data.texts) || data.texts.length < 1 || data.texts.length > 30) {
    throw new Error('Choose a supported regional language and 1–30 English phrases.');
  }
  const texts = data.texts.map(text => {
    if (typeof text !== 'string' || !text.trim() || [...text].length > 300 || /<ctrl\d+>|<unk>|[\u0000-\u0008\u000b\u000c\u000e-\u001f]/i.test(text)) {
      throw new Error('Each English phrase must contain 1–300 characters of plain text.');
    }
    return text.trim();
  });
  if (texts.reduce((sum, text) => sum + [...text].length, 0) > 5_000) throw new Error('Translate fewer phrases at once.');
  return { texts, target: data.target };
}

export function chargeUsage(project, phone, characters, now) {
  const day = new Date(now).toISOString().slice(0, 10);
  const minute = Math.floor(now / 60_000);
  const total = (project.day === day ? project.characters || 0 : 0) + characters;
  const personal = (phone.day === day ? phone.characters || 0 : 0) + characters;
  const calls = (phone.minute === minute ? phone.calls || 0 : 0) + 1;
  if (total > DAILY_PROJECT_LIMIT || personal > DAILY_PHONE_LIMIT || calls > 20) {
    throw new Error('Translation limit reached. Saved translations still work; try again later.');
  }
  return { project: { day, characters: total }, phone: { day, characters: personal, minute, calls } };
}
