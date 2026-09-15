#!/usr/bin/env python3
"""Download one preregistered RepOD EDF from dataset version 1.0 and verify its published MD5."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import urllib.parse
import urllib.request

DATASET_DOI = "doi:10.18150/repod.0107441"
DATASET_VERSION = "1.0"
BASE = "https://repod.icm.edu.pl"
ALLOWED = {*(f"h{i:02d}.edf" for i in range(1, 15)), *(f"s{i:02d}.edf" for i in range(1, 15))}


def digest(path: str, algorithm: str) -> str:
    h = hashlib.new(algorithm)
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def get_json(url: str) -> dict:
    request = urllib.request.Request(url, headers={"User-Agent": "MeegRead-v0.7-validation/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def download(url: str, path: str) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "MeegRead-v0.7-validation/1.0"})
    with urllib.request.urlopen(request, timeout=300) as response, open(path, "wb") as output:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            output.write(chunk)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--filename", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--metadata-output", required=True)
    args = parser.parse_args()

    filename = args.filename.lower()
    if filename not in ALLOWED:
        raise SystemExit(f"Filename outside frozen cohort: {filename}")

    persistent = urllib.parse.quote(DATASET_DOI, safe="")
    metadata_url = f"{BASE}/api/datasets/:persistentId/versions/{DATASET_VERSION}?persistentId={persistent}"
    payload = get_json(metadata_url)
    if payload.get("status") != "OK":
        raise SystemExit(f"RepOD metadata API did not return OK: {payload.get('status')}")
    data = payload.get("data", {})
    files = data.get("files") or data.get("latestVersion", {}).get("files") or []

    matches = []
    for entry in files:
        data_file = entry.get("dataFile", entry)
        if str(data_file.get("filename", "")).lower() == filename:
            matches.append(data_file)
    if len(matches) != 1:
        raise SystemExit(f"Expected exactly one metadata entry for {filename}, found {len(matches)}")

    data_file = matches[0]
    file_id = data_file.get("id")
    if not isinstance(file_id, int):
        raise SystemExit(f"Missing numeric datafile id for {filename}")
    checksum = data_file.get("checksum") or {}
    expected_md5 = data_file.get("md5") or checksum.get("value")
    checksum_type = str(checksum.get("type", "MD5")).upper()
    if checksum_type not in {"MD5", ""}:
        raise SystemExit(f"Unexpected published checksum type for {filename}: {checksum_type}")
    if not isinstance(expected_md5, str) or len(expected_md5) != 32:
        raise SystemExit(f"Missing published MD5 for {filename}")
    expected_md5 = expected_md5.lower()

    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    download_url = f"{BASE}/api/access/datafile/{file_id}"
    download(download_url, args.output)
    actual_md5 = digest(args.output, "md5")
    if actual_md5 != expected_md5:
        raise SystemExit(f"MD5 mismatch for {filename}: expected {expected_md5}, got {actual_md5}")

    declared_size = data_file.get("filesize")
    actual_size = os.path.getsize(args.output)
    if isinstance(declared_size, int) and declared_size != actual_size:
        raise SystemExit(f"Size mismatch for {filename}: expected {declared_size}, got {actual_size}")

    result = {
        "dataset_doi": DATASET_DOI,
        "dataset_version": DATASET_VERSION,
        "filename": filename,
        "datafile_id": file_id,
        "published_md5": expected_md5,
        "downloaded_md5": actual_md5,
        "downloaded_sha256": digest(args.output, "sha256"),
        "declared_size": declared_size,
        "downloaded_size": actual_size,
        "metadata_url": metadata_url,
        "download_url": download_url,
    }
    with open(args.metadata_output, "w", encoding="utf-8") as handle:
        json.dump(result, handle, indent=2, sort_keys=True)
        handle.write("\n")
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
