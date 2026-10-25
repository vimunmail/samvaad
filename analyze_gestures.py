"""
Gesture CSV Analyzer
====================
Analyzes gestures.csv to diagnose why the model predicts gestures at rest.

Run:  python analyze_gestures.py [path_to_csv]
Default path: d:/Xgestalk_BLE_mobileapp/gestures.csv
"""

import csv
import sys
import os
import math
from collections import defaultdict, Counter

# ─── Configuration ───────────────────────────────────────────────────────────
CSV_PATH = sys.argv[1] if len(sys.argv) > 1 else "d:/Xgestalk_BLE_mobileapp/gestures.csv"

# Thresholds (must match the app's TcnTrainer filter)
ACCEL_RANGE_THRESH = 1.5   # max(ax)-min(ax) across a window
GYRO_MAG_THRESH = 15.0     # sum of abs(gx,gy,gz) across a window
WINDOW_SIZE = 20           # sliding window for stationarity check

# ─── Helpers ─────────────────────────────────────────────────────────────────

def parse_csv(path):
    """Parse the gesture CSV. Returns (headers, rows_by_label)."""
    rows_by_label = defaultdict(list)
    with open(path, "r", encoding="utf-8-sig") as f:
        reader = csv.reader(f)
        headers = next(reader)
        for row in reader:
            if len(row) < 7:
                continue
            label = row[0].strip()
            try:
                vals = [float(v) for v in row[1:7]]  # ax,ay,az,gx,gy,gz
            except ValueError:
                continue
            rows_by_label[label].append(vals)
    return headers, rows_by_label


def compute_stats(values):
    """Compute min, max, mean, std for a list of numbers."""
    n = len(values)
    if n == 0:
        return {"min": 0, "max": 0, "mean": 0, "std": 0, "range": 0}
    mn = min(values)
    mx = max(values)
    mean = sum(values) / n
    variance = sum((x - mean) ** 2 for x in values) / n
    std = math.sqrt(variance)
    return {"min": mn, "max": mx, "mean": mean, "std": std, "range": mx - mn}


def is_stationary_window(window):
    """Check if a window of frames is stationary (rest-like)."""
    if len(window) < WINDOW_SIZE:
        return False
    ax_vals = [r[0] for r in window]
    ay_vals = [r[1] for r in window]
    az_vals = [r[2] for r in window]
    
    accel_range_x = max(ax_vals) - min(ax_vals)
    accel_range_y = max(ay_vals) - min(ay_vals)
    accel_range_z = max(az_vals) - min(az_vals)
    max_accel_range = max(accel_range_x, accel_range_y, accel_range_z)
    
    gyro_mag = sum(abs(r[3]) + abs(r[4]) + abs(r[5]) for r in window) / len(window)
    
    return max_accel_range < ACCEL_RANGE_THRESH and gyro_mag < GYRO_MAG_THRESH


def count_stationary_windows(frames):
    """Count how many sliding windows are stationary."""
    stationary = 0
    total = 0
    for i in range(len(frames) - WINDOW_SIZE + 1):
        window = frames[i:i + WINDOW_SIZE]
        total += 1
        if is_stationary_window(window):
            stationary += 1
    return stationary, total


def analyze_value_distribution(frames, label):
    """Analyze the distribution of sensor values for a label."""
    ax = [f[0] for f in frames]
    ay = [f[1] for f in frames]
    az = [f[2] for f in frames]
    gx = [f[3] for f in frames]
    gy = [f[4] for f in frames]
    gz = [f[5] for f in frames]
    
    return {
        "accel_x": compute_stats(ax),
        "accel_y": compute_stats(ay),
        "accel_z": compute_stats(az),
        "gyro_x": compute_stats(gx),
        "gyro_y": compute_stats(gy),
        "gyro_z": compute_stats(gz),
    }


def find_unique_values(frames):
    """Find unique values per axis - helps detect quantization."""
    unique_per_axis = []
    for axis in range(6):
        vals = set(f[axis] for f in frames)
        unique_per_axis.append(sorted(vals))
    return unique_per_axis


