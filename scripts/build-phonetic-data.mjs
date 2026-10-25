// Optional data regeneration: temporarily install @indic-transliteration/sanscript@1.3.3
// under guest-chat. The shipped clients consume JSON only, without this dependency.
import Sanscript from '../guest-chat/node_modules/@indic-transliteration/sanscript/sanscript.js';
import { readFile, writeFile } from 'node:fs/promises';
const roman = Sanscript.schemes.itrans_dravidian;
const scripts = { hi: 'devanagari', bn: 'bengali', te: 'telugu', mr: 'devanagari', ta: 'tamil', ur: 'urdu', gu: 'gujarati', kn: 'kannada', ml: 'malayalam', pa: 'gurmukhi', or: 'oriya', as: 'assamese' };
const words = {
  ml: {
    sugam: 'സുഖം', sukham: 'സുഖം', sugham: 'സുഖം', sugamano: 'സുഖമാണോ', sughamano: 'സുഖമാണോ', sukhamano: 'സുഖമാണോ', sukhamalle: 'സുഖമല്ലേ',
    namaskaram: 'നമസ്കാരം', namaskaaram: 'നമസ്കാരം', nanni: 'നന്ദി', nandhi: 'നന്ദി', nandi: 'നന്ദി',
    ente: 'എന്റെ', enthe: 'എന്തേ', enikku: 'എനിക്ക്', enik: 'എനിക്ക്', enikk: 'എനിക്ക്', njan: 'ഞാൻ', njaan: 'ഞാൻ',
    nee: 'നീ', ningal: 'നിങ്ങൾ', ningalkku: 'നിങ്ങൾക്ക്', nammal: 'നമ്മൾ', namukku: 'നമുക്ക്',
    peru: 'പേര്', per: 'പേര്', perentha: 'പേരെന്താ', entha: 'എന്താ', enthu: 'എന്ത്', enth: 'എന്ത്',
    vellam: 'വെള്ളം', venam: 'വേണം', venum: 'വേണം', venda: 'വേണ്ട', vendam: 'വേണ്ട',
    aanu: 'ആണ്', aan: 'ആണ്', alla: 'അല്ല', alle: 'അല്ലേ', athe: 'അതെ', atheyo: 'അതെയോ',
    illa: 'ഇല്ല', undu: 'ഉണ്ട്', und: 'ഉണ്ട്', undo: 'ഉണ്ടോ', evide: 'എവിടെ', evideya: 'എവിടെയാ',
    engane: 'എങ്ങനെ', angane: 'അങ്ങനെ', enthinu: 'എന്തിന്', enthukondu: 'എന്തുകൊണ്ട്',
    kazhicho: 'കഴിച്ചോ', kazhichu: 'കഴിച്ചു', kazhikkan: 'കഴിക്കാൻ', bhakshanam: 'ഭക്ഷണം',
    sahayam: 'സഹായം', sahaayam: 'സഹായം', sahayikkanam: 'സഹായിക്കണം',
    manassilayi: 'മനസ്സിലായി', manassilaayi: 'മനസ്സിലായി', manassilayilla: 'മനസ്സിലായില്ല',
    parayu: 'പറയൂ', parayamo: 'പറയാമോ', veendum: 'വീണ്ടും', pathukke: 'പതുക്കെ',
    samsarikku: 'സംസാരിക്കൂ', samsarikkamo: 'സംസാരിക്കാമോ', suhruthe: 'സുഹൃത്തേ',
    innu: 'ഇന്ന്', naale: 'നാളെ', innale: 'ഇന്നലെ', ippo: 'ഇപ്പോൾ', ippol: 'ഇപ്പോൾ',
    varam: 'വരാം', varaam: 'വരാം', varumo: 'വരുമോ', pokam: 'പോകാം', pokaam: 'പോകാം',
    sheri: 'ശരി', sari: 'ശരി', sheriyanu: 'ശരിയാണ്', nallathu: 'നല്ലത്',
    kshamikkanam: 'ക്ഷമിക്കണം', kshamikku: 'ക്ഷമിക്കൂ', santhosham: 'സന്തോഷം',
    veedu: 'വീട്', veettil: 'വീട്ടിൽ', amma: 'അമ്മ', achan: 'അച്ഛൻ', acha: 'അച്ഛാ',
    chettan: 'ചേട്ടൻ', chechi: 'ചേച്ചി', kutti: 'കുട്ടി', kunju: 'കുഞ്ഞ്',
    malayalam: 'മലയാളം', malayaalam: 'മലയാളം', neram: 'നേരം', ethra: 'എത്ര',
    vishakkunnu: 'വിശക്കുന്നു', daahikkunnu: 'ദാഹിക്കുന്നു', vedana: 'വേദന',
    doctore: 'ഡോക്ടറേ', pani: 'പനി', thalavedana: 'തലവേദന'
  },
  hi: { namaste: 'नमस्ते', aap: 'आप', kaise: 'कैसे', hain: 'हैं', main: 'मैं', hu: 'हूँ', hoon: 'हूँ', mujhe: 'मुझे', paani: 'पानी', pani: 'पानी', chahiye: 'चाहिए', dhanyavaad: 'धन्यवाद', dhanyavad: 'धन्यवाद', madad: 'मदद', shukriya: 'शुक्रिया' },
  ta: { vanakkam: 'வணக்கம்', enakku: 'எனக்கு', thanni: 'தண்ணீர்', thanneer: 'தண்ணீர்', vendum: 'வேண்டும்', venum: 'வேணும்', nandri: 'நன்றி', eppadi: 'எப்படி', irukkinga: 'இருக்கீங்க', neenga: 'நீங்க', nalla: 'நல்ல', tamil: 'தமிழ்' },
  te: { namaskaram: 'నమస్కారం', namaskaaram: 'నమస్కారం', ela: 'ఎలా', elaa: 'ఎలా', unnaru: 'ఉన్నారు', unnaaru: 'ఉన్నారు', naaku: 'నాకు', neellu: 'నీళ్ళు', kavali: 'కావాలి', kaavaali: 'కావాలి', dhanyavadalu: 'ధన్యవాదాలు' },
  kn: { namaskara: 'ನಮಸ್ಕಾರ', namaskaara: 'ನಮಸ್ಕಾರ', hegiddira: 'ಹೇಗಿದ್ದೀರಾ', hegiddeera: 'ಹೇಗಿದ್ದೀರಾ', nanage: 'ನನಗೆ', neeru: 'ನೀರು', beku: 'ಬೇಕು', dhanyavadagalu: 'ಧನ್ಯವಾದಗಳು', dhanyavaadagalu: 'ಧನ್ಯವಾದಗಳು' },
  mr: { namaskar: 'नमस्कार', namaskaar: 'नमस्कार', mala: 'मला', paani: 'पाणी', pani: 'पाणी', pahije: 'पाहिजे', dhanyavaad: 'धन्यवाद', dhanyavad: 'धन्यवाद', tumhi: 'तुम्ही' },
  bn: { nomoshkar: 'নমস্কার', namaskar: 'নমস্কার', kemon: 'কেমন', achen: 'আছেন', achhen: 'আছেন', ami: 'আমি', jol: 'জল', chai: 'চাই', dhonnobad: 'ধন্যবাদ', dhanyabad: 'ধন্যবাদ' },
  gu: { namaste: 'નમસ્તે', kem: 'કેમ', cho: 'છો', mane: 'મને', paani: 'પાણી', pani: 'પાણી', joiye: 'જોઈએ', dhanyavaad: 'ધન્યવાદ', dhanyavad: 'ધન્યવાદ' },
  pa: { sat: 'ਸਤਿ', sri: 'ਸ੍ਰੀ', shri: 'ਸ੍ਰੀ', akal: 'ਅਕਾਲ', akaal: 'ਅਕਾਲ', tusi: 'ਤੁਸੀਂ', kiven: 'ਕਿਵੇਂ', ho: 'ਹੋ', mainu: 'ਮੈਨੂੰ', paani: 'ਪਾਣੀ', chahida: 'ਚਾਹੀਦਾ', dhanvaad: 'ਧੰਨਵਾਦ', dhannvaad: 'ਧੰਨਵਾਦ' },
  or: { namaskar: 'ନମସ୍କାର', namaskaar: 'ନମସ୍କାର', kemiti: 'କେମିତି', achhanti: 'ଅଛନ୍ତି', mote: 'ମୋତେ', paani: 'ପାଣି', pani: 'ପାଣି', darkar: 'ଦରକାର', dhanyabad: 'ଧନ୍ୟବାଦ' },
  as: { nomoskar: 'নমস্কাৰ', namaskar: 'নমস্কাৰ', kene: 'কেনে', asa: 'আছা', aase: 'আছে', moi: 'মই', pani: 'পানী', paani: 'পানী', lage: 'লাগে', dhonyobad: 'ধন্যবাদ' },
  ur: { assalamualaikum: 'السلام علیکم', salam: 'سلام', salaam: 'سلام', aap: 'آپ', kaise: 'کیسے', hain: 'ہیں', main: 'میں', mujhe: 'مجھے', pani: 'پانی', paani: 'پانی', chahiye: 'چاہیے', shukriya: 'شکریہ', madad: 'مدد', hai: 'ہے', ho: 'ہو' }
};
const examples = { ml: 'sughamano', hi: 'namaste', bn: 'nomoshkar', te: 'namaskaram', mr: 'namaskar', ta: 'vanakkam', ur: 'salam', gu: 'namaste', kn: 'namaskara', pa: 'sat sri akal', or: 'namaskar', as: 'nomoskar' };
const languages = {};
for (const [code, name] of Object.entries(scripts)) {
  const target = Sanscript.schemes[name];
  const clean = value => code === 'ur' ? value.replace(/[\u064b-\u065f]/g, '') : value;
  const spec = { vowels: {}, consonants: {}, signs: {}, virama: clean(target.virama['्']), terminal: {}, words: words[code], example: examples[code] };
  for (const [key, token] of Object.entries(roman.vowels)) {
    const markKey = Object.keys(roman.vowel_marks).find(k => roman.vowel_marks[k] === token);
    const value = [clean(target.vowels[key] ?? ''), key === 'अ' ? '' : clean(target.vowel_marks[markKey] ?? '')];
    if (value[0]) for (const alias of [token, ...(roman.alternates[token] ?? [])]) spec.vowels[alias] = value;
  }
  for (const group of ['consonants', 'extra_consonants']) for (const [key, token] of Object.entries(roman[group])) {
    const value = clean(target[group]?.[key] || target.consonants[key] || '');
    if (value) for (const alias of [token, ...(roman.alternates[token] ?? [])]) spec.consonants[alias] = value;
  }
  for (const [key, token] of Object.entries(roman.yogavaahas)) {
    const value = clean(target.yogavaahas[key] ?? '');
    if (value) for (const alias of [token, ...(roman.alternates[token] ?? [])]) spec.signs[alias] = value;
  }
  if (code === 'ml') spec.terminal = { 'മ്': 'ം', 'ന്': 'ൻ', 'ണ്': 'ൺ', 'ല്': 'ൽ', 'ള്': 'ൾ', 'ര്': 'ർ' };
  if (!['ml', 'te', 'kn', 'ta', 'ur'].includes(code)) spec.terminal[spec.virama] = '';
  // Common phonetic spellings, while retaining explicit case-sensitive ITRANS forms.
  spec.consonants.ng = target.consonants['ङ'] ? clean(target.consonants['ङ']) : spec.consonants.n;
  spec.consonants.nj = target.consonants['ञ'] ? clean(target.consonants['ञ']) : spec.consonants.n;
  spec.consonants.f ??= spec.consonants.ph; spec.consonants.z ??= spec.consonants.j; spec.consonants.q ??= spec.consonants.k;
  if (!['ml', 'te', 'kn', 'ta'].includes(code)) {
    spec.vowels.e = spec.vowels.E; spec.vowels.o = spec.vowels.O;
  }
  languages[code] = spec;
}
const license = await readFile('guest-chat/node_modules/@indic-transliteration/sanscript/LICENSE', 'utf8');
await writeFile('app/src/main/assets/translations/phonetic-input.json', JSON.stringify({ version: 1,
  source: 'Script maps adapted from @indic-transliteration/sanscript 1.3.3 (https://github.com/indic-transliteration/sanscript.js). Common phonetic spellings maintained by Gestalk.',
  license, languages }, null, 2) + '\n');
console.log('Generated offline phonetic maps for 12 writing languages.');
