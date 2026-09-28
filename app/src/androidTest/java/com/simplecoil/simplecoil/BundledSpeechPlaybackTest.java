package com.simplecoil.simplecoil;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
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

/** Focused Android 5.1-compatible decoder/queue checks, muted and without gameplay. */
@RunWith(AndroidJUnit4.class)
public class BundledSpeechPlaybackTest {
    @Test public void everyBundledJokeIsReadableByThePhonesMediaDecoder() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        KillStreakVoice voice = new KillStreakVoice(new Random(4));
        Set<Integer> checked = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            int resource = KillStreakAudio.resourceFor(voice.recordKill());
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
        assertEquals(20, checked.size());
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
            Map<String, String> keys = KillStreakAudio.register(context, speech, Locale.US);
            assertEquals(20, keys.size());
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
            Bundle clipParams = KillStreakAudio.parameters("joke");
            clipParams.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f);
            assertEquals(TextToSpeech.SUCCESS, speech.speak(keys.get(context.getString(
                    R.string.kill_streak_2_voice_prompt)), TextToSpeech.QUEUE_ADD, clipParams, "joke"));
            assertTrue("Playback completion timed out", done.await(20, TimeUnit.SECONDS));
            assertNull(error.get());
            assertTrue("Missing cue completion", cueFinished.get() > 0);
            long pause = jokeStarted.get() - cueFinished.get();
            assertTrue("Joke started too early: " + pause + "ms", pause >= 950);
            assertTrue("Kill cue speed incorrectly accelerated the recording",
                    jokeFinished.get() - jokeStarted.get() >= 1750);
            android.util.Log.i("BundledSpeechTest", "Clip playback completed; cue-to-joke pause=" + pause + "ms");
        } finally {
            if (reference.get() != null) {
                reference.get().stop();
                reference.get().shutdown();
            }
        }
    }
}