def detect_flat_segments(frames, min_length=10):
    """Detect segments where all sensor values are constant (stuck sensor)."""
    flat_segments = []
    start = 0
    for i in range(1, len(frames)):
        if frames[i] != frames[i-1]:
            length = i - start
            if length >= min_length:
                flat_segments.append((start, i-1, length, frames[start]))
            start = i
    # Check last segment
    length = len(frames) - start
    if length >= min_length:
        flat_segments.append((start, len(frames)-1, length, frames[start]))
    return flat_segments


def compute_class_overlap(rows_by_label):
    """Compute overlap between gesture classes using mean/std of each axis."""
    labels = sorted(rows_by_label.keys())
    profiles = {}
    for label in labels:
        frames = rows_by_label[label]
        profiles[label] = analyze_value_distribution(frames, label)
    
    overlap_pairs = []
    for i, l1 in enumerate(labels):
        for l2 in labels[i+1:]:
            # Compute overlap score based on how similar the distributions are
            overlap_score = 0
            axes = ["accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z"]
            for axis in axes:
                s1 = profiles[l1][axis]
                s2 = profiles[l2][axis]
                # Overlap if ranges overlap significantly
                range_overlap = min(s1["max"], s2["max"]) - max(s1["min"], s2["min"])
                total_range = max(s1["max"], s2["max"]) - min(s1["min"], s2["min"])
                if total_range > 0:
                    overlap_score += max(0, range_overlap / total_range)
            overlap_score /= len(axes)
            overlap_pairs.append((l1, l2, overlap_score))
    
    return sorted(overlap_pairs, key=lambda x: -x[2])


# ─── Main Analysis ───────────────────────────────────────────────────────────

