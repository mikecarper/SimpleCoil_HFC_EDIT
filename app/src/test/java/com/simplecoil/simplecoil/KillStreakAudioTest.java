package com.simplecoil.simplecoil;

import org.junit.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

public class KillStreakAudioTest {
    @Test public void everyPossibleStreakPromptHasItsOwnRecording() {
        KillStreakVoice voice = new KillStreakVoice(new Random(4));
        Set<Integer> prompts = new HashSet<>();
        Set<Integer> recordings = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            int prompt = voice.recordKill();
            if (prompt == 0) continue;
            int recording = KillStreakAudio.resourceFor(prompt);
            assertNotEquals("Missing bundled audio for " + prompt, 0, recording);
            prompts.add(prompt);
            recordings.add(recording);
        }
        assertEquals(20, KillStreakAudio.count());
        assertEquals(KillStreakAudio.count(), prompts.size());
        assertEquals(prompts.size(), recordings.size());
    }

    @Test public void ordinaryAnnouncementsHaveNoRecordingOverride() {
        assertEquals(0, KillStreakAudio.resourceFor(R.string.enemy_destroyed_voice_prompt));
        assertEquals(0, KillStreakAudio.resourceFor(R.string.reload_voice_prompt));
        assertEquals(0, KillStreakAudio.resourceFor(R.string.game_start_voice_prompt));
        assertEquals(0, KillStreakAudio.resourceFor(0));
    }

    @Test public void onlyEnglishPromptsUseEnglishRecordings() {
        assertTrue(KillStreakAudio.supports(Locale.UK));
        assertTrue(KillStreakAudio.supports(Locale.US));
        assertFalse(KillStreakAudio.supports(Locale.GERMANY));
        assertFalse(KillStreakAudio.supports(null));
    }
}
