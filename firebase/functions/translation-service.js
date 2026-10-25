import { validateRequest } from './translation-policy.js';
export class TranslationError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}

export async function runTranslation(request, { reserve, translate }) {
  if (!request.auth) throw new TranslationError('unauthenticated', 'Sign in before translating.');
  let input;
  try { input = validateRequest(request.data); }
  catch (error) { throw new TranslationError('invalid-argument', error.message); }
  await reserve(request.auth.uid, input.texts.reduce((sum, text) => sum + [...text].length, 0));
  try {
    const [result] = await translate({ contents: input.texts, sourceLanguageCode: 'en',
      targetLanguageCode: input.target, mimeType: 'text/plain' });
    const translations = result.translations?.map(item => item.translatedText);
    if (translations?.length !== input.texts.length || translations.some(text => !text?.trim() || /<ctrl\d+>|<unk>/i.test(text))) {
      throw new Error('Incomplete translation response');
    }
    return { translations, target: input.target, source: 'google-cloud' };
  } catch {
    throw new TranslationError('unavailable', 'Google translation is temporarily unavailable. Check internet and service setup, then retry.');
  }
}
