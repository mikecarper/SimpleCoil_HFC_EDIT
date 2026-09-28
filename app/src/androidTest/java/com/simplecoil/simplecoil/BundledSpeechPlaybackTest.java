package com.simplecoil.simplecoil;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaMetadataRetriever;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Android 5.1-compatible decoder/queue checks, plus an opt-in speaker test. */
@RunWith(AndroidJUnit4.class)
public class BundledSpeechPlaybackTest {
    @Test public void everyBundledAnnouncementIsReadableByThePhonesMediaDecoder() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        KillStreakVoice voice = new KillStreakVoice(new Random(4));
        Set<Integer> checked = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            int resource = BundledAnnouncementAudio.resourceFor(voice.recordKill());
            if (resource == 0 || !checked.add(resource)) continue;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try (AssetFileDescriptor file = context.getResources().openRawResourceFd(resource)) {
                assertNotNull(file);
                retriever.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
                long duration = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                assertTrue("Invalid clip duration: " + duration, duration >= 400 && duration <= 12000);
            } finally {
                retriever.release();
            }
        }
        for (int resource : new int[]{R.raw.game_end_won, R.raw.game_end_lost, R.raw.game_end_tied}) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try (AssetFileDescriptor file = context.getResources().openRawResourceFd(resource)) {
                assertNotNull(file);
                retriever.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
                long duration = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                assertTrue("Invalid clip duration: " + duration, duration >= 400 && duration <= 12000);
                checked.add(resource);
            } finally {
                retriever.release();
            }
        }
        assertEquals(23, checked.size());
    }

    @Test public void realSpeechQueuePlaysBundledJokeAfterAFullOneSecondPause() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<TextToSpeech> reference = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicLong cueFinished = new AtomicLong();
        AtomicLong jokeStarted = new AtomicLong();
        AtomicLong jokeFinished = new AtomicLong();
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> reference.set(new TextToSpeech(context, status -> {
            if (status != TextToSpeech.SUCCESS) error.set("TTS initialization: " + status);
            ready.countDown();
        })));
        try {
            assertTrue("TTS initialization timed out", ready.await(15, TimeUnit.SECONDS));
            assertNull(error.get());
            TextToSpeech speech = reference.get();
            assertNotNull(speech);
            assertTrue(speech.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE);
            Map<String, String> keys = BundledAnnouncementAudio.register(context, speech, Locale.US);
            assertEquals(23, keys.size());
            speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    android.util.Log.i("BundledSpeechTest", "onStart: " + id);
                    if ("joke".equals(id)) jokeStarted.set(SystemClock.elapsedRealtime());
                }
                @Override public void onDone(String id) {
                    android.util.Log.i("BundledSpeechTest", "onDone: " + id);
                    if ("cue".equals(id)) cueFinished.set(SystemClock.elapsedRealtime());
                    if ("joke".equals(id)) {
                        jokeFinished.set(SystemClock.elapsedRealtime());
                        done.countDown();
                    }
                }
                @Override public void onError(String id) {
                    android.util.Log.e("BundledSpeechTest", "onError: " + id);
                    error.set("Playback failed: " + id);
                    done.countDown();
                }
            });
            Bundle muted = new Bundle();
            muted.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f);
            speech.setSpeechRate(1.5f);
            assertEquals(TextToSpeech.SUCCESS, speech.speak(
                    context.getString(R.string.enemy_destroyed_voice_prompt), TextToSpeech.QUEUE_ADD, muted, "cue"));
            assertEquals(TextToSpeech.SUCCESS, speech.playSilentUtterance(1000, TextToSpeech.QUEUE_ADD, "pause"));
            Bundle clipParams = BundledAnnouncementAudio.parameters("joke");
            clipParams.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f);
            assertEquals(TextToSpeech.SUCCESS, speech.speak(keys.get(context.getString(
                    R.string.kill_streak_2_voice_prompt)), TextToSpeech.QUEUE_ADD, clipParams, "joke"));
            assertTrue("Playback completion timed out", done.await(20, TimeUnit.SECONDS));
            assertNull(error.get());
            assertTrue("Missing cue completion", cueFinished.get() > 0);
            long pause = jokeStarted.get() - cueFinished.get();
            assertTrue("Joke started too early: " + pause + "ms", pause >= 950);
            assertTrue("Kill cue speed incorrectly accelerated the recording",
                    jokeFinished.get() - jokeStarted.get() >= 900);
            android.util.Log.i("BundledSpeechTest", "Clip playback completed; cue-to-joke pause=" + pause + "ms");
        } finally {
            if (reference.get() != null) {
                reference.get().stop();
                reference.get().shutdown();
            }
        }
    }

    @Test public void realSpeechQueuePlaysEachGameEndRecording() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<TextToSpeech> reference = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> reference.set(new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) ready.countDown();
        })));
        try {
            assertTrue("TTS initialization timed out", ready.await(15, TimeUnit.SECONDS));
            TextToSpeech speech = reference.get();
            assertNotNull(speech);
            assertTrue(speech.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE);
            Map<String, String> keys = BundledAnnouncementAudio.register(context, speech, Locale.US);
            assertEquals(23, keys.size());
            int[] prompts = {R.string.game_end_won_voice_prompt,
                    R.string.game_end_lost_voice_prompt, R.string.game_end_tied_voice_prompt};
            for (int prompt : prompts) {
                String id = "game-end-" + prompt;
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<String> error = new AtomicReference<>();
                speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { }
                    @Override public void onDone(String utteranceId) {
                        if (id.equals(utteranceId)) done.countDown();
                    }
                    @Override public void onError(String utteranceId) {
                        if (id.equals(utteranceId)) {
                            error.set("Playback failed: " + utteranceId);
                            done.countDown();
                        }
                    }
                });
                Bundle muted = BundledAnnouncementAudio.parameters(id);
                muted.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f);
                assertEquals(TextToSpeech.SUCCESS, speech.speak(keys.get(context.getString(prompt)),
                        TextToSpeech.QUEUE_FLUSH, muted, id));
                assertTrue("Game-end playback timed out: " + prompt, done.await(12, TimeUnit.SECONDS));
                assertNull(error.get());
            }
        } finally {
            if (reference.get() != null) {
                reference.get().stop();
                reference.get().shutdown();
            }
        }
    }

    /** Manual speaker test. Run only with -e audibleAnnouncements true. */
    @Test public void playEveryBundledAnnouncementAudibly() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments()
                .getString("audibleAnnouncements")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        assertNotNull(audio);
        int audibleLevel = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 3 / 5);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, audibleLevel, 0);
        assertTrue("Media volume is muted", audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0);

        int[] prompts = {
                R.string.game_end_won_voice_prompt, R.string.game_end_lost_voice_prompt,
                R.string.game_end_tied_voice_prompt, R.string.kill_streak_2_voice_prompt,
                R.string.kill_streak_3_voice_prompt, R.string.kill_streak_4_voice_prompt,
                R.string.kill_streak_5_voice_prompt, R.string.kill_streak_6_voice_prompt,
                R.string.kill_streak_7_voice_prompt, R.string.kill_streak_8_voice_prompt,
                R.string.kill_streak_9_a_voice_prompt, R.string.kill_streak_9_b_voice_prompt,
                R.string.kill_streak_9_c_voice_prompt, R.string.kill_streak_9_d_voice_prompt,
                R.string.kill_streak_9_e_voice_prompt, R.string.kill_streak_9_f_voice_prompt,
                R.string.kill_streak_9_g_voice_prompt, R.string.kill_streak_9_h_voice_prompt,
                R.string.kill_streak_9_i_voice_prompt, R.string.kill_streak_9_j_voice_prompt,
                R.string.kill_streak_9_k_voice_prompt, R.string.kill_streak_9_l_voice_prompt,
                R.string.kill_streak_9_m_voice_prompt
        };
        assertEquals(BundledAnnouncementAudio.count(), prompts.length);
        AtomicReference<TextToSpeech> reference = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> reference.set(new TextToSpeech(context, status -> {
            if (status != TextToSpeech.SUCCESS) error.set("TTS initialization: " + status);
            ready.countDown();
        })));
        try {
            assertTrue("TTS initialization timed out", ready.await(15, TimeUnit.SECONDS));
            assertNull(error.get());
            TextToSpeech speech = reference.get();
            assertNotNull(speech);
            assertTrue(speech.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE);
            Map<String, String> keys = BundledAnnouncementAudio.register(context, speech, Locale.US);
            assertEquals(prompts.length, keys.size());
            for (int index = 0; index < prompts.length; index++) {
                String id = "audible-" + index;
                CountDownLatch started = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<String> playbackError = new AtomicReference<>();
                speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {
                        if (id.equals(utteranceId)) started.countDown();
                    }
                    @Override public void onDone(String utteranceId) {
                        if (id.equals(utteranceId)) done.countDown();
                    }
                    @Override public void onError(String utteranceId) {
                        if (id.equals(utteranceId)) {
                            playbackError.set("Playback failed: " + utteranceId);
                            done.countDown();
                        }
                    }
                });
                String line = context.getString(prompts[index]);
                String key = keys.get(line);
                assertNotNull("Unregistered clip: " + line, key);
                Bundle params = BundledAnnouncementAudio.parameters(id);
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
                assertEquals("Could not queue: " + line, TextToSpeech.SUCCESS,
                        speech.speak(key, TextToSpeech.QUEUE_ADD, params, id));
                assertTrue("Clip did not start: " + line, started.await(5, TimeUnit.SECONDS));
                assertTrue("Clip did not finish: " + line, done.await(12, TimeUnit.SECONDS));
                assertNull(playbackError.get());
                android.util.Log.i("BundledSpeechTest", "Audible " + (index + 1) + "/"
                        + prompts.length + ": " + line);
            }
        } finally {
            if (reference.get() != null) {
                reference.get().stop();
                reference.get().shutdown();
            }
        }
    }
}
