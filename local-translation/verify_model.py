"""Check the running local service with fixed examples, without exposing its key."""
import json
from pathlib import Path
import time
import urllib.request
import urllib.error

from server import STATE, LANGUAGES, valid_output


def main():
    token = json.loads((STATE / "config.json").read_text())["token"]

    def request(path, payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        req = urllib.request.Request("http://127.0.0.1:8765" + path, data=data,
            headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=180) as response:
            return json.load(response)

    deadline = time.monotonic() + 45
    while True:
        try:
            ready = request("/health")
            break
        except urllib.error.HTTPError:
            raise
        except urllib.error.URLError:
            if time.monotonic() >= deadline:
                raise RuntimeError("The model is still loading. Wait for Ready in the translator window, then rerun this check.") from None
            time.sleep(3)
    assert ready["provider"] == "indictrans2" and ready["ready"]
    report = []
    checks = [("ml", "why you are crying"), ("ml", "Why are you crying?"),
              ("ml", "I do not want water.")] + [(lang, "I need water.") for lang in LANGUAGES]
    for lang, phrase in checks:
        started = time.monotonic()
        response = request("/translate", {"texts": [phrase], "target": lang})
        assert response["provider"] == "indictrans2" and response["target"] == lang
        text = response["translations"][0]
        assert valid_output(text, lang), f"No regional output for {lang}"
        report.append({"english": phrase, "language": lang, "translation": text,
                       "seconds": round(time.monotonic() - started, 2)})
        print(f"{lang}: {text}", flush=True)
    (STATE / "live-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Passed: authenticated service, Malayalam examples, and output in all 12 regional scripts.")
    print("Review meanings with fluent speakers; script checks do not prove semantic accuracy.")


if __name__ == "__main__":
    main()
