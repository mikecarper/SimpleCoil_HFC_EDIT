package com.simplecoil.simplecoil;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import static org.junit.Assert.*;

public class SpeechVoicePreferenceTest {
    private static final String GOOGLE = "com.google.android.tts";

    private SpeechVoicePreference.Candidate voice(String name) {
        return voice(name, Locale.UK, 400, 200, false, false);
    }

    private SpeechVoicePreference.Candidate voice(String name, Locale locale, int quality,
                                                  int latency, boolean network, boolean missing) {
        return new SpeechVoicePreference.Candidate(name, locale, quality, latency, network,
                missing ? Collections.singleton("notInstalled") : Collections.emptySet());
    }

    @Test public void recognizesLegacyAndNewGoogleBritishMaleVoices() {
        for (String name : new String[]{"en-gb-x-rjs-local", "en-gb-x-gbb-local", "en-gb-x-gbd-local",
                "en-gb-x-rjs#male_1-local"}) {
            SpeechVoicePreference.Candidate candidate = voice(name);
            assertSame(candidate, SpeechVoicePreference.britishMale(GOOGLE, Collections.singleton(candidate)));
        }
    }

    @Test public void neverMatchesMaleInsideFemaleOrAssumesUkDefaultIsMale() {
        for (String name : new String[]{"en-gb-x-fis-local", "en-gb-x-gba-local", "en-gb-x-gbc-local",
                "en-gb-x-gbg-local", "en-GB-language", "en-gb-x-rjs#female_1-local", "UK Female"})
            assertNull(SpeechVoicePreference.britishMale(GOOGLE, Collections.singleton(voice(name))));
    }

    @Test public void explicitMaleNamesWorkForOtherEnginesButGoogleIdsDoNot() {
        SpeechVoicePreference.Candidate male = voice("British English Male 1");
        assertSame(male, SpeechVoicePreference.britishMale("other.tts", Arrays.asList(
                voice("en-gb-x-gbb-local"), male, voice("British English Female"))));
        assertNull(SpeechVoicePreference.britishMale("other.tts",
                Collections.singleton(voice("en-gb-x-gbb-local"))));
    }

    @Test public void offlineGameplayNeverSelectsANetworkOrUninstalledVoice() {
        assertNull(SpeechVoicePreference.britishMale(GOOGLE, Arrays.asList(
                voice("en-gb-x-rjs#male_1-network", Locale.UK, 500, 100, true, false),
                voice("en-gb-x-rjs-local", Locale.UK, 500, 100, false, true))));
    }

    @Test public void onlyBritishEnglishQualifiesEvenWhenTheNameLooksRight() {
        for (Locale locale : new Locale[]{Locale.US, Locale.GERMANY, Locale.ENGLISH, new Locale("en", "AU")})
            assertNull(SpeechVoicePreference.britishMale(GOOGLE,
                    Collections.singleton(voice("en-gb-x-rjs-local", locale, 400, 200, false, false))));
    }

    @Test public void missingOrMalformedEngineInventoryFallsBackWithoutThrowing() {
        assertNull(SpeechVoicePreference.britishMale(GOOGLE, null));
        assertNull(SpeechVoicePreference.britishMale(GOOGLE, Arrays.asList(null,
                voice(null), voice("UK Male", null, 400, 200, false, false))));
    }

    @Test public void bestQualityThenLowestLatencyWinsRegardlessOfEnumerationOrder() {
        SpeechVoicePreference.Candidate best = voice("en-gb-x-gbd-local", Locale.UK, 400, 100, false, false);
        java.util.List<SpeechVoicePreference.Candidate> voices = Arrays.asList(
                voice("en-gb-x-rjs-local", Locale.UK, 300, 100, false, false),
                voice("en-gb-x-gbb-local"), best);
        assertSame(best, SpeechVoicePreference.britishMale(GOOGLE, voices));
        Collections.reverse(voices);
        assertSame(best, SpeechVoicePreference.britishMale(GOOGLE, voices));
    }

    @Test public void equalVoicesHaveADeterministicTieBreak() {
        SpeechVoicePreference.Candidate first = voice("en-gb-x-gbb-local");
        SpeechVoicePreference.Candidate second = voice("en-gb-x-gbd-local");
        assertSame(first, SpeechVoicePreference.britishMale(GOOGLE, Arrays.asList(first, second)));
        assertSame(first, SpeechVoicePreference.britishMale(GOOGLE, Arrays.asList(second, first)));
    }
}
