package com.simplecoil.simplecoil;

import org.junit.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

public class BundledAnnouncementAudioTest {
    @Test public void everyPossibleStreakPromptHasItsOwnRecording() {
        KillStreakVoice voice = new KillStreakVoice(new Random(4));
        Set<Integer> prompts = new HashSet<>();
        Set<Integer> recordings = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            int prompt = voice.recordKill();
            if (prompt == 0) continue;
            int recording = BundledAnnouncementAudio.resourceFor(prompt);
            assertNotEquals("Missing bundled audio for " + prompt, 0, recording);
            prompts.add(prompt);
            recordings.add(recording);
        }
        assertEquals(20, prompts.size());
        assertEquals(prompts.size(), recordings.size());
    }

    @Test public void everyGameEndOutcomeHasADistinctRecording() {
        Set<Integer> recordings = new HashSet<>();
        recordings.add(BundledAnnouncementAudio.resourceFor(R.string.game_end_won_voice_prompt));
        recordings.add(BundledAnnouncementAudio.resourceFor(R.string.game_end_lost_voice_prompt));
        recordings.add(BundledAnnouncementAudio.resourceFor(R.string.game_end_tied_voice_prompt));
        assertFalse(recordings.contains(0));
        assertEquals(3, recordings.size());
        assertEquals(23, BundledAnnouncementAudio.count());
    }

    @Test public void ordinaryAnnouncementsHaveNoRecordingOverride() {
        assertEquals(0, BundledAnnouncementAudio.resourceFor(R.string.enemy_destroyed_voice_prompt));
        assertEquals(0, BundledAnnouncementAudio.resourceFor(R.string.reload_voice_prompt));
        assertEquals(0, BundledAnnouncementAudio.resourceFor(R.string.game_start_voice_prompt));
        assertEquals(0, BundledAnnouncementAudio.resourceFor(0));
    }

    @Test public void onlyEnglishPromptsUseEnglishRecordings() {
        assertTrue(BundledAnnouncementAudio.supports(Locale.UK));
        assertTrue(BundledAnnouncementAudio.supports(Locale.US));
        assertFalse(BundledAnnouncementAudio.supports(Locale.GERMANY));
        assertFalse(BundledAnnouncementAudio.supports(null));
    }
}
