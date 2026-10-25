import { mkdir, readFile, writeFile, cp, readdir } from 'node:fs/promises';
import { resolve, join, relative } from 'node:path';
const root = resolve(import.meta.dirname, '..');
const assets = join(root, 'app/src/main/assets');
const publicDir = join(root, 'guest-chat/public');
await mkdir(publicDir, { recursive: true });
await cp(join(assets, 'firebase-config.json'), join(publicDir, 'firebase-config.json'));
await cp(join(assets, 'sign-media.json'), join(publicDir, 'sign-media.json'));
await cp(join(assets, 'translations/gesture_labels.json'), join(publicDir, 'translations.json'));
await cp(join(assets, 'translations/phonetic-input.json'), join(publicDir, 'phonetic-input.json'));
const images = [];
async function copyImages(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const file = join(dir, entry.name);
    if (entry.isDirectory()) await copyImages(file);
    else if (/\.png$/i.test(entry.name) && !/^default\.png$/i.test(entry.name)) {
      const path = relative(assets, file).replaceAll('\\', '/');
      images.push({ path, label: entry.name.replace(/\.png$/i, '').replaceAll('_', ' ') });
      await mkdir(resolve(publicDir, path, '..'), { recursive: true });
      await cp(file, join(publicDir, path));
    }
  }
}
await copyImages(join(assets, 'images'));
await writeFile(join(publicDir, 'sign-images.json'), JSON.stringify(images, null, 2) + '\n');
await cp(join(assets, 'videos'), join(publicDir, 'videos'), { recursive: true }).catch(error => {
  if (error.code !== 'ENOENT') throw error;
});
const media = JSON.parse(await readFile(join(assets, 'sign-media.json'), 'utf8'));
for (const video of media.videos) {
  if (!/^videos\/[A-Za-z0-9_/-]+\.mp4$/.test(video.path) || video.path.includes('..')) throw new Error('Invalid sign video path');
  await readFile(join(assets, video.path));
}
console.log(`Prepared ${images.length} sign pictures and ${media.videos.length} reviewed video entries.`);
