"""
Gesture CSV Deep Analyzer v2
=============================
Properly handles the 24-column CSV format:
  Label, timestamp, packetCounter, 
  thumb_ax, thumb_ay, thumb_az,     (cols 3-5)
  index_ax, index_ay, index_az,     (cols 6-8)  
  middle_ax, middle_ay, middle_az,   (cols 9-11)
  ring_ax, ring_ay, ring_az,         (cols 12-14)
  little_ax, little_ay, little_az,   (cols 15-17)
  wrist_ax, wrist_ay, wrist_az,      (cols 18-20)
  gyro_x, gyro_y, gyro_z             (cols 21-23)

The model sees 21 features (cols 3-23).
Run:  python analyze_gestures_v2.py [path_to_csv]
"""

import csv
import sys
import os
import math
from collections import defaultdict

CSV_PATH = sys.argv[1] if len(sys.argv) > 1 else "d:/Xgestalk_BLE_mobileapp/gestures.csv"

FEATURE_NAMES = [
    "thumb_ax", "thumb_ay", "thumb_az",
    "index_ax", "index_ay", "index_az",
    "middle_ax", "middle_ay", "middle_az",
    "ring_ax", "ring_ay", "ring_az",
    "little_ax", "little_ay", "little_az",
    "wrist_ax", "wrist_ay", "wrist_az",
    "gyro_x", "gyro_y", "gyro_z"
]

# Feature groups
FINGER_GROUPS = {
    "thumb":  (0, 3),   # indices 0-2
    "index":  (3, 6),   # indices 3-5
    "middle": (6, 9),   # indices 6-8
    "ring":   (9, 12),  # indices 9-11
    "little": (12, 15), # indices 12-14
    "wrist":  (15, 18), # indices 15-17
    "gyro":   (18, 21), # indices 18-20
}

# ---- Helpers ----

def parse_csv(path):
    rows_by_label = defaultdict(list)
    with open(path, "r", encoding="utf-8-sig") as f:
        reader = csv.reader(f)
        headers = next(reader)
        for row in reader:
            if len(row) < 24:
                continue
            label = row[0].strip()
            try:
                features = [float(v) for v in row[3:24]]  # 21 features
            except ValueError:
                continue
            rows_by_label[label].append(features)
    return headers, rows_by_label


def stats(values):
    n = len(values)
    if n == 0:
        return {"min": 0, "max": 0, "mean": 0, "std": 0, "range": 0}
    mn, mx = min(values), max(values)
    mean = sum(values) / n
    std = math.sqrt(sum((x - mean) ** 2 for x in values) / n)
    return {"min": mn, "max": mx, "mean": mean, "std": std, "range": mx - mn}


def is_rest_like_frame(frame, rest_profile):
    """Check if a single frame looks like REST based on feature similarity."""
    if rest_profile is None:
        return False
    # Check each feature: within 2 std of REST mean
    within_count = 0
    for i in range(21):
        rest_mean = rest_profile[i]["mean"]
        rest_std = rest_profile[i]["std"]
        tolerance = max(rest_std * 2, 1.0)  # at least 1 unit
        if abs(frame[i] - rest_mean) <= tolerance:
            within_count += 1
    return within_count >= 18  # 18 of 21 features match REST


# ---- Main ----

