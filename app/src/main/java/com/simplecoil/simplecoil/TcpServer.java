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

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;

public class TcpServer extends Service {
    private static final String TAG = "TCPServer";

    public static final int SEND_ALL = -100;

    public static final int TCP_SERVER_PORT = 17510;
    // Amount of time to sleep before checking if data is available. Lower is more responsive but may gobble up more CPU time
    public static final int TCP_READ_WAIT_MS = 200;
    public static final int TCP_DEDICATED_READ_WAIT_MS = 100;
    public static final String TCP_SERVER_PING = "ping";

    public static final String TCPMESSAGE_PREFIX = NetMsg.MESSAGE_PREFIX + NetMsg.NETWORK_VERSION;
    public static final String TCPPREFIX_MESG = "MESG";
    public static final String TCPPREFIX_JSON = "JSON";

    public static final String JSON_PLAYERS = "players";
    public static final String JSON_PLAYERNAME = "playername";
    public static final String JSON_PLAYERNAMECHANGE = "playernamechange";
    public static final String JSON_PLAYERID = "playerID";
    public static final String JSON_PLAYERIP = "playerIP";
    public static final String JSON_LIMITS = "limits";
    public static final String JSON_TIMELIMIT = "timelimit";
    public static final String JSON_LIVESLIMIT = "liveslimit";
    public static final String JSON_SCORELIMIT = "scorelimit";
    public static final String JSON_GAMEMODE = "gamemode";
    public static final String JSON_REJOIN = "rejoin";
    public static final String JSON_DEDICATED = "dedicatedserver";
    public static final String JSON_USEGPS = "usegps";
    public static final String JSON_TEAM = "team";
    public static final String JSON_GAMESTATE = "gamestate";
    public static final String JSON_ONLY_SERVER_SETTINGS = "onlyserversettings";
    public static final String JSON_PAIRED_GRENADE_ID = "pairedgrenadeID";
    public static final String JSON_GRENADE_PAIRINGS = "grenadepairings";

    public static final String JSON_GPSLONGITUDE = "gpslongitude";
    public static final String JSON_GPSLATITUDE = "gpslatitude";
    public static final String JSON_GPSUPDATE = "gpsupdate";
    public static final String JSON_GPSFULLUPDATE = "gpsfullupdate";

    public static final String JSON_PLAYERDATA = "playerdata";
    public static final String JSON_PLAYERPOINTS = "points";
    public static final String JSON_PLAYERELIMINATED = "eliminated";
    public static final String JSON_TEAMPOINTS = "teampoints";
    public static final String JSON_TIMEREMAINING = "timeremaining";
    public static final String JSON_PLAYERGAMEUPDATE = "playergameupdate";

    public static final String JSON_PLAYERSETTINGS = "playersettings";
    public static final String JSON_HEALTH = "health";
    public static final String JSON_RELOAD_SHOTS = "reloadshots";
    public static final String JSON_RELOAD_TIME = "reloadtime";
    public static final String JSON_RELOAD_ON_EMPTY = "reloadonempty";
    public static final String JSON_SPAWN_TIME = "spawntime";
    public static final String JSON_DAMAGE = "damage";
    public static final String JSON_SHOT_MODE_SINGLE = "shotmodesingle";
    public static final String JSON_SHOT_MODE_BURST3 = "shotmodeburst3";
    public static final String JSON_SHOT_MODE_AUTO = "shotmodeauto";
    public static final String JSON_FIRING_MODE = "firingmode";

    public static final String JSON_ALLOWPLAYERSETTINGS = "allowplayersettings";

//TODO PRESET CHECKS
   // public static final String JSON_PLAYER_PRESET = "playerpreset";
  //  public static final String JSON_WEAPON_PRESET = "Weaponpreset";

    private volatile boolean keepListening = false;
    private final Object mServerStateLock = new Object();
    private boolean mDestroyed;
    // Guarded by mServerStateLock from scheduling through cleanup completion.
    private boolean mEndingGame;
    private ServerSocket mListenSocket;
    private volatile Thread mServerThread = null;
    private volatile Thread mClientThread = null;
    private final Semaphore mClientDataSemaphore = new Semaphore(1);
    // Guarded by mServerStateLock, including registration before Thread.start().
    private final Set<Thread> mClientTasks = new HashSet<>();
    private volatile Map<Integer, ClientData> mClientData = null;
    // Explicitly leaving must not reset a player's score or spent lives in this round.
    private final Map<Byte, ScoreData> mDepartedScores = new ConcurrentHashMap<>();
    private volatile boolean mIsDedicated = false;
    private boolean mGPSRunning = false;

