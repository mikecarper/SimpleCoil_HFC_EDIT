package com.simplecoil.simplecoil;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Pre-generated Kokoro/George recordings. No model or voice downloads on the phone. */
final class KillStreakAudio {
    private static final int[][] CLIPS = {
            {R.string.kill_streak_2_voice_prompt, R.raw.kill_streak_2},
            {R.string.kill_streak_3_voice_prompt, R.raw.kill_streak_3},
            {R.string.kill_streak_4_voice_prompt, R.raw.kill_streak_4},
            {R.string.kill_streak_5_voice_prompt, R.raw.kill_streak_5},
            {R.string.kill_streak_6_voice_prompt, R.raw.kill_streak_6},
            {R.string.kill_streak_7_voice_prompt, R.raw.kill_streak_7},
            {R.string.kill_streak_8_voice_prompt, R.raw.kill_streak_8},
            {R.string.kill_streak_9_a_voice_prompt, R.raw.kill_streak_9_a},
            {R.string.kill_streak_9_b_voice_prompt, R.raw.kill_streak_9_b},
            {R.string.kill_streak_9_c_voice_prompt, R.raw.kill_streak_9_c},
            {R.string.kill_streak_9_d_voice_prompt, R.raw.kill_streak_9_d},
            {R.string.kill_streak_9_e_voice_prompt, R.raw.kill_streak_9_e},
            {R.string.kill_streak_9_f_voice_prompt, R.raw.kill_streak_9_f},
            {R.string.kill_streak_9_g_voice_prompt, R.raw.kill_streak_9_g},
            {R.string.kill_streak_9_h_voice_prompt, R.raw.kill_streak_9_h},
            {R.string.kill_streak_9_i_voice_prompt, R.raw.kill_streak_9_i},
            {R.string.kill_streak_9_j_voice_prompt, R.raw.kill_streak_9_j},
            {R.string.kill_streak_9_k_voice_prompt, R.raw.kill_streak_9_k},
            {R.string.kill_streak_9_l_voice_prompt, R.raw.kill_streak_9_l},
            {R.string.kill_streak_9_m_voice_prompt, R.raw.kill_streak_9_m}
    };

    static int count() { return CLIPS.length; }

    static int resourceFor(int prompt) {
        for (int[] clip : CLIPS)
            if (clip[0] == prompt) return clip[1];
        return 0;
    }

    static boolean supports(Locale locale) {
        return locale != null && "en".equals(locale.getLanguage());
    }

    static Bundle parameters(String utteranceId) {
        Bundle params = new Bundle();
        // Android 5.1 AudioSpeechItemV1 reads this legacy key instead of the
        // separate utteranceId argument. Supply both so clip callbacks work.
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
        return params;
    }

    static Map<String, String> register(Context context, TextToSpeech speech, Locale locale) {
        Map<String, String> keys = new HashMap<>();
        if (!supports(locale)) return keys;
        for (int[] clip : CLIPS) {
            // Private keys keep matching words in ordinary speech from being
            // replaced. Only speakGameText's kill-streak path uses these keys.
            String key = "simplecoil.kill-streak." + clip[0];
            try {
                if (speech.addSpeech((CharSequence) key, context.getPackageName(), clip[1])
                        == TextToSpeech.SUCCESS)
                    keys.put(context.getString(clip[0]), key);
            } catch (RuntimeException e) {
                Log.w("KillStreakAudio", "Unable to register bundled joke " + clip[0], e);
            }
        }
        return keys;
    }

    private KillStreakAudio() { }
}
