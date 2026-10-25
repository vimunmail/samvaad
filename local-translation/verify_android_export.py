"""Check quantized mobile graphs against real English sentences before packaging."""
import json
from pathlib import Path
import numpy as np
import onnxruntime as ort
from onnxruntime_extensions import get_library_path
from vendor.processor import IndicProcessor

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / '.state/android-assets/indictrans2'
opts = ort.SessionOptions()
opts.intra_op_num_threads = 4
opts.inter_op_num_threads = 1
opts.enable_cpu_mem_arena = False
opts.enable_mem_pattern = False
opts.register_custom_ops_library(get_library_path())
encoder = ort.InferenceSession(str(ASSETS / 'encoder.onnx'), opts)
decoder = ort.InferenceSession(str(ASSETS / 'decoder.onnx'), opts)
tokenizer = ort.InferenceSession(str(ASSETS / 'tokenizer.onnx'), opts)
vocab = json.loads((ASSETS / 'vocab.json').read_text(encoding='utf-8'))
report = []
for text in ['why you are crying', "why didn't you come", 'I do not want water.', 'I need water.']:
    proc = IndicProcessor(inference=True)
    normalized = proc.preprocess_batch([text], src_lang='eng_Latn', tgt_lang='mal_Mlym')[0].split(' ', 2)[2]
    sp = tokenizer.run(None, {'inputs': np.array([normalized], dtype=object)})[0]
    ids = np.array([[vocab['englishTag'], vocab['languageTags']['ml'],
        *[vocab['sourceMap'][i] for i in sp], 2]], dtype=np.int64)
    hidden = encoder.run(None, {'input_ids': ids, 'attention_mask': np.ones_like(ids)})[0]
    beams, finished = [([2], 0.0)], []
    for step in range(128):
        scores = decoder.run(None, {'decoder_ids': np.array([b[0] for b in beams], dtype=np.int64),
            'encoder_hidden': np.repeat(hidden, len(beams), axis=0),
            'attention_mask': np.ones((len(beams), ids.shape[1]), dtype=np.int64)})[0]
        maxes = scores.max(axis=1, keepdims=True)
        scores = scores - maxes - np.log(np.exp(scores - maxes).sum(axis=1, keepdims=True))
        scores += np.array([b[1] for b in beams])[:, None]
        scores[:, [0, 1, 3]] = -np.inf
        best = np.argpartition(scores.ravel(), -10)[-10:]
        best = best[np.argsort(scores.ravel()[best])[::-1]]
        next_beams = []
        for rank, flat in enumerate(best):
            parent, token = divmod(int(flat), scores.shape[1])
            beam = (beams[parent][0] + [token], float(scores[parent, token]))
            if token == 2:
                if rank < 5: finished.append(beam)
            elif len(next_beams) < 5: next_beams.append(beam)
        if len(finished) >= 5 or not next_beams: break
        beams = next_beams
    assert finished, text
    winner = max(finished, key=lambda b: b[1] / (len(b[0]) - 1))
    raw = ''.join(vocab['pieces'][i] for i in winner[0][1:] if i > 3).replace('▁', ' ').strip()
    result = proc.postprocess_batch([raw], lang='mal_Mlym')[0]
    assert any(0xd00 <= ord(c) <= 0xd7f for c in result), result
    report.append({'english': text, 'normalized': normalized, 'translation': result})
    print(f'{text} -> {result}', flush=True)
(ROOT / '.state/android-export-verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print('Real INT8 Android graphs passed the Malayalam samples.', flush=True)
