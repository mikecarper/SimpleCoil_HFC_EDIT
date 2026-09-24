package com.simplecoil.simplecoil;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class KillStreakVoiceTest {
    @Test public void prescribedLinesBeginAtSecondKillAndStreakResetsAfterDeath() {
        KillStreakVoice voice = new KillStreakVoice(new Random(7));
        assertEquals(0, voice.recordKill());
        assertEquals(R.string.kill_streak_2_voice_prompt, voice.recordKill());
        assertEquals(R.string.kill_streak_3_voice_prompt, voice.recordKill());
        assertEquals(R.string.kill_streak_4_voice_prompt, voice.recordKill());
        assertTrue(allRandomLines().contains(voice.recordKill()));
        voice.resetStreak();
        assertEquals(0, voice.recordKill());
        assertEquals(R.string.kill_streak_2_voice_prompt, voice.recordKill());
        assertEquals(R.string.kill_streak_3_voice_prompt, voice.recordKill());
        assertEquals(R.string.kill_streak_4_voice_prompt, voice.recordKill());
    }

    @Test public void fifthAndLaterKillsUseEveryRandomLineBeforeRepeating() {
        KillStreakVoice voice = new KillStreakVoice(new Random(11));
        for (int i = 0; i < 4; i++)
            voice.recordKill();
        Set<Integer> expected = allRandomLines();
        Set<Integer> firstCycle = new HashSet<>();
        Set<Integer> secondCycle = new HashSet<>();
        int last = 0;
        for (int i = 0; i < expected.size(); i++) {
            last = voice.recordKill();
            firstCycle.add(last);
        }
        assertEquals(expected, firstCycle);
        int next = voice.recordKill();
        assertNotEquals(last, next);
        secondCycle.add(next);
        for (int i = 1; i < expected.size(); i++)
            secondCycle.add(voice.recordKill());
        assertEquals(expected, secondCycle);
    }

    @Test public void deathDoesNotRestartTheRandomShuffleBag() {
        KillStreakVoice voice = new KillStreakVoice(new Random(19));
        for (int i = 0; i < 4; i++)
            voice.recordKill();
        Set<Integer> heard = new HashSet<>();
        heard.add(voice.recordKill());
        heard.add(voice.recordKill());

        voice.resetStreak();
        for (int i = 0; i < 4; i++)
            voice.recordKill();
        for (int i = 0; i < allRandomLines().size() - 2; i++)
            heard.add(voice.recordKill());
        assertEquals(allRandomLines(), heard);
    }

    private static Set<Integer> allRandomLines() {
        Set<Integer> lines = new HashSet<>();
        lines.add(R.string.kill_streak_5_voice_prompt);
        lines.add(R.string.kill_streak_6_voice_prompt);
        lines.add(R.string.kill_streak_7_voice_prompt);
        lines.add(R.string.kill_streak_8_voice_prompt);
        lines.add(R.string.kill_streak_9_a_voice_prompt);
        lines.add(R.string.kill_streak_9_b_voice_prompt);
        lines.add(R.string.kill_streak_9_c_voice_prompt);
        lines.add(R.string.kill_streak_9_d_voice_prompt);
        lines.add(R.string.kill_streak_9_e_voice_prompt);
        lines.add(R.string.kill_streak_9_f_voice_prompt);
        lines.add(R.string.kill_streak_9_g_voice_prompt);
        lines.add(R.string.kill_streak_9_h_voice_prompt);
        lines.add(R.string.kill_streak_9_i_voice_prompt);
        lines.add(R.string.kill_streak_9_j_voice_prompt);
        lines.add(R.string.kill_streak_9_k_voice_prompt);
        lines.add(R.string.kill_streak_9_l_voice_prompt);
        lines.add(R.string.kill_streak_9_m_voice_prompt);
        return lines;
    }
}
