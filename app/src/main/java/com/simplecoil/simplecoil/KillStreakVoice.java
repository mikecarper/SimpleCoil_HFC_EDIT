package com.simplecoil.simplecoil;

import java.util.Random;

/** Chooses a voice line for consecutive credited kills without a death. */
final class KillStreakVoice {
    private static final int[] RANDOM_LINES = {
            R.string.kill_streak_5_voice_prompt,
            R.string.kill_streak_6_voice_prompt,
            R.string.kill_streak_7_voice_prompt,
            R.string.kill_streak_8_voice_prompt,
            R.string.kill_streak_9_a_voice_prompt,
            R.string.kill_streak_9_b_voice_prompt,
            R.string.kill_streak_9_c_voice_prompt,
            R.string.kill_streak_9_d_voice_prompt,
            R.string.kill_streak_9_e_voice_prompt,
            R.string.kill_streak_9_f_voice_prompt,
            R.string.kill_streak_9_g_voice_prompt,
            R.string.kill_streak_9_h_voice_prompt,
            R.string.kill_streak_9_i_voice_prompt,
            R.string.kill_streak_9_j_voice_prompt,
            R.string.kill_streak_9_k_voice_prompt,
            R.string.kill_streak_9_l_voice_prompt,
            R.string.kill_streak_9_m_voice_prompt
    };

    private final Random random;
    private final int[] shuffledLines = RANDOM_LINES.clone();
    private int streak;
    private int nextRandom = shuffledLines.length;
    private int lastRandom;

    KillStreakVoice(Random random) {
        this.random = random;
    }

    void resetStreak() {
        streak = 0;
    }

    int recordKill() {
        if (streak < Integer.MAX_VALUE)
            streak++;
        switch (streak) {
            case 2: return R.string.kill_streak_2_voice_prompt;
            case 3: return R.string.kill_streak_3_voice_prompt;
            case 4: return R.string.kill_streak_4_voice_prompt;
            default: return streak >= 5 ? nextRandomLine() : 0;
        }
    }

    private int nextRandomLine() {
        if (nextRandom == shuffledLines.length) {
            for (int i = shuffledLines.length - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int value = shuffledLines[i];
                shuffledLines[i] = shuffledLines[j];
                shuffledLines[j] = value;
            }
            if (shuffledLines[0] == lastRandom) {
                int value = shuffledLines[0];
                shuffledLines[0] = shuffledLines[1];
                shuffledLines[1] = value;
            }
            nextRandom = 0;
        }
        lastRandom = shuffledLines[nextRandom];
        return shuffledLines[nextRandom++];
    }
}
