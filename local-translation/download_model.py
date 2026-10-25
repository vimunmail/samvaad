"""Download only the official pinned model, then check a real Malayalam sentence."""
from pathlib import Path
import sys

from huggingface_hub import get_token, login, snapshot_download
from huggingface_hub.errors import GatedRepoError, HfHubHTTPError
from server import STATE, MODEL, REVISION, IndicTranslator, pairing


def main():
    print("This is the free IndicTrans2 model. No Google billing is required.")
    print("First accept access at https://huggingface.co/ai4bharat/indictrans2-en-indic-dist-200M")
    if not get_token():
        print("Create a READ token at https://huggingface.co/settings/tokens and paste it into the hidden prompt below.")
        print("For a fine-grained token, allow reading the gated IndicTrans2 model. Never put this token in the phone app.")
        login(add_to_git_credential=False)
    STATE.mkdir(exist_ok=True)
    snapshot_download(
        MODEL, revision=REVISION, local_dir=STATE / "model",
        allow_patterns=["*.json", "*.py", "*.model", "model.SRC", "model.TGT", "*.vocab", "*.safetensors", "*.txt", "LICENSE*"],
    )
    pairing(8765)
    print("Checking a real English-to-Malayalam translation on this computer…", flush=True)
    engine = IndicTranslator()
    output = engine.translate(["I need a quiet place to rest."], "ml")
    print("Malayalam:", output[0])
    print("Setup complete. Open Start translator.cmd and keep it running while new labels are translated.")


if __name__ == "__main__":
    try:
        main()
    except GatedRepoError:
        print("Model access is not available. Sign into Hugging Face, accept the model's conditions, and wait for access before running setup again.")
        sys.exit(2)
    except HfHubHTTPError:
        print("The model could not be downloaded. Check your internet connection and account access, then run setup again.")
        sys.exit(2)
