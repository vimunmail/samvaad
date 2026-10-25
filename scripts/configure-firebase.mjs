import { spawnSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
const root = resolve(import.meta.dirname, '..');
const projectId = process.argv[2];
const webAppId = process.argv[3];
const androidAppId = process.argv[4];
if (!projectId || !webAppId) {
  console.error('Usage: npm run firebase:configure -- PROJECT_ID WEB_APP_ID [ANDROID_APP_ID]');
  process.exit(1);
}
// Firebase configuration is public client metadata, never a service-account key.
const cli = join(root, 'node_modules/firebase-tools/lib/bin/firebase.js');
const output = spawnSync(process.execPath, [cli, 'apps:sdkconfig', 'WEB', webAppId, '--project', projectId, '--json'], { encoding: 'utf8' });
if (output.status !== 0) { console.error('Firebase CLI could not read the web app configuration. Sign in first.'); process.exit(1); }
const result = JSON.parse(output.stdout).result;
let config = result.sdkConfig;
if (typeof config === 'string') {
  // Current CLI returns sdkConfig as an object; reject executable configuration.
  config = JSON.parse(config);
}
if (!config?.apiKey || !config?.appId || config.projectId !== projectId) throw new Error('Unexpected SDK configuration');
const value = { enabled: true, ...config, guestChatUrl: `https://${projectId}.web.app` };
if (androidAppId) value.androidAppId = androidAppId;
await writeFile(join(root, 'app/src/main/assets/firebase-config.json'), JSON.stringify(value, null, 2) + '\n');
await mkdir(join(root, 'guest-chat/public'), { recursive: true });
await writeFile(join(root, 'guest-chat/public/firebase-config.json'), JSON.stringify(value, null, 2) + '\n');
await writeFile(join(root, '.firebaserc'), JSON.stringify({ projects: { default: projectId } }, null, 2) + '\n');
console.log(`Android and guest chat configured for ${projectId}. Enable anonymous auth, create Firestore, then deploy.`);
