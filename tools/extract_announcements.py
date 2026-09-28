#!/usr/bin/env python3
"""Split the supplied Roman recording into the game's English announcements.

Extraction requires ffmpeg on PATH (or --ffmpeg PATH). --check uses only the
Python standard library and verifies the checked-in audio without decoding MP3.
"""
import argparse
from array import array
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import wave
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "audio/source/roman-announcements.mp3"
SOURCE_SHA256 = "2b54a578886c45ae236923f87c054f4e2d9f34cecb4e21e3efa00983e8a13335"
RAW = ROOT / "app/src/main/res/raw"
MANIFEST = ROOT / "docs/announcements-audio.json"
RATE = 24000
COMPRESSOR = "acompressor=threshold=0.08:ratio=3:attack=5:release=120:knee=2:makeup=1:detection=peak"
TARGET_ACTIVE_RMS_DBFS = -17.0
PEAK_LIMIT_DBFS = -1.5
ACTIVE_THRESHOLD_RELATIVE_DB = -30.0
ACTIVE_WINDOW_MS = 20
PROCESSING = {"compressor": COMPRESSOR,
              "target_active_rms_dbfs": TARGET_ACTIVE_RMS_DBFS,
              "peak_limit_dbfs": PEAK_LIMIT_DBFS,
              "active_threshold_relative_db": ACTIVE_THRESHOLD_RELATIVE_DB,
              "active_window_ms": ACTIVE_WINDOW_MS}

# The recording follows this order exactly. Each pair is the start and end of
# the quiet gap after a complete line, measured in seconds on the decoded audio.
PROMPTS = [
    "game_end_won", "game_end_lost", "game_end_tied",
    "kill_streak_2", "kill_streak_3", "kill_streak_4", "kill_streak_5",
    "kill_streak_6", "kill_streak_7", "kill_streak_8",
    *[f"kill_streak_9_{letter}" for letter in "abcdefghijklm"],
]
GAPS = [
    (2.833333, 3.347750), (6.603917, 7.237250),
    (10.425375, 10.862500), (11.702833, 12.147625),
    (13.745417, 14.468792), (15.021792, 15.580958),
    (16.647875, 17.451833), (18.513000, 19.045000),
    (21.289125, 22.312958), (23.507958, 24.292792),
    (27.304417, 27.947000), (28.845625, 30.156167),
    (31.239083, 31.722958), (32.892833, 33.706833),
    (35.239542, 35.784875), (37.263042, 37.976667),
    (40.052292, 40.633417), (42.938958, 43.434958),
    (44.985958, 45.647542), (48.486500, 49.368083),
    (51.754167, 52.346208), (53.710833, 54.253833),
]


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def texts():
    strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
    values = {node.attrib["name"]: "".join(node.itertext()).replace("\\'", "'")
              for node in strings.findall("string")}
    return {name: values[name + "_voice_prompt"] for name in PROMPTS}


def pcm_samples(frames):
    samples = array("h")
    samples.frombytes(frames)
    if sys.byteorder != "little":
        samples.byteswap()
    return samples


def pcm_metrics(frames):
    samples = pcm_samples(frames)
    window = RATE * ACTIVE_WINDOW_MS // 1000
    peak_sample = max(abs(int(sample)) for sample in samples)
    minimum_power = (peak_sample * 10 ** (ACTIVE_THRESHOLD_RELATIVE_DB / 20)) ** 2
    active_power = []
    for offset in range(0, len(samples) - window + 1, window):
        power = sum(int(sample) ** 2 for sample in samples[offset:offset + window]) / window
        if power >= minimum_power:
            active_power.append(power)
    assert active_power, "No audible speech in clip"
    active_rms = math.sqrt(sum(active_power) / len(active_power)) / 32768
    peak = peak_sample / 32768
    return 20 * math.log10(active_rms), 20 * math.log10(peak)


def compress_and_level(ffmpeg, frames):
    with tempfile.TemporaryDirectory() as temporary:
        original = Path(temporary) / "original.wav"
        compressed = Path(temporary) / "compressed.wav"
        with wave.open(str(original), "wb") as clip:
            clip.setnchannels(1)
            clip.setsampwidth(2)
            clip.setframerate(RATE)
            clip.writeframes(frames)
        subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-i",
                        str(original), "-af", COMPRESSOR, "-ac", "1", "-ar", str(RATE),
                        "-c:a", "pcm_s16le", "-y", str(compressed)], check=True)
        with wave.open(str(compressed), "rb") as clip:
            assert clip.getnchannels() == 1 and clip.getsampwidth() == 2
            assert clip.getframerate() == RATE and clip.getnframes() == len(frames) // 2
            compressed_frames = clip.readframes(clip.getnframes())
    active_dbfs, peak_dbfs = pcm_metrics(compressed_frames)
    gain_db = min(TARGET_ACTIVE_RMS_DBFS - active_dbfs, PEAK_LIMIT_DBFS - peak_dbfs)
    gain = 10 ** (gain_db / 20)
    samples = pcm_samples(compressed_frames)
    leveled = array("h", (round(sample * gain) for sample in samples))
    if sys.byteorder != "little":
        leveled.byteswap()
    return leveled.tobytes(), gain_db


