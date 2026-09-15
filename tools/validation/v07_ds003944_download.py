#!/usr/bin/env python3
"""Download one frozen NEMAR on003944 BrainVision recording and verify git-annex MD5E identity.

This is transport/identity code only. It computes no v0.7 outcome.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import shutil
import time
import urllib.request
from pathlib import Path

DATASET = "on003944"
VERSION = "v1.0.0"
FROZEN_METADATA_COMMIT = "0831487239218bc9daef1cc43d91d07b0c7d153d"
PARTICIPANTS_BLOB = "f83c8861f7f4ab397e08ed86a6fff4e9c79f28b3"
BASE = f"https://data.nemar.org/{DATASET}/{VERSION}"
ANNEX_RE = re.compile(r"MD5E-s(?P<size>\d+)--(?P<md5>[0-9a-f]{32})(?P<suffix>\.[^/]+)$")


def md5_file(path: Path) -> str:
    h = hashlib.md5()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def load_participants(metadata_root: Path) -> dict[str, str]:
    path = metadata_root / "participants.tsv"
    rows: dict[str, str] = {}
    with path.open("r", encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            pid = row["participant_id"]
            typ = row["type"]
            if not pid.endswith("A") and typ in {"Control", "Psychosis"}:
                rows[pid] = typ
    controls = sum(v == "Control" for v in rows.values())
    psychosis = sum(v == "Psychosis" for v in rows.values())
    if (len(rows), controls, psychosis) != (72, 28, 44):
        raise ValueError(f"Frozen cohort assertion failed: total={len(rows)} Control={controls} Psychosis={psychosis}")
    return rows


def annex_identity(pointer_path: Path) -> tuple[int, str]:
    if pointer_path.is_symlink():
        target = os.readlink(pointer_path)
    else:
        # Some checkout modes materialize the symlink target as plain text.
        target = pointer_path.read_text(encoding="utf-8").strip()
    name = os.path.basename(target)
    match = ANNEX_RE.fullmatch(name)
    if not match:
        raise ValueError(f"Not a frozen MD5E annex pointer: {pointer_path}: {target}")
    return int(match.group("size")), match.group("md5")


def download_verified(url: str, out: Path, expected_size: int, expected_md5: str) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    last_error: Exception | None = None
    for attempt in range(4):
        try:
            tmp = out.with_suffix(out.suffix + ".part")
            if tmp.exists():
                tmp.unlink()
            request = urllib.request.Request(url, headers={"User-Agent": "MeegRead-v0.7-validation/1.0"})
            with urllib.request.urlopen(request, timeout=120) as response, tmp.open("wb") as handle:
                shutil.copyfileobj(response, handle, length=1024 * 1024)
            actual_size = tmp.stat().st_size
            actual_md5 = md5_file(tmp)
            if actual_size != expected_size or actual_md5 != expected_md5:
                raise ValueError(
                    f"Identity mismatch for {url}: size {actual_size}/{expected_size}, md5 {actual_md5}/{expected_md5}"
                )
            tmp.replace(out)
            return
        except Exception as exc:
            last_error = exc
            time.sleep(2 ** attempt)
    raise RuntimeError(f"Failed verified download after retries: {url}") from last_error


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata-root", required=True)
    parser.add_argument("--subject", required=True)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()

    metadata_root = Path(args.metadata_root).resolve()
    subject = args.subject
    out_dir = Path(args.out_dir).resolve()
    participants = load_participants(metadata_root)
    if subject not in participants:
        raise SystemExit(f"Subject outside frozen non-A cohort: {subject}")

    eeg_dir = metadata_root / subject / "eeg"
    stem = f"{subject}_task-Rest_eeg"
    identities = {}
    for ext in ("vhdr", "eeg"):
        rel = f"{subject}/eeg/{stem}.{ext}"
        pointer = metadata_root / rel
        expected_size, expected_md5 = annex_identity(pointer)
        out = out_dir / f"{stem}.{ext}"
        download_verified(f"{BASE}/{rel}", out, expected_size, expected_md5)
        identities[ext] = {
            "relative_path": rel,
            "bytes": expected_size,
            "md5": expected_md5,
            "downloaded_file": out.name,
        }

    channels_src = eeg_dir / f"{subject}_task-Rest_channels.tsv"
    channels_out = out_dir / channels_src.name
    shutil.copyfile(channels_src, channels_out)

    metadata = {
        "dataset": DATASET,
        "version": VERSION,
        "frozen_metadata_commit": FROZEN_METADATA_COMMIT,
        "participants_blob_sha1": PARTICIPANTS_BLOB,
        "subject": subject,
        "group": participants[subject],
        "raw_files": identities,
        "channels_tsv": channels_out.name,
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    with (out_dir / "source_metadata.json").open("w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2, sort_keys=True)
        f.write("\n")
    print(json.dumps(metadata, sort_keys=True))


if __name__ == "__main__":
    main()
