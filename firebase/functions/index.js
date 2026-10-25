import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { initializeApp, getApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { v3 } from '@google-cloud/translate';
import { chargeUsage } from './translation-policy.js';
import { runTranslation, TranslationError } from './translation-service.js';

initializeApp();
let translator;

// Google credentials stay on the server. Firestore rules deny client access to usage records.
export const translateGestureLabels = onCall({
  region: 'asia-south1', timeoutSeconds: 60, memory: '256MiB', maxInstances: 1,
  serviceAccount: process.env.TRANSLATION_SERVICE_ACCOUNT || 'gestalk-translator@gestalk-6f008.iam.gserviceaccount.com',
}, async request => {
  try {
    return await runTranslation(request, {
      reserve: async (uid, characters) => {
        const db = getFirestore();
        const globalRef = db.doc('_translationUsage/project');
        const phoneRef = db.doc(`_translationUsage/phone-${uid}`);
        await db.runTransaction(async transaction => {
          const [project, phone] = await transaction.getAll(globalRef, phoneRef);
          let usage;
          try { usage = chargeUsage(project.data() || {}, phone.data() || {}, characters, Date.now()); }
          catch (error) { throw new TranslationError('resource-exhausted', error.message); }
          transaction.set(globalRef, usage.project);
          transaction.set(phoneRef, usage.phone);
        });
      },
      translate: async input => {
        translator ||= new v3.TranslationServiceClient();
        const projectId = getApp().options.projectId || await translator.getProjectId();
        return translator.translateText({ parent: `projects/${projectId}/locations/global`, ...input }, { timeout: 45_000 });
      },
    });
  } catch (error) {
    if (error instanceof TranslationError) throw new HttpsError(error.code, error.message);
    // Never log gesture labels or Google's potentially sensitive response body.
    console.error('Cloud Translation request failed', { code: error.code || 'unknown' });
    throw new HttpsError('unavailable', 'Google translation is temporarily unavailable. Check internet and service setup, then retry.');
  }
});
