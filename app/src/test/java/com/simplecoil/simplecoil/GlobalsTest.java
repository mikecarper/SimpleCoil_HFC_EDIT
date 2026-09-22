package com.simplecoil.simplecoil;

import org.junit.Test;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
            globals.mTeamIPMap.put((byte) 6, InetAddress.getLoopbackAddress());
            globals.mTeamIPMap.put((byte) 12, InetAddress.getLoopbackAddress());
            globals.mTeamIPMap.put((byte) 20, InetAddress.getLoopbackAddress());
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
                globals.mTeamIPMap.put((byte) 11, InetAddress.getLoopbackAddress());
                globals.mTeamIPMap.put((byte) 20, InetAddress.getLoopbackAddress());
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
}
