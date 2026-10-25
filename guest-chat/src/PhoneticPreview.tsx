import React from 'react';
import { languages } from './accessibility.mjs';
export type PhoneticData = { languages: Record<string, { example: string; [key: string]: unknown }> };

export function PhoneticPreview({ value, language, onChange }: {
  value: string; language: string; onChange: (value: string) => void;
}) {
  const target = languages.find(l => l[0] === language)?.[1];
  return <section className="phonetic-preview" aria-label="Converted message preview">
    <label htmlFor="converted-message">Message to send · {target} · edit below</label>
    <textarea id="converted-message" dir="auto" value={value} onChange={event => onChange(event.target.value)} rows={1}/>
    {value.length > 2000 && <small>Shorten the converted message to 2,000 characters.</small>}
  </section>;
}
