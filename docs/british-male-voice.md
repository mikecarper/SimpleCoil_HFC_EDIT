# British male kill-streak speech

The APK includes all 20 English kill-streak jokes, generated with Kokoro v1.0's
`bm_george` British male voice through the GitHub project
[kokoro-onnx](https://github.com/thewh1teagle/kokoro-onnx). The phone needs no
additional voice pack, network connection, or neural-model runtime. Only the
small PCM WAV recordings are shipped, not the model or generation dependencies.

"Enemy Destroyed" keeps the existing system voice at 150% speed. The existing
one-second silent utterance follows it, then the British male joke plays at its
recorded speed. Countdown, reload, respawn, inactivity, and round announcements
keep the normal voice. Non-English prompts keep their existing language.

## Playback and fallback

At TTS initialization, `KillStreakAudio` registers the clips using Android's
`TextToSpeech.addSpeech` resource API. Private keys prevent ordinary speech
with matching words from accidentally playing a joke. The recordings use the
same speech queue as the kill cue and pause, so cancellation, death, round-end
queue flushes, and shutdown also stop queued recordings. No second media player
or second speech engine is created by the game.

Clip requests include both the modern utterance ID argument and the legacy
`KEY_PARAM_UTTERANCE_ID` parameter. Android 5.1's audio playback implementation
requires the legacy parameter for start/completion callbacks, even though
ordinary synthesized speech uses the modern argument.

Registration failures are handled per clip. Where registration is incomplete,
the previous installed-offline-British-male voice selector remains a fallback.
If a clip is rejected when queued, that mapping is disabled for the session and
the original words are queued as speech, using the existing voice if necessary.
The fallback never downloads a voice pack. An asynchronous engine/audio-output
failure after a queue request is accepted is still subject to Android's normal
TTS error handling; registration success is not a guarantee of audible output.

The LG Android 5.1.1 phone inspected before this change only had the US English
offline voice installed. Its old Google TTS engine did not list a British male
voice. Bundled recordings avoid depending on that catalog.

## Source and licensing

- Generator: [thewh1teagle/kokoro-onnx](https://github.com/thewh1teagle/kokoro-onnx), MIT.
- Model: [hexgrad/Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M), Apache-2.0.
- [Upstream voice list](https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md)
  identifies `bm_george` as British English, male.
- Generated from this game's existing prompt text, without custom voice cloning.
- Attribution is also included inside the APK in `assets/THIRD_PARTY_VOICES.txt`.

## Regenerating recordings

Generation is a development-time operation using Python 3.12. Normal Android
builds use the checked-in WAV files and do not install these tools or download
anything. From the repository root:

```sh
python3 -m venv output/voice-generation/venv
output/voice-generation/venv/bin/pip install -r tools/voice-requirements.txt
mkdir -p output/voice-generation/models
curl -fL -o output/voice-generation/models/kokoro-v1.0.onnx \
  https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/kokoro-v1.0.onnx
curl -fL -o output/voice-generation/models/voices-v1.0.bin \
  https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/voices-v1.0.bin
output/voice-generation/venv/bin/python tools/generate_kill_streak_audio.py \
  --model-dir output/voice-generation/models
```

The script checks both download hashes before generation, uses `bm_george`,
`en-gb`, speed 1.0, and two CPU inference threads. Audio is mono, 24 kHz,
16-bit PCM, peak-normalized to 0.89 for consistent volume with headroom.
No pitch shift is used to imitate a male voice.

Whenever a joke's text changes, regenerate it and commit the updated recordings
and `docs/kill-streak-audio.json`. The manifest records exact prompt text, model
hashes, frame counts, and per-file hashes. This check requires no speech tools:

```sh
python3 tools/generate_kill_streak_audio.py --check
```

Unit tests cover prompt-to-recording mapping and language selection. Focused
Android tests cover registration/fallback and the existing queue ordering.
`BundledSpeechPlaybackTest` checks all 20 resources using Android's media
decoder and plays a muted real TTS cue/pause/recording sequence. It does not
launch a game or alter media-volume settings, but starting instrumentation
still closes the target app, so use only a phone approved for testing.
