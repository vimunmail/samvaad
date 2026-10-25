"""Private-network IndicTrans2 translator. No paid translation API is used."""
from __future__ import annotations

import argparse
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import socket
import sqlite3
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parent
STATE = ROOT / ".state"
MODEL = "ai4bharat/indictrans2-en-indic-dist-200M"
REVISION = "2cfd0a20057756d756bf3e53528f492afc52f91a"
LANGUAGES = {
    "hi": "hin_Deva", "bn": "ben_Beng", "te": "tel_Telu", "mr": "mar_Deva",
    "ta": "tam_Taml", "ur": "urd_Arab", "gu": "guj_Gujr", "kn": "kan_Knda",
    "ml": "mal_Mlym", "pa": "pan_Guru", "or": "ory_Orya", "as": "asm_Beng",
}
SCRIPT_RANGES = {
    "hi": (0x900, 0x97F), "mr": (0x900, 0x97F), "bn": (0x980, 0x9FF),
    "as": (0x980, 0x9FF), "pa": (0xA00, 0xA7F), "gu": (0xA80, 0xAFF),
    "or": (0xB00, 0xB7F), "ta": (0xB80, 0xBFF), "te": (0xC00, 0xC7F),
    "kn": (0xC80, 0xCFF), "ml": (0xD00, 0xD7F), "ur": (0x600, 0x6FF),
}


def validate(payload):
    if not isinstance(payload, dict) or payload.get("target") not in LANGUAGES:
        raise ValueError("Choose a supported regional language.")
    texts = payload.get("texts")
    if not isinstance(texts, list) or not 1 <= len(texts) <= 16:
        raise ValueError("Send 1 to 16 English phrases.")
    for text in texts:
        if not isinstance(text, str) or not text.strip() or len(text) > 300:
            raise ValueError("Use English phrases of 1 to 300 characters.")
        if re.search(r"[\x00-\x1f\x7f]|<ctrl\d+>|<unk>", text, re.I):
            raise ValueError("Invalid phrase.")
    if sum(map(len, texts)) > 5000:
        raise ValueError("Too many characters in one request.")
    return texts, payload["target"]


def valid_output(text, language):
    if not isinstance(text, str) or not text.strip() or re.search(r"<ctrl\d+>|<unk>", text, re.I):
        return False
    letters = [c for c in text if c.isalpha()]
    low, high = SCRIPT_RANGES[language]
    # Numbers may remain unchanged; English sentences must not become English audio.
    return not letters or any(low <= ord(c) <= high for c in letters)


