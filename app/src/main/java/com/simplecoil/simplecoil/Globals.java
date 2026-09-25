/*
 * Copyright (C) 2018 Ethan Yonker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.simplecoil.simplecoil;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

public class Globals {
    private static Globals mInstance= null;
    // GPS-derived UTC is intentionally kept separate from the device wall
    // clock. Ordinary apps cannot set system time, and game timing uses the
    // monotonic clock even when a phone's displayed time is wrong.
    public final GpsGameTime mGpsGameTime = new GpsGameTime();

    /* Highest player ID allowed in the GUI, absolute max is 0x3F or 63. Player ID 0 can technically
    be used but would require code changes to the hit detection if you really need 64 players. */
    public static final byte MAX_PLAYER_ID = (byte) 32;

    // Scoreboards aggregate player scores into team totals. Keep each score below
    // a value whose sum remains representable for every supported player.
    public static final int MAX_SCOREBOARD_VALUE = Integer.MAX_VALUE / MAX_PLAYER_ID;
    public static final int MAX_TEAM_SCOREBOARD_VALUE = MAX_SCOREBOARD_VALUE * MAX_PLAYER_ID;

    /**
     * Network messages may carry arbitrary integers, while UI and map storage are sized for
     * the supported player-ID range.  Keep validation in one place before an ID reaches an
     * array-backed consumer.
     */
    public static boolean isValidPlayerID(int playerID) {
        return playerID >= 0 && playerID <= MAX_PLAYER_ID;
    }

    public static final byte RELOAD_COUNT = (byte) 30; // Number of shots you get after a reload, max 255
    public static final int MIN_RELOAD_COUNT = 1;
    public static final int MAX_RELOAD_COUNT = 255;
    public volatile byte mFullReload = RELOAD_COUNT;
    public static final long RESPAWN_TIME_SECONDS = 10; // Time to wait for respawn after elimination and to start the game
    public static final long MIN_RESPAWN_TIME_SECONDS = 1;
    public static final long MAX_RESPAWN_TIME_SECONDS = 1000;
    // Team games give a player three minutes to reach and scan their team's respawn checkpoint.
    public static final long TEAM_QR_RESPAWN_WAIT_SECONDS = 3 * 60L;
    public volatile long mRespawnTime = RESPAWN_TIME_SECONDS;
    public static final long RELOAD_TIME_MILLISECONDS = 1500; // Reload downtime in ms. 1.5 seconds
    public static final long MIN_RELOAD_TIME_MILLISECONDS = 0;
    public static final long MAX_RELOAD_TIME_MILLISECONDS = 10000;
    public volatile long mReloadTime = RELOAD_TIME_MILLISECONDS;
    public static final int MAX_HEALTH = 5; // Health hits after shields are depleted
    public static final int MIN_HEALTH = 1;
    public static final int MAX_CONFIGURED_HEALTH = 1000;
    public volatile int mFullHealth = MAX_HEALTH;
    public static final int MAX_SHIELDS = 10;
    public volatile int mFullShields = MAX_SHIELDS;
    public static final byte BOSS_PLAYER_ID = 1;
    public static final int BOSS_HUNTER_HEALTH = 2;
    public static final int BOSS_HUNTER_SHIELDS = 3;
    public static final int BOSS_BASE_HEALTH = 5;
    public static final int BOSS_BASE_SHIELDS = 10;
    public static final int BOSS_HEALTH_PER_HUNTER = 1;
    public static final int BOSS_SHIELDS_PER_HUNTER = 2;
    public static final byte BOSS_RELOAD_COUNT = 120;
    public static final int DAMAGE_PER_HIT = -1;
    public static final int MIN_DAMAGE_PER_HIT = -1000;
    public static final int MAX_DAMAGE_PER_HIT = -1;
    public volatile int mDamage = DAMAGE_PER_HIT;
    public volatile boolean mOverrideLives = false;
    public volatile int mOverrideLivesVal = 0;
    public volatile boolean mAllowPlayerSettings = false;
    /** Tournament remains the default, but classic FFA/team modes may be selected in the lobby. */
    public static final boolean TOURNAMENT_RULES_REQUIRED = false;
    public volatile boolean mTournamentMode = true;
    public volatile boolean mReloadOnEmpty = false; // Primarily intended for instagib
    // Local feedback defaults on. Tournament mode also forces it on.
    public volatile boolean mVibrateOnHit = true;
   //TODO checks // add new presets in player settings alert dialog and in the menu list item(frontend)
   // public static final int PLAYER_PRESET_DEFAULT = 0;
   // public static final int PLAYER_PRESET_RECON = 1;
    //public static final int PLAYER_PRESET_JUGGERNAUT = 2;
   // public static final int PLAYER_PRESET_TANK = 3;
   // public static final int PLAYER_PRESET_MEDIC = 4;

  //  public volatile int mCurrentPlayerPreset = PLAYER_PRESET_DEFAULT;

 //   public static final int WEAPON_PRESET_DEFAULT = 0;
 //   public static final int WEAPON_PRESET_SNIPER = 1;
  //  public static final int WEAPON_PRESET_SHOTGUN = 2;
