package com.simplecoil.simplecoil;

import org.junit.Test;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GlobalsTest {
    @Test public void defaultGameLengthIsFiveMinutes() {
        assertEquals(5, FullscreenActivity.DEFAULT_TIME_LIMIT_MINUTES);
    }

    @Test public void wifiIpv4UsesAndroidLittleEndianOctets() {
        InetAddress address = Globals.fromWifiIPv4Address(0x2A01A8C0);
        assertEquals("192.168.1.42", address.getHostAddress());
    }

    @Test public void wifiWithoutAnAddressDoesNotProduceAnyLocalAddress() {
        assertNull(Globals.fromWifiIPv4Address(0));
    }

    @Test public void hotspotInterfaceNamesAreAcceptedWithoutAcceptingCellularInterfaces() {
        assertTrue(Globals.isWifiInterfaceName("wlan0"));
        assertTrue(Globals.isWifiInterfaceName("ap0"));
        assertTrue(Globals.isWifiInterfaceName("softap0"));
        assertTrue(Globals.isWifiInterfaceName("swlan0"));
        assertFalse(Globals.isWifiInterfaceName("rmnet0"));
        assertFalse(Globals.isWifiInterfaceName("lo"));
    }

    @Test public void tournamentProfileMakesEveryWeaponSingleShotAndIdentical() {
        Globals.PlayerSettings settings = new Globals.PlayerSettings();
        settings.health = 400;
        settings.shots = 4;
        settings.reloadTime = 9_000;
        settings.reloadOnEmpty = true;
        settings.damage = -20;
        settings.allowShotModeSingle = false;
        settings.allowShotModeBurst3 = true;
        settings.allowShotModeAuto = true;
        settings.firingMode = Globals.FIRING_MODE_INDOOR_NO_CONE;

        Globals.applyTournamentRules(settings);

        assertFalse(Globals.TOURNAMENT_RULES_REQUIRED);
        assertEquals(Globals.MAX_HEALTH, settings.health);
        assertEquals(Globals.RELOAD_COUNT, settings.shots);
        assertEquals(Globals.RELOAD_TIME_MILLISECONDS, settings.reloadTime);
        assertFalse(settings.reloadOnEmpty);
        assertEquals(Globals.DAMAGE_PER_HIT, settings.damage);
        assertTrue(settings.allowShotModeSingle);
        assertFalse(settings.allowShotModeBurst3);
        assertFalse(settings.allowShotModeAuto);
        assertEquals(Globals.FIRING_MODE_OUTDOOR_NO_CONE, settings.firingMode);
    }

    @Test public void localTournamentProfileKeepsIndoorRangeWithoutUnlockingCone() {
        Globals globals = Globals.getInstance();
        boolean oldBossMode = globals.mBossMode;
        byte oldPlayerID = globals.mPlayerID;
        int oldFiringMode = globals.mCurrentFiringMode;
        try {
            globals.mBossMode = false;
            globals.mPlayerID = 2;
            globals.mCurrentFiringMode = Globals.FIRING_MODE_INDOOR_NO_CONE;
            globals.applyTournamentRules();
            assertEquals(Globals.FIRING_MODE_INDOOR_NO_CONE, globals.mCurrentFiringMode);
            globals.mCurrentFiringMode = Globals.FIRING_MODE_OUTDOOR_WITH_CONE;
            globals.applyTournamentRules();
            assertEquals(Globals.FIRING_MODE_OUTDOOR_NO_CONE, globals.mCurrentFiringMode);
        } finally {
            globals.mBossMode = oldBossMode;
            globals.mPlayerID = oldPlayerID;
            globals.mCurrentFiringMode = oldFiringMode;
        }
    }

    @Test public void bossProfileScalesAndUnlocksTheBossWeaponOnly() {
        Globals.PlayerSettings boss = new Globals.PlayerSettings();
        Globals.applyTournamentRules(boss);
        Globals.applyBossHealth(boss, Globals.BOSS_PLAYER_ID, 10);
        assertEquals(15, boss.health);
        assertEquals(120, boss.shots & 0xff);
        assertTrue(boss.allowShotModeSingle);
        assertTrue(boss.allowShotModeBurst3);
        assertTrue(boss.allowShotModeAuto);

        Globals.PlayerSettings hunter = new Globals.PlayerSettings();
        Globals.applyTournamentRules(hunter);
        Globals.applyBossHealth(hunter, 2, 10);
        assertEquals(2, hunter.health);
        assertEquals(30, hunter.shots & 0xff);
        assertTrue(hunter.allowShotModeSingle);
        assertFalse(hunter.allowShotModeBurst3);
        assertFalse(hunter.allowShotModeAuto);
    }

    @Test public void classicModesDisableTournamentWithoutChangingTheSelectedTeamMode() {
        Globals globals = Globals.getInstance();
        boolean oldTournament = globals.mTournamentMode;
        boolean oldBoss = globals.mBossMode;
        int oldMode = globals.mGameMode;
        try {
            for (int mode : new int[]{Globals.GAME_MODE_FFA, Globals.GAME_MODE_2TEAMS,
                    Globals.GAME_MODE_4TEAMS}) {
                globals.applyClassicRules(mode);
                assertFalse(globals.mTournamentMode);
                assertFalse(globals.mBossMode);
                assertEquals(mode, globals.mGameMode);
                assertTrue(globals.mAllowSingleShotMode);
                assertTrue(globals.mAllowBurst3ShotMode);
                assertTrue(globals.mAllowAutoShotMode);
            }
        } finally {
            globals.mTournamentMode = oldTournament;
            globals.mBossMode = oldBoss;
            globals.mGameMode = oldMode;
        }
    }

    @Test public void clearingLimitsRemovesAllActiveLimitValues() {
        Globals globals = Globals.getInstance();
        int gameLimit = globals.mGameLimit;
        int timeLimit = globals.mTimeLimit;
        int livesLimit = globals.mLivesLimit;
        int scoreLimit = globals.mScoreLimit;
        try {
            globals.mGameLimit = Globals.GAME_LIMIT_TIME | Globals.GAME_LIMIT_LIVES
                    | Globals.GAME_LIMIT_SCORE;
            globals.mTimeLimit = 30;
            globals.mLivesLimit = 5;
            globals.mScoreLimit = 100;

            globals.clearGameLimits();

            assertEquals(Globals.GAME_LIMIT_NONE, globals.mGameLimit);
            assertEquals(0, globals.mTimeLimit);
            assertEquals(0, globals.mLivesLimit);
            assertEquals(0, globals.mScoreLimit);
        } finally {
            globals.mGameLimit = gameLimit;
            globals.mTimeLimit = timeLimit;
            globals.mLivesLimit = livesLimit;
            globals.mScoreLimit = scoreLimit;
        }
    }

    @Test public void teamRosterCountsIncludeTheHostAndAllConnectedPeers() {
        Globals globals = Globals.getInstance();
        int originalGameMode = globals.mGameMode;
        byte originalPlayerID = globals.mPlayerID;
        Map<Byte, InetAddress> originalRoster;
        Globals.getmTeamIPMapSemaphore();
        try {
            originalRoster = new HashMap<>(globals.mTeamIPMap);
            globals.mTeamIPMap.clear();
            globals.mGameMode = Globals.GAME_MODE_4TEAMS;
            globals.mPlayerID = 1;
            globals.mTeamIPMap.put((byte) 9, InetAddress.getLoopbackAddress());
            globals.mTeamIPMap.put((byte) 17, InetAddress.getLoopbackAddress());
            globals.mTeamIPMap.put((byte) 25, InetAddress.getLoopbackAddress());
        } finally {
            globals.mTeamIPMapSemaphore.release();
        }
        try {
            assertArrayEquals(new int[]{1, 1, 1, 1}, globals.getNetworkTeamPlayerCounts());

            Globals.getmTeamIPMapSemaphore();
            try {
                globals.mTeamIPMap.clear();
                globals.mGameMode = Globals.GAME_MODE_2TEAMS;
                globals.mTeamIPMap.put((byte) 2, InetAddress.getLoopbackAddress());
                globals.mTeamIPMap.put((byte) 17, InetAddress.getLoopbackAddress());
                globals.mTeamIPMap.put((byte) 32, InetAddress.getLoopbackAddress());
            } finally {
                globals.mTeamIPMapSemaphore.release();
            }
            assertArrayEquals(new int[]{2, 2}, globals.getNetworkTeamPlayerCounts());
        } finally {
            Globals.getmTeamIPMapSemaphore();
            try {
                globals.mTeamIPMap.clear();
                globals.mTeamIPMap.putAll(originalRoster);
                globals.mGameMode = originalGameMode;
                globals.mPlayerID = originalPlayerID;
            } finally {
                globals.mTeamIPMapSemaphore.release();
            }
        }
    }

    @Test public void respawnQrCodesAcceptStructuredAndHumanReadableTeamCodes() {
        assertEquals(1, Globals.getRespawnTeamFromQrCode("SIMPLECOIL:RESPAWN:1"));
        assertEquals(4, Globals.getRespawnTeamFromQrCode("  team  4  respawn "));
    }

    @Test public void respawnQrCodesRejectInvalidTeamsAndUnrelatedPayloads() {
        assertEquals(0, Globals.getRespawnTeamFromQrCode("SIMPLECOIL:RESPAWN:5"));
        assertEquals(0, Globals.getRespawnTeamFromQrCode("TEAM 1 RESPAWN NOW"));
        assertEquals(0, Globals.getRespawnTeamFromQrCode("not a checkpoint"));
    }

    @Test public void flagQrCodesAcceptStructuredAndHumanReadableTeamCodes() {
        assertEquals(1, Globals.getFlagTeamFromQrCode("SIMPLECOIL:FLAG:1"));
        assertEquals(2, Globals.getFlagTeamFromQrCode(" team 2 flag "));
    }

    @Test public void flagQrCodesRejectInvalidTeamsAndRespawnCodes() {
        assertEquals(0, Globals.getFlagTeamFromQrCode("SIMPLECOIL:FLAG:3"));
        assertEquals(0, Globals.getFlagTeamFromQrCode("TEAM 1 RESPAWN"));
        assertEquals(0, Globals.getFlagTeamFromQrCode("TEAM 2 FLAG NOW"));
    }

    @Test public void infectionTeamsChangeOnlyAfterConversion() {
        Globals globals = Globals.getInstance();
        boolean oldInfection = globals.mInfectionMode;
        java.util.Set<Byte> oldInfected = globals.mInfectedPlayers;
        try {
            globals.mInfectionMode = true;
            globals.resetInfectedPlayers();
            assertEquals(1, globals.calcNetworkTeam((byte) 1));
            assertEquals(2, globals.calcNetworkTeam((byte) 2));
            globals.setPlayerInfected((byte) 2, true);
            assertEquals(1, globals.calcNetworkTeam((byte) 2));
            globals.setPlayerInfected((byte) 2, false);
            assertEquals(1, globals.calcNetworkTeam((byte) 2));
        } finally {
            globals.mInfectionMode = oldInfection;
            globals.mInfectedPlayers = oldInfected;
        }
    }

    @Test public void infectionEndsForFullConversionOrOneRemainingSurvivor() {
        assertFalse(Globals.infectionRoundShouldEnd(2, 1));
        assertTrue(Globals.infectionRoundShouldEnd(2, 2));
        assertFalse(Globals.infectionRoundShouldEnd(4, 1));
        assertFalse(Globals.infectionRoundShouldEnd(4, 2));
        assertTrue(Globals.infectionRoundShouldEnd(4, 3));
        assertTrue(Globals.infectionRoundShouldEnd(4, 4));
    }

    @Test public void infectionAlwaysUsesFiveMinuteTimeLimit() {
        Globals globals = Globals.getInstance();
        int oldGameLimit = globals.mGameLimit;
        int oldLivesLimit = globals.mLivesLimit;
        int oldScoreLimit = globals.mScoreLimit;
        int oldTimeLimit = globals.mTimeLimit;
        try {
            globals.mGameLimit = Globals.GAME_LIMIT_LIVES | Globals.GAME_LIMIT_SCORE;
            globals.mLivesLimit = 9;
            globals.mScoreLimit = 9;
            globals.mTimeLimit = 0;

            globals.applyInfectionGameLimits();

            assertEquals(Globals.GAME_LIMIT_TIME, globals.mGameLimit);
            assertEquals(Globals.INFECTION_TIME_LIMIT_MINUTES, globals.mTimeLimit);
            assertEquals(0, globals.mLivesLimit);
            assertEquals(0, globals.mScoreLimit);
        } finally {
            globals.mGameLimit = oldGameLimit;
            globals.mLivesLimit = oldLivesLimit;
            globals.mScoreLimit = oldScoreLimit;
            globals.mTimeLimit = oldTimeLimit;
        }
    }
}
