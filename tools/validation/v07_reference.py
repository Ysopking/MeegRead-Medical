#!/usr/bin/env python3
"""Independent Python oracle for CouplingFieldV07Engine at pinned app commit.

No third-party numeric packages are used. The implementation intentionally mirrors the
published/frozen mathematical operations, but it does not import or execute Android code.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
from dataclasses import dataclass
from typing import List, Sequence, Tuple

EPS = 1e-12
MIN_SAMPLES = 128
DEFAULT_WINDOW_SAMPLES = 2048
DEFAULT_MAX_SCALES = 8
ENGINE_VERSION = "coupling-field-v0.7-adapter-1.0"
ORACLE_VERSION = "python-v07-oracle-1.0"
CHANNEL_HEADER_MAP = {
    "TP9 (left ear)": "TP9",
    "AF7 (left forehead)": "AF7",
    "AF8 (right forehead)": "AF8",
    "TP10 (right ear)": "TP10",
}


@dataclass
class ComplexNode:
    label: str
    real: List[float]
    imaginary: List[float]

    @property
    def size(self) -> int:
        return min(len(self.real), len(self.imaginary))


@dataclass
class PairCoupling:
    nodeA: str
    nodeB: str
    phaseTransportRad: float
    phaseLocking: float
    informationOverlap: float
    bundleWeight: float


@dataclass
class ScaleResult:
    level: int
    nodeCount: int
    kappa: float
    directionCoherence: float
    gamma: float
    flowMagnitudeP: float
    couplingLossQ: float
    chiDyn: float
    stabilityReserve: float
    meanPhaseLocking: float
    pairs: List[PairCoupling]


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def git_blob_sha1(path: str) -> str:
    data = open(path, "rb").read()
    header = f"blob {len(data)}\0".encode("ascii")
    return hashlib.sha1(header + data).hexdigest()


def floor_power_of_two(value: int) -> int:
    if value < 1:
        return 0
    return 1 << (value.bit_length() - 1)


def biquad(samples: Sequence[float], b0: float, b1: float, b2: float, a1: float, a2: float) -> List[float]:
    x1 = x2 = y1 = y2 = 0.0
    out: List[float] = []
    for raw in samples:
        x0 = raw if math.isfinite(raw) else 0.0
        y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1 = x1, x0
        y2, y1 = y1, y0
        out.append(y0)
    return out


def high_pass(samples: Sequence[float], fs: float, cutoff: float) -> List[float]:
    omega = 2.0 * math.pi * cutoff / fs
    cos_w = math.cos(omega)
    sin_w = math.sin(omega)
    alpha = sin_w / (2.0 / math.sqrt(2.0))
    a0 = 1.0 + alpha
    return biquad(samples, (1.0 + cos_w) / 2.0 / a0, -(1.0 + cos_w) / a0,
                  (1.0 + cos_w) / 2.0 / a0, -2.0 * cos_w / a0, (1.0 - alpha) / a0)


def low_pass(samples: Sequence[float], fs: float, cutoff: float) -> List[float]:
    omega = 2.0 * math.pi * cutoff / fs
    cos_w = math.cos(omega)
    sin_w = math.sin(omega)
    alpha = sin_w / (2.0 / math.sqrt(2.0))
    a0 = 1.0 + alpha
    return biquad(samples, (1.0 - cos_w) / 2.0 / a0, (1.0 - cos_w) / a0,
                  (1.0 - cos_w) / 2.0 / a0, -2.0 * cos_w / a0, (1.0 - alpha) / a0)


def bandpass_filter(samples: Sequence[float], sample_rate_hz: float, low_hz: float, high_hz: float) -> List[float]:
    result = list(samples)
    if low_hz > 0.0 and low_hz < sample_rate_hz / 2.0:
        result = high_pass(result, sample_rate_hz, low_hz)
    if high_hz > 0.0 and high_hz < sample_rate_hz / 2.0:
        result = low_pass(result, sample_rate_hz, high_hz)
    return result


def z_score(values: Sequence[float]) -> List[float]:
    if not values:
        return list(values)
    mean = sum(values) / len(values)
    variance = sum((x - mean) * (x - mean) for x in values) / len(values)
    sd = max(math.sqrt(variance), EPS)
    return [(x - mean) / sd for x in values]


def analytic_signal(real_input: Sequence[float], radius: int = 31) -> Tuple[List[float], List[float]]:
    real = list(real_input)
    imag = [0.0] * len(real)
    for t in range(len(real)):
        acc = 0.0
        for k in range(-radius, radius + 1):
            if k == 0 or abs(k) % 2 == 0:
                continue
            index = t - k
            if index < 0 or index >= len(real):
                continue
            acc += (2.0 / (math.pi * k)) * real[index]
        imag[t] = acc
    return real, imag


def bin_index(value: float, min_value: float, max_value: float, bins: int) -> int:
    fraction = (value - min_value) / (max_value - min_value)
    return max(0, min(bins - 1, int(math.floor(fraction * bins))))


def normalized_mutual_information(a: Sequence[float], b: Sequence[float], bins: int = 8) -> float:
    n = min(len(a), len(b))
    if n < 8:
        return 0.0
    aa = list(a[:n])
    bb = list(b[:n])
    min_a, max_a = min(aa), max(aa)
    min_b, max_b = min(bb), max(bb)
    if max_a - min_a <= EPS or max_b - min_b <= EPS:
        return 0.0
    joint = [[0 for _ in range(bins)] for _ in range(bins)]
    count_a = [0] * bins
    count_b = [0] * bins
    for i in range(n):
        x = bin_index(aa[i], min_a, max_a, bins)
        y = bin_index(bb[i], min_b, max_b, bins)
        joint[x][y] += 1
        count_a[x] += 1
        count_b[y] += 1
    mi = h_a = h_b = 0.0
    for x in range(bins):
        px = count_a[x] / n
        if px > 0.0:
            h_a -= px * math.log(px)
        py = count_b[x] / n
        if py > 0.0:
            h_b -= py * math.log(py)
    for x in range(bins):
        for y in range(bins):
            pxy = joint[x][y] / n
            if pxy <= 0.0:
                continue
            px = count_a[x] / n
            py = count_b[y] / n
            if px > 0.0 and py > 0.0:
                mi += pxy * math.log(pxy / (px * py))
    denom = math.sqrt(h_a * h_b)
    return 0.0 if denom <= EPS else max(0.0, min(1.0, mi / denom))


def phase_coupling(a: ComplexNode, b: ComplexNode) -> Tuple[float, float]:
    n = min(a.size, b.size)
    if n == 0:
        return 0.0, 0.0
    sum_cos = sum_sin = 0.0
    count = 0
    for t in range(n):
        amp_a = math.sqrt(a.real[t] * a.real[t] + a.imaginary[t] * a.imaginary[t])
        amp_b = math.sqrt(b.real[t] * b.real[t] + b.imaginary[t] * b.imaginary[t])
        denom = amp_a * amp_b
        if denom <= EPS:
            continue
        sum_cos += (a.real[t] * b.real[t] + a.imaginary[t] * b.imaginary[t]) / denom
        sum_sin += (a.imaginary[t] * b.real[t] - a.real[t] * b.imaginary[t]) / denom
        count += 1
    if count == 0:
        return 0.0, 0.0
    mean_cos = sum_cos / count
    mean_sin = sum_sin / count
    phase = math.atan2(mean_sin, mean_cos)
    plv = max(0.0, min(1.0, math.sqrt(mean_cos * mean_cos + mean_sin * mean_sin)))
    return phase, plv


def wrap_phase(value: float) -> float:
    x = value
    while x > math.pi:
        x -= 2.0 * math.pi
    while x < -math.pi:
        x += 2.0 * math.pi
    return x


def phase_flow(node: ComplexNode, sample_rate_hz: float) -> float:
    if node.size < 2:
        return 0.0
    total = 0.0
    count = 0
    previous = math.atan2(node.imaginary[0], node.real[0])
    for t in range(1, node.size):
        phase = math.atan2(node.imaginary[t], node.real[t])
        d_phi = wrap_phase(phase - previous)
        amplitude2 = node.real[t] * node.real[t] + node.imaginary[t] * node.imaginary[t]
        if math.isfinite(amplitude2) and math.isfinite(d_phi):
            total += amplitude2 * d_phi * sample_rate_hz
            count += 1
        previous = phase
    return 0.0 if count == 0 else total / count


def multiply(a: Sequence[Sequence[float]], b: Sequence[Sequence[float]]) -> List[List[float]]:
    rows = len(a)
    inner = 0 if not a else len(a[0])
    cols = 0 if not b else len(b[0])
    out = [[0.0] * cols for _ in range(rows)]
    for i in range(rows):
        for k in range(inner):
            aik = a[i][k]
            if abs(aik) <= EPS:
                continue
            for j in range(cols):
                out[i][j] += aik * b[k][j]
    return out


def invert(input_matrix: Sequence[Sequence[float]]) -> List[List[float]] | None:
    n = len(input_matrix)
    if n == 0 or any(len(row) != n for row in input_matrix):
        return None
    aug = []
    for i in range(n):
        row = [0.0] * (2 * n)
        for j in range(2 * n):
            if j < n:
                row[j] = input_matrix[i][j]
            elif j - n == i:
                row[j] = 1.0
        aug.append(row)
    for col in range(n):
        pivot = col
        for row in range(col + 1, n):
            if abs(aug[row][col]) > abs(aug[pivot][col]):
                pivot = row
        if abs(aug[pivot][col]) <= EPS:
            return None
        if pivot != col:
            aug[pivot], aug[col] = aug[col], aug[pivot]
        scale = aug[col][col]
        for j in range(2 * n):
            aug[col][j] /= scale
        for row in range(n):
            if row == col:
                continue
            factor = aug[row][col]
            if abs(factor) <= EPS:
                continue
            for j in range(2 * n):
                aug[row][j] -= factor * aug[col][j]
    return [[aug[i][j + n] for j in range(n)] for i in range(n)]


def frobenius_norm(matrix: Sequence[Sequence[float]]) -> float:
    total = 0.0
    for row in matrix:
        row_total = 0.0
        for x in row:
            row_total += x * x
        total += row_total
    return math.sqrt(total)


def spectral_radius_gelfand(matrix: Sequence[Sequence[float]], iterations: int = 80) -> float:
    n = len(matrix)
    if n == 0:
        return 0.0
    power = [[1.0 if i == j else 0.0 for j in range(n)] for i in range(n)]
    cumulative_log_norm = 0.0
    estimate = 0.0
    for k in range(1, iterations + 1):
        power = multiply(power, matrix)
        norm = frobenius_norm(power)
        if not math.isfinite(norm) or norm <= EPS:
            return 0.0
        cumulative_log_norm += math.log(norm)
        for i in range(n):
            for j in range(n):
                power[i][j] /= norm
        estimate = math.exp(cumulative_log_norm / k)
    return max(0.0, estimate) if math.isfinite(estimate) else 0.0


def estimate_var_spectral_radius(signals: Sequence[Sequence[float]], ridge: float = 1e-3) -> float:
    if not signals:
        return 0.0
    n = len(signals)
    t_count = min(len(s) for s in signals)
    if t_count < 8:
        return 0.0
    standardized: List[List[float]] = []
    for input_signal in signals:
        vals = list(input_signal[:t_count])
        mean = sum(vals) / t_count
        variance = sum((x - mean) * (x - mean) for x in vals) / t_count
        sd = max(math.sqrt(variance), EPS)
        standardized.append([(vals[t] - mean) / sd for t in range(t_count)])
    xx = [[0.0] * n for _ in range(n)]
    yx = [[0.0] * n for _ in range(n)]
    observations = float(t_count - 1)
    for t in range(1, t_count):
        for i in range(n):
            yi = standardized[i][t]
            for j in range(n):
                xj = standardized[j][t - 1]
                yx[i][j] += yi * xj / observations
                xx[i][j] += standardized[i][t - 1] * xj / observations
    for i in range(n):
        xx[i][i] += ridge
    inverse = invert(xx)
    if inverse is None:
        return 0.0
    return spectral_radius_gelfand(multiply(yx, inverse))


def summarize(nodes: Sequence[ComplexNode], sample_rate_hz: float, level: int) -> ScaleResult:
    pairs: List[PairCoupling] = []
    for i in range(0, len(nodes) - 1):
        for j in range(i + 1, len(nodes)):
            phase, plv = phase_coupling(nodes[i], nodes[j])
            info = normalized_mutual_information(nodes[i].real, nodes[j].real)
            pairs.append(PairCoupling(nodes[i].label, nodes[j].label, phase, plv, info,
                                      max(0.0, min(1.0, plv * info))))
    kappa = 1.0 if not pairs else max(0.0, min(1.0, sum(p.informationOverlap for p in pairs) / len(pairs)))
    currents = [phase_flow(node, sample_rate_hz) for node in nodes]
    p_value = sum(abs(x) for x in currents)
    c_value = 1.0 if p_value <= EPS else max(0.0, min(1.0, abs(sum(currents)) / p_value))
    gamma = max(0.0, min(1.0, kappa * c_value))
    q_value = max(0.0, p_value * (1.0 - gamma))
    chi = estimate_var_spectral_radius([node.real for node in nodes])
    mean_plv = 1.0 if not pairs else max(0.0, min(1.0, sum(p.phaseLocking for p in pairs) / len(pairs)))
    return ScaleResult(level, len(nodes), kappa, c_value, gamma, p_value, q_value, chi, 1.0 - chi,
                       mean_plv, sorted(pairs, key=lambda p: p.bundleWeight, reverse=True))


def bundle(a: ComplexNode, b: ComplexNode, pair: PairCoupling) -> ComplexNode:
    n = min(a.size, b.size)
    weight = max(0.0, min(1.0, pair.bundleWeight))
    phase = pair.phaseTransportRad
    cos_p, sin_p = math.cos(phase), math.sin(phase)
    norm = max(math.sqrt(1.0 + weight * weight), EPS)
    real = [0.0] * n
    imaginary = [0.0] * n
    for t in range(n):
        br = b.real[t] * cos_p - b.imaginary[t] * sin_p
        bi = b.real[t] * sin_p + b.imaginary[t] * cos_p
        real[t] = (a.real[t] + weight * br) / norm
        imaginary[t] = (a.imaginary[t] + weight * bi) / norm
    return ComplexNode(f"({a.label}+{b.label})", real, imaginary)


def coarse_grain(nodes: Sequence[ComplexNode], pair_metrics: Sequence[PairCoupling]) -> List[ComplexNode]:
    if len(nodes) <= 1:
        return list(nodes)
    by_name = {node.label: node for node in nodes}
    used = set()
    output: List[ComplexNode] = []
    for pair in sorted(pair_metrics, key=lambda p: p.bundleWeight, reverse=True):
        if pair.nodeA in used or pair.nodeB in used:
            continue
        a = by_name.get(pair.nodeA)
        b = by_name.get(pair.nodeB)
        if a is None or b is None:
            continue
        output.append(bundle(a, b, pair))
        used.add(pair.nodeA)
        used.add(pair.nodeB)
    for node in nodes:
        if node.label not in used:
            output.append(node)
    return sorted(output, key=lambda node: node.label)


def load_web_muse_csv(path: str) -> Tuple[dict[str, List[float]], int, List[float]]:
    channels = {name: [] for name in CHANNEL_HEADER_MAP.values()}
    timestamps: List[float] = []
    row_count = 0
    with open(path, "r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames is None:
            raise ValueError("CSV has no header")
        missing = [header for header in CHANNEL_HEADER_MAP if header not in reader.fieldnames]
        if missing:
            raise ValueError(f"Missing Muse headers: {missing}; got {reader.fieldnames}")
        if "Timestamp (ms)" not in reader.fieldnames:
            raise ValueError("Missing Timestamp (ms) column")
        for row in reader:
            row_count += 1
            timestamps.append(float(row["Timestamp (ms)"]))
            for source_header, compact in CHANNEL_HEADER_MAP.items():
                channels[compact].append(float(row[source_header]))
    return channels, row_count, timestamps


def analyze(channels: dict[str, List[float]], sample_rate_hz: float,
            requested_window_samples: int, max_scales: int) -> Tuple[List[str], int, float, List[ScaleResult]]:
    if sample_rate_hz <= 0.0 or not math.isfinite(sample_rate_hz):
        raise ValueError("Invalid sample rate")
    eligible = sorted((name, samples) for name, samples in channels.items() if len(samples) >= MIN_SAMPLES)
    if len(eligible) < 2:
        raise ValueError("Need at least two channels")
    common_length = min(len(samples) for _, samples in eligible)
    requested = min(max(requested_window_samples, MIN_SAMPLES), common_length)
    window = floor_power_of_two(requested)
    if window < MIN_SAMPLES:
        raise ValueError("Analysis window too short")
    finite_count = total_count = 0
    nodes: List[ComplexNode] = []
    for name, raw in eligible:
        tail = raw[-window:]
        total_count += len(tail)
        finite_count += sum(1 for x in tail if math.isfinite(x))
        clean = [x if math.isfinite(x) else 0.0 for x in tail]
        high = min(40.0, sample_rate_hz * 0.45)
        if high <= 0.5:
            continue
        low = min(1.0, high * 0.25)
        filtered = bandpass_filter(clean, sample_rate_hz, low, high)
        normalized = z_score(filtered)
        real, imaginary = analytic_signal(normalized)
        nodes.append(ComplexNode(name, real, imaginary))
    if len(nodes) < 2:
        raise ValueError("Need at least two valid nodes")
    source_channels = [node.label for node in nodes]
    scales: List[ScaleResult] = []
    current = nodes
    level = 0
    hard_max = max(max_scales, 1)
    while current and level < hard_max:
        summary = summarize(current, sample_rate_hz, level)
        scales.append(summary)
        if len(current) <= 1:
            break
        nxt = coarse_grain(current, summary.pairs)
        if len(nxt) >= len(current):
            break
        current = nxt
        level += 1
    finite_fraction = 0.0 if total_count == 0 else finite_count / total_count
    return source_channels, window, finite_fraction, scales


def write_outputs(out_dir: str, input_path: str, sample_rate_hz: float,
                  source_channels: List[str], window: int, finite_fraction: float,
                  scales: Sequence[ScaleResult], timestamps: Sequence[float],
                  source_commit: str | None, source_blob_sha1: str | None,
                  app_commit: str | None, engine_blob_sha1: str | None) -> None:
    os.makedirs(out_dir, exist_ok=True)
    scales_path = os.path.join(out_dir, "scales.csv")
    pairs_path = os.path.join(out_dir, "pairs.csv")
    manifest_path = os.path.join(out_dir, "manifest.json")
    with open(scales_path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["level", "nodeCount", "kappa", "directionCoherence", "gamma", "flowMagnitudeP",
                    "couplingLossQ", "chiDyn", "stabilityReserve", "meanPhaseLocking"])
        for s in scales:
            w.writerow([s.level, s.nodeCount, repr(s.kappa), repr(s.directionCoherence), repr(s.gamma),
                        repr(s.flowMagnitudeP), repr(s.couplingLossQ), repr(s.chiDyn),
                        repr(s.stabilityReserve), repr(s.meanPhaseLocking)])
    with open(pairs_path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["level", "index", "nodeA", "nodeB", "phaseTransportRad", "phaseLocking",
                    "informationOverlap", "bundleWeight"])
        for s in scales:
            for idx, p in enumerate(s.pairs):
                w.writerow([s.level, idx, p.nodeA, p.nodeB, repr(p.phaseTransportRad), repr(p.phaseLocking),
                            repr(p.informationOverlap), repr(p.bundleWeight)])
    timestamp_diffs = [timestamps[i] - timestamps[i - 1] for i in range(1, len(timestamps))
                       if math.isfinite(timestamps[i]) and math.isfinite(timestamps[i - 1])]
    timestamp_diffs_sorted = sorted(timestamp_diffs)
    median_dt_ms = None
    if timestamp_diffs_sorted:
        m = len(timestamp_diffs_sorted)
        median_dt_ms = (timestamp_diffs_sorted[m // 2] if m % 2 else
                        (timestamp_diffs_sorted[m // 2 - 1] + timestamp_diffs_sorted[m // 2]) / 2.0)
    manifest = {
        "oracle_version": ORACLE_VERSION,
        "engine_version_expected": ENGINE_VERSION,
        "input_file": os.path.basename(input_path),
        "input_sha256": sha256_file(input_path),
        "input_git_blob_sha1": git_blob_sha1(input_path),
        "source_repository": "itayinbarr/web-muse",
        "source_path": "assets/resting-state.csv",
        "source_commit": source_commit,
        "source_blob_sha1_expected": source_blob_sha1,
        "app_repository": "Ysopking/MeegRead-Medical",
        "app_commit": app_commit,
        "engine_blob_sha1_expected": engine_blob_sha1,
        "sample_rate_hz_frozen": sample_rate_hz,
        "requested_window_samples": DEFAULT_WINDOW_SAMPLES,
        "analysis_window_samples": window,
        "window_policy": "tail; floorPowerOfTwo(min(requestedWindowSamples, commonLength))",
        "source_channels_sorted": source_channels,
        "finite_input_fraction": finite_fraction,
        "scale_node_counts": [s.nodeCount for s in scales],
        "timestamp_median_delta_ms_descriptive_only": median_dt_ms,
        "timestamp_derived_rate_hz_descriptive_only": None if not median_dt_ms or median_dt_ms <= 0 else 1000.0 / median_dt_ms,
        "operator_parameters": {
            "bandpass_low_hz": 1.0,
            "bandpass_high_hz": 40.0,
            "hilbert_kernel_radius": 31,
            "nmi_bins": 8,
            "var_order": 1,
            "var_ridge": 0.001,
            "gelfand_iterations": 80,
            "max_scales": DEFAULT_MAX_SCALES,
        },
        "outputs": {
            "scales.csv": sha256_file(scales_path),
            "pairs.csv": sha256_file(pairs_path),
        },
        "guardrails": [
            "SENSOR_SPACE_PROXY only",
            "Q is a normalized coupling-loss proxy, not physical power",
            "chi_dyn is VAR(1) spectral radius, not calibrated L/C",
            "No parameter tuning from this public-data result",
        ],
    }
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
        f.write("\n")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--sample-rate-hz", type=float, default=256.0)
    ap.add_argument("--source-commit")
    ap.add_argument("--source-blob-sha1")
    ap.add_argument("--app-commit")
    ap.add_argument("--engine-blob-sha1")
    args = ap.parse_args()
    channels, row_count, timestamps = load_web_muse_csv(args.input)
    if row_count < MIN_SAMPLES:
        raise SystemExit(f"Dataset too short: {row_count} rows")
    source_channels, window, finite_fraction, scales = analyze(
        channels, args.sample_rate_hz, DEFAULT_WINDOW_SAMPLES, DEFAULT_MAX_SCALES)
    write_outputs(args.out_dir, args.input, args.sample_rate_hz, source_channels, window,
                  finite_fraction, scales, timestamps, args.source_commit, args.source_blob_sha1,
                  args.app_commit, args.engine_blob_sha1)
    print(json.dumps({
        "oracle_version": ORACLE_VERSION,
        "rows": row_count,
        "source_channels": source_channels,
        "window": window,
        "scale_node_counts": [s.nodeCount for s in scales],
        "input_sha256": sha256_file(args.input),
        "input_git_blob_sha1": git_blob_sha1(args.input),
    }, indent=2))


if __name__ == "__main__":
    main()
