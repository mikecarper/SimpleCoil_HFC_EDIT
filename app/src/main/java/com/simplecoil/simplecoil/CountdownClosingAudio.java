package com.simplecoil.simplecoil;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import com.simplecoil.protocol.GameStartCountdown;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** A measured, locally played closing line, independent of the gameplay TTS queue. */
class CountdownClosingAudio implements AutoCloseable {
    private static final String TAG = "CountdownClosingAudio";
    private static final int MAX_WAV_BYTES = 8 * 1024 * 1024;
    private static final long PREPARATION_TIMEOUT_MS = 15_000L;
    private static final int TAIL_MS = 20;
    private final Context context;
    private final String text;
    private final String engineName;
    private final Voice voice;
    private final Locale locale;
    private final Runnable onPrepared;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<File> ownedFiles = new ArrayList<>();
    private final String owner = UUID.randomUUID().toString();
    private TextToSpeech renderer;
    private MediaPlayer player;
    private String synthesisId;
    private File synthesisFile;
    private float synthesisRate = 1.0f;
    private int attempt;
    private long duration;
    private long playGeneration;
    private boolean requested, completed, closed, playing;
    private Runnable finished;
    private final Runnable preparationTimeout = () -> failPreparation("Closing speech preparation timed out");

    CountdownClosingAudio(Context context, TextToSpeech playbackEngine, String text, Runnable onPrepared) {
        this.context = context.getApplicationContext();
        this.text = text;
        this.onPrepared = onPrepared;
        String selectedEngine = null;
        Voice selectedVoice = null;
        try {
            if (playbackEngine != null) {
                selectedEngine = playbackEngine.getDefaultEngine();
                selectedVoice = playbackEngine.getVoice();
            }
        } catch (RuntimeException e) { Log.w(TAG, "Cannot copy the current speech voice", e); }
        engineName = selectedEngine;
        voice = selectedVoice;
        locale = selectedVoice == null ? Locale.getDefault() : selectedVoice.getLocale();
    }