//    public static final int WEAPON_PRESET_LMG = 3;
 //   public static final int WEAPON_PRESET_MP = 4;
 //   public static final int WEAPON_PRESET_CARBINE = 5;
  //  public static final int WEAPON_PRESET_PISTOL = 6;

 //   public volatile int mCurrentWeaponPreset = WEAPON_PRESET_DEFAULT;

    public static final int INVALID_PLAYER_ID = -100;
    public static final int GRENADE_PLAYER_ID = 167;

    // 1 for FFA, 2 for 2 Teams, and 4 for 4 Teams
    public static final int GAME_MODE_FFA = 1;
    public static final int GAME_MODE_2TEAMS = 2;
    public static final int GAME_MODE_4TEAMS = 4;
    public volatile int mGameMode = GAME_MODE_2TEAMS;
    /** Player 1 is the boss; every other valid player ID is on the hunter team. */
    public volatile boolean mBossMode = false;
    // -1 means derive the lobby preview from the currently visible roster.
    // Once a host commits a round, the advertised value is retained so a
    // disconnect cannot reduce the boss's maximum health or shields.
    public volatile int mBossHunterCount = -1;
    public volatile boolean mBalancedRandom = false;
    public volatile boolean mBalancedRequireQr = true;
    // Zero disables the gameplay camera and QR rewards. The host advertises
    // this setting to every phone before a round starts.
    public volatile int mPowerupQrRequired = 0;
    // Published as immutable snapshots so UDP and UI readers never see a partial assignment.
    public volatile Map<Byte, Integer> mBalancedTeams = Collections.emptyMap();
    public volatile Set<Byte> mBalancedCheckedIn = Collections.emptySet();

    public void clearBalancedAssignments() {
        mBalancedTeams = Collections.emptyMap();
        mBalancedCheckedIn = Collections.emptySet();
    }

    public void setBalancedAssignments(Map<Byte, Integer> teams, Set<Byte> checkedIn) {
        mBalancedTeams = Collections.unmodifiableMap(new HashMap<>(teams));
        mBalancedCheckedIn = Collections.unmodifiableSet(new HashSet<>(checkedIn));
    }

    public static boolean isValidGameMode(int gameMode) {
        return gameMode == GAME_MODE_FFA || gameMode == GAME_MODE_2TEAMS || gameMode == GAME_MODE_4TEAMS;
    }

    public static final int GAME_LIMIT_NONE = 0;
    public static final int GAME_LIMIT_TIME = 1;
    public static final int GAME_LIMIT_LIVES = 2;
    public static final int GAME_LIMIT_SCORE = 4;
    public static final long NEXT_GAME_WAIT_MILLISECONDS = 30_000L;
    public volatile int mGameLimit = GAME_LIMIT_NONE;
    public volatile int mTimeLimit = 0;
    public volatile int mScoreLimit = 0;
    public volatile int mLivesLimit = 0;
    public static final int MAX_GAME_LIMIT = 100;

    public static boolean isValidGameLimit(int limit) {
        return limit >= 0 && limit <= MAX_GAME_LIMIT;
    }

    /**
     * Disable every game limit together.  Clear the bit field before the values so a concurrent
     * reader never observes an enabled limit with a zero value.
     */
    void clearGameLimits() {
        mGameLimit = GAME_LIMIT_NONE;
        mTimeLimit = 0;
        mLivesLimit = 0;
        mScoreLimit = 0;
    }

    public static final int GPS_DISABLED = 0;
    public static final int GPS_TEAMMATE = 1;
    public static final int GPS_ALL = 2;
    // Team games should not disclose the opposing team's positions by default.
    // Hosts can still deliberately select GPS_ALL for a referee or training game.
    public volatile int mGPSMode = GPS_TEAMMATE;

    public static boolean isValidGPSMode(int gpsMode) {
        return gpsMode == GPS_DISABLED || gpsMode == GPS_TEAMMATE || gpsMode == GPS_ALL;
    }

    public static boolean isValidCoordinates(double longitude, double latitude) {
        // Game policy: a zero on either axis means the GPS fix is not usable.
        return !Double.isNaN(longitude) && !Double.isInfinite(longitude)
                && !Double.isNaN(latitude) && !Double.isInfinite(latitude)
                && longitude != 0.0 && latitude != 0.0
                && longitude >= -180.0 && longitude <= 180.0
                && latitude >= -90.0 && latitude <= 90.0;
    }

    public static final int GAME_STATE_NONE = 0; // Game not started
    public static final int GAME_STATE_RUNNING = 1; // Game running and player is in the game
    public static final int GAME_STATE_ELIMINATED = 2; // Game running but player is out right now
    public volatile int mGameState = GAME_STATE_NONE;

    public static final int SHOT_MODE_FULL_AUTO = 1;
    public static final int SHOT_MODE_SINGLE = 2;
    public static final int SHOT_MODE_BURST = 4;
    public volatile boolean mAllowSingleShotMode = true;
    public volatile boolean mAllowBurst3ShotMode = false;
    public volatile boolean mAllowAutoShotMode = false;

    public static final int FIRING_MODE_OUTDOOR_NO_CONE = 0;
    public static final int FIRING_MODE_OUTDOOR_WITH_CONE = 1;
    public static final int FIRING_MODE_INDOOR_NO_CONE = 2;
    public volatile int mCurrentFiringMode = FIRING_MODE_OUTDOOR_NO_CONE;

    public static boolean isValidFiringMode(int firingMode) {
        return firingMode == FIRING_MODE_OUTDOOR_NO_CONE
                || firingMode == FIRING_MODE_OUTDOOR_WITH_CONE
                || firingMode == FIRING_MODE_INDOOR_NO_CONE;
    }

    public static boolean isValidPlayerSettings(int health, int reloadShots, long reloadTime,
                                                long spawnTime, int damage, int lives,
                                                boolean allowSingle, boolean allowBurst,
                                                boolean allowAuto, int firingMode) {
        return health >= MIN_HEALTH && health <= MAX_CONFIGURED_HEALTH
                && reloadShots >= MIN_RELOAD_COUNT && reloadShots <= MAX_RELOAD_COUNT
                && reloadTime >= MIN_RELOAD_TIME_MILLISECONDS && reloadTime <= MAX_RELOAD_TIME_MILLISECONDS
                && spawnTime >= MIN_RESPAWN_TIME_SECONDS && spawnTime <= MAX_RESPAWN_TIME_SECONDS
                && damage >= MIN_DAMAGE_PER_HIT && damage <= MAX_DAMAGE_PER_HIT
                && lives >= 0 && lives <= MAX_CONFIGURED_HEALTH
                && (allowSingle || allowBurst || allowAuto)
                && isValidFiringMode(firingMode);
    }

    /** Apply the common tournament profile to one network player record. */
    public static void applyTournamentRules(PlayerSettings settings) {
        if (settings == null)
            return;
        settings.health = MAX_HEALTH;
        settings.shots = RELOAD_COUNT;
        settings.reloadTime = RELOAD_TIME_MILLISECONDS;
        settings.reloadOnEmpty = false;
        settings.spawnTime = RESPAWN_TIME_SECONDS;
        settings.damage = DAMAGE_PER_HIT;
        settings.overrideLives = false;
        settings.lives = 0;
        // Tournament matches are deliberately single-shot only.  Keep this in the profile,
        // rather than relying on the client UI, so a reconnect cannot restore auto or burst.
        settings.allowShotModeSingle = true;
        settings.allowShotModeBurst3 = false;
        settings.allowShotModeAuto = false;
        settings.firingMode = FIRING_MODE_OUTDOOR_NO_CONE;
    }

    /** Restore the common baseline while allowing the classic firing-mode choices. */
    public static void applyClassicRules(PlayerSettings settings) {
        if (settings == null)
            return;
        settings.health = MAX_HEALTH;
        settings.shots = RELOAD_COUNT;
        settings.reloadTime = RELOAD_TIME_MILLISECONDS;
        settings.reloadOnEmpty = false;
        settings.spawnTime = RESPAWN_TIME_SECONDS;
        settings.damage = DAMAGE_PER_HIT;
        settings.overrideLives = false;
        settings.lives = 0;
        settings.allowShotModeSingle = true;
        settings.allowShotModeBurst3 = true;
        settings.allowShotModeAuto = true;
        settings.firingMode = FIRING_MODE_OUTDOOR_NO_CONE;
    }

    /** Apply Boss Mode health to a server-owned player profile. */
    public static void applyBossHealth(PlayerSettings settings, int playerID, int hunterCount) {
        if (settings == null)
            return;
        settings.health = playerID == BOSS_PLAYER_ID
                ? BOSS_BASE_HEALTH + Math.max(0, hunterCount) * BOSS_HEALTH_PER_HUNTER
                : BOSS_HUNTER_HEALTH;
        settings.shots = playerID == BOSS_PLAYER_ID ? BOSS_RELOAD_COUNT : RELOAD_COUNT;
        settings.allowShotModeSingle = true;
        settings.allowShotModeBurst3 = playerID == BOSS_PLAYER_ID;
        settings.allowShotModeAuto = playerID == BOSS_PLAYER_ID;
    }

    /** Apply the common tournament profile to the local phone and weapon configuration. */
    public void applyTournamentRules() {
        mTournamentMode = true;
        mVibrateOnHit = true;
        mGameMode = GAME_MODE_2TEAMS;
        if (mBossMode) {
            int hunterCount = mBossHunterCount >= 0 ? mBossHunterCount
                    : Math.max(0, getPlayerCount() - 1);
            if (mPlayerID == BOSS_PLAYER_ID) {
                mFullHealth = BOSS_BASE_HEALTH + hunterCount * BOSS_HEALTH_PER_HUNTER;
                mFullShields = BOSS_BASE_SHIELDS + hunterCount * BOSS_SHIELDS_PER_HUNTER;
            } else {
                mFullHealth = BOSS_HUNTER_HEALTH;
                mFullShields = BOSS_HUNTER_SHIELDS;
            }
        } else {
            mFullHealth = MAX_HEALTH;
            mFullShields = MAX_SHIELDS;
        }
        boolean localBoss = mBossMode && mPlayerID == BOSS_PLAYER_ID;
        mFullReload = localBoss ? BOSS_RELOAD_COUNT : RELOAD_COUNT;
        mReloadTime = RELOAD_TIME_MILLISECONDS;
        mReloadOnEmpty = false;
        mRespawnTime = RESPAWN_TIME_SECONDS;
        mDamage = DAMAGE_PER_HIT;
        mOverrideLives = false;
        mOverrideLivesVal = 0;
        mAllowSingleShotMode = true;
        mAllowBurst3ShotMode = localBoss;
        mAllowAutoShotMode = localBoss;
        // Indoor range is a field choice, not a weapon advantage. Keep it for
        // every player while continuing to reserve the wide cone for the boss.
        if (!isValidFiringMode(mCurrentFiringMode)
                || (!localBoss && mCurrentFiringMode == FIRING_MODE_OUTDOOR_WITH_CONE))
            mCurrentFiringMode = FIRING_MODE_OUTDOOR_NO_CONE;
        mAllowPlayerSettings = false;
        mOnlyServerSettings = true;
    }

    public void applyClassicRules(int gameMode) {
        if (!isValidGameMode(gameMode))
            gameMode = GAME_MODE_2TEAMS;
        mTournamentMode = false;
        mBossMode = false;
        mBossHunterCount = -1;
        mBalancedRandom = false;
        clearBalancedAssignments();
        mGameMode = gameMode;
        mFullHealth = MAX_HEALTH;
        mFullShields = MAX_SHIELDS;
        mFullReload = RELOAD_COUNT;
        mReloadTime = RELOAD_TIME_MILLISECONDS;
        mReloadOnEmpty = false;
        mRespawnTime = RESPAWN_TIME_SECONDS;
        mDamage = DAMAGE_PER_HIT;
        mOverrideLives = false;
        mOverrideLivesVal = 0;
        mAllowSingleShotMode = true;
        mAllowBurst3ShotMode = true;
        mAllowAutoShotMode = true;
        if (!isValidFiringMode(mCurrentFiringMode))
            mCurrentFiringMode = FIRING_MODE_OUTDOOR_NO_CONE;
        mAllowPlayerSettings = true;
        mOnlyServerSettings = false;
    }

    public volatile byte mPlayerID = 0;
    public volatile String mPlayerName = "";
    public volatile Map<InetAddress, Byte> mIPTeamMap;
    public volatile Map<Byte, InetAddress> mTeamIPMap;
    public volatile Map<Byte, String> mTeamPlayerNameMap;
    public volatile Map<Byte, GPSData> mGPSData;
    public volatile Map<Byte, PlayerSettings> mPlayerSettings;

    public static final int MAX_GRENADE_IDS = 16;
    public volatile byte mPairedGrenadeID = 0;
    public volatile int[] mGrenadePairings;

    public static boolean isValidGrenadeID(int grenadeID) {
        return grenadeID >= 0 && grenadeID < MAX_GRENADE_IDS;
    }

    public Semaphore mIPTeamMapSemaphore;
    public Semaphore mTeamIPMapSemaphore;
    public Semaphore mTeamPlayerNameSemaphore;
    public Semaphore mGPSDataSemaphore;
    public Semaphore mPlayerSettingsSemaphore;
    public Semaphore mGrenadePairingsSemaphore;
    public volatile boolean mUseGPS = false;
    public volatile boolean mOnlyServerSettings = true;

    public volatile long mServerGameTimeRemaining = 0; // in seconds

    public volatile InetAddress mServerIP = null;
    // Zero means the current host did not advertise an offline map tile service.
    public volatile int mMapTilePort = 0;

    protected Globals(){}

    public static synchronized Globals getInstance(){
        if(mInstance == null){
            mInstance = new Globals();
            mInstance.mIPTeamMap = new HashMap<>();
            mInstance.mTeamIPMap = new HashMap<>();
            mInstance.mTeamPlayerNameMap = new HashMap<>();
            mInstance.mGPSData = new HashMap<>();
            mInstance.mPlayerSettings = new HashMap<>();

            mInstance.mIPTeamMapSemaphore = new Semaphore(1);
            mInstance.mTeamIPMapSemaphore = new Semaphore(1);
            mInstance.mTeamPlayerNameSemaphore = new Semaphore(1);
            mInstance.mGPSDataSemaphore = new Semaphore(1);
            mInstance.mPlayerSettingsSemaphore = new Semaphore(1);
            mInstance.mGrenadePairingsSemaphore = new Semaphore(1);
            mInstance.mGrenadePairings = new int[MAX_GRENADE_IDS];
            ClearGrenadePairings(false);
        }
        return mInstance;
    }

    public int calcNetworkTeam(byte player_id) {
        int team = 1;
        if (!isValidPlayerID(player_id))
            return INVALID_PLAYER_ID;
        if (mBossMode)
            return player_id == BOSS_PLAYER_ID ? 1 : 2;
        if (mBalancedRandom && mGameMode == GAME_MODE_2TEAMS) {
            Integer assigned = mBalancedTeams.get(player_id);
            if (assigned != null)
                return assigned;
        }
        if (mGameMode == GAME_MODE_2TEAMS) {
            final int x = ((MAX_PLAYER_ID + 1) / 2);
            if (player_id > x)
                team = 2;
        } else if (mGameMode == GAME_MODE_4TEAMS){
            final int x = ((MAX_PLAYER_ID + 1) / 4);
            if (player_id > 3 * x)
                team = 4;
            else if (player_id > 2 * x)
                team = 3;
            else if (player_id > x)
                team = 2;
        } else if (mGameMode == GAME_MODE_FFA)
            return player_id;
        return team;
    }

    /**
     * Decode one of the printed team-respawn QR payloads.  The structured form is
     * SIMPLECOIL:RESPAWN:&lt;team&gt;; the human-readable TEAM &lt;team&gt; RESPAWN form is
     * accepted too, so existing printed checkpoint signs can stay simple.
     *
     * @return a team from 1 through 4, or 0 when the QR payload is not a respawn code.
     */
    public static int getRespawnTeamFromQrCode(String contents) {
        if (contents == null || contents.length() > 64)
            return 0;
        String value = contents.trim();
        final String prefix = "SIMPLECOIL:RESPAWN:";
        if (value.regionMatches(true, 0, prefix, 0, prefix.length()))
            return parseRespawnTeam(value.substring(prefix.length()));

        String[] parts = value.split("\\s+");
        if (parts.length == 3 && "TEAM".equalsIgnoreCase(parts[0])
                && "RESPAWN".equalsIgnoreCase(parts[2]))
            return parseRespawnTeam(parts[1]);
        return 0;
    }

    private static int parseRespawnTeam(String teamValue) {
        try {
            int team = Integer.parseInt(teamValue);
            return team >= 1 && team <= GAME_MODE_4TEAMS ? team : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public String getPlayerName(Byte playerID) {
        getmTeamPlayerNameSemaphore();
        String ret = getInstance().mTeamPlayerNameMap.get(playerID);
        getInstance().mTeamPlayerNameSemaphore.release();
        if (ret == null)
            ret = "";
        return ret;
    }

    /**
     * Get IP address from first non-localhost interface
     * @return  address or empty string
     */
    public static String getIPAddressStr() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;

                        if (isIPv4) {
                            return sAddr;
                        } /*else {
                            // IPv6 stuff
                            int delim = sAddr.indexOf('%'); // drop ip6 zone suffix
                            return delim<0 ? sAddr.toUpperCase() : sAddr.substring(0, delim).toUpperCase();
                        }*/
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return "";
    }

    /**
     * Return the address associated with the Wi-Fi network when one is available.  The game
     * discovers peers with Wi-Fi broadcast, so a cellular or VPN address is not a usable
     * substitute when a phone has more than one active network interface.
     */
    public static InetAddress getIPAddress(Context context) {
        if (context != null) {
            try {
                Context applicationContext = context.getApplicationContext();
                // On Android 5.1, WifiManager can retain the context used for its
                // lookup. Never substitute an Activity when an application context
                // is unavailable; fall back to the interface scan below instead.
                if (applicationContext != null) {
                    Object service = applicationContext.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
                    if (service instanceof WifiManager) {
                        WifiInfo info = ((WifiManager) service).getConnectionInfo();
                        if (info != null) {
                            InetAddress wifiAddress = fromWifiIPv4Address(info.getIpAddress());
                            if (wifiAddress != null)
                                return wifiAddress;
                        }
                    }
                }
            } catch (SecurityException ignored) {
                // ACCESS_WIFI_STATE can be unavailable on a modified device. Fall back below.
            }
        }
        return getIPAddress();
    }

    public static String getIPAddressStr(Context context) {
        InetAddress address = getIPAddress(context);
        return address == null ? "" : address.getHostAddress();
    }

    /**
     * WifiInfo stores IPv4 octets in little-endian order. A zero value means that Wi-Fi has
     * not received an address yet.
     */
    static InetAddress fromWifiIPv4Address(int address) {
        if (address == 0)
            return null;
        byte[] octets = new byte[4];
        for (int k = 0; k < octets.length; k++)
            octets[k] = (byte) ((address >> (k * 8)) & 0xFF);
        try {
            return InetAddress.getByAddress(octets);
        } catch (UnknownHostException e) {
            // A four-octet address is always valid, but do not let an impossible conversion
            // failure bring down game setup.
            return null;
        }
    }

    public static InetAddress getIPAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;

                        if (isIPv4) {
                            return addr;
                        } /*else {
                            // IPv6 stuff
                            int delim = sAddr.indexOf('%'); // drop ip6 zone suffix
                            return delim<0 ? sAddr.toUpperCase() : sAddr.substring(0, delim).toUpperCase();
                        }*/
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static int getPlayerCount() {
        int ret = 0;
        getmTeamIPMapSemaphore();
        if (getInstance().mTeamIPMap != null)
            ret = getInstance().mTeamIPMap.size();
        getInstance().mTeamIPMapSemaphore.release();
        return ret + 1; // All other players plus ourself
    }

    /**
     * Returns the connected-player roster grouped by the current team mode.
     * The local player is deliberately counted separately because peer hosts do
     * not store themselves in {@link #mTeamIPMap}.
     */
    public int[] getNetworkTeamPlayerCounts() {
        int teamCount;
        if (mGameMode == GAME_MODE_2TEAMS) {
            teamCount = 2;
        } else if (mGameMode == GAME_MODE_4TEAMS) {
            teamCount = 4;
        } else {
            return new int[0];
        }

        int[] counts = new int[teamCount];
        addPlayerToTeamCount(counts, mPlayerID);
        getmTeamIPMapSemaphore();
        try {
            if (mTeamIPMap != null) {
                for (Byte playerID : mTeamIPMap.keySet()) {
                    if (playerID != null && playerID != mPlayerID)
                        addPlayerToTeamCount(counts, playerID);
                }
            }
        } finally {
            mTeamIPMapSemaphore.release();
        }
        return counts;
    }

    private void addPlayerToTeamCount(int[] counts, byte playerID) {
        if (playerID <= 0 || !isValidPlayerID(playerID))
            return;
        int team = calcNetworkTeam(playerID);
        if (team >= 1 && team <= counts.length)
            counts[team - 1]++;
    }

    public static class GPSData {
        double latitude = 0;
        double longitude = 0;
        int team = 0;
        boolean hasUpdate = false;
    }

    public static void getmIPTeamMapSemaphore() {
        try {
            getInstance().mIPTeamMapSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring IP/team map lock", e);
        }
    }

    public static void getmTeamIPMapSemaphore() {
        try {
            getInstance().mTeamIPMapSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring team/IP map lock", e);
        }
    }

    public static void getmTeamPlayerNameSemaphore() {
        try {
            getInstance().mTeamPlayerNameSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring player-name map lock", e);
        }
    }

    public static void getmGPSDataSemaphore() {
        try {
            getInstance().mGPSDataSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring GPS data lock", e);
        }
    }

    public static void getmPlayerSettingsSemaphore() {
        try {
            getInstance().mPlayerSettingsSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring player-settings lock", e);
        }
    }

    public static void getmGrenadePairingsSemaphore() {
        try {
            getInstance().mGrenadePairingsSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring grenade-pairings lock", e);
        }
    }

    public static void ClearGrenadePairings(boolean getSemaphore) {
        if (getSemaphore)
            getmGrenadePairingsSemaphore();
        for (int index = 0; index < MAX_GRENADE_IDS; index++) {
            getInstance().mGrenadePairings[index] = Globals.INVALID_PLAYER_ID;
        }
        if (getSemaphore)
            getInstance().mGrenadePairingsSemaphore.release();
    }

    public static class PlayerSettings {
        int health = Globals.MAX_HEALTH;
        byte shots = Globals.RELOAD_COUNT;
        long reloadTime = Globals.RELOAD_TIME_MILLISECONDS;
        boolean reloadOnEmpty = false;
        long spawnTime = Globals.RESPAWN_TIME_SECONDS;
        int damage = Globals.DAMAGE_PER_HIT;
        boolean overrideLives = false;
        int lives = 0;
        boolean allowShotModeSingle = true;
        boolean allowShotModeBurst3 = false;
        boolean allowShotModeAuto = false;
        int firingMode = FIRING_MODE_OUTDOOR_NO_CONE;
        //TODO checks
     //   boolean allowVibratePhone = false;
     //   int playerPreset = PLAYER_PRESET_DEFAULT;   // //presets maybe use int 0 for preset 0 and so on
     //   int weaponPreset = WEAPON_PRESET_DEFAULT;    // needed ?
    }
}