def main():
    print("=" * 80)
    print("  GESTURE CSV ANALYZER")
    print("=" * 80)
    
    if not os.path.exists(CSV_PATH):
        print(f"\n❌ CSV file not found: {CSV_PATH}")
        print("   Please provide the correct path as an argument.")
        print(f"   Usage: python {sys.argv[0]} <path_to_gestures.csv>")
        sys.exit(1)
    
    file_size = os.path.getsize(CSV_PATH)
    print(f"\n📁 File: {CSV_PATH}")
    print(f"   Size: {file_size:,} bytes ({file_size/1024:.1f} KB)")
    
    headers, rows_by_label = parse_csv(CSV_PATH)
    total_frames = sum(len(v) for v in rows_by_label.values())
    
    print(f"   Headers: {headers}")
    print(f"   Total frames: {total_frames:,}")
    print(f"   Gesture classes: {len(rows_by_label)}")
    
    # ─── 1. Label Distribution ────────────────────────────────────────────
    print("\n" + "─" * 80)
    print("  1. LABEL DISTRIBUTION")
    print("─" * 80)
    
    for label in sorted(rows_by_label.keys()):
        count = len(rows_by_label[label])
        pct = count / total_frames * 100
        bar = "█" * int(pct / 2) + "░" * (50 - int(pct / 2))
        print(f"  {label:20s} │ {count:6d} frames ({pct:5.1f}%) │ {bar}")
    
    # Check for class imbalance
    counts = [len(v) for v in rows_by_label.values()]
    max_count = max(counts)
    min_count = min(counts)
    imbalance_ratio = max_count / min_count if min_count > 0 else float('inf')
    
    print(f"\n  ⚖️  Class imbalance ratio: {imbalance_ratio:.1f}x (max/min)")
    if imbalance_ratio > 3:
        print("  ⚠️  WARNING: Significant class imbalance detected!")
        print("     The model may be biased toward the majority class.")
    
    # ─── 2. Stationary Frame Analysis ─────────────────────────────────────
    print("\n" + "─" * 80)
    print("  2. STATIONARY (REST-LIKE) FRAME ANALYSIS")
    print("─" * 80)
    print(f"  (Window={WINDOW_SIZE}, AccelRangeThresh={ACCEL_RANGE_THRESH}, GyroMagThresh={GYRO_MAG_THRESH})")
    
    issues_found = False
    for label in sorted(rows_by_label.keys()):
        frames = rows_by_label[label]
        stationary, total_windows = count_stationary_windows(frames)
        if total_windows == 0:
            print(f"  {label:20s} │ Too few frames for analysis")
            continue
        pct = stationary / total_windows * 100
        status = "✅" if label.upper() == "REST" or pct < 10 else "🔴"
        if status == "🔴":
            issues_found = True
        print(f"  {status} {label:20s} │ {stationary:5d}/{total_windows:5d} windows stationary ({pct:5.1f}%)")
    
    if issues_found:
        print("\n  🔴 ISSUE DETECTED: Active gesture classes contain stationary (rest-like) windows!")
        print("     These windows look like REST but are labeled as active gestures.")
        print("     This confuses the model during training — it learns that 'rest' = 'gesture'.")
        print("     FIX: The training filter should remove these. If still happening,")
        print("          re-collect data with cleaner gesture recordings.")
    else:
        print("\n  ✅ No major stationary contamination in active gesture classes.")
    
    # ─── 3. Sensor Value Distribution ─────────────────────────────────────
    print("\n" + "─" * 80)
    print("  3. SENSOR VALUE DISTRIBUTION PER CLASS")
    print("─" * 80)
    
    axis_names = ["accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z"]
    
    for label in sorted(rows_by_label.keys()):
        frames = rows_by_label[label]
        stats = analyze_value_distribution(frames, label)
        print(f"\n  📊 {label} ({len(frames)} frames):")
        print(f"    {'Axis':10s} │ {'Min':>8s} │ {'Max':>8s} │ {'Range':>8s} │ {'Mean':>8s} │ {'Std':>8s}")
        print(f"    {'─'*10}─┼─{'─'*8}─┼─{'─'*8}─┼─{'─'*8}─┼─{'─'*8}─┼─{'─'*8}")
        for axis in axis_names:
            s = stats[axis]
            print(f"    {axis:10s} │ {s['min']:8.2f} │ {s['max']:8.2f} │ {s['range']:8.2f} │ {s['mean']:8.2f} │ {s['std']:8.2f}")
    
    # ─── 4. Quantization / Unique Value Check ────────────────────────────
    print("\n" + "─" * 80)
    print("  4. QUANTIZATION CHECK (unique values per axis)")
    print("─" * 80)
    
    all_frames = []
    for frames in rows_by_label.values():
        all_frames.extend(frames)
    
    unique = find_unique_values(all_frames)
    axis_labels = ["accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z"]
    
    for i, (name, vals) in enumerate(zip(axis_labels, unique)):
        n = len(vals)
        preview = vals[:10] if n > 10 else vals
        preview_str = ", ".join(f"{v:.1f}" for v in preview)
        if n > 10:
            preview_str += f" ... (+{n-10} more)"
        status = "⚠️" if n < 10 else "✅"
        print(f"  {status} {name:10s} │ {n:4d} unique values │ {preview_str}")
    
    heavily_quantized = sum(1 for v in unique if len(v) < 10)
    if heavily_quantized > 0:
        print(f"\n  ⚠️  WARNING: {heavily_quantized} axes have fewer than 10 unique values!")
        print("     Data is heavily quantized. Small sensor changes are invisible to the model.")
        print("     This makes REST vs. gentle-gesture separation very hard.")
    
    # ─── 5. Flat Segment Detection ────────────────────────────────────────
    print("\n" + "─" * 80)
    print("  5. FLAT (STUCK) SEGMENT DETECTION")
    print("─" * 80)
    
    for label in sorted(rows_by_label.keys()):
        frames = rows_by_label[label]
        flat = detect_flat_segments(frames, min_length=10)
        total_flat_frames = sum(s[2] for s in flat)
        pct = total_flat_frames / len(frames) * 100 if len(frames) > 0 else 0
        
        if len(flat) > 0:
            longest = max(flat, key=lambda x: x[2])
            status = "🔴" if pct > 30 and label.upper() != "REST" else "✅"
            print(f"  {status} {label:20s} │ {len(flat):3d} flat segments │ {total_flat_frames:5d} flat frames ({pct:5.1f}%) │ longest: {longest[2]} frames")
        else:
            print(f"  ✅ {label:20s} │ No flat segments detected")
    
    # ─── 6. Class Overlap Analysis ────────────────────────────────────────
    print("\n" + "─" * 80)
    print("  6. CLASS OVERLAP ANALYSIS")
    print("─" * 80)
    print("  (Higher overlap = harder for model to distinguish)")
    
    overlaps = compute_class_overlap(rows_by_label)
    for l1, l2, score in overlaps[:15]:  # Top 15 overlapping pairs
        bar_len = int(score * 40)
        bar = "█" * bar_len + "░" * (40 - bar_len)
        status = "🔴" if score > 0.7 else ("⚠️" if score > 0.5 else "✅")
        print(f"  {status} {l1:15s} vs {l2:15s} │ {score:.3f} │ {bar}")
    
    # ─── 7. REST vs Active Comparison ─────────────────────────────────────
    print("\n" + "─" * 80)
    print("  7. REST vs ACTIVE GESTURE COMPARISON")
    print("─" * 80)
    
    rest_labels = [l for l in rows_by_label if l.upper() == "REST"]
    active_labels = [l for l in rows_by_label if l.upper() != "REST"]
    
    if rest_labels:
        rest_frames = rows_by_label[rest_labels[0]]
        rest_stats = analyze_value_distribution(rest_frames, "REST")
        
        print(f"\n  REST profile ({len(rest_frames)} frames):")
        for axis in axis_names:
            s = rest_stats[axis]
            print(f"    {axis:10s}: range={s['range']:.2f}, std={s['std']:.2f}")
        
        print(f"\n  Comparison with active gestures:")
        for label in sorted(active_labels):
            frames = rows_by_label[label]
            stats = analyze_value_distribution(frames, label)
            
            # Check how different each gesture is from REST
            diff_score = 0
            for axis in axis_names:
                gs = stats[axis]
                rs = rest_stats[axis]
                mean_diff = abs(gs["mean"] - rs["mean"])
                range_diff = abs(gs["range"] - rs["range"])
                diff_score += mean_diff + range_diff
            
            status = "🔴" if diff_score < 5 else ("⚠️" if diff_score < 15 else "✅")
            print(f"    {status} {label:20s} │ separation score: {diff_score:8.2f} {'(TOO SIMILAR TO REST!)' if diff_score < 5 else ''}")
    else:
        print("  ⚠️  No 'REST' class found in the dataset!")
        print("     Consider adding a REST class for better idle detection.")
    
    # ─── 8. Summary & Recommendations ────────────────────────────────────
    print("\n" + "=" * 80)
    print("  SUMMARY & RECOMMENDATIONS")
    print("=" * 80)
    
    problems = []
    
    # Check imbalance
    if imbalance_ratio > 3:
        problems.append(("Class Imbalance", 
            f"Ratio is {imbalance_ratio:.1f}x. Undersample majority or oversample minority classes."))
    
    # Check stationary contamination
    for label in active_labels if rest_labels else []:
        frames = rows_by_label[label]
        stationary, total_windows = count_stationary_windows(frames)
        if total_windows > 0 and stationary / total_windows > 0.2:
            problems.append(("Stationary Contamination",
                f"'{label}' has {stationary/total_windows*100:.0f}% stationary windows. "
                f"Re-record with continuous motion, or rely on training-time filter."))
    
    # Check quantization
    if heavily_quantized > 0:
        problems.append(("Heavy Quantization",
            f"{heavily_quantized} axes have <10 unique values. Sensor resolution is very low. "
            f"Ensure deadband thresholds in the prediction engine account for this."))
    
    # Check REST similarity
    if rest_labels:
        for label in active_labels:
            frames = rows_by_label[label]
            stats = analyze_value_distribution(frames, label)
            diff_score = 0
            for axis in axis_names:
                gs = stats[axis]
                rs = rest_stats[axis]
                diff_score += abs(gs["mean"] - rs["mean"]) + abs(gs["range"] - rs["range"])
            if diff_score < 5:
                problems.append(("Low Separability",
                    f"'{label}' is very similar to REST (score={diff_score:.1f}). "
                    f"The model will struggle to distinguish them. Consider re-collecting."))
    
    if problems:
        print(f"\n  Found {len(problems)} issue(s):\n")
        for i, (title, desc) in enumerate(problems, 1):
            print(f"  {i}. 🔴 {title}")
            print(f"     {desc}\n")
    else:
        print("\n  ✅ No major issues detected in the CSV data.")
        print("     If predictions are still wrong at rest, the issue is likely in the")
        print("     inference engine thresholds (GesturePredictionEngine.kt).")
    
    print("=" * 80)


if __name__ == "__main__":
    main()