    /** Initialization and engine configuration run on main; WAV work never does. */
    void prepare() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::prepare);
            return;
        }
        synchronized (this) {
            if (closed || requested) return;
            requested = true;
            main.postDelayed(preparationTimeout, PREPARATION_TIMEOUT_MS);
            try {
                renderer = new TextToSpeech(context,
                        status -> main.post(() -> rendererInitialized(status)), engineName);
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot initialize closing speech renderer", e);
                failPreparation("Closing speech renderer is unavailable");
            }
        }
    }

    private synchronized void rendererInitialized(int status) {
        if (closed || completed || renderer == null) return;
        if (status != TextToSpeech.SUCCESS) {
            failPreparation("Closing speech renderer initialization failed");
            return;
        }
        final TextToSpeech source = renderer;
        try {
            int language = source.setLanguage(locale);
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                failPreparation("Closing speech language is unavailable");
                return;
            }
            if (voice != null) source.setVoice(voice);
            int result = source.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { onSynthesisDone(source, id); }
                @Override public void onError(String id) { onSynthesisError(source, id); }
                @Override public void onError(String id, int errorCode) { onSynthesisError(source, id); }
                @Override public void onStop(String id, boolean interrupted) { onSynthesisError(source, id); }
            });
            if (result != TextToSpeech.SUCCESS) {
                failPreparation("Closing speech listener was rejected");
                return;
            }
            synthesize(1.0f);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot configure closing speech renderer", e);
            failPreparation("Closing speech renderer configuration failed");
        }
    }

    private synchronized void synthesize(float rate) {
        if (closed || completed || renderer == null) return;
        try {
            synthesisFile = createOwnedFile();
            synthesisRate = rate;
            synthesisId = "simplecoil-closing-render-" + owner + "-" + (++attempt);
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, synthesisId);
            if (renderer.setSpeechRate(rate) != TextToSpeech.SUCCESS
                    || renderer.synthesizeToFile(text, params, synthesisFile, synthesisId) != TextToSpeech.SUCCESS)
                failPreparation("Closing speech synthesis was rejected");
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Cannot synthesize closing speech", e);
            failPreparation("Closing speech synthesis failed");
        }
    }

    /** Callbacks are accepted only for the exact private renderer and current request. */
    synchronized boolean onSynthesisDone(TextToSpeech source, String id) {
        if (!ownsSynthesis(source, id)) return false;
        final File input = synthesisFile;
        final float rate = synthesisRate;
        synthesisId = null;
        try {
            worker.execute(() -> preparePlayer(input, rate));
        } catch (RejectedExecutionException e) { failPreparation("Closing speech worker was stopped"); }
        return true;
    }

    synchronized boolean onSynthesisError(TextToSpeech source, String id) {
        if (!ownsSynthesis(source, id)) return false;
        synthesisId = null;
        failPreparation("Closing speech synthesis did not complete");
        return true;
    }

    private boolean ownsSynthesis(TextToSpeech source, String id) {
        return !closed && !completed && source == renderer && id != null && id.equals(synthesisId);
    }

    private void preparePlayer(File input, float rate) {
        MediaPlayer prepared = null;
        File output = null;
        boolean handedOff = false;
        try {
            Wav clip = readAndTrim(input);
            if (clip.durationMillis > GameStartCountdown.ENDING_VOICE_MS) {
                if (rate > 1.0f) throw new IOException("Closing speech remains longer than its countdown window");
                float retryRate = Math.min(2.0f, Math.max(1.1f,
                        clip.durationMillis / (float) GameStartCountdown.ENDING_VOICE_MS * 1.1f));
                main.post(() -> synthesize(retryRate));
                return;
            }
            output = createOwnedFile();
            try (FileOutputStream stream = new FileOutputStream(output)) { stream.write(clip.bytes); }
            synchronized (this) {
                if (closed || completed) throw new IOException("Closing audio preparation was cancelled");
            }
            prepared = new MediaPlayer();
            prepared.setDataSource(output.getAbsolutePath());
            prepared.prepare();
            final MediaPlayer readyPlayer = prepared;
            final File readyFile = output;
            if (!main.post(() -> publishPreparedPlayer(readyPlayer, readyFile, clip.durationMillis)))
                throw new IOException("Closing audio callback was rejected");
            handedOff = true;
            prepared = null;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Cannot prepare measured closing audio", e);
            failPreparation("Measured closing audio is unavailable");
        } finally {
            if (prepared != null) release(prepared);
            // close() may unlink before FileOutputStream opens. If it recreated the
            // exact owned file after that unlink, this worker is responsible for it.
            if (!handedOff && output != null) deleteOwnedFile(output);
        }
    }

    private synchronized void publishPreparedPlayer(MediaPlayer prepared, File output, long measuredDuration) {
        if (closed || completed) {
            release(prepared);
            deleteOwnedFile(output);
            return;
        }
        player = prepared;
        duration = measuredDuration;
        completed = true;
        main.removeCallbacks(preparationTimeout);
        shutdownRenderer();
        notifyPrepared();
    }

    private synchronized void failPreparation(String reason) {
        if (closed || completed) return;
        Log.w(TAG, reason);
        completed = true;
        synthesisId = null;
        main.removeCallbacks(preparationTimeout);
        shutdownRenderer();
        notifyPrepared();
    }

    private void notifyPrepared() {
        main.post(() -> {
            synchronized (CountdownClosingAudio.this) {
                if (!closed && onPrepared != null) onPrepared.run();
            }
        });
    }

    synchronized boolean isReady() { return !closed && player != null && duration > 0; }
    synchronized long durationMillis() { return isReady() ? duration : 0; }
    synchronized boolean isPlaying() { return !closed && playing; }

    /** The player is already prepared, so this does not introduce a file-opening delay. */
    synchronized boolean play(Runnable onFinished) {
        if (!isReady() || playing) return false;
        final long generation = ++playGeneration;
        final MediaPlayer current = player;
        finished = onFinished;
        try {
            current.setOnCompletionListener(ignored -> playbackFinished(current, generation, false));
            current.setOnErrorListener((ignored, what, extra) -> {
                playbackFinished(current, generation, true);
                return true;
            });
            playing = true;
            current.start();
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot play measured closing audio", e);
            playing = false;
            finished = null;
            player = null;
            release(current);
            return false;
        }
    }

    private void playbackFinished(MediaPlayer current, long generation, boolean error) {
        main.post(() -> {
            synchronized (CountdownClosingAudio.this) {
                if (closed || player != current || generation != playGeneration || !playing) return;
                boolean broken = error;
                if (!error) {
                    try { if (current.isPlaying()) return; }
                    catch (RuntimeException e) { broken = true; }
                }
                playing = false;
                Runnable callback = finished;
                finished = null;
                if (broken) errorCleanup(current);
                else {
                    try { current.seekTo(0); }
                    catch (RuntimeException e) { errorCleanup(current); }
                }
                if (callback != null) callback.run();
            }
        });
    }

    private void errorCleanup(MediaPlayer current) {
        if (player == current) player = null;
        release(current);
    }

    /** Keep the prepared clip, but never deliver the cancelled play's completion. */
    synchronized void stop() {
        ++playGeneration;
        finished = null;
        if (player == null) { playing = false; return; }
        try {
            if (playing) player.pause();
            playing = false;
            player.seekTo(0);
        } catch (RuntimeException e) {
            playing = false;
            errorCleanup(player);
        }
    }

    /** Only explicit host-clock refinements seek; ordinary countdown ticks never do. */
    synchronized void alignToRemaining(long remainingMillis) {
        if (!isReady() || !playing || remainingMillis <= 0 || remainingMillis >= duration) return;
        try { player.seekTo((int) (duration - remainingMillis)); }
        catch (RuntimeException e) { playbackFinished(player, playGeneration, true); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        ++playGeneration;
        playing = false;
        finished = null;
        main.removeCallbacks(preparationTimeout);
        synthesisId = null;
        shutdownRenderer();
        if (player != null) release(player);
        player = null;
        worker.shutdownNow();
        // Playback is released first. Only exact files created by this instance are removed.
        for (File file : ownedFiles)
            deleteOwnedFile(file);
        ownedFiles.clear();
    }

    private void shutdownRenderer() {
        TextToSpeech current = renderer;
        renderer = null;
        if (current == null) return;
        try { current.stop(); }
        catch (RuntimeException e) { Log.w(TAG, "Cannot stop closing speech renderer", e); }
        try { current.shutdown(); }
        catch (RuntimeException e) { Log.w(TAG, "Cannot shut down closing speech renderer", e); }
    }

    private synchronized File createOwnedFile() throws IOException {
        if (closed || completed) throw new IOException("Closing audio preparation was cancelled");
        File file = File.createTempFile("simplecoil-countdown-", ".wav", context.getCacheDir());
        ownedFiles.add(file);
        return file;
    }

    private static void release(MediaPlayer current) {
        try { current.release(); }
        catch (RuntimeException e) { Log.w(TAG, "Cannot release closing audio player", e); }
    }

    private static void deleteOwnedFile(File file) {
        if (file.exists() && !file.delete()) Log.w(TAG, "Cannot remove closing audio cache file");
    }

    static final class Wav {
        final byte[] bytes;
        final long durationMillis;
        Wav(byte[] bytes, long durationMillis) { this.bytes = bytes; this.durationMillis = durationMillis; }
    }

    /** Validate bounded PCM chunks and normalize their trimmed, frame-aligned data to a WAV. */
    static Wav readAndTrim(File file) throws IOException {
        long size = file.length();
        if (size < 44 || size > MAX_WAV_BYTES) throw new IOException("Invalid closing WAV size");
        byte[] bytes = new byte[(int) size];
        try (FileInputStream input = new FileInputStream(file)) {
            int at = 0, count;
            while (at < bytes.length && (count = input.read(bytes, at, bytes.length - at)) != -1) at += count;
            if (at != bytes.length || input.read() != -1) throw new IOException("Closing WAV changed while reading");
        }
        return trimPcmWave(bytes);
    }

    static Wav trimPcmWave(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 44 || bytes.length > MAX_WAV_BYTES
                || !tag(bytes, 0, "RIFF") || !tag(bytes, 8, "WAVE"))
            throw new IOException("Invalid closing WAV header");
        long riffEnd = u32(bytes, 4) + 8L;
        if (riffEnd != bytes.length) throw new IOException("Invalid closing WAV RIFF size");
        int format = 0, channels = 0, bits = 0, block = 0, dataAt = -1, dataSize = 0;
        long sampleRate = 0, byteRate = 0;
        boolean hasFormat = false;
        for (long at = 12; at < riffEnd;) {
            if (riffEnd - at < 8) throw new IOException("Truncated closing WAV chunk");
            int offset = (int) at;
            long chunkSize = u32(bytes, offset + 4), next = at + 8 + chunkSize + (chunkSize & 1L);
            if (next > riffEnd) throw new IOException("Closing WAV chunk exceeds file");
            if (tag(bytes, offset, "fmt ")) {
                if (hasFormat || chunkSize < 16 || chunkSize > 1024) throw new IOException("Invalid PCM format chunk");
                hasFormat = true;
                format = u16(bytes, offset + 8); channels = u16(bytes, offset + 10);
                sampleRate = u32(bytes, offset + 12); byteRate = u32(bytes, offset + 16);
                block = u16(bytes, offset + 20); bits = u16(bytes, offset + 22);
            } else if (tag(bytes, offset, "data")) {
                if (dataAt != -1 || chunkSize == 0) throw new IOException("Invalid PCM data chunk");
                dataAt = offset + 8; dataSize = (int) chunkSize;
            }
            at = next;
        }
        if (!hasFormat || dataAt < 0 || channels < 1 || channels > 8 || sampleRate < 8000 || sampleRate > 192000
                || !(format == 1 && (bits == 8 || bits == 16 || bits == 24 || bits == 32)
                || format == 3 && bits == 32) || block != channels * (bits / 8)
                || byteRate != sampleRate * block || dataSize % block != 0)
            throw new IOException("Unsupported or inconsistent closing PCM format");
        int frames = dataSize / block, lastAudible = -1;
        if (format == 3) {
            for (int at = dataAt; at < dataAt + dataSize; at += 4) {
                float sample = Float.intBitsToFloat((int) u32(bytes, at));
                if (Float.isNaN(sample) || Float.isInfinite(sample)) throw new IOException("Invalid float PCM sample");
            }
        }
        for (int frame = frames - 1; frame >= 0; frame--) {
            boolean audible = false;
            for (int channel = 0; channel < channels; channel++) {
                int sampleAt = dataAt + frame * block + channel * (bits / 8);
                if (format == 3) {
                    float sample = Float.intBitsToFloat((int) u32(bytes, sampleAt));
                    if (Float.isNaN(sample) || Float.isInfinite(sample)) throw new IOException("Invalid float PCM sample");
                    audible |= Math.abs(sample) > 0.0005f;
                } else if (bits == 8) audible |= Math.abs((bytes[sampleAt] & 255) - 128) > 1;
                else {
                    long sample = 0;
                    for (int b = 0; b < bits / 8; b++) sample |= (bytes[sampleAt + b] & 255L) << (b * 8);
                    if ((sample & (1L << (bits - 1))) != 0) sample -= 1L << bits;
                    audible |= Math.abs(sample) > (1L << (bits - 12 < 0 ? 0 : bits - 12));
                }
            }
            if (audible) { lastAudible = frame; break; }
        }
        if (lastAudible < 0) throw new IOException("Closing PCM contains no audible speech");
        int keepFrames = (int) Math.min(frames, lastAudible + 1L + (sampleRate * TAIL_MS + 999L) / 1000L);
        int keepBytes = keepFrames * block;
        byte[] result = new byte[44 + keepBytes + (keepBytes & 1)];
        putTag(result, 0, "RIFF"); put32(result, 4, result.length - 8); putTag(result, 8, "WAVE");
        putTag(result, 12, "fmt "); put32(result, 16, 16); put16(result, 20, format);
        put16(result, 22, channels); put32(result, 24, sampleRate); put32(result, 28, byteRate);
        put16(result, 32, block); put16(result, 34, bits); putTag(result, 36, "data"); put32(result, 40, keepBytes);
        System.arraycopy(bytes, dataAt, result, 44, keepBytes);
        return new Wav(result, (keepFrames * 1000L + sampleRate - 1L) / sampleRate);
    }

    private static boolean tag(byte[] bytes, int at, String tag) {
        for (int n = 0; n < 4; n++) if (bytes[at + n] != tag.charAt(n)) return false;
        return true;
    }
    private static int u16(byte[] bytes, int at) { return (bytes[at] & 255) | (bytes[at + 1] & 255) << 8; }
    private static long u32(byte[] bytes, int at) {
        return (bytes[at] & 255L) | (bytes[at + 1] & 255L) << 8 | (bytes[at + 2] & 255L) << 16
                | (bytes[at + 3] & 255L) << 24;
    }
    private static void putTag(byte[] bytes, int at, String tag) {
        for (int n = 0; n < 4; n++) bytes[at + n] = (byte) tag.charAt(n);
    }
    private static void put16(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value; bytes[at + 1] = (byte) (value >>> 8);
    }
    private static void put32(byte[] bytes, int at, long value) {
        for (int n = 0; n < 4; n++) bytes[at + n] = (byte) (value >>> (8 * n));
    }
}
