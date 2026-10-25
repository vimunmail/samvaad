export const languages = [
  ['en', 'English', 'en-US'], ['hi', 'हिन्दी', 'hi-IN'], ['bn', 'বাংলা', 'bn-IN'],
  ['te', 'తెలుగు', 'te-IN'], ['mr', 'मराठी', 'mr-IN'], ['ta', 'தமிழ்', 'ta-IN'],
  ['ur', 'اردو', 'ur-IN'], ['gu', 'ગુજરાતી', 'gu-IN'], ['kn', 'ಕನ್ನಡ', 'kn-IN'],
  ['ml', 'മലയാളം', 'ml-IN'], ['pa', 'ਪੰਜਾਬੀ', 'pa-IN'], ['or', 'ଓଡ଼ିଆ', 'or-IN'], ['as', 'অসমীয়া', 'as-IN']
];
export function knownTranslation(text, language, labels) {
  if (language === 'en') return '';
  const key = text.trim().replace(/\s+/g, ' ').toUpperCase();
  const value = labels?.[key]?.[language];
  return typeof value === 'string' && value.trim() && !/<ctrl\d+>|<unk>/i.test(value) ? value : '';
}
export function freshPresence(value, now = Date.now()) {
  const age = now - (value?.updatedAt?.toMillis?.() ?? 0);
  return age >= -30_000 && age < 8_000 ? value : undefined;
}
export function receiptLabel(pending, receipt, group = false) {
  if (pending) return 'Sending…';
  if (group && receipt?.readCount) return `Read by ${receipt.readCount}`;
  if (group && receipt?.deliveredCount) return `Delivered to ${receipt.deliveredCount}`;
  return receipt?.readAt ? 'Read' : receipt?.deliveredAt ? 'Delivered' : 'Sent';
}
export function summarizeReceipts(receipts) {
  const grouped = new Map();
  for (const receipt of receipts) {
    const id = receipt.messageId || receipt.id;
    if (!grouped.has(id)) grouped.set(id, new Map());
    const previous = grouped.get(id).get(receipt.receiverUid);
    grouped.get(id).set(receipt.receiverUid, previous?.readAt ? previous : receipt);
  }
  return Object.fromEntries([...grouped].map(([id, people]) => {
    const values = [...people.values()];
    return [id, { deliveredAt: values.find(r => r.deliveredAt)?.deliveredAt,
      readAt: values.find(r => r.readAt)?.readAt,
      deliveredCount: values.filter(r => r.deliveredAt).length, readCount: values.filter(r => r.readAt).length }];
  }));
}
export const clarifyPhrases = ['Please repeat.', 'I didn’t understand. Please rephrase.'];