    Handler mGPSHandler = new Handler();
    Runnable mGPSRunnable = null;
    private static final long GPS_UPDATE_INTERVAL = 1000;
    private static final int SEND_ALL_GPS_INTERVAL = 20; // We will send all GPS data regardless of whether there was a GPS change every xx intervals
    private volatile int mGPSIntervalCount = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        synchronized (mServerStateLock) {
            mDestroyed = true;
        }
        stopTcpServer();
        super.onDestroy();
    }

    public class LocalBinder extends Binder {
        TcpServer getService() {
            return TcpServer.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    private final class ClientRecipient {
        final int connectionID;
        final ClientData client;
        final byte playerID;
        final int team;

        ClientRecipient(int connectionID, ClientData client) {
            this.connectionID = connectionID;
            this.client = client;
            playerID = client.mPlayerID;
            team = Globals.getInstance().calcNetworkTeam(playerID);
        }

        boolean isCurrent() {
            Map<Integer, ClientData> clients = mClientData;
            return clients != null && clients.get(connectionID) == client
                    && client.mPlayerID == playerID;
        }

        boolean canStartGame() {
            // TCP registration reserves zero for an unregistered socket.
            return isCurrent() && playerID > 0 && Globals.isValidPlayerID(playerID)
                    && client.clientSocket != null && client.out != null;
        }
    }

    private List<ClientRecipient> getClientRecipients() {
        List<ClientRecipient> recipients = new ArrayList<>();
        Map<Integer, ClientData> clients = mClientData;
        if (clients != null) {
            for (Map.Entry<Integer, ClientData> entry : clients.entrySet())
                recipients.add(new ClientRecipient(entry.getKey(), entry.getValue()));
        }
        return recipients;
    }

    private boolean isClientTaskActive() {
        synchronized (mServerStateLock) {
            return keepListening && !mDestroyed && !Thread.currentThread().isInterrupted();
        }
    }

    private boolean runClientTask(Runnable action) {
        return runClientTask(action, false);
    }

    private boolean runClientTask(Runnable action, boolean endsRound) {
        synchronized (mServerStateLock) {
            if (!keepListening || mDestroyed || (endsRound && mEndingGame))
                return false;
            if (endsRound)
                mEndingGame = true;
            Thread task = new Thread(() -> {
                boolean acquired = false;
                try {
                    // These are new threads. Even a caller that already holds
                    // the client lock cannot lend its ownership to a sender.
                    mClientDataSemaphore.acquire();
                    acquired = true;
                    if (isClientTaskActive())
                        action.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    // The shared-state lock helpers propagate interruption as
                    // an exception. Shutdown must cancel, not crash, this task.
                    if (!Thread.currentThread().isInterrupted())
                        Log.e(TAG, "TCP client task failed", e);
                } finally {
                    if (acquired)
                        mClientDataSemaphore.release();
                    synchronized (mServerStateLock) {
                        // Cancellation while waiting for the lock must also
                        // release this reservation so a later round can start.
                        if (endsRound)
                            mEndingGame = false;
                        mClientTasks.remove(Thread.currentThread());
                    }
                }
            }, "SimpleCoil TCP send");
            mClientTasks.add(task);
            task.start();
            return true;
        }
    }

    public void sendTCPMessageAll(final String message) {
        sendTCPMessageAll(message, false);
    }

    public void sendTCPMessageAll(final String message, final boolean queueMessage) {
        sendTCPMessageAll(message, queueMessage, getClientRecipients());
    }

    private void sendTCPMessageAll(final String message, final boolean queueMessage,
                                   final List<ClientRecipient> recipients) {
        if (recipients.isEmpty())
            return;
        runClientTask(() -> {
            for (ClientRecipient recipient : recipients) {
                if (!isClientTaskActive())
                    return;
                // Delayed snapshots must not reach players who joined later or
                // reused an ID. A real rejoin retains its original ClientData.
                if (recipient.isCurrent())
                    recipient.client.sendTCPMessage(message, queueMessage);
            }
        });
    }

    private void sendTCPMessageID(final String message, final byte playerID, final boolean queueMessage) {
        final List<ClientRecipient> recipients = getClientRecipients();
        if (recipients.isEmpty())
            return;
        runClientTask(() -> {
            for (ClientRecipient recipient : recipients) {
                if (!isClientTaskActive())
                    return;
                if (recipient.playerID == playerID && recipient.isCurrent()) {
                    recipient.client.sendTCPMessage(message, queueMessage);
                    break;
                }
            }
        });
    }

    private void sendTCPMessageTeam(final String message, final byte playerID, final boolean includePlayer, final boolean queueMessage) {
        final List<ClientRecipient> recipients = getClientRecipients();
        if (recipients.isEmpty())
            return;
        runClientTask(() -> {
            int team = -1;
            for (ClientRecipient recipient : recipients) {
                if (!isClientTaskActive())
                    return;
                if (recipient.playerID == playerID) {
                    team = recipient.team;
                    if (includePlayer && recipient.isCurrent())
                        recipient.client.sendTCPMessage(message, queueMessage);
                    break;
                }
            }
            if (team != -1) {
                Log.e(TAG, "team is " + team);
                for (ClientRecipient recipient : recipients) {
                    if (!isClientTaskActive())
                        return;
                    if (recipient.team == team && recipient.playerID != playerID && recipient.isCurrent())
                        recipient.client.sendTCPMessage(message, queueMessage);
                }
            }
        });
    }

    public boolean startGame() {
        final List<ClientRecipient> recipients = getClientRecipients();
        boolean hasPlayers = false;
        for (ClientRecipient recipient : recipients) {
            if (recipient.canStartGame()) {
                hasPlayers = true;
                break;
            }
        }
        if (!hasPlayers)
            return false;
        synchronized (mServerStateLock) {
            if (mEndingGame)
                return false;
            return runClientTask(() -> {
                // Send to clients before confirming the start locally. A queued
                // end takes precedence over a start that has not been delivered.
                String message = TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_STARTGAME;
                boolean delivered = false;
                for (ClientRecipient recipient : recipients) {
                    synchronized (mServerStateLock) {
                        if (!isClientTaskActive() || mEndingGame)
                            return;
                    }
                    if (recipient.canStartGame())
                        delivered = recipient.client.sendTCPMessage(message) || delivered;
                }
                if (!delivered)
                    return;
                synchronized (mServerStateLock) {
                    if (!isClientTaskActive() || mEndingGame)
                        return;
                    sendBroadcast(new Intent(NetMsg.NETMSG_STARTGAME));
                    if (!mIsDedicated)
                        keepListening = false;
                }
            });
        }
    }

    public void endGame() {
        runClientTask(() -> {
            // Keep registration excluded until every shared roster has been
            // cleared, not just until the old client sockets have been closed.
            if (mClientData != null) {
                sendPlayerData(SEND_ALL);
                // We want to send the end-game message before removing clients.
                String message = TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ENDGAME;
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    if (!isClientTaskActive())
                        return;
                    entry.getValue().sendTCPMessage(message);
                    entry.getValue().close();
                }
                if (!isClientTaskActive())
                    return;
                mClientData.clear();
                mDepartedScores.clear();
            }
            Globals.getmGPSDataSemaphore();
            try {
                if (!isClientTaskActive())
                    return;
                if (Globals.getInstance().mGPSData != null)
                    Globals.getInstance().mGPSData.clear();
            } finally {
                Globals.getInstance().mGPSDataSemaphore.release();
            }
            Globals.getmTeamPlayerNameSemaphore();
            try {
                if (!isClientTaskActive())
                    return;
                Globals.getInstance().mTeamPlayerNameMap.clear();
            } finally {
                Globals.getInstance().mTeamPlayerNameSemaphore.release();
            }
            Globals.getmTeamIPMapSemaphore();
            try {
                if (!isClientTaskActive())
                    return;
                Globals.getInstance().mTeamIPMap.clear();
            } finally {
                Globals.getInstance().mTeamIPMapSemaphore.release();
            }
            Globals.getmIPTeamMapSemaphore();
            try {
                if (!isClientTaskActive())
                    return;
                Globals.getInstance().mIPTeamMap.clear();
            } finally {
                Globals.getInstance().mIPTeamMapSemaphore.release();
            }
            // A player's physical grenade pairing remains valid across rounds, but the
            // server's ownership table must not leak into the next round.
            Globals.getmGrenadePairingsSemaphore();
            try {
                if (!isClientTaskActive())
                    return;
                Globals.ClearGrenadePairings(false);
            } finally {
                Globals.getInstance().mGrenadePairingsSemaphore.release();
            }
            synchronized (mServerStateLock) {
                if (isClientTaskActive())
                    sendBroadcast(new Intent(NetMsg.NETMSG_ENDGAME));
            }
        }, true);
    }

    private Map<Byte, InetAddress> getTeamIPMapSnapshot() {
        Globals.getmTeamIPMapSemaphore();
        try {
            return new HashMap<>(Globals.getInstance().mTeamIPMap);
        } finally {
            Globals.getInstance().mTeamIPMapSemaphore.release();
        }
    }

    /**
     * Keep the two player/IP maps consistent when a TCP connection is replaced.
     * A reconnect can legitimately arrive from a different DHCP address.
     */
    private void updatePlayerEndpoint(byte playerID, InetAddress address) {
        if (address == null)
            return;
        Globals.getmIPTeamMapSemaphore();
        try {
            Iterator<Map.Entry<InetAddress, Byte>> iterator = Globals.getInstance().mIPTeamMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<InetAddress, Byte> entry = iterator.next();
                Byte mappedPlayerID = entry.getValue();
                if (mappedPlayerID != null && mappedPlayerID.byteValue() == playerID
                        && !address.equals(entry.getKey())) {
                    iterator.remove();
                }
            }
            Globals.getInstance().mIPTeamMap.put(address, playerID);
        } finally {
            Globals.getInstance().mIPTeamMapSemaphore.release();
        }
        Globals.getmTeamIPMapSemaphore();
        try {
            Globals.getInstance().mTeamIPMap.put(playerID, address);
        } finally {
            Globals.getInstance().mTeamIPMapSemaphore.release();
        }
    }

    private void updatePlayerName(byte playerID, String playerName) {
        Globals.getmTeamPlayerNameSemaphore();
        try {
            Globals.getInstance().mTeamPlayerNameMap.put(playerID, playerName);
        } finally {
            Globals.getInstance().mTeamPlayerNameSemaphore.release();
        }
    }

    public void sendAllGameInfo(final int id) {
        final List<ClientRecipient> recipients = getClientRecipients();
        if (recipients.isEmpty())
            return;
        try {
            JSONArray players = new JSONArray();
            Map<Byte, InetAddress> teamIPMap = getTeamIPMapSnapshot();
            if (teamIPMap.isEmpty())
                return;
            for (Map.Entry<Byte, InetAddress> entry : teamIPMap.entrySet()) {
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERNAME, Globals.getInstance().getPlayerName(entry.getKey()));
                player.put(JSON_PLAYERID, entry.getKey());
                player.put(JSON_PLAYERIP, entry.getValue());
                players.put(player);
            }
            if (!mIsDedicated) {
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERNAME, Globals.getInstance().mPlayerName);
                player.put(JSON_PLAYERID, Globals.getInstance().mPlayerID);
                player.put(JSON_PLAYERIP, Globals.getIPAddressStr());
                players.put(player);
            }
            JSONObject game = new JSONObject();
            game.put(JSON_PLAYERS, players);
            JSONObject limits = new JSONObject();
            if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0) {
                limits.put(JSON_TIMELIMIT, Globals.getInstance().mTimeLimit);
            }
            if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_LIVES) != 0) {
                limits.put(JSON_LIVESLIMIT, Globals.getInstance().mLivesLimit);
            }
            if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_SCORE) != 0) {
                limits.put(JSON_SCORELIMIT, Globals.getInstance().mScoreLimit);
            }
            game.put(JSON_LIMITS, limits);
            game.put(JSON_GAMEMODE, Globals.getInstance().mGameMode);
            if (mIsDedicated) {
                game.put(JSON_DEDICATED, true);
                game.put(JSON_GAMESTATE, Globals.getInstance().mGameState);
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE && (Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0)
                    game.put(JSON_TIMEREMAINING, Globals.getInstance().mServerGameTimeRemaining);
            }
            if (Globals.getInstance().mUseGPS)
                game.put(JSON_USEGPS, Globals.getInstance().mGPSMode);
            game.put(JSON_ALLOWPLAYERSETTINGS, Globals.getInstance().mAllowPlayerSettings);
            game.put(JSON_PLAYERSETTINGS, getPlayerSettings(id, false));
            game.put(JSON_ONLY_SERVER_SETTINGS, Globals.getInstance().mOnlyServerSettings);
            final String allMessage = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
            if (id == SEND_ALL)
                sendTCPMessageAll(allMessage, false, recipients);
            else {
                // Get update data for this specific player
                ScoreData scoreData = getScore((byte)id);
                JSONObject playerGameUpdate = new JSONObject();
                if (scoreData != null) {
                    playerGameUpdate.put(JSON_PLAYERPOINTS, scoreData.points);
                    playerGameUpdate.put(JSON_PLAYERELIMINATED, scoreData.eliminated);
                } else {
                    playerGameUpdate.put(JSON_PLAYERPOINTS, 0);
                    playerGameUpdate.put(JSON_PLAYERELIMINATED, 0);
                }
                if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                    int teamPoints = 0;
                    int team = Globals.getInstance().calcNetworkTeam((byte)id);
                    for (byte x = 0; x <= Globals.MAX_PLAYER_ID; x++) {
                        ScoreData teamMemberScore = getScore(x);
                        if (Globals.getInstance().calcNetworkTeam(x) == team && teamMemberScore != null)
                            teamPoints += teamMemberScore.points;
                    }
                    playerGameUpdate.put(JSON_TEAMPOINTS, teamPoints);
                }
                if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0 && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
                    playerGameUpdate.put(JSON_TIMEREMAINING, Globals.getInstance().mServerGameTimeRemaining);
                }
                game.put(JSON_PLAYERGAMEUPDATE, playerGameUpdate);
                final String idMessage = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();

                runClientTask(() -> {
                    for (ClientRecipient recipient : recipients) {
                        if (!isClientTaskActive())
                            return;
                        if (!recipient.isCurrent())
                            continue;
                        if (recipient.playerID != id)
                            recipient.client.sendTCPMessage(allMessage, false);
                        else
                            recipient.client.sendTCPMessage(idMessage, false);
                    }
                });
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void sendGPSData() {
        synchronized (mServerStateLock) {
            if (mGPSRunning || mDestroyed || !keepListening || !Globals.getInstance().mUseGPS)
                return;
            mGPSRunning = true;
            mGPSIntervalCount = 0;
            // Save the callback before posting, so even its first run can be cancelled.
            mGPSRunnable = new Runnable() {
                public void run() {
                    final boolean fullUpdate;
                    synchronized (mServerStateLock) {
                        if (!isCurrentGPSCallbackLocked(this))
                            return;
                        // Reserve this tick's refresh before waiting on locations.
                        // A later join/leave request must survive for the next tick.
                        fullUpdate = mGPSIntervalCount >= SEND_ALL_GPS_INTERVAL;
                        mGPSIntervalCount = fullUpdate ? 0 : mGPSIntervalCount + 1;
                    }
                    try {
                        JSONArray players = new JSONArray();
                        Globals.getmGPSDataSemaphore();
                        try {
                            synchronized (mServerStateLock) {
                                // Cancellation can happen while this tick waits for
                                // the location lock. Never consume a newer session's
                                // updates, even if the server is listening again.
                                if (!isCurrentGPSCallbackLocked(this))
                                    return;
                                if (Globals.getInstance().mGPSData != null) {
                                    for (Map.Entry<Byte, Globals.GPSData> entry : Globals.getInstance().mGPSData.entrySet()) {
                                        if (entry.getValue().hasUpdate || fullUpdate) {
                                            JSONObject player = new JSONObject();
                                            player.put(JSON_PLAYERID, entry.getKey());
                                            // A stationary player's cached GPS entry can predate
                                            // a lobby game-mode change. Use the current team layout.
                                            player.put(JSON_TEAM, Globals.getInstance().calcNetworkTeam(entry.getKey()));
                                            player.put(JSON_GPSLONGITUDE, entry.getValue().longitude);
                                            player.put(JSON_GPSLATITUDE, entry.getValue().latitude);
                                            players.put(player);
                                        }
                                        entry.getValue().hasUpdate = false;
                                    }
                                }
                            }
                        } finally {
                            Globals.getInstance().mGPSDataSemaphore.release();
                        }
                        // An empty full snapshot removes departed players from clients.
                        // Keep the periodic refresh advancing even with no locations.
                        if (players.length() != 0 || fullUpdate) {
                            JSONObject game = new JSONObject();
                            game.put(JSON_GPSUPDATE, players);
                            if (fullUpdate)
                                game.put(JSON_GPSFULLUPDATE, true);
                            String message = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
                            synchronized (mServerStateLock) {
                                if (!isCurrentGPSCallbackLocked(this))
                                    return;
                                sendTCPMessageAll(message, false);
                            }
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    synchronized (mServerStateLock) {
                        if (isCurrentGPSCallbackLocked(this))
                            mGPSHandler.postDelayed(this, GPS_UPDATE_INTERVAL);
                    }
                }
            };
            mGPSHandler.postDelayed(mGPSRunnable, GPS_UPDATE_INTERVAL);
        }
    }

    private boolean isCurrentGPSCallbackLocked(Runnable callback) {
        if (mGPSRunnable != callback)
            return false;
        if (mDestroyed || !keepListening || !Globals.getInstance().mUseGPS) {
            stopGPSDataLocked();
            return false;
        }
        return true;
    }

    private void stopGPSDataLocked() {
        if (mGPSRunnable != null)
            mGPSHandler.removeCallbacks(mGPSRunnable);
        mGPSRunnable = null;
        mGPSRunning = false;
    }

    private void requestFullGPSUpdate() {
        synchronized (mServerStateLock) {
            mGPSIntervalCount = SEND_ALL_GPS_INTERVAL;
        }
    }

    public void sendPlayerData(int playerID) {
        if (mClientData == null || mClientData.size() == 0)
            return;
        try {
            JSONArray players = new JSONArray();
            Map<Byte, InetAddress> teamIPMap = getTeamIPMapSnapshot();
            if (teamIPMap.isEmpty())
                return;
            for (Map.Entry<Byte, InetAddress> entry : teamIPMap.entrySet()) {
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERNAME, Globals.getInstance().getPlayerName(entry.getKey()));
                player.put(JSON_PLAYERID, entry.getKey());
                player.put(JSON_PLAYERIP, entry.getValue());
                ScoreData scoreData = getScore(entry.getKey());
                player.put(JSON_PLAYERPOINTS, scoreData == null ? 0 : scoreData.points);
                player.put(JSON_PLAYERELIMINATED, scoreData == null ? 0 : scoreData.eliminated);
                players.put(player);
            }
            JSONObject game = new JSONObject();
            game.put(JSON_PLAYERDATA, players);
            String message = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
            if (playerID != SEND_ALL)
                sendTCPMessageID(message, (byte)playerID, false);
            else {
                // Using sendTCPMessageAll here does not work because the server ends before the messages get sent, mostly due to semaphore locking
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    if (!isClientTaskActive())
                        return;
                    entry.getValue().sendTCPMessage(message);
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
//TODO player presets
    public JSONArray getPlayerSettings(int playerID, boolean applyAll) {
        Globals.getmPlayerSettingsSemaphore();
        try {
            JSONArray players = new JSONArray();
            if (applyAll && playerID != SEND_ALL) {
                Globals.PlayerSettings sourceSettings = Globals.getInstance().mPlayerSettings.get((byte) playerID);
                if (sourceSettings == null) {
                    Log.w(TAG, "Cannot apply settings from unknown player " + playerID);
                    return null;
                }
                for (Byte x = 1; x <= Globals.MAX_PLAYER_ID; x++) {
                    if (x != playerID) {
                        Globals.PlayerSettings playerSettings = Globals.getInstance().mPlayerSettings.get(x);
                        if (playerSettings == null) {
                            playerSettings = new Globals.PlayerSettings();
                            Globals.getInstance().mPlayerSettings.put(x, playerSettings);
                        }
                        playerSettings.health = sourceSettings.health;
                        playerSettings.shots = sourceSettings.shots;
                        playerSettings.reloadTime = sourceSettings.reloadTime;
                        playerSettings.reloadOnEmpty = sourceSettings.reloadOnEmpty;
                        playerSettings.spawnTime = sourceSettings.spawnTime;
                        playerSettings.damage = sourceSettings.damage;
                        playerSettings.overrideLives = sourceSettings.overrideLives;
                        playerSettings.lives = sourceSettings.lives;
                        playerSettings.allowShotModeSingle = sourceSettings.allowShotModeSingle;
                        playerSettings.allowShotModeBurst3 = sourceSettings.allowShotModeBurst3;
                        playerSettings.allowShotModeAuto = sourceSettings.allowShotModeAuto;
                        playerSettings.firingMode = sourceSettings.firingMode;
                    }
                }
            }
            if (mClientData == null || mClientData.size() == 0)
                return null;
            for (Map.Entry<Byte, Globals.PlayerSettings> entry : Globals.getInstance().mPlayerSettings.entrySet()) {
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERID, entry.getKey());
                player.put(JSON_HEALTH, entry.getValue().health);
                player.put(JSON_RELOAD_SHOTS, entry.getValue().shots & 0xff);
                player.put(JSON_RELOAD_TIME, entry.getValue().reloadTime);
                player.put(JSON_RELOAD_ON_EMPTY, entry.getValue().reloadOnEmpty);
                player.put(JSON_SPAWN_TIME, entry.getValue().spawnTime);
                player.put(JSON_DAMAGE, entry.getValue().damage);
                if (entry.getValue().overrideLives)
                    player.put(JSON_LIVESLIMIT, entry.getValue().lives);
                player.put(JSON_SHOT_MODE_SINGLE, entry.getValue().allowShotModeSingle);
                player.put(JSON_SHOT_MODE_BURST3, entry.getValue().allowShotModeBurst3);
                player.put(JSON_SHOT_MODE_AUTO, entry.getValue().allowShotModeAuto);
                player.put(JSON_FIRING_MODE, entry.getValue().firingMode);
                //TODO checks
             //   player.put(JSON_PLAYER_PRESET,entry.getValue().playerPreset);
              //  player.put(JSON_WEAPON_PRESET,entry.getValue().weaponPreset);

                players.put(player);
            }
            return players;
        } catch (JSONException e) {
            e.printStackTrace();
        } finally {
            Globals.getInstance().mPlayerSettingsSemaphore.release();
        }
        return null;
    }

    public void sendPlayerSettings(int playerID, boolean applyAll, boolean allowPlayerSettings) {
        // A host policy change is local state even when nobody is connected yet.
        Globals.getInstance().mAllowPlayerSettings = allowPlayerSettings;
        sendPlayerSettingsUpdate(playerID, applyAll);
    }

    private void sendPlayerSettingsUpdate(int playerID, boolean applyAll) {
        JSONArray players = getPlayerSettings(playerID, applyAll);
        if (players == null)
            return;
        try {
            JSONObject game = new JSONObject();
            // A reply must not restore a policy captured before waiting for settings.
            game.put(JSON_ALLOWPLAYERSETTINGS, Globals.getInstance().mAllowPlayerSettings);
            game.put(JSON_PLAYERSETTINGS, players);
            String message = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
            sendTCPMessageAll(message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    void startTcpServer() {
        synchronized (mServerStateLock) {
            if (mDestroyed)
                return;
            if ((mServerThread != null && mServerThread.isAlive())
                    || (mClientThread != null && mClientThread.isAlive())
                    || !mClientTasks.isEmpty()) {
                Log.d(TAG, "Server is still starting, listening, or shutting down");
                return;
            }
            keepListening = true;
            mServerThread = new Thread(this::runTcpServer, "SimpleCoil TCP server");
            mServerThread.start();
        }
    }

    private void runTcpServer() {
        ServerSocket ss = null;
        Socket pendingSocket = null;
        int clientID = 0;
        try {
            ss = new ServerSocket();
            synchronized (mServerStateLock) {
                if (!keepListening || mDestroyed)
                    return;
                mListenSocket = ss;
            }
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(TCP_SERVER_PORT));
            ss.setSoTimeout(1000);
            // A failed bind must not erase another server's active round state.
            mDepartedScores.clear();
            if (mClientData == null)
                mClientData = new ConcurrentHashMap<>();
            else
                mClientData.clear();
            Globals.getmGPSDataSemaphore();
            try {
                if (Globals.getInstance().mGPSData == null)
                    Globals.getInstance().mGPSData = new HashMap<>();
                else
                    Globals.getInstance().mGPSData.clear();
            } finally {
                Globals.getInstance().mGPSDataSemaphore.release();
            }
            if (!keepListening)
                return;
            Globals.ClearGrenadePairings(true);
            Log.d(TAG, "TCP Server listening");
            while (keepListening) {
                try {
                    // Wait for new clients
                    pendingSocket = ss.accept();
                    if (!keepListening)
                        break;
                    ClientData client = new ClientData();
                    if (!client.initialize(pendingSocket, clientID + 1)) {
                        pendingSocket = null;
                        continue;
                    }
                    mClientDataSemaphore.acquire();
                    try {
                        synchronized (mServerStateLock) {
                            if (!keepListening || mDestroyed) {
                                client.close();
                                break;
                            }
                            clientID++;
                            mClientData.put(clientID, client);
                            pendingSocket = null;
                            if (clientID == 1) {
                                mClientThread = new Thread(new ClientThread(), "SimpleCoil TCP clients");
                                mClientThread.start();
                            }
                        }
                    } finally {
                        mClientDataSemaphore.release();
                    }
                } catch (SocketTimeoutException e) {
                    // do nothing
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (keepListening)
                Log.e(TAG, "TCP listener failed", e);
        } catch (RuntimeException e) {
            // Globals' interruptible lock helpers throw when startup is cancelled.
            // Never let service shutdown become an uncaught background exception.
            if (keepListening)
                Log.e(TAG, "TCP server startup failed", e);
        } finally {
            closeSocket(pendingSocket);
            closeListener(ss);
            synchronized (mServerStateLock) {
                if (mListenSocket == ss)
                    mListenSocket = null;
                if (Thread.currentThread() == mServerThread) {
                    keepListening = false;
                    stopGPSDataLocked();
                    mServerThread = null;
                }
            }
            Log.d(TAG, "TCP Server done");
        }
    }

    private int clientIDFromPlayerID(byte playerID) {
        if (mClientData == null) return -1;
        for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
            if (entry.getValue().mPlayerID == playerID) {
                return entry.getKey();
            }
        }
        return -1;
    }

    public void lockAccess() {
        try {
            mClientDataSemaphore.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void unlockAccess() {
        mClientDataSemaphore.release();
    }

    public ScoreData getScore(byte playerID) {
        int clientID = clientIDFromPlayerID(playerID);
        ClientData client = clientID < 0 ? null : mClientData.get(clientID);
        ScoreData scoreData = new ScoreData();
        if (client != null) {
            // These fields are volatile. The client monitor also guards socket
            // writes, which must never block the host UI's scoreboard reads.
            scoreData.points = client.points;
            scoreData.eliminated = client.eliminated;
            scoreData.isConnected = client.out != null;
        } else {
            ScoreData departed = mDepartedScores.get(playerID);
            if (departed == null)
                return null;
            scoreData.points = departed.points;
            scoreData.eliminated = departed.eliminated;
        }
        return scoreData;
    }

    private boolean hasReachedScoreLimit(ClientData scoringPlayer) {
        Globals globals = Globals.getInstance();
        if (!mIsDedicated || (globals.mGameLimit & Globals.GAME_LIMIT_SCORE) == 0 || globals.mScoreLimit <= 0)
            return false;
        long score = scoringPlayer.points;
        if (globals.mGameMode != Globals.GAME_MODE_FFA) {
            score = 0;
            int team = scoringPlayer.getNetworkTeam();
            for (byte id = 1; id <= Globals.MAX_PLAYER_ID; id++) {
                if (globals.calcNetworkTeam(id) == team) {
                    ScoreData playerScore = getScore(id);
                    if (playerScore != null)
                        score += playerScore.points;
                }
            }
        }
        return score >= globals.mScoreLimit;
    }

    public class ScoreData {
        int points = 0;
        int eliminated = 0;
        boolean isConnected = false;
    }

    private class ClientData {
        private volatile Socket clientSocket = null;
        private int clientID = -1;
        private volatile byte mPlayerID = 0;
        private int noReadCount = 0;
        private InputStream is = null;
        private OutputStream os = null;
        private DataInputStream in = null;
        private TcpMessageReader messageReader = new TcpMessageReader();
        private volatile DataOutputStream out = null;
        private volatile boolean connectionFailed;
        private Queue<String> messageQueue;
        private volatile int points = 0;
        private volatile int eliminated = 0;

        private int getNetworkTeam() {
            // The host can change the game mode after players have joined the lobby.
            return Globals.getInstance().calcNetworkTeam(mPlayerID);
        }

        public synchronized boolean initialize(Socket s, int cID) {
            clientSocket = s;
            clientID = cID;
            noReadCount = 0;
            messageReader = new TcpMessageReader();
            if (messageQueue == null)
                messageQueue = new LinkedList<>();
            try {
                is = clientSocket.getInputStream();
                in = new DataInputStream(is);
                os = clientSocket.getOutputStream();
                out = new DataOutputStream(os);
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                close();
                connectionFailed = true;
                return false;
            }
        }

        public synchronized boolean sendTCPMessage(final String message) { return sendTCPMessage(message, false); }

        public synchronized boolean sendTCPMessage(final String message, boolean queueFailed) {
            if (out == null) {
                if (queueFailed) {
                    Log.e(TAG, "queuing: " + message);
                    messageQueue.add(message);
                }
                return false;
            }
            try {
                out.writeUTF(message);
                out.flush();
                if (!message.equals(TcpServer.TCP_SERVER_PING))
                    Log.i(TAG, "sent(" + clientID + "): " + message);
                return true;
            } catch (IOException e) {
                //Log.e(TAG, "IO Error:", e);
                e.printStackTrace();
                if (queueFailed) {
                    Log.e(TAG, "queuing: " + message);
                    messageQueue.add(message);
                }
                // The failed write may have emitted only part of its frame.
                // Keep pending events, but never append to that damaged stream.
                close();
                connectionFailed = true;
                return false;
            }
        }

        public synchronized void close() {
            connectionFailed = false;
            if (clientSocket == null) return;
            try { clientSocket.close(); } catch (IOException e) {/*do nothing*/}
            try { if (in != null) in.close(); } catch (IOException e) {/*do nothing*/}
            try { if (is != null) is.close(); } catch (IOException e) {/*do nothing*/}
            try { if (out != null) out.close(); } catch (IOException e) {/*do nothing*/}
            try { if (os != null) os.close(); } catch (IOException e) {/*do nothing*/}
            clientSocket = null;
            in = null;
            is = null;
            out = null;
            os = null;
        }

        public synchronized void rejoin(Socket s) {
            close();
            if (!initialize(s, clientID))
                return;
            while (!messageQueue.isEmpty()) {
                Log.e(TAG, "sending queued: " + messageQueue.peek());
                // Only dequeue after a successful write and flush. A replacement
                // connection can fail too; preserve the remainder for another rejoin.
                if (!sendTCPMessage(messageQueue.peek()))
                    break;
                messageQueue.remove();
            }
        }
    }

    private class ClientThread implements Runnable {
        public void run() {
            try {
                runSession();
            } catch (RuntimeException e) {
                if (keepListening)
                    Log.e(TAG, "TCP client handler failed", e);
                stopTcpServer();
            } finally {
                if (mClientData != null) {
                    for (ClientData client : mClientData.values())
                        client.close();
                }
                synchronized (mServerStateLock) {
                    if (mClientThread == Thread.currentThread())
                        mClientThread = null;
                }
            }
        }

        private void runSession() {
            Log.i(TAG, "Running TCP listening server for clients");
            if (Globals.getInstance().mUseGPS) {
                sendGPSData();
            }
            //receive a message
            while (keepListening) {
                try {
                    mClientDataSemaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stopTcpServer();
                    return;
                }
                long startTime = System.currentTimeMillis();
                try {
                    if (!keepListening)
                        return;
                    for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                        try {
                            if (entry.getValue().connectionFailed) {
                                // Writes happen on sender threads. Let this loop update
                                // lobby membership (or preserve the player for rejoin)
                                // under the same lock used for read-side disconnects.
                                removeClient(entry.getValue(), entry.getKey(), false);
                                break;
                            }
                            String message = entry.getValue().in == null ? null
                                    : entry.getValue().messageReader.poll(entry.getValue().in);
                            if (message != null) {
                                entry.getValue().noReadCount = 0;
                                if (!message.equals(TcpClient.TCP_CLIENT_PONG)) {
                                    Log.i(TAG, "received: '" + message + "' from " + entry.getValue().clientID);
                                    if (message.startsWith(TCPMESSAGE_PREFIX)) {
                                        if (message.startsWith(TCPPREFIX_JSON, TCPMESSAGE_PREFIX.length())) {
                                            // Handle JSON Data
                                            message = message.substring(TCPMESSAGE_PREFIX.length() + TCPPREFIX_JSON.length());
                                            parsePlayerInfo(message, entry.getValue());
                                            break;
                                        } else if (message.startsWith(TCPPREFIX_MESG, TCPMESSAGE_PREFIX.length())) {
                                            // Handle messages
                                            message = message.substring(TCPMESSAGE_PREFIX.length() + TCPPREFIX_MESG.length());
                                            if (entry.getValue().mPlayerID <= 0
                                                    || !Globals.isValidPlayerID(entry.getValue().mPlayerID)) {
                                                // A socket is not a player until registration succeeds.
                                                // Its departure must not end an existing players' round.
                                                if (message.equals(NetMsg.NETMSG_LEAVE))
                                                    removeClient(entry.getValue(), entry.getKey(), true);
                                                Log.w(TAG, "Ignoring game control from an unregistered connection");
                                                continue;
                                            }
                                            if (message.equals(NetMsg.NETMSG_LEAVE)) {
                                                removeClient(entry.getValue(), entry.getKey(), true);
                                                if (mClientData.size() <= 1 && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                                                    endGame();
                                                break;
                                            } else if (message.startsWith(NetMsg.NETMSG_ELIMINATED)) {
                                                if (Globals.getInstance().mGameState != Globals.GAME_STATE_RUNNING
                                                        || entry.getValue().mPlayerID <= 0
                                                        || !Globals.isValidPlayerID(entry.getValue().mPlayerID)) {
                                                    Log.w(TAG, "Ignoring elimination outside an active, registered game");
                                                    continue;
                                                }
                                                // A player was eliminated
                                                message = message.substring(NetMsg.NETMSG_ELIMINATED.length());
                                                final int rawPlayerID;
                                                try {
                                                    rawPlayerID = Integer.parseInt(message);
                                                } catch (NumberFormatException e) {
                                                    Log.w(TAG, "Ignoring malformed elimination player ID");
                                                    continue;
                                                }
                                                if (!Globals.isValidPlayerID(rawPlayerID) || rawPlayerID <= 0) {
                                                    Log.w(TAG, "Ignoring elimination with invalid player ID " + rawPlayerID);
                                                    continue;
                                                }
                                                byte id = (byte) rawPlayerID;
                                                ClientData scoringPlayer = mClientData.get(clientIDFromPlayerID(id));
                                                if (scoringPlayer == null || id == entry.getValue().mPlayerID
                                                        || (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA
                                                        && scoringPlayer.getNetworkTeam() == entry.getValue().getNetworkTeam())) {
                                                    Log.w(TAG, "Ignoring invalid elimination report from player " + entry.getValue().mPlayerID);
                                                    continue;
                                                }
                                                entry.getValue().eliminated++;
                                                scoringPlayer.points++;
                                                sendTCPMessageID(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ELIMINATED + entry.getValue().mPlayerID, id, true);
                                                if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                                                    // Send a message to all teammates about the score increase
                                                    sendTCPMessageTeam(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_TEAMELIMINATED, id, false, true);
                                                }
                                                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                                                if (hasReachedScoreLimit(scoringPlayer))
                                                    endGame();
                                            } else if (message.equals(NetMsg.NETMSG_PLAYERDATAREQUEST)) {
                                                sendPlayerData(entry.getValue().mPlayerID);
                                            } else if (message.equals(NetMsg.NETMSG_STARTGAME)) {
                                                if (Globals.getInstance().mOnlyServerSettings
                                                        || Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
                                                    Log.w(TAG, "Ignoring disallowed or duplicate game-start request");
                                                } else {
                                                    startGame();
                                                }
                                            } else if (message.equals(NetMsg.NETMSG_ENDGAME)) {
                                                if (Globals.getInstance().mOnlyServerSettings) {
                                                    // If server settings only is enabled, then we treat this as if the client is leaving rather than ending the game
                                                    removeClient(entry.getValue(), entry.getKey(), true);
                                                    if (mClientData.size() <= 1 && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                                                        endGame();
                                                    break;
                                                }
                                                endGame();
                                            }
                                        } else {
                                            Log.d(TAG, "unknown tcp message received");
                                        }
                                    } else if (message.startsWith(NetMsg.MESSAGE_PREFIX)) {
                                        entry.getValue().sendTCPMessage(NetMsg.NETMSG_VERSIONERROR);
                                        break;
                                    }
                                }
                            } else {
                                entry.getValue().noReadCount++;
                                if (entry.getValue().out != null && entry.getValue().noReadCount >= 35) {
                                    Log.d(TAG, "no reply so dropping client " + entry.getValue().clientID);
                                    removeClient(entry.getValue(), entry.getKey(), false);
                                    break;
                                } else if (entry.getValue().noReadCount == 10 || entry.getValue().noReadCount == 20) {
                                    entry.getValue().sendTCPMessage(TCP_SERVER_PING);
                                }
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                            removeClient(entry.getValue(), entry.getKey(), false);
                            break;
                        } catch (RuntimeException e) {
                            if (Thread.currentThread().isInterrupted())
                                throw e;
                            Log.e(TAG, "Invalid TCP data from client " + entry.getValue().clientID, e);
                            removeClient(entry.getValue(), entry.getKey(), false);
                            break;
                        }
                    }
                } finally {
                    mClientDataSemaphore.release();
                }
                long sleepTime = TCP_READ_WAIT_MS - (System.currentTimeMillis() - startTime);
                if (mIsDedicated)
                    sleepTime = TCP_DEDICATED_READ_WAIT_MS - (System.currentTimeMillis() - startTime);
                //Log.d(TAG, "Took " + (System.currentTimeMillis() - startTime) + "ms to service " + mClientData.size() + " clients");
                sleep(sleepTime);
            }
        }

        private void sendGrenadePairings(boolean getSemaphore) {
            if (getSemaphore)
                Globals.getmGrenadePairingsSemaphore();
            try {
                JSONArray grenadePairings = new JSONArray();
                for (int index = 1; index < Globals.MAX_GRENADE_IDS; index++) {
                    if (Globals.getInstance().mGrenadePairings[index] != Globals.INVALID_PLAYER_ID) {
                        JSONObject grenadePairing = new JSONObject();
                        grenadePairing.put(JSON_PLAYERID, Globals.getInstance().mGrenadePairings[index]);
                        grenadePairing.put(JSON_PAIRED_GRENADE_ID, index);
                        grenadePairings.put(grenadePairing);
                    }
                }
                if (getSemaphore)
                    Globals.getInstance().mGrenadePairingsSemaphore.release();
                getSemaphore = false; // don't release the semaphore in the exception clause if we fail after this point
                // Send empty updates too.  Otherwise a client can keep a pairing from a
                // previous round after the server has cleared it.
                JSONObject data = new JSONObject();
                data.put(JSON_GRENADE_PAIRINGS, grenadePairings);
                String message = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + data.toString();
                sendTCPMessageAll(message);
            } catch (JSONException e) {
                if (getSemaphore)
                    Globals.getInstance().mGrenadePairingsSemaphore.release();
                e.printStackTrace();
            }
        }

        private void parsePlayerInfo(String message, ClientData client) {
            JSONObject player;
            try {
                player = TcpJson.parseObject(message);
            } catch (JSONException e) {
                e.printStackTrace();
                return;
            }
            if (player.has(JSON_PAIRED_GRENADE_ID)) {
                final int grenadeID;
                final int playerID;
                try {
                    grenadeID = player.getInt(JSON_PAIRED_GRENADE_ID);
                    playerID = player.getInt(JSON_PLAYERID);
                } catch (JSONException e) {
                    e.printStackTrace();
                    return;
                }
                if (!Globals.isValidGrenadeID(grenadeID)
                        || client.mPlayerID <= 0
                        || playerID != client.mPlayerID) {
                    Log.w(TAG, "Ignoring invalid grenade pairing ID " + grenadeID);
                    return;
                }
                Globals.getmGrenadePairingsSemaphore();
                try {
                    int[] pairings = Globals.getInstance().mGrenadePairings;
                    // A player can have only one paired grenade. ID zero means
                    // unpaired, not a grenade that can be assigned an owner.
                    for (int index = 0; index < pairings.length; index++) {
                        if (pairings[index] == playerID)
                            pairings[index] = Globals.INVALID_PLAYER_ID;
                    }
                    if (grenadeID != 0)
                        pairings[grenadeID] = playerID;
                    sendGrenadePairings(false);
                } finally {
                    Globals.getInstance().mGrenadePairingsSemaphore.release();
                }
                return;
            }
            if (player.has(JSON_GPSLONGITUDE)) {
                if (client.mPlayerID <= 0 || !Globals.isValidPlayerID(client.mPlayerID)) {
                    Log.w(TAG, "Ignoring GPS data from an unregistered client");
                    return;
                }
                Globals.getmGPSDataSemaphore();
                Globals.GPSData gps = Globals.getInstance().mGPSData.get(client.mPlayerID);
                double longitude, latitude;
                try {
                    longitude = player.getDouble(JSON_GPSLONGITUDE);
                    latitude = player.getDouble(JSON_GPSLATITUDE);
                } catch (JSONException e) {
                    e.printStackTrace();
                    Globals.getInstance().mGPSDataSemaphore.release();
                    return;
                }
                if (!Globals.isValidCoordinates(longitude, latitude)) {
                    Log.w(TAG, "Ignoring GPS data with invalid coordinates from " + client.mPlayerID);
                    Globals.getInstance().mGPSDataSemaphore.release();
                    return;
                }
                if (gps == null) {
                    gps = new Globals.GPSData();
                    Globals.getInstance().mGPSData.put(client.mPlayerID, gps);
                }
                gps.longitude = longitude;
                gps.latitude = latitude;
                gps.team = client.getNetworkTeam();
                gps.hasUpdate = true; // Client takes care of making sure that it is only sending us changes in coordinates
                Globals.getInstance().mGPSDataSemaphore.release();
                return;
            }
            if (player.has(JSON_PLAYERSETTINGS)) {
                if (!Globals.getInstance().mAllowPlayerSettings || Globals.getInstance().mOnlyServerSettings
                        || client.mPlayerID <= 0 || !Globals.isValidPlayerID(client.mPlayerID)) {
                    Log.e(TAG, "Player " + client.mPlayerID + "not allowed to send player settings");
                    sendPlayerSettingsUpdate(SEND_ALL, false);
                    return;
                }
                boolean applied = false;
                try {
                    int playerID = player.getInt(JSON_PLAYERID);
                    int health = player.getInt(JSON_HEALTH);
                    int reloadShots = player.getInt(JSON_RELOAD_SHOTS);
                    long reloadTime = player.getLong(JSON_RELOAD_TIME);
                    boolean reloadOnEmpty = player.getBoolean(JSON_RELOAD_ON_EMPTY);
                    long spawnTime = player.getLong(JSON_SPAWN_TIME);
                    int damage = player.getInt(JSON_DAMAGE);
                    boolean overrideLives = player.has(JSON_LIVESLIMIT);
                    int lives = overrideLives ? player.getInt(JSON_LIVESLIMIT) : 0;
                    boolean allowAuto = player.getBoolean(JSON_SHOT_MODE_AUTO);
                    boolean allowBurst = player.getBoolean(JSON_SHOT_MODE_BURST3);
                    boolean allowSingle = player.getBoolean(JSON_SHOT_MODE_SINGLE);
                    int firingMode = player.getInt(JSON_FIRING_MODE);
                    if (playerID != client.mPlayerID || !Globals.isValidPlayerSettings(health, reloadShots,
                            reloadTime, spawnTime, damage, lives, allowSingle, allowBurst, allowAuto, firingMode)) {
                        Log.w(TAG, "Ignoring invalid player settings from " + client.mPlayerID);
                        sendPlayerSettingsUpdate(SEND_ALL, false);
                        return;
                    }
                    Globals.getmPlayerSettingsSemaphore();
                    try {
                        // The host can revoke permission while this request waits for the lock.
                        if (!Globals.getInstance().mAllowPlayerSettings || Globals.getInstance().mOnlyServerSettings
                                || playerID != client.mPlayerID) {
                            Log.w(TAG, "Ignoring player settings after permission or identity changed");
                        } else {
                            Globals.PlayerSettings settings = Globals.getInstance().mPlayerSettings.get(client.mPlayerID);
                            if (settings == null) {
                                settings = new Globals.PlayerSettings();
                                Globals.getInstance().mPlayerSettings.put(client.mPlayerID, settings);
                            }
                            settings.health = health;
                            settings.shots = (byte) reloadShots;
                            settings.reloadTime = reloadTime;
                            settings.reloadOnEmpty = reloadOnEmpty;
                            settings.spawnTime = spawnTime;
                            settings.damage = damage;
                            settings.overrideLives = overrideLives;
                            settings.lives = lives;
                            settings.allowShotModeAuto = allowAuto;
                            settings.allowShotModeBurst3 = allowBurst;
                            settings.allowShotModeSingle = allowSingle;
                            settings.firingMode = firingMode;
                            applied = true;
                        }
                    } finally {
                        Globals.getInstance().mPlayerSettingsSemaphore.release();
                    }
                } catch (JSONException e) {
                    Log.w(TAG, "Ignoring malformed player settings from " + client.mPlayerID, e);
                    return;
                }
                sendPlayerSettingsUpdate(SEND_ALL, false);
                if (applied)
                    sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                return;
            }
            if (player.has(JSON_PLAYERNAMECHANGE)) {
                int id;
                String playerName;
                try {
                    id = player.getInt(JSON_PLAYERID);
                    playerName = TcpJson.getPlayerName(player, JSON_PLAYERNAMECHANGE);
                } catch (JSONException e) {
                    e.printStackTrace();
                    return;
                }
                if (client.mPlayerID <= 0 || id != client.mPlayerID) {
                    Log.w(TAG, "Ignoring player-name change with an invalid player ID");
                    return;
                }
                Globals.getmTeamPlayerNameSemaphore();
                Globals.getInstance().mTeamPlayerNameMap.put(client.mPlayerID, playerName);
                Globals.getInstance().mTeamPlayerNameSemaphore.release();

                sendAllGameInfo(SEND_ALL);
                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                return;
            }
            boolean rejoin;
            byte id;
            String playerName;
            try {
                rejoin = player.has(JSON_REJOIN);
                int rawPlayerID = player.getInt(JSON_PLAYERID);
                if (!Globals.isValidPlayerID(rawPlayerID) || rawPlayerID <= 0) {
                    Log.w(TAG, "Ignoring client with invalid player ID " + rawPlayerID);
                    return;
                }
                id = (byte) rawPlayerID;
                playerName = TcpJson.getPlayerName(player, JSON_PLAYERNAME);
            } catch (JSONException e) {
                e.printStackTrace();
                return;
            }
            if (client.clientSocket == null)
                return;
            if (client.mPlayerID != 0 && client.mPlayerID != id) {
                Log.w(TAG, "Ignoring an attempt to change a registered connection's player ID");
                return;
            }
            requestFullGPSUpdate(); // Send all GPS info because of the new client
            for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                if (entry.getValue() != client && entry.getValue().mPlayerID == id) {
                    Log.d(TAG, "rejoining " + client.clientID + " to " + entry.getValue().clientID);
                    entry.getValue().rejoin(client.clientSocket);
                    mClientData.remove(client.clientID);
                    client = entry.getValue();
                    break;
                }
            }
            if (client.clientSocket == null)
                return; // A replacement socket can fail while its streams are opened.
            boolean newRegistration = client.mPlayerID == 0;
            if (rejoin && newRegistration)
                Log.d(TAG, "rejoined client " + client.clientID + " not present so adding as a new player");
            client.mPlayerID = id;
            ScoreData departed = mDepartedScores.remove(id);
            if (newRegistration && departed != null) {
                client.points = departed.points;
                client.eliminated = departed.eliminated;
            }
            Log.d(TAG, "network team is " + client.getNetworkTeam());
            InetAddress inetAddress = client.clientSocket.getInetAddress();
            Log.d(TAG, "client " + client.clientID + " player '" + playerName + "' (" + client.mPlayerID + ") found at " + inetAddress.toString());
            updatePlayerEndpoint(client.mPlayerID, inetAddress);
            updatePlayerName(client.mPlayerID, playerName);

            sendAllGameInfo(id);
            // Synchronize a joining client even when no grenade is currently paired.
            sendGrenadePairings(true);
            sendBroadcast(new Intent(newRegistration ? NetMsg.NETMSG_JOIN : NetMsg.NETMSG_PLAYERDATAUPDATE));
        }

        private void removeClient(ClientData client, Integer clientID, boolean alwaysRemove) {
            if (mClientData.get(clientID) != client)
                return;
            if (client.mPlayerID != 0) {
                if (alwaysRemove || Globals.getInstance().mGameState == Globals.GAME_STATE_NONE) {
                    if (mIsDedicated && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
                        ScoreData score = getScore(client.mPlayerID);
                        if (score != null) {
                            score.isConnected = false;
                            mDepartedScores.put(client.mPlayerID, score);
                        }
                    } else {
                        mDepartedScores.remove(client.mPlayerID);
                    }
                    Globals.getmTeamPlayerNameSemaphore();
                    try {
                        Globals.getInstance().mTeamPlayerNameMap.remove(client.mPlayerID);
                    } finally {
                        Globals.getInstance().mTeamPlayerNameSemaphore.release();
                    }
                    Globals.getmIPTeamMapSemaphore();
                    try {
                        // A disconnected client no longer has a socket. Remove endpoints
                        // by player ID, without dereferencing that socket or another player's IP.
                        Iterator<Map.Entry<InetAddress, Byte>> endpoints = Globals.getInstance().mIPTeamMap.entrySet().iterator();
                        while (endpoints.hasNext()) {
                            Byte playerID = endpoints.next().getValue();
                            if (playerID != null && playerID.byteValue() == client.mPlayerID)
                                endpoints.remove();
                        }
                    } finally {
                        Globals.getInstance().mIPTeamMapSemaphore.release();
                    }
                    Globals.getmTeamIPMapSemaphore();
                    try {
                        Globals.getInstance().mTeamIPMap.remove(client.mPlayerID);
                    } finally {
                        Globals.getInstance().mTeamIPMapSemaphore.release();
                    }
                    Globals.getmGPSDataSemaphore();
                    try {
                        Globals.getInstance().mGPSData.remove(client.mPlayerID);
                        requestFullGPSUpdate(); // Force a full GPS update when someone leaves
                    } finally {
                        Globals.getInstance().mGPSDataSemaphore.release();
                    }
                    client.close();
                    mClientData.remove(clientID);
                    sendAllGameInfo(SEND_ALL);
                    sendBroadcast(new Intent(NetMsg.NETMSG_LEAVE));
                } else {
                    client.close(); // We'll keep this client around in case they rejoin later since the game was underway
                }
            } else {
                client.close();
                mClientData.remove(clientID);
            }
            sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
        }
    }

    public void stopTcpServer() {
        final ServerSocket listener;
        final Thread worker;
        final Thread clients;
        final Thread[] tasks;
        final List<Socket> sockets = new ArrayList<>();
        synchronized (mServerStateLock) {
            keepListening = false;
            stopGPSDataLocked();
            listener = mListenSocket;
            worker = mServerThread;
            clients = mClientThread;
            tasks = mClientTasks.toArray(new Thread[0]);
            if (mClientData != null) {
                for (ClientData client : mClientData.values())
                    sockets.add(client.clientSocket);
            }
        }
        closeListener(listener);
        // Socket writes need not respond to interruption. Close only this
        // session's sockets, without taking a blocked writer's client monitor.
        for (Socket socket : sockets)
            closeSocket(socket);
        for (Thread task : tasks) {
            if (task != Thread.currentThread())
                task.interrupt();
        }
        // Closing wakes accept(); interruption also cancels startup/registration
        // while it is waiting for a shared-state lock.
        if (worker != null && worker != Thread.currentThread())
            worker.interrupt();
        // The client loop may be waiting on registration or GPS state instead
        // of its next socket read. Cancelling the listener alone cannot wake it.
        if (clients != null && clients != Thread.currentThread())
            clients.interrupt();
    }

    private static void closeListener(ServerSocket socket) {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void sleep(long millis) {
        if (millis <= 0)
            return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopTcpServer();
        }
    }

    public void setDedicated(boolean dedicated) { mIsDedicated = dedicated; }
}
