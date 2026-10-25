import { useState } from 'react';

const preference = 'gestalk-guest-audio-volume';
export function useAudioVolume() {
  const [volume, setValue] = useState(() => {
    try {
      const saved = localStorage.getItem(preference);
      const value = saved === null || !saved.trim() ? 1 : Number(saved);
      return Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 1;
    } catch { return 1; }
  });
  function setVolume(value: number) {
    const bounded = Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 1;
    setValue(bounded);
    try { localStorage.setItem(preference, String(bounded)); } catch { /* Optional device preference. */ }
  }
  return { volume, setVolume };
}