class IndicTranslator:
    def __init__(self):
        import torch
        from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
        from vendor.processor import IndicProcessor

        torch.set_num_threads(max(1, min(4, (os.cpu_count() or 2) // 2)))
        self.torch = torch
        path = STATE / "model"
        if not (path / "config.json").exists():
            raise RuntimeError("The free model is not downloaded. Run Setup translator.cmd first.")
        self.tokenizer = AutoTokenizer.from_pretrained(path, trust_remote_code=True, local_files_only=True)
        self.model = AutoModelForSeq2SeqLM.from_pretrained(
            path, trust_remote_code=True, local_files_only=True,
            torch_dtype=torch.float32, attn_implementation="eager",
        ).to("cpu").eval()
        self.processor_type = IndicProcessor
        self.cache = sqlite3.connect(STATE / "translations.sqlite3", check_same_thread=False)
        self.cache.execute("CREATE TABLE IF NOT EXISTS translations (key TEXT PRIMARY KEY, text TEXT NOT NULL)")

    def translate(self, texts, language):
        result = []
        # One short phrase at a time keeps CPU memory and generation latency bounded.
        for text in texts:
            key = json.dumps([REVISION, text, language], ensure_ascii=False)
            cached = self.cache.execute("SELECT text FROM translations WHERE key=?", (key,)).fetchone()
            if cached:
                result.append(cached[0])
                continue
            processor = self.processor_type(inference=True)
            batch = processor.preprocess_batch([text], src_lang="eng_Latn", tgt_lang=LANGUAGES[language])
            inputs = self.tokenizer(batch, truncation=False, padding=True, return_tensors="pt", return_attention_mask=True)
            if inputs["input_ids"].shape[1] > 256:
                raise ValueError("This phrase is too long for the model. Use a shorter English meaning.")
            with self.torch.inference_mode():
                generated = self.model.generate(**inputs, use_cache=True, max_length=256, num_beams=5, num_return_sequences=1)
            decoded = self.tokenizer.batch_decode(generated, skip_special_tokens=True, clean_up_tokenization_spaces=True)
            output = processor.postprocess_batch(decoded, lang=LANGUAGES[language])[0].strip()
            if not valid_output(output, language):
                raise ValueError("The model did not produce regional text. Review the English meaning and retry.")
            self.cache.execute("INSERT OR REPLACE INTO translations VALUES (?, ?)", (key, output))
            self.cache.commit()
            result.append(output)
        return result


class TranslationServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, token, translator):
        super().__init__(address, Handler)
        self.token = token
        self.translator = translator
        self.translation_lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Do not log English phrases or pairing keys.

    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def send_json(self, status, value):
        data = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def authorized(self):
        address = ipaddress.ip_address(self.client_address[0])
        if not (address.is_private or address.is_loopback):
            self.send_json(403, {"error": "Private network only."})
            return False
        supplied = self.headers.get("Authorization", "")
        if not supplied.isascii() or len(supplied) > 512 or not hmac.compare_digest(supplied, "Bearer " + self.server.token):
            self.send_json(401, {"error": "Pairing key required."})
            return False
        return True

    def do_GET(self):
        if not self.authorized():
            return
        if self.path != "/health":
            self.send_json(404, {"error": "Unknown path."})
            return
        ready = self.server.translator is not None
        self.send_json(200 if ready else 503, {"provider": "indictrans2", "ready": ready, "languages": list(LANGUAGES)})

    def do_POST(self):
        if not self.authorized():
            return
        if self.path != "/translate":
            self.send_json(404, {"error": "Unknown path."})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 1 <= size <= 65536 or "application/json" not in self.headers.get("Content-Type", ""):
                raise ValueError("Send a small JSON request.")
            raw = self.rfile.read(size)
            if len(raw) != size:
                raise ValueError("Incomplete request.")
            texts, target = validate(json.loads(raw))
        except (ValueError, UnicodeError):
            self.send_json(400, {"error": "Invalid English phrases or target language."})
            return
        if self.server.translator is None:
            self.send_json(503, {"error": "Finish model setup first."})
            return
        if not self.server.translation_lock.acquire(blocking=False):
            self.send_json(429, {"error": "Translator busy. Retry shortly."})
            return
        try:
            output = self.server.translator.translate(texts, target)
            if len(output) != len(texts) or not all(valid_output(x, target) for x in output):
                raise ValueError("Incomplete translation.")
            self.send_json(200, {"provider": "indictrans2", "target": target, "translations": output})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            self.send_json(422, {"error": "Could not produce a complete regional translation. Shorten or review the English meaning."})
        finally:
            self.server.translation_lock.release()


def pairing(port):
    STATE.mkdir(exist_ok=True)
    config = STATE / "config.json"
    if config.exists():
        token = json.loads(config.read_text())["token"]
    else:
        token = secrets.token_urlsafe(32)
        config.write_text(json.dumps({"token": token}))
    address = "127.0.0.1"
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("192.0.2.1", 9))  # Route lookup only; no data is sent.
            address = sock.getsockname()[0]
    except OSError:
        pass
    text = f"Samvaad computer translator\n\nComputer address: http://{address}:{port}\nPairing key: {token}\n\nEnter both in Tools > Model translations on your phone.\nUse the same trusted Wi-Fi. Keep this key private.\n"
    (STATE / "connection.txt").write_text(text, encoding="utf-8")
    return token, address


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--host", default="0.0.0.0")
    args = parser.parse_args()
    token, address = pairing(args.port)
    print("Loading IndicTrans2 on your computer. No translation API charges.", flush=True)
    translator = IndicTranslator()
    server = TranslationServer((args.host, args.port), token, translator)
    print(f"Ready at http://{address}:{args.port}. Pairing details are in .state/connection.txt.", flush=True)
    print("Keep this window open. Press Ctrl+C to stop.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Translator could not start: {error}")
        raise SystemExit(1)
