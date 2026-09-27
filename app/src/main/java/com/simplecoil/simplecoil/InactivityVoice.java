package com.simplecoil.simplecoil;

import java.util.Random;

/** Chooses non-repeating prompts for a living player who has stopped participating. */
final class InactivityVoice {
    private static final int[] LINES = {
            R.string.inactivity_battle_voice_prompt,
            R.string.inactivity_recruit_voice_prompt,
            R.string.inactivity_legs_voice_prompt,
            R.string.inactivity_breaking_news_voice_prompt
    };

    private final Random random;
    private final int[] shuffledLines = LINES.clone();
    private int nextLine = shuffledLines.length;
    private int lastLine;

    InactivityVoice(Random random) {
        this.random = random;
    }

    int nextPrompt() {
        if (nextLine == shuffledLines.length) {
            for (int i = shuffledLines.length - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int value = shuffledLines[i];
                shuffledLines[i] = shuffledLines[j];
                shuffledLines[j] = value;
            }
            if (shuffledLines[0] == lastLine) {
                int value = shuffledLines[0];
                shuffledLines[0] = shuffledLines[1];
                shuffledLines[1] = value;
            }
            nextLine = 0;
        }
        lastLine = shuffledLines[nextLine];
        return shuffledLines[nextLine++];
    }
}
