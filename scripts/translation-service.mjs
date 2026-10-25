import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
const require = createRequire(import.meta.url);
const auth = require('firebase-tools/lib/auth');
const { requireAuth } = require('firebase-tools/lib/requireAuth');
const { Client } = require('firebase-tools/lib/apiv2');
const root = new URL('../', import.meta.url);
const project = JSON.parse(await readFile(new URL('.firebaserc', root), 'utf8')).projects.default;
const mode = process.argv[2] || 'status';
if (!['status', 'deploy'].includes(mode)) throw new Error('Choose status or deploy.');
const options = { project };
auth.setActiveAccount(options, auth.getGlobalDefaultAccount());
await requireAuth(options);
const billing = new Client({ urlPrefix: 'https://cloudbilling.googleapis.com', auth: true });
const { body: billingInfo } = await billing.get(`/v1/projects/${project}/billingInfo`);
console.log(`Project: ${project}. Billing: ${billingInfo.billingEnabled ? 'enabled' : 'not enabled'}.`);
if (mode === 'status') process.exit(0);
if (!billingInfo.billingEnabled) {
  console.log(`Enable billing yourself first: https://console.cloud.google.com/billing/linkedaccount?project=${project}`);
  process.exit(2);
}

// Deploy is an explicit action. Status never enables APIs or changes cloud resources.
const usage = new Client({ urlPrefix: 'https://serviceusage.googleapis.com', auth: true });
const { body: operation } = await usage.post(`/v1/projects/${project}/services/translate.googleapis.com:enable`, { body: {} });
if (operation.name && !operation.done) {
  let done = false;
  for (let attempt = 0; attempt < 90; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 2000));
    const { body: status } = await usage.get(`/v1/${operation.name}`);
    if (status.error) throw new Error('Cloud Translation API could not be enabled.');
    if (status.done) { done = true; break; }
  }
  if (!done) throw new Error('API setup is still running. Retry deployment shortly.');
}
const accountId = 'gestalk-translator';
const serviceAccount = `${accountId}@${project}.iam.gserviceaccount.com`;
const iam = new Client({ urlPrefix: 'https://iam.googleapis.com', auth: true });
try {
  await iam.post(`/v1/projects/${project}/serviceAccounts`, { body: { accountId, serviceAccount: { displayName: 'Gestalk translation service' } } });
} catch (error) { if (error.status !== 409 && error.context?.response?.statusCode !== 409) throw error; }
const manager = new Client({ urlPrefix: 'https://cloudresourcemanager.googleapis.com', auth: true });
const { body: policy } = await manager.post(`/v1/projects/${project}:getIamPolicy`, { body: { options: { requestedPolicyVersion: 3 } } });
policy.bindings ||= [];
for (const role of ['roles/cloudtranslate.user', 'roles/datastore.user', 'roles/serviceusage.serviceUsageConsumer']) {
  let binding = policy.bindings.find(value => value.role === role && !value.condition);
  if (!binding) { binding = { role, members: [] }; policy.bindings.push(binding); }
  const member = `serviceAccount:${serviceAccount}`;
  if (!binding.members.includes(member)) binding.members.push(member);
}
await manager.post(`/v1/projects/${project}:setIamPolicy`, { body: { policy } });
console.log('Translation API and dedicated service account are ready. Deploying only the translation function.');
const child = spawn(process.execPath, [require.resolve('firebase-tools/lib/bin/firebase.js'), 'deploy', '--only', 'functions:translation:translateGestureLabels', '--project', project],
  { cwd: root, stdio: 'inherit', env: { ...process.env, TRANSLATION_SERVICE_ACCOUNT: serviceAccount } });
child.on('error', error => { console.error(error.message); process.exitCode = 1; });
child.on('exit', code => { process.exitCode = code ?? 1; });
