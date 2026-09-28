# Recorded English announcements

The APK includes 23 clips extracted from the supplied Roman MP3: three game
over results and all 20 kill streak lines. The original recording is checked
in at `audio/source/roman-announcements.mp3`. The current source is the
`ElevenLabs_2026-09-28T08_21_58_Roman_pvc_sp115_s30_sb30_se95_b_m2.mp3`
recording. Exact text, clip time ranges,
frame counts, and hashes are recorded in `docs/announcements-audio.json`.

Each clip is compressed at 3:1 above about -22 dBFS (5 ms attack, 120 ms
release), then leveled to -17 dBFS speech-active RMS. Gain is capped to keep
peaks below -1.5 dBFS. The extraction check verifies the stored levels and
hashes so these settings stay reproducible.

The English clips play through Android's `TextToSpeech.addSpeech` resource API.
They use the existing speech queue, so the game over announcement still flushes
earlier speech, and a kill streak line still follows "Enemy Destroyed" and the
one second pause. Countdown, reload, respawn, inactivity, and round start
continue to use the system voice. Non-English announcements use translated
system speech.

If a clip cannot be registered or queued, the same text falls back to system
speech. The existing installed British male voice preference remains an
optional fallback for kill streak lines. No extra voice pack or network
connection is required for playback of the recordings.

To extract the clips again, install `ffmpeg`, then run:

```sh
python3 tools/extract_announcements.py --extract
python3 tools/extract_announcements.py --check
```

The check uses only the Python standard library. Unit tests cover resource
mapping and language selection. Android tests cover the device decoder,
registration, queue ordering, and fallback behavior.

To play all 23 clips through one connected phone's speaker, install the debug
app and Android test APKs, then run the opt-in test below. It sets that phone's
media volume to 60% and leaves it unmuted.

```sh
adb -s SERIAL shell am instrument -w -e audibleAnnouncements true \
  -e class com.simplecoil.simplecoil.BundledSpeechPlaybackTest#playEveryBundledAnnouncementAudibly \
  com.simplecoil.simplecoil.test/androidx.test.runner.AndroidJUnitRunner
```
