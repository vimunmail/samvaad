import http.client
import json
import threading
import unittest

from server import LANGUAGES, TranslationServer, validate, valid_output


class FakeTranslator:
    def __init__(self):
        self.calls = []
        self.output = "എനിക്ക് വിശ്രമിക്കാൻ ശാന്തമായ ഒരു സ്ഥലം വേണം"

    def translate(self, texts, target):
        self.calls.append((texts, target))
        return [self.output] * len(texts)


class ComputerTranslationTest(unittest.TestCase):
    def setUp(self):
        self.engine = FakeTranslator()
        self.server = TranslationServer(("127.0.0.1", 0), "test-key-with-at-least-24-chars", self.engine)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def request(self, method, path, body=None, authorized=True):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        headers = {"Content-Type": "application/json"}
        if authorized:
            headers["Authorization"] = "Bearer test-key-with-at-least-24-chars"
        connection.request(method, path, None if body is None else json.dumps(body), headers)
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_health_and_all_twelve_language_mappings(self):
        status, body = self.request("GET", "/health")
        self.assertEqual(status, 200)
        self.assertTrue(body["ready"])
        self.assertEqual(len(body["languages"]), 12)
        self.assertEqual(LANGUAGES["ml"], "mal_Mlym")
        for language in LANGUAGES:
            self.assertEqual(validate({"texts": ["I do not need water."], "target": language}), (["I do not need water."], language))

    def test_pairing_required_before_model_call(self):
        status, _ = self.request("POST", "/translate", {"texts": ["Hello"], "target": "ml"}, authorized=False)
        self.assertEqual(status, 401)
        self.assertEqual(self.engine.calls, [])

    def test_full_phrase_and_selected_language_reach_model(self):
        text = "I do not want to leave this place."
        status, body = self.request("POST", "/translate", {"texts": [text], "target": "ml"})
        self.assertEqual(status, 200)
        self.assertEqual(self.engine.calls, [([text], "ml")])
        self.assertEqual(body["translations"], [self.engine.output])
        self.assertEqual(body["target"], "ml")

    def test_invalid_requests_never_reach_model(self):
        for payload in [{"texts": [], "target": "ml"}, {"texts": ["x" * 301], "target": "ml"},
                        {"texts": ["hello"], "target": "en"}, {"texts": ["<ctrl1>"], "target": "ml"}]:
            self.assertEqual(self.request("POST", "/translate", payload)[0], 400)
        self.assertEqual(self.engine.calls, [])

    def test_english_output_is_not_returned_as_malayalam(self):
        self.engine.output = "I need water"
        self.assertEqual(self.request("POST", "/translate", {"texts": ["I need water"], "target": "ml"})[0], 422)
        self.assertTrue(valid_output("123", "ml"))
        self.assertFalse(valid_output("<unk>", "ml"))

    def test_busy_and_missing_model_return_retryable_status(self):
        self.server.translation_lock.acquire()
        try:
            self.assertEqual(self.request("POST", "/translate", {"texts": ["hello"], "target": "ml"})[0], 429)
        finally:
            self.server.translation_lock.release()
        self.server.translator = None
        self.assertEqual(self.request("GET", "/health")[0], 503)
        self.assertEqual(self.request("POST", "/translate", {"texts": ["hello"], "target": "ml"})[0], 503)


if __name__ == "__main__":
    unittest.main()
