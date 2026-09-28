#!/usr/bin/env python3
"""Generate/check bundled British male jokes; never runs on a phone.

See docs/british-male-voice.md for pinned dependencies and model downloads.
"""
import argparse
import hashlib
import json
from pathlib import Path
import wave
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "app/src/main/res/raw"
MANIFEST = ROOT / "docs/kill-streak-audio.json"
MODEL_HASH = "beb0d1848dee9a49da392cc3df26958d46cfa35d321edf434f52949153f0df3a"
VOICES_HASH = "bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d"
VOICE = "bm_george"


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def prompts():
    strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
    return {node.attrib["name"]: "".join(node.itertext()).replace("\\'", "'")
            for node in strings.findall("string")
            if node.attrib["name"].startswith("kill_streak_")
            and node.attrib["name"].endswith("_voice_prompt")}


def verify():
    manifest = json.loads(MANIFEST.read_text(encoding="ascii"))
    lines = prompts()
    assert manifest["voice"] == VOICE
    assert {entry["prompt"]: entry["text"] for entry in manifest["clips"]} == lines, (
        "Kill-streak text changed; regenerate the bundled recordings")
    assert len(manifest["clips"]) == len(lines)
    expected_files = set()
    for entry in manifest["clips"]:
        expected = entry["prompt"].removesuffix("_voice_prompt") + ".wav"
        assert entry["file"] == expected
        expected_files.add(expected)
        path = RAW / expected
        assert sha256(path) == entry["sha256"], path
        with wave.open(str(path), "rb") as audio:
            assert audio.getnchannels() == 1 and audio.getsampwidth() == 2, path
            assert audio.getframerate() == 24000, path
            assert audio.getnframes() == entry["frames"], path
            assert 0.4 <= audio.getnframes() / audio.getframerate() <= 12, path
    assert {path.name for path in RAW.glob("kill_streak_*.wav")} == expected_files
    print(f"Verified {len(lines)} bundled {VOICE} clips and their exact prompt text.")


def generate(model_dir):
    # Imports are deliberately lazy: --check needs only the Python standard library.
    import numpy as np
    import onnxruntime as rt
    import soundfile as sf
    from kokoro_onnx import Kokoro

    model = model_dir / "kokoro-v1.0.onnx"
    voices = model_dir / "voices-v1.0.bin"
    assert sha256(model) == MODEL_HASH, "Unexpected Kokoro model download"
    assert sha256(voices) == VOICES_HASH, "Unexpected Kokoro voice download"
    rt.set_seed(0)
    options = rt.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = rt.InferenceSession(str(model), sess_options=options,
                                  providers=["CPUExecutionProvider"])
    engine = Kokoro.from_session(session, str(voices))
    entries = []
    for name, text in prompts().items():
        samples, rate = engine.create(text, voice=VOICE, lang="en-gb", speed=1.0)
        assert rate == 24000 and np.isfinite(samples).all(), name
        peak = float(np.max(np.abs(samples)))
        assert peak > 0.01, f"Empty audio: {name}"
        # Normalize consistently, leaving headroom. No pitch or timing changes.
        samples = samples * (0.89 / peak)
        filename = name.removesuffix("_voice_prompt") + ".wav"
        path = RAW / filename
        sf.write(str(path), samples, rate, subtype="PCM_16")
        entries.append({"prompt": name, "text": text, "file": filename,
                        "frames": len(samples), "sha256": sha256(path)})
        print(f"{filename}: {len(samples) / rate:.2f}s - {text}", flush=True)
    manifest = {"voice": VOICE, "language": "en-gb", "speed": 1.0,
                "engine": "kokoro-onnx 0.6.1", "model": "Kokoro v1.0",
                "model_sha256": MODEL_HASH, "voices_sha256": VOICES_HASH,
                "clips": entries}
    MANIFEST.write_text(json.dumps(manifest, indent=2, ensure_ascii=True) + "\n",
                        encoding="ascii")
    verify()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--model-dir", type=Path)
    args = parser.parse_args()
    if args.check:
        verify()
    elif args.model_dir:
        generate(args.model_dir)
    else:
        parser.error("Use --check or --model-dir PATH")