def check():
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    assert sha256(SOURCE) == SOURCE_SHA256 == manifest["source_sha256"]
    assert manifest["source"] == str(SOURCE.relative_to(ROOT))
    assert manifest["sample_rate"] == RATE
    assert manifest["processing"] == PROCESSING
    assert [entry["prompt"] for entry in manifest["clips"]] == PROMPTS
    assert {entry["prompt"]: entry["text"] for entry in manifest["clips"]} == texts()
    assert len(GAPS) == len(PROMPTS) - 1
    expected_files = {name + ".wav" for name in PROMPTS}
    actual_files = {path.name for pattern in ("game_end_*.wav", "kill_streak_*.wav")
                    for path in RAW.glob(pattern)}
    assert actual_files == expected_files, "Unexpected or missing announcement clips"
    for entry in manifest["clips"]:
        path = RAW / (entry["prompt"] + ".wav")
        assert entry["file"] == path.name
        assert 0 < entry["level_gain_db"] < 20, path
        assert sha256(path) == entry["sha256"], path
        with wave.open(str(path), "rb") as audio:
            assert audio.getnchannels() == 1 and audio.getsampwidth() == 2, path
            assert audio.getframerate() == RATE, path
            assert audio.getnframes() == entry["frames"], path
            assert 0.4 <= audio.getnframes() / RATE <= 12, path
            active_dbfs, peak_dbfs = pcm_metrics(audio.readframes(audio.getnframes()))
            assert abs(active_dbfs - entry["active_rms_dbfs"]) < 0.01, path
            assert abs(peak_dbfs - entry["peak_dbfs"]) < 0.01, path
            assert TARGET_ACTIVE_RMS_DBFS - 0.5 <= active_dbfs <= TARGET_ACTIVE_RMS_DBFS + 0.5, path
            assert peak_dbfs <= PEAK_LIMIT_DBFS, path
    print(f"Verified {len(PROMPTS)} recorded English announcements.")


def extract(ffmpeg):
    assert sha256(SOURCE) == SOURCE_SHA256, "Unexpected source recording"
    with tempfile.TemporaryDirectory() as temporary:
        decoded = Path(temporary) / "decoded.wav"
        subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-i",
                        str(SOURCE), "-ac", "1", "-ar", str(RATE),
                        "-c:a", "pcm_s16le", "-y", str(decoded)], check=True)
        with wave.open(str(decoded), "rb") as audio:
            assert audio.getnchannels() == 1 and audio.getsampwidth() == 2
            assert audio.getframerate() == RATE
            frames = audio.readframes(audio.getnframes())
    duration = len(frames) / (2 * RATE)
    assert 57.0 <= duration <= 57.2, duration
    lines = texts()
    entries = []
    for index, name in enumerate(PROMPTS):
        # Keep 120 ms of lead-in and 160 ms of tail in the neighboring quiet
        # gaps. The final clip retains the natural end of the recording.
        start = 0 if index == 0 else GAPS[index - 1][1] - 0.120
        end = duration if index == len(PROMPTS) - 1 else GAPS[index][0] + 0.160
        first = round(start * RATE)
        last = round(end * RATE)
        assert first < last
        path = RAW / (name + ".wav")
        processed, gain_db = compress_and_level(ffmpeg, frames[first * 2:last * 2])
        active_dbfs, peak_dbfs = pcm_metrics(processed)
        with wave.open(str(path), "wb") as clip:
            clip.setnchannels(1)
            clip.setsampwidth(2)
            clip.setframerate(RATE)
            clip.writeframes(processed)
        entries.append({"prompt": name, "text": lines[name], "file": path.name,
                        "start_seconds": round(start, 3), "end_seconds": round(end, 3),
                        "frames": last - first, "level_gain_db": round(gain_db, 2),
                        "active_rms_dbfs": round(active_dbfs, 2),
                        "peak_dbfs": round(peak_dbfs, 2), "sha256": sha256(path)})
        print(f"{path.name}: {(last - first) / RATE:.2f}s, "
              f"active {active_dbfs:.1f} dBFS, peak {peak_dbfs:.1f} dBFS")
    MANIFEST.write_text(json.dumps({"source": str(SOURCE.relative_to(ROOT)),
                                    "source_sha256": SOURCE_SHA256,
                                    "sample_rate": RATE, "processing": PROCESSING,
                                    "clips": entries},
                                   indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    check()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--check", action="store_true")
    group.add_argument("--extract", action="store_true")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    args = parser.parse_args()
    check() if args.check else extract(args.ffmpeg)
