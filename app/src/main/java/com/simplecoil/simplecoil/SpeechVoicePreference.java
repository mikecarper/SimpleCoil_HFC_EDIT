package com.simplecoil.simplecoil;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Select an installed UK male voice without making gameplay depend on Internet TTS. */
final class SpeechVoicePreference {
    private static final Pattern MALE = Pattern.compile("(^|[^a-z])male([^a-z]|$)");
    private static final Pattern FEMALE = Pattern.compile("(^|[^a-z])female([^a-z]|$)");

    static final class Candidate {
        final String name;
        final Locale locale;
        final int quality;
        final int latency;
        final boolean networkRequired;
        final Set<String> features;

        Candidate(String name, Locale locale, int quality, int latency,
                  boolean networkRequired, Set<String> features) {
            this.name = name;
            this.locale = locale;
            this.quality = quality;
            this.latency = latency;
            this.networkRequired = networkRequired;
            this.features = features;
        }
    }

    private SpeechVoicePreference() { }

    static Candidate britishMale(String engine, Iterable<Candidate> candidates) {
        Candidate best = null;
        if (candidates == null) return null;
        for (Candidate candidate : candidates) {
            if (!eligible(engine, candidate)) continue;
            if (best == null || candidate.quality > best.quality
                    || candidate.quality == best.quality && candidate.latency < best.latency
                    || candidate.quality == best.quality && candidate.latency == best.latency
                    && candidate.name.compareTo(best.name) < 0) best = candidate;
        }
        return best;
    }

    private static boolean eligible(String engine, Candidate voice) {
        if (voice == null || voice.name == null || voice.locale == null || voice.networkRequired
                || !"en".equals(voice.locale.getLanguage()) || !"GB".equals(voice.locale.getCountry())
                || voice.features != null && voice.features.contains("notInstalled")) return false;
        String name = voice.name.toLowerCase(Locale.ROOT);
        // Android Voice has no standard gender field. Explicit male names are
        // usable across engines; do not accidentally match "male" in "female".
        if (FEMALE.matcher(name).find()) return false;
        if (MALE.matcher(name).find()) return true;
        if (!"com.google.android.tts".equals(engine)) return false;
        // Google's legacy rjs male is documented in its voices-list-r1.proto.
        // Newer gbb/gbd voices are also male (RT-Voice's Android voice catalog).
        // Only exact local names qualify: language defaults have unknown gender.
        return "en-gb-x-rjs-local".equals(name) || "en-gb-x-gbb-local".equals(name)
                || "en-gb-x-gbd-local".equals(name);
    }
}