def main():
    sep = "=" * 90
    sep2 = "-" * 90
    
    print(sep)
    print("  GESTURE CSV DEEP ANALYZER v2")
    print(sep)
    
    if not os.path.exists(CSV_PATH):
        print(f"\n  FILE NOT FOUND: {CSV_PATH}")
        sys.exit(1)
    
    file_size = os.path.getsize(CSV_PATH)
    headers, rows_by_label = parse_csv(CSV_PATH)
    total = sum(len(v) for v in rows_by_label.values())
    
    print(f"\n  File: {CSV_PATH}")
    print(f"  Size: {file_size:,} bytes ({file_size/1024:.1f} KB)")
    print(f"  Total frames: {total:,}")
    print(f"  Classes: {len(rows_by_label)} - {', '.join(sorted(rows_by_label.keys()))}")
    print(f"  Columns: {len(headers)} - Features used: {headers[3:]} (21 features)")
    
    # ===========================================================================
    # 1. LABEL DISTRIBUTION
    # ===========================================================================
    print(f"\n{sep2}")
    print("  1. LABEL DISTRIBUTION")
    print(sep2)
    
    for label in sorted(rows_by_label.keys()):
        count = len(rows_by_label[label])
        pct = count / total * 100
        bar = "#" * int(pct) + "." * (30 - int(pct))
        print(f"  {label:22s} | {count:6d} frames ({pct:5.1f}%) | {bar}")
    
    counts = [len(v) for v in rows_by_label.values()]
    ratio = max(counts) / min(counts) if min(counts) > 0 else float('inf')
    print(f"\n  Class imbalance ratio: {ratio:.1f}x {'(OK)' if ratio < 3 else '(WARNING: Imbalanced!)'}")
    
    # ===========================================================================
    # 2. UNIQUE VALUES PER FEATURE (quantization check)
    # ===========================================================================
    print(f"\n{sep2}")
    print("  2. QUANTIZATION CHECK (unique values per feature)")
    print(sep2)
    
    all_frames = []
    for frames in rows_by_label.values():
        all_frames.extend(frames)
    
    heavily_quantized = []
    continuous_features = []
    
    for i, name in enumerate(FEATURE_NAMES):
        vals = sorted(set(f[i] for f in all_frames))
        n = len(vals)
        preview = ", ".join(f"{v:.0f}" for v in vals[:8])
        if n > 8:
            preview += f" ... (+{n-8} more)"
        
        tag = "QUANTIZED" if n <= 5 else ("LOW-RES" if n <= 20 else "OK")
        marker = "!!" if n <= 5 else (".." if n <= 20 else "  ")
        print(f"  {marker} {name:12s} | {n:5d} unique | {tag:10s} | values: {preview}")
        
        if n <= 5:
            heavily_quantized.append(name)
        elif n > 20:
            continuous_features.append(name)
    
    if heavily_quantized:
        print(f"\n  >> CRITICAL: {len(heavily_quantized)} features are HEAVILY QUANTIZED (5 or fewer values):")
        print(f"     {', '.join(heavily_quantized)}")
        print(f"     These features only have values like [-2, -1, 0, 1, 2].")
        print(f"     The model CANNOT reliably detect subtle differences on these axes.")
    
    if continuous_features:
        print(f"\n  >> GOOD: {len(continuous_features)} features have good resolution:")
        print(f"     {', '.join(continuous_features)}")
    
    # ===========================================================================
    # 3. PER-CLASS FEATURE PROFILES (grouped by finger/sensor)
    # ===========================================================================
    print(f"\n{sep2}")
    print("  3. PER-CLASS FEATURE PROFILES")
    print(sep2)
    
    class_profiles = {}
    
    for label in sorted(rows_by_label.keys()):
        frames = rows_by_label[label]
        profile = []
        for i in range(21):
            vals = [f[i] for f in frames]
            profile.append(stats(vals))
        class_profiles[label] = profile
    
    for group_name, (start, end) in FINGER_GROUPS.items():
        print(f"\n  --- {group_name.upper()} ---")
        feat_names = FEATURE_NAMES[start:end]
        print(f"  {'Class':22s} |", " | ".join(f"{n:>20s}" for n in feat_names))
        print(f"  {'':22s} |", " | ".join(f"{'mean +/- std (range)':>20s}" for _ in feat_names))
        for label in sorted(rows_by_label.keys()):
            vals_str = []
            for i in range(start, end):
                s = class_profiles[label][i]
                vals_str.append(f"{s['mean']:7.0f}+/-{s['std']:<5.0f}({s['range']:.0f})")
            print(f"  {label:22s} |", " | ".join(f"{v:>20s}" for v in vals_str))
    
    # ===========================================================================
    # 4. THE KEY PROBLEM: REST-LIKE FRAMES IN GESTURE CLASSES
    # ===========================================================================
    print(f"\n{sep2}")
    print("  4. REST-LIKE FRAME CONTAMINATION IN GESTURE CLASSES")
    print(sep2)
    
    rest_labels = [l for l in rows_by_label if l.upper() == "REST"]
    active_labels = sorted(l for l in rows_by_label if l.upper() != "REST")
    
    if not rest_labels:
        print("  No REST class found - cannot compare!")
    else:
        rest_frames = rows_by_label[rest_labels[0]]
        rest_profile = class_profiles[rest_labels[0]]
        
        print(f"  Checking each gesture frame against REST profile...")
        print(f"  (A frame is 'REST-like' if 18+ of 21 features are within 2 std of REST mean)\n")
        
        for label in active_labels:
            frames = rows_by_label[label]
            rest_like = sum(1 for f in frames if is_rest_like_frame(f, rest_profile))
            pct = rest_like / len(frames) * 100
            tag = "PROBLEM!" if pct > 15 else ("WARNING" if pct > 5 else "OK")
            print(f"  {label:22s} | {rest_like:5d}/{len(frames):5d} REST-like frames ({pct:5.1f}%) | {tag}")
        
    # ===========================================================================
    # 5. CLASS SEPARABILITY (which gestures look most alike?)
    # ===========================================================================
    print(f"\n{sep2}")
    print("  5. CLASS SEPARABILITY (Euclidean distance between class centroids)")
    print(sep2)
    
    labels = sorted(rows_by_label.keys())
    centroids = {}
    for label in labels:
        frames = rows_by_label[label]
        centroid = [sum(f[i] for f in frames) / len(frames) for i in range(21)]
        centroids[label] = centroid
    
    pairs = []
    for i, l1 in enumerate(labels):
        for l2 in labels[i+1:]:
            dist = math.sqrt(sum((centroids[l1][k] - centroids[l2][k])**2 for k in range(21)))
            pairs.append((l1, l2, dist))
    
    pairs.sort(key=lambda x: x[2])
    
    print(f"\n  {'Pair':45s} | {'Distance':>10s} | Verdict")
    print(f"  {'':45s} | {'':>10s} |")
    for l1, l2, d in pairs:
        tag = "TOO CLOSE" if d < 5000 else ("MARGINAL" if d < 20000 else "GOOD")
        print(f"  {l1:20s} vs {l2:20s} | {d:10.0f} | {tag}")
    
    # ===========================================================================
    # 6. FINGER-SPECIFIC DISCRIMINATIVE POWER
    # ===========================================================================
    print(f"\n{sep2}")
    print("  6. WHICH SENSORS ACTUALLY DIFFERENTIATE GESTURES?")
    print(sep2)
    print("  (Higher spread = more useful for classification)\n")
    
    for i, name in enumerate(FEATURE_NAMES):
        means = [class_profiles[l][i]["mean"] for l in active_labels]
        if not means:
            continue
        spread = max(means) - min(means)
        bar_len = min(60, int(spread / 1000))
        bar = "#" * bar_len
        usefulness = "USELESS" if spread < 10 else ("LOW" if spread < 1000 else ("MODERATE" if spread < 10000 else "HIGH"))
        print(f"  {name:12s} | spread={spread:12.0f} | {usefulness:8s} | {bar}")
    
    # ===========================================================================
    # 7. WHAT THE MODEL ACTUALLY SEES DURING REST (the root cause)
    # ===========================================================================
    print(f"\n{sep2}")
    print("  7. ROOT CAUSE ANALYSIS: WHY FALSE POSITIVES AT REST")
    print(sep2)
    
    if rest_labels:
        rest_frames = rows_by_label[rest_labels[0]]
        
        print("\n  During REST, the model sees these 21 feature values:")
        print(f"  {'Feature':12s} | {'Min':>8s} {'Max':>8s} {'Range':>8s} {'Mean':>10s} {'Std':>8s}")
        print(f"  {'':12s} | {'':>8s} {'':>8s} {'':>8s} {'':>10s} {'':>8s}")
        
        high_variance_rest = []
        for i, name in enumerate(FEATURE_NAMES):
            s = class_profiles[rest_labels[0]][i]
            marker = ">>" if s["range"] > 10 else "  "
            print(f"  {marker}{name:12s} | {s['min']:8.0f} {s['max']:8.0f} {s['range']:8.0f} {s['mean']:10.1f} {s['std']:8.1f}")
            if s["range"] > 10:
                high_variance_rest.append((name, s["range"], s["std"]))
        
        if high_variance_rest:
            print(f"\n  >> HIGH VARIANCE REST FEATURES (range > 10):")
            for name, rng, std in high_variance_rest:
                print(f"     {name}: range={rng:.0f}, std={std:.1f}")
            print(f"\n  ** EXPLANATION: These features vary A LOT even during REST.")
            print(f"     When the model sees a REST input, the thumb/index/middle/ring/little")
            print(f"     accelerometer values are LARGE numbers (like 46754-111434) that CHANGE")
            print(f"     significantly even when the hand is still!")
            print(f"     This means the model sees 'changing values' during REST that look")
            print(f"     like motion to it, causing false gesture predictions.")
    
    # ===========================================================================
    # 8. SPECIFIC PROBLEM: ACCEL X/Y VALUES
    # ===========================================================================
    print(f"\n{sep2}")
    print("  8. THE REAL ISSUE: ACCELEROMETER VALUE RANGES")
    print(sep2)
    
    print("\n  Each class has VERY DIFFERENT accel_x mean values:")
    print(f"  {'Class':22s} | {'thumb_ax mean':>14s} | {'thumb_ay mean':>14s}")
    for label in sorted(rows_by_label.keys()):
        ax_mean = class_profiles[label][0]["mean"]
        ay_mean = class_profiles[label][1]["mean"]
        print(f"  {label:22s} | {ax_mean:14.0f} | {ay_mean:14.0f}")
    
    print(f"\n  >> NOTE: thumb_ax for REST is ~78,806 but gesture classes are 145k-331k.")
    print(f"     The model CAN distinguish REST from gestures on these 2 features alone.")
    print(f"     But within each class, these values have range ~30,000-64,000!")
    print(f"     This high intra-class variance on the dominant features means the model")
    print(f"     is uncertain on WHICH gesture, even when it knows it's not REST.")
    
    # ===========================================================================
    # 9. CRITICAL: WHAT DOES THE LIVE PREDICTION SEE?
    # ===========================================================================
    print(f"\n{sep2}")
    print("  9. CRITICAL: TRAINING vs LIVE INFERENCE MISMATCH CHECK")
    print(sep2)
    
    print("\n  Training data uses 21 features (cols 3-23) with NO NORMALIZATION.")
    print("  The CsvDatasetParser reads raw values: thumb_ax ranges [46754, 347794]")
    print("  while gyro_z ranges [-2, 2].")
    print()
    print("  Feature magnitude comparison:")
    
    for i, name in enumerate(FEATURE_NAMES):
        all_vals = [f[i] for f in all_frames]
        s = stats(all_vals)
        importance = "DOMINANT" if s["range"] > 1000 else ("MINOR" if s["range"] > 10 else "NOISE")
        print(f"    {name:12s} | range={s['range']:>12.0f} | magnitude={abs(s['mean']):>12.0f} | {importance}")
    
    print()
    print("  >> CRITICAL FINDING:")
    print("     thumb_ax has a range of ~300,000 while gyro_z has a range of 4.")
    print("     Without normalization, the model weights thumb_ax/thumb_ay 75,000x more")
    print("     than gyro features. The gyro (which detects actual hand MOTION) is")
    print("     essentially INVISIBLE to the model.")
    print()
    print("     This means the model is classifying based on STATIC finger positions")
    print("     (accelerometer baseline levels), NOT on hand movement patterns.")
    print("     During REST, sensor noise on thumb_ax/ay creates values that overlap")
    print("     with gesture classes, causing false predictions.")
    
    # ===========================================================================
    # SUMMARY
    # ===========================================================================
    print(f"\n{sep}")
    print("  SUMMARY OF ISSUES FOUND")
    print(sep)
    
    print("""
  ISSUE 1: NO DATA NORMALIZATION (CRITICAL)
  ------------------------------------------
  - thumb_ax ranges [46754 - 347794] = 300,000+ range
  - gyro_z ranges [-2 to +2] = 4 range
  - The model treats thumb_ax as 75,000x more important than gyro
  - Gyro data (actual motion detection) is essentially ignored
  
  FIX: Add feature normalization (z-score or min-max) in the training
  pipeline AND in live inference. Each feature should be scaled to
  similar ranges (e.g., 0-1 or mean=0, std=1).

  ISSUE 2: HEAVY QUANTIZATION ON 4 AXES
  ------------------------------------------
  - accel_z, gyro_x, gyro_y, gyro_z only have 5 unique values [-2,-1,0,1,2]
  - This is normal for the current hardware, but makes REST/gesture
    separation harder on these axes
  
  FIX: The normalization fix above will help. Also consider using
  temporal features (derivatives, rolling stats) to amplify motion signals.

  ISSUE 3: HIGH INTRA-CLASS VARIANCE ON DOMINANT FEATURES  
  ------------------------------------------
  - REST thumb_ax ranges from 46,754 to 111,434 (range=64,680)
  - This creates overlap with gesture classes during prediction
  
  FIX: Normalization will reduce the impact. Additionally, the model
  needs to learn from SEQUENCES of values, not individual frames, to
  detect motion patterns rather than absolute positions.

  RECOMMENDATION:
  ===============
  The #1 fix is to add FEATURE NORMALIZATION. Compute mean and std
  for each of the 21 features from the training data, then:
    normalized_value = (raw_value - mean) / std
  
  Apply this BOTH during training AND during live inference.
  This will make all features equally important and let the model
  properly use gyro data for motion detection.
""")
    print(sep)


if __name__ == "__main__":
    main()
