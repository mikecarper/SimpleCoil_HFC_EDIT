package com.simplecoil.simplecoil;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class InactivityVoiceTest {
    @Test public void everyPromptIsUsedBeforeAnyPromptRepeats() {
        InactivityVoice voice = new InactivityVoice(new Random(13));
        Set<Integer> expected = prompts();
        Set<Integer> firstCycle = new HashSet<>();
        int last = 0;
        for (int i = 0; i < expected.size(); i++) {
            last = voice.nextPrompt();
            firstCycle.add(last);
        }
        assertEquals(expected, firstCycle);

        int next = voice.nextPrompt();
        assertNotEquals(last, next);
        Set<Integer> secondCycle = new HashSet<>();
        secondCycle.add(next);
        for (int i = 1; i < expected.size(); i++)
            secondCycle.add(voice.nextPrompt());
        assertEquals(expected, secondCycle);
    }

    private static Set<Integer> prompts() {
        Set<Integer> prompts = new HashSet<>();
        prompts.add(R.string.inactivity_battle_voice_prompt);
        prompts.add(R.string.inactivity_recruit_voice_prompt);
        prompts.add(R.string.inactivity_legs_voice_prompt);
        prompts.add(R.string.inactivity_breaking_news_voice_prompt);
        return prompts;
    }
}
