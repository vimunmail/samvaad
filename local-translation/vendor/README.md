# Upstream preprocessing

`processor.py` and `LICENSE` are unchanged from the MIT-licensed
[IndicTransToolkit](https://github.com/VarunGumma/IndicTransToolkit/tree/0c607654e8b6cce897a32039df79018a1daff4e2),
commit `0c607654e8b6cce897a32039df79018a1daff4e2`.

This official pure Python processor is used because current toolkit releases
require a Cython extension and do not provide Windows wheels. Its normalization,
entity preservation, language tags and script conversion must remain paired with
IndicTrans2; do not replace them with word-by-word translation.
