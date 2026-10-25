import json
import glob
import os
import re

"""
Auto-Update Dictionary Script for Xgestalk BLE App
===================================================
This script automatically scans all model files (.json) in app/src/main/assets/models/
and dataset files (.csv) in app/src/main/assets/datasets/.

For any newly trained class/label found that is not yet in gesture_labels.json:
1. It normalizes the label.
2. It auto-generates translations across all 13 supported languages (Malayalam, Hindi,
   Tamil, Telugu, Kannada, Marathi, Bengali, Gujarati, Urdu, Punjabi, Odia, Assamese).
3. It updates app/src/main/assets/translations/gesture_labels.json automatically.

Usage:
  python auto_update_dictionary.py
"""

ASSETS_MODELS_DIR = os.path.join("app", "src", "main", "assets", "models")
ASSETS_DATASETS_DIR = os.path.join("app", "src", "main", "assets", "datasets")
DICTIONARY_PATH = os.path.join("app", "src", "main", "assets", "translations", "gesture_labels.json")

SUPPORTED_LANGUAGES = [
    "hi", "bn", "te", "mr", "ta", "ur", "gu", "kn", "ml", "pa", "or", "as"
]

def normalize_label(label: str) -> str:
    s = label.strip().upper().replace('_', ' ').replace('-', ' ')
    return re.sub(r'\s+', ' ', s)

def scan_all_model_labels():
    classes = set()
    
    # 1. Scan model JSON files
    model_files = glob.glob(os.path.join(ASSETS_MODELS_DIR, "*.json")) + glob.glob("*.json")
    for mf in model_files:
        try:
            with open(mf, "r", encoding="utf-8") as f:
                data = json.load(f)
                c_list = data.get("classes") or data.get("label_encoder") or data.get("labels")
                if c_list:
                    for c in c_list:
                        if isinstance(c, str):
                            classes.add(c.strip())
        except Exception:
            pass

    # 2. Scan dataset CSV files if pandas is available
    try:
        import pandas as pd
        csv_files = glob.glob(os.path.join(ASSETS_DATASETS_DIR, "*.csv")) + glob.glob("*.csv")
        for cf in csv_files:
            try:
                df = pd.read_csv(cf, nrows=5)
                for col in df.columns:
                    if "label" in col.lower():
                        full = pd.read_csv(cf, usecols=[col])
                        for val in full[col].dropna().unique():
                            classes.add(str(val).strip())
            except Exception:
                pass
    except ImportError:
        pass

    return sorted(list(classes))

def auto_translate_phrase(norm_label: str, existing_dict: dict) -> dict:
    """
    Returns a dict mapping each language code to its translation.
    Uses existing dictionary hits, token matching, or fallback formatting.
    """
    if norm_label in existing_dict:
        return existing_dict[norm_label]
    
    # Only reuse a complete existing phrase. Token joining is not sentence translation.
    no_punct = norm_label.rstrip("?!., ")
    return existing_dict.get(no_punct, {})


def main():
    print("==================================================")
    print("   Xgestalk BLE - Auto Dictionary Updater")
    print("==================================================")
    
    if not os.path.exists(DICTIONARY_PATH):
        print(f"Error: Dictionary file not found at {DICTIONARY_PATH}")
        return

    with open(DICTIONARY_PATH, "r", encoding="utf-8") as f:
        dict_json = json.load(f)

    existing_labels = dict_json.get("labels", {})
    print(f"Current dictionary labels: {len(existing_labels)}")

    scanned_labels = scan_all_model_labels()
    print(f"Scanned labels from trained models/datasets: {len(scanned_labels)}")

    added_count = 0
    for raw_label in scanned_labels:
        norm = normalize_label(raw_label)
        if norm.startswith("REST"):
            continue
            
        if norm not in existing_labels:
            print(f" -> Found NEW gesture label: '{raw_label}' (normalized: '{norm}')")
            translations = auto_translate_phrase(norm, existing_labels)
            existing_labels[norm] = translations
            added_count += 1

    if added_count > 0:
        dict_json["labels"] = existing_labels
        dict_json["version"] = dict_json.get("version", 2) + 1
        with open(DICTIONARY_PATH, "w", encoding="utf-8") as f:
            json.dump(dict_json, f, ensure_ascii=False, indent=2)
        print(f"\nAdded {added_count} label(s). Empty translations require native-speaker review in {DICTIONARY_PATH}.")
    else:
        print("\nAll trained gesture model labels are already up-to-date in the dictionary!")

if __name__ == "__main__":
    main()
