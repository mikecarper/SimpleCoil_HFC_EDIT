package com.simplecoil.simplecoil;

/** UI and speech use the same reward ordering as the shared host protocol. */
final class PowerupPresentation {
    private static final int[] NAMES = {0, R.string.powerup_emp, R.string.powerup_shield_player,
            R.string.powerup_shield_team, R.string.powerup_auto, R.string.powerup_auto_90,
            R.string.powerup_double_damage, R.string.powerup_heal_team,
            R.string.powerup_damage_team, R.string.powerup_invulnerable,
            R.string.powerup_airstrike};
    private static final int[] VOICES = {0, R.string.powerup_emp_voice, R.string.powerup_shield_player_voice,
            R.string.powerup_shield_team_voice, R.string.powerup_auto_voice, R.string.powerup_auto_90_voice,
            R.string.powerup_double_damage_voice, R.string.powerup_heal_team_voice,
            R.string.powerup_damage_team_voice, R.string.powerup_invulnerable_voice,
            R.string.powerup_airstrike_voice};
    static int name(int kind) { return kind > 0 && kind < NAMES.length ? NAMES[kind] : 0; }
    static int voice(int kind) { return kind > 0 && kind < VOICES.length ? VOICES[kind] : 0; }
    static boolean isVoice(int resource) {
        for (int voice : VOICES) if (voice != 0 && voice == resource) return true;
        return false;
    }
    private PowerupPresentation() {}
}
