package com.simplecoil.simplecoil;

import com.simplecoil.protocol.Powerups;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class PowerupPresentationTest {
    @Test public void everyRewardHasItsOwnNameAndRecognizedVoice() {
        Set<Integer> names = new HashSet<>();
        Set<Integer> voices = new HashSet<>();
        for (int kind = Powerups.EMP; kind <= Powerups.AIRSTRIKE; kind++) {
            int name = PowerupPresentation.name(kind);
            int voice = PowerupPresentation.voice(kind);
            assertNotEquals(0, name);
            assertNotEquals(0, voice);
            assertTrue(names.add(name));
            assertTrue(voices.add(voice));
            assertTrue(PowerupPresentation.isVoice(voice));
        }
        assertEquals(10, names.size());
    }

    @Test public void airstrikeUsesTheLocalizedAirstrikeResources() {
        assertEquals(10, Powerups.AIRSTRIKE);
        assertEquals(R.string.powerup_airstrike, PowerupPresentation.name(Powerups.AIRSTRIKE));
        assertEquals(R.string.powerup_airstrike_voice, PowerupPresentation.voice(Powerups.AIRSTRIKE));
    }

    @Test public void unknownKindsAndUnrelatedSpeechAreNotRewards() {
        for (int kind : new int[]{Integer.MIN_VALUE, -1, 0, Powerups.AIRSTRIKE + 1,
                Integer.MAX_VALUE}) {
            assertEquals(0, PowerupPresentation.name(kind));
            assertEquals(0, PowerupPresentation.voice(kind));
        }
        assertFalse(PowerupPresentation.isVoice(0));
        assertFalse(PowerupPresentation.isVoice(R.string.inactivity_battlefield_voice_prompt));
        assertFalse(PowerupPresentation.isVoice(R.string.powerup_airstrike_eliminated_voice));
    }
}
