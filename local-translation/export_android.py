"""Export the approved pinned IndicTrans2 weights for offline Android inference.

Run after download_model.py. Generated assets are private build artifacts, not Git files.
"""
from pathlib import Path
import argparse
import json
import shutil

ROOT = Path(__file__).resolve().parent
MODEL = ROOT / '.state/model'
OUT = ROOT / '.state/android-assets/indictrans2'
WORK = ROOT / '.state/onnx-export'


def export(part):
    import torch
    from transformers import AutoModelForSeq2SeqLM
    torch.set_num_threads(4)
    model = AutoModelForSeq2SeqLM.from_pretrained(str(MODEL), trust_remote_code=True,
        local_files_only=True, attn_implementation='eager').eval()

    class Encoder(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.encoder = model.get_encoder()
        def forward(self, input_ids, attention_mask):
            return self.encoder(input_ids=input_ids, attention_mask=attention_mask, return_dict=False)[0]

    class Decoder(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.decoder = model.get_decoder()
            self.head = model.lm_head
        def forward(self, decoder_ids, encoder_hidden, attention_mask):
            hidden = self.decoder(input_ids=decoder_ids, encoder_hidden_states=encoder_hidden,
                encoder_attention_mask=attention_mask, use_cache=False, return_dict=False)[0]
            return self.head(hidden[:, -1:, :]).squeeze(1)

    if part == 'encoder':
        wrapper = Encoder()
        args = (torch.tensor([[4, 5, 6, 7, 2]]), torch.ones(1, 5, dtype=torch.long))
        inputs = ['input_ids', 'attention_mask']
        outputs = ['encoder_hidden']
        dynamic = {k: {0: 'batch', 1: 'source_length'} for k in inputs + outputs}
    else:
        wrapper = Decoder()
        args = (torch.tensor([[2, 4, 5]]), torch.zeros(1, 5, 512), torch.ones(1, 5, dtype=torch.long))
        inputs = ['decoder_ids', 'encoder_hidden', 'attention_mask']
        outputs = ['logits']
        dynamic = {'decoder_ids': {0: 'batch', 1: 'target_length'},
            'encoder_hidden': {0: 'batch', 1: 'source_length'},
            'attention_mask': {0: 'batch', 1: 'source_length'}, 'logits': {0: 'batch'}}
    with torch.no_grad():
        torch.onnx.export(wrapper, args, str(WORK / f'{part}.onnx'), opset_version=17,
            input_names=inputs, output_names=outputs, dynamic_axes=dynamic, dynamo=False)
    print(f'Exported {part}', flush=True)


def quantize(part):
    from onnxruntime.quantization import quantize_dynamic, QuantType
    quantize_dynamic(str(WORK / f'{part}.onnx'), str(OUT / f'{part}.onnx'),
        weight_type=QuantType.QInt8, op_types_to_quantize=['MatMul', 'Gather'],
        per_channel=False, extra_options={'MatMulConstBOnly': True})
    print(f'Quantized {part}: {(OUT / (part + ".onnx")).stat().st_size // 1048576} MB', flush=True)


def tokenizer():
    import numpy as np
    import onnx
    import sentencepiece as spm
    from onnxruntime_extensions import PyOrtFunction, SentencepieceTokenizer
    from indicnlp.transliterate.unicode_transliterate import UnicodeIndicTransliterator
    from indicnlp.tokenize import indic_detokenize
    from sacremoses import MosesTokenizer, MosesPunctNormalizer
    from vendor.processor import IndicProcessor
    sp = spm.SentencePieceProcessor(model_file=str(MODEL / 'model.SRC'))
    src = json.loads((MODEL / 'dict.SRC.json').read_text(encoding='utf-8'))
    tgt = json.loads((MODEL / 'dict.TGT.json').read_text(encoding='utf-8'))
    fn = PyOrtFunction.from_customop(SentencepieceTokenizer, model=(MODEL / 'model.SRC').read_bytes())
    graph = fn.onnx_model
    graph.ir_version = 10
    from onnx import numpy_helper
    for value in list(graph.graph.input)[1:]:
        dtype = np.int64 if value.name == 'nbest_size' else np.float32 if value.name == 'alpha' else np.bool_
        graph.graph.initializer.append(numpy_helper.from_array(np.array([0], dtype=dtype), value.name))
    del graph.graph.input[1:]
    graph.graph.input[0].type.tensor_type.shape.dim[0].dim_param = 'sentences'
    onnx.save(graph, OUT / 'tokenizer.onnx')
    mapping = [src.get(sp.id_to_piece(i), src['<unk>']) for i in range(sp.get_piece_size())]
    pieces = [''] * (max(tgt.values()) + 1)
    for piece, index in tgt.items():
        pieces[index] = piece if index > 3 else ''
    languages = {'hi': 'hin_Deva', 'bn': 'ben_Beng', 'te': 'tel_Telu', 'mr': 'mar_Deva',
        'ta': 'tam_Taml', 'ur': 'urd_Arab', 'gu': 'guj_Gujr', 'kn': 'kan_Knda',
        'ml': 'mal_Mlym', 'pa': 'pan_Guru', 'or': 'ory_Orya', 'as': 'asm_Beng'}
    # Preserve the official IndicNLP per-character script conversion, including Tamil mappings.
    scripts = {lang: [UnicodeIndicTransliterator.transliterate(chr(i), 'hi', lang)
        for i in range(0x900, 0x980)] for lang in languages}
    tok = MosesTokenizer(lang='en')
    def compact(characters):
        codes = sorted(set(map(ord, characters)))
        ranges = []
        start = end = codes[0]
        def escaped(code):
            return f'\\u{code:04x}' if code <= 0xffff else f'\\x{{{code:x}}}'
        for code in codes[1:]:
            if code == end + 1: end = code
            else:
                ranges.append(escaped(start) + ('-' + escaped(end) if end > start else ''))
                start = end = code
        ranges.append(escaped(start) + ('-' + escaped(end) if end > start else ''))
        return ''.join(ranges)
    charsets = [(getattr(tok, name), compact(getattr(tok, name))) for name in ['IsAlnum', 'IsAlpha', 'IsLower', 'IsN'] if hasattr(tok, name)]
    def rules(values):
        result = []
        for pattern, replacement in values:
            pattern = getattr(pattern, 'pattern', pattern)
            for original, ranges in charsets: pattern = pattern.replace(original, ranges)
            result.append([pattern, replacement])
        return result
    proc = IndicProcessor(inference=True)
    import ast
    import inspect
    import textwrap
    punc_literals = []
    class Replacements(ast.NodeVisitor):
        def visit_Call(self, node):
            self.generic_visit(node)
            if isinstance(node.func, ast.Attribute) and node.func.attr == 'replace' and len(node.args) == 2:
                if all(isinstance(arg, ast.Constant) for arg in node.args):
                    punc_literals.append([arg.value for arg in node.args])
    Replacements().visit(ast.parse(textwrap.dedent(inspect.getsource(IndicProcessor._punc_norm))))
    proc._normalize('gestalk@example.com')
    entity_variants = [key.replace('1', '{id}') for key in proc._placeholder_entity_maps.get()]
    processing = {'normalizer': rules(MosesPunctNormalizer().substitutions),
        'punctuationLiterals': punc_literals,
        'punctuationRegex': rules([(proc._multispace_regex, ' '), (proc._end_bracket_space_punc_regex, r')\1'),
            (proc._digit_space_percent, r'\1%'), (proc._double_quot_punc, r'\1"'), (proc._digit_nbsp_digit, r'\1.\2')]),
        'initial': rules([tok.DEDUPLICATE_SPACE, tok.ASCII_JUNK, tok.PAD_NOT_ISALNUM]),
        'afterDots': rules([tok.COMMA_SEPARATE_1, tok.COMMA_SEPARATE_2, tok.COMMA_SEPARATE_3,
            *tok.ENGLISH_SPECIFIC_APOSTROPHE]),
        'trailing': rules([tok.DEDUPLICATE_SPACE, tok.TRAILING_DOT_APOSTROPHE]),
        'prefixes': list(tok.NONBREAKING_PREFIXES), 'numericPrefixes': list(tok.NUMERIC_ONLY_PREFIXES),
        'entities': [proc._EMAIL_PATTERN, proc._URL_PATTERN, proc._NUMERAL_PATTERN, proc._OTHER_PATTERN],
        'entityVariants': entity_variants,
        'detokenizer': rules([(getattr(indic_detokenize, n), r'\1') for n in ['pat_lra', 'pat_la', 'pat_ra']]),
        'numbers': indic_detokenize.pat_num_seq.pattern}
    (OUT / 'vocab.json').write_text(json.dumps({'sourceMap': mapping, 'pieces': pieces,
        'englishTag': src['eng_Latn'], 'languageTags': {k: src[v] for k, v in languages.items()},
        'scripts': scripts, 'processing': processing}, ensure_ascii=False), encoding='utf-8')
    shutil.copyfile(MODEL / 'LICENSE', OUT / 'LICENSE')
    shutil.copyfile(ROOT / 'vendor/LICENSE', OUT / 'PREPROCESSOR-LICENSE')
    import sacremoses
    sacremoses_license = next(Path(sacremoses.__file__).parent.parent.glob('sacremoses-*.dist-info/LICENSE'))
    shutil.copyfile(sacremoses_license, OUT / 'SACREMOSES-LICENSE')
    if (ROOT / 'vendor/INDICNLP-LICENSE').exists():
        shutil.copyfile(ROOT / 'vendor/INDICNLP-LICENSE', OUT / 'INDICNLP-LICENSE')
    (OUT / 'NOTICE.txt').write_text('IndicTrans2 English-to-Indic distilled 200M, AI4Bharat, MIT license.\n'
        'Original revision: 2cfd0a20057756d756bf3e53528f492afc52f91a.\n'
        'Converted to ONNX with INT8 weight quantization for Samvaad Android.\n'
        'IndicNLP script mapping: Anoop Kunchukuttan, MIT license.\n', encoding='utf-8')
    print('Exported native SentencePiece tokenizer and official script mappings', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['encoder', 'decoder', 'quantize-encoder', 'quantize-decoder', 'tokenizer'])
    action = parser.parse_args().action
    OUT.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=True)
    if action.startswith('quantize-'): quantize(action.removeprefix('quantize-'))
    elif action == 'tokenizer': tokenizer()
    else: export(action)
