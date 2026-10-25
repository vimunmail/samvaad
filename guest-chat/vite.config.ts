import { defineConfig } from 'vite';
export default defineConfig({
  build: { rollupOptions: { output: { manualChunks: {
    'firebase-auth': ['firebase/app', 'firebase/auth'],
    'firebase-firestore': ['firebase/firestore'],
    'react': ['react', 'react-dom/client']
  } } } }
});
