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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class TcpClient extends Service {
    private static final String TAG = "TCPClient";

    public static final String TCP_CLIENT_PONG = "pong";
    private static final int MAX_REJOIN_TRIES = 3;
    private static final int CONNECTION_TIMEOUT_MS = 1000;
    private static final int RECONNECT_RETRY_DELAY_MS = 1000;

    private static volatile boolean keepListening = false;
    private static volatile boolean isListening = false;
    private volatile boolean mIsDedicatedServer = false;

    private volatile DataOutputStream out = null;
    private final Queue<String> messageQueue = new ConcurrentLinkedQueue<>();
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    public class LocalBinder extends Binder {
        TcpClient getService() {
            return TcpClient.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        ContextCompat.registerReceiver(this, mGPSUpdateReceiver,
                new IntentFilter(NetMsg.NETMSG_GPSLOCUPDATE), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mGPSUpdateReceiver);
        sendExecutor.shutdownNow();
        super.onDestroy();
    }

    public void sendTCPMessage(final String message) {
        sendTCPMessage(message, false);
    }

    public void sendTCPMessage(final String message, final boolean queueMessage) {
        final DataOutputStream writer = out;
        if (writer == null) {
            Log.d(TAG, "Writer is null");
            if (queueMessage) {
                Log.e(TAG, "queuing: " + message);
                messageQueue.offer(message);
            }
            return;
        }
        if (sendExecutor.isShutdown()) {
            if (queueMessage)
                messageQueue.offer(message);
            return;
        }
        try {
            sendExecutor.execute(() -> {
                try {
                    writer.writeUTF(message);
                    writer.flush();
                    if (!message.equals(TCP_CLIENT_PONG))
                        Log.i(TAG, "sent: " + message);
                } catch (IOException e) {
                    if (queueMessage) {
                        Log.e(TAG, "queuing: " + message);
                        messageQueue.offer(message);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // The service can be torn down after the isShutdown() check above.
            if (queueMessage)
                messageQueue.offer(message);
            Log.w(TAG, "TCP sender is shutting down", e);
        }
    }

    void startTcpClient() {
        synchronized (TcpClient.class) {
            if (keepListening) {
                Log.d(TAG, "Client is already listening");
                return;
            }
            if (isListening) {
                Log.e(TAG, "Please wait for client to stop listening");
                return;
            }
            mIsDedicatedServer = false;
            keepListening = true;
            isListening = true;
            new Thread(this::runTcpClient).start();
        }
    }

    private void runTcpClient() {
        Globals.getmGPSDataSemaphore();
        try {
            if (Globals.getInstance().mGPSData == null)
                Globals.getInstance().mGPSData = new HashMap<>();
            else
                Globals.getInstance().mGPSData.clear();
        } finally {
            Globals.getInstance().mGPSDataSemaphore.release();
        }
        Log.d(TAG, "Starting client...");
        Socket s = null;
        InputStream is = null;
        DataInputStream in = null;
        OutputStream os = null;
        int retryCount = MAX_REJOIN_TRIES;
        boolean rejoin = false;
        boolean wasConnected = false;
        while (retryCount > 0 && keepListening) {
            DataOutputStream connectionOut = null;
            try {
                if (wasConnected) {
                    wasConnected = false;
                    sendBroadcast(new Intent(NetMsg.NETMSG_NETWORKDISCONNECTED));
                }
                if (Globals.getInstance().mGameState == Globals.GAME_STATE_NONE)
                    retryCount--;
                s = new Socket();
                s.connect(new InetSocketAddress(Globals.getInstance().mServerIP, TcpServer.TCP_SERVER_PORT), CONNECTION_TIMEOUT_MS);
                is = s.getInputStream();
                in = new DataInputStream(is);
                os = s.getOutputStream();
                connectionOut = new DataOutputStream(new BufferedOutputStream(os));
                out = connectionOut;
                sendPlayerInfo(rejoin);
                wasConnected = true;
                if (rejoin)
                    sendBroadcast(new Intent(NetMsg.NETMSG_NETWORKCONNECTED));
                rejoin = true;
                int noReadCount = 0;
                retryCount = MAX_REJOIN_TRIES;
                while (keepListening) {
                    if (in.available() > 0) {
                        noReadCount = 0;
                        String message = in.readUTF();
                        if (message.equals(TcpServer.TCP_SERVER_PING))
                            sendTCPMessage(TCP_CLIENT_PONG);
                        else {
                            Log.i(TAG, "received: '" + message + "'");
                            if (message.startsWith(TcpServer.TCPPREFIX_JSON, TcpServer.TCPMESSAGE_PREFIX.length())) {
                                message = message.substring(TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_JSON.length());
                                parseGameInfo(message);
                            } else if (message.startsWith(TcpServer.TCPPREFIX_MESG, TcpServer.TCPMESSAGE_PREFIX.length())) {
                                message = message.substring(TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_MESG.length());
                                if (message.startsWith(NetMsg.NETMSG_ELIMINATED)) {
                                    message = message.substring(NetMsg.NETMSG_ELIMINATED.length());
                                    int playerID = Integer.parseInt(message);
                                    if (!Globals.isValidPlayerID(playerID) || playerID <= 0) {
                                        Log.w(TAG, "Ignoring elimination for invalid player ID " + playerID);
                                        continue;
                                    }
                                    byte id = (byte) playerID;
                                    Intent intent = new Intent(NetMsg.NETMSG_ELIMINATED);
                                    intent.putExtra(UDPListenerService.INTENT_PLAYERID, id);
                                    sendBroadcast(intent);
                                } else if (message.equals(NetMsg.NETMSG_TEAMELIMINATED)) {
                                    sendBroadcast(new Intent(NetMsg.NETMSG_TEAMELIMINATED));
                                } else if (message.equals(NetMsg.NETMSG_ENDGAME)) {
                                    sendBroadcast(new Intent(NetMsg.NETMSG_ENDGAME));
                                } else if (message.equals(NetMsg.NETMSG_STARTGAME)) {
                                    sendBroadcast(new Intent(NetMsg.NETMSG_STARTGAME));
                                } else if (message.equals(NetMsg.NETMSG_SERVERCANCEL)) {
                                    sendBroadcast(new Intent(NetMsg.NETMSG_SERVERCANCEL));
                                    break;
                                }
                            } else if (message.startsWith(NetMsg.NETMSG_VERSIONERROR)) {
                                sendBroadcast(new Intent(NetMsg.NETMSG_VERSIONERROR));
                                break;
                            } else {
                                Log.d(TAG, "unknown tcp message received");
                            }
                        }
                    } else {
                        if (mIsDedicatedServer)
                            sleep(TcpServer.TCP_DEDICATED_READ_WAIT_MS);
                        else
                            sleep(TcpServer.TCP_READ_WAIT_MS);
                        noReadCount++;
                        if (noReadCount >= 35) {
                            Log.d(TAG, "no ping from server, disconnecting");
                            break;
                        }
                    }
                }
            } catch (ConnectException e) {
                Log.d(TAG, "Server not running: " + e.getLocalizedMessage());
                waitBeforeReconnect();
            } catch (IOException e) {
                e.printStackTrace();
                waitBeforeReconnect();
            } catch (RuntimeException e) {
                Log.e(TAG, "Invalid TCP data; reconnecting", e);
                waitBeforeReconnect();
            } finally {
                try { if (s != null) s.close(); } catch (Exception e) { /* ignored */ }
                try { if (is != null) is.close(); } catch (Exception e) { /* ignored */ }
                try { if (in != null) in.close(); } catch (Exception e) { /* ignored */ }
                try { if (os != null) os.close(); } catch (Exception e) { /* ignored */ }
                try { if (connectionOut != null) connectionOut.close(); } catch (Exception e) { /* ignored */ }
                if (out == connectionOut)
                    out = null;
            }
        }
        if (retryCount == 0 && keepListening) {
            sendBroadcast(new Intent(NetMsg.NETMSG_SERVERCANCEL));
        }
        Log.d(TAG, "Client stopping");
        keepListening = false;
        isListening = false;
    }

    public void sendPlayerNameChange() {
        try {
            JSONObject playerInfo = new JSONObject();
            playerInfo.put(TcpServer.JSON_PLAYERID, Globals.getInstance().mPlayerID);
            playerInfo.put(TcpServer.JSON_PLAYERNAMECHANGE, Globals.getInstance().mPlayerName);
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + playerInfo.toString();
            sendTCPMessage(message);
            String queuedMessage;
            while ((queuedMessage = messageQueue.poll()) != null) {
                Log.e(TAG, "sending queued: " + queuedMessage);
                sendTCPMessage(queuedMessage);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void sendPlayerInfo(boolean rejoin) {
        try {
            JSONObject playerInfo = new JSONObject();
            playerInfo.put(TcpServer.JSON_PLAYERID, Globals.getInstance().mPlayerID);
            playerInfo.put(TcpServer.JSON_PLAYERNAME, Globals.getInstance().mPlayerName);
            if (rejoin) {
                Log.d(TAG, "Attempting to rejoin server");
                playerInfo.put(TcpServer.JSON_REJOIN, true);
            }
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + playerInfo.toString();
            sendTCPMessage(message);
            String queuedMessage;
            while ((queuedMessage = messageQueue.poll()) != null) {
                Log.e(TAG, "sending queued: " + queuedMessage);
                sendTCPMessage(queuedMessage);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        if (Globals.getInstance().mPairedGrenadeID != 0)
            sendPlayerGrenade();
    }
//TODO player presets
    public void sendPlayerSettings() {
        try {
            JSONObject playerSettings = new JSONObject();
            playerSettings.put(TcpServer.JSON_PLAYERSETTINGS, true);
            playerSettings.put(TcpServer.JSON_PLAYERID, Globals.getInstance().mPlayerID);
            playerSettings.put(TcpServer.JSON_HEALTH, Globals.getInstance().mFullHealth);
            playerSettings.put(TcpServer.JSON_RELOAD_SHOTS, Globals.getInstance().mFullReload & 0xff);
            playerSettings.put(TcpServer.JSON_RELOAD_TIME, Globals.getInstance().mReloadTime);
            playerSettings.put(TcpServer.JSON_RELOAD_ON_EMPTY, Globals.getInstance().mReloadOnEmpty);
            playerSettings.put(TcpServer.JSON_SPAWN_TIME, Globals.getInstance().mRespawnTime);
            playerSettings.put(TcpServer.JSON_DAMAGE, Globals.getInstance().mDamage);
            if (Globals.getInstance().mOverrideLives)
                playerSettings.put(TcpServer.JSON_LIVESLIMIT, Globals.getInstance().mOverrideLivesVal);
            playerSettings.put(TcpServer.JSON_SHOT_MODE_SINGLE, Globals.getInstance().mAllowSingleShotMode);
            playerSettings.put(TcpServer.JSON_SHOT_MODE_BURST3, Globals.getInstance().mAllowBurst3ShotMode);
            playerSettings.put(TcpServer.JSON_SHOT_MODE_AUTO, Globals.getInstance().mAllowAutoShotMode);
            playerSettings.put(TcpServer.JSON_FIRING_MODE, Globals.getInstance().mCurrentFiringMode);
     // TODO checks
          //  playerSettings.put(TcpServer.JSON_PLAYER_PRESET, Globals.getInstance().mCurrentPlayerPreset);
          //  playerSettings.put(TcpServer.JSON_WEAPON_PRESET, Globals.getInstance().mCurrentWeaponPreset);
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + playerSettings.toString();
            sendTCPMessage(message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void sendPlayerGrenade() {
        try {
            JSONObject playerGrenade = new JSONObject();
            playerGrenade.put(TcpServer.JSON_PLAYERID, Globals.getInstance().mPlayerID);
            playerGrenade.put(TcpServer.JSON_PAIRED_GRENADE_ID, Globals.getInstance().mPairedGrenadeID);
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + playerGrenade.toString();
            sendTCPMessage(message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void stopTcpClient() {
        if (mIsDedicatedServer)
            sleep(75); // Make sure that the end game message gets sent before we close down the socket
        keepListening = false;
    }

    public void leaveServer() {
        if (keepListening) {
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_LEAVE;
            sendTCPMessage(message);
            stopTcpClient();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void waitBeforeReconnect() {
        if (keepListening)
            sleep(RECONNECT_RETRY_DELAY_MS);
    }

    private void parseGameInfo(String message) {
        boolean gotGPSSemaphore = false;
        boolean gotPlayerSemaphores = false;
        boolean gotPlayerSettingsSemaphore = false;
        boolean gotGrenadePairingsSemaphore = false;
        try {
            JSONObject game = new JSONObject(message);
            if (game.has(TcpServer.JSON_GRENADE_PAIRINGS)) {
                Globals.getmGrenadePairingsSemaphore();
                gotGrenadePairingsSemaphore = true;
                Globals.ClearGrenadePairings(false);
                JSONArray grenadePairings = game.getJSONArray(TcpServer.JSON_GRENADE_PAIRINGS);
                for (int x = 0; x < grenadePairings.length(); x++) {
                    JSONObject grenadePairing = grenadePairings.getJSONObject(x);
                    int grenadeID = grenadePairing.getInt(TcpServer.JSON_PAIRED_GRENADE_ID);
                    if (!Globals.isValidGrenadeID(grenadeID)) {
                        Log.w(TAG, "Ignoring invalid grenade pairing ID " + grenadeID);
                        continue;
                    }
                    int playerID = grenadePairing.getInt(TcpServer.JSON_PLAYERID);
                    if (!Globals.isValidPlayerID(playerID) || playerID <= 0) {
                        Log.w(TAG, "Ignoring grenade pairing with invalid player ID " + playerID);
                        continue;
                    }
                    Globals.getInstance().mGrenadePairings[grenadeID] = playerID;
                }
                gotGrenadePairingsSemaphore = false;
                Globals.getInstance().mGrenadePairingsSemaphore.release();
                return;
            }
            if (game.has(TcpServer.JSON_GPSUPDATE)) {
                JSONArray updates = game.getJSONArray(TcpServer.JSON_GPSUPDATE);
                Globals.getmGPSDataSemaphore();
                gotGPSSemaphore = true;
                boolean fullUpdate = false;
                if (game.has(TcpServer.JSON_GPSFULLUPDATE)) {
                    Globals.getInstance().mGPSData.clear();
                    fullUpdate = true;
                }
                for (int x = 0; x < updates.length(); x++) {
                    JSONObject update = updates.getJSONObject(x);
                    int rawPlayerID = update.getInt(TcpServer.JSON_PLAYERID);
                    if (!Globals.isValidPlayerID(rawPlayerID)) {
                        Log.w(TAG, "Ignoring GPS data for invalid player ID " + rawPlayerID);
                        continue;
                    }
                    byte playerID = (byte) rawPlayerID;
                    if (playerID != Globals.getInstance().mPlayerID) {
                        double longitude = update.getDouble(TcpServer.JSON_GPSLONGITUDE);
                        double latitude = update.getDouble(TcpServer.JSON_GPSLATITUDE);
                        if (!Globals.isValidCoordinates(longitude, latitude)) {
                            Log.w(TAG, "Ignoring GPS data with invalid coordinates");
                            continue;
                        }
                        Globals.GPSData gps = Globals.getInstance().mGPSData.get(playerID);
                        if (gps == null) {
                            gps = new Globals.GPSData();
                            gps.longitude = longitude;
                            gps.latitude = latitude;
                            gps.team = update.getInt(TcpServer.JSON_TEAM);
                            Globals.getInstance().mGPSData.put(playerID, gps);
                        } else {
                            gps.longitude = longitude;
                            gps.latitude = latitude;
                        }
                        gps.hasUpdate = true; // Anything that the server sends us is considered an update
                    }
                }
                gotGPSSemaphore = false;
                Globals.getInstance().mGPSDataSemaphore.release();
                Intent intent = new Intent(NetMsg.NETMSG_GPSDATAUPDATE);
                intent.putExtra(NetMsg.INTENT_FULLUPDATE, fullUpdate);
                sendBroadcast(intent);
                return;
            }
            if (game.has(TcpServer.JSON_PLAYERDATA)) {
                Intent intent = new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE);
                intent.putExtra(NetMsg.INTENT_PLAYERDATA, message);
                sendBroadcast(intent);
                return;
            }
            if (game.has(TcpServer.JSON_PLAYERSETTINGS)) {
                JSONArray settings = game.getJSONArray(TcpServer.JSON_PLAYERSETTINGS);
                Globals.getmPlayerSettingsSemaphore();
                gotPlayerSettingsSemaphore = true;
                for (int x = 0; x < settings.length(); x++) {
                    JSONObject setting = settings.getJSONObject(x);
                    int rawPlayerID = setting.getInt(TcpServer.JSON_PLAYERID);
                    int health = setting.getInt(TcpServer.JSON_HEALTH);
                    int reloadShots = setting.getInt(TcpServer.JSON_RELOAD_SHOTS);
                    long reloadTime = setting.getLong(TcpServer.JSON_RELOAD_TIME);
                    boolean reloadOnEmpty = setting.getBoolean(TcpServer.JSON_RELOAD_ON_EMPTY);
                    long spawnTime = setting.getLong(TcpServer.JSON_SPAWN_TIME);
                    int damage = setting.getInt(TcpServer.JSON_DAMAGE);
                    boolean overrideLives = setting.has(TcpServer.JSON_LIVESLIMIT);
                    int lives = overrideLives ? setting.getInt(TcpServer.JSON_LIVESLIMIT) : 0;
                    boolean allowSingle = setting.getBoolean(TcpServer.JSON_SHOT_MODE_SINGLE);
                    boolean allowBurst = setting.getBoolean(TcpServer.JSON_SHOT_MODE_BURST3);
                    boolean allowAuto = setting.getBoolean(TcpServer.JSON_SHOT_MODE_AUTO);
                    int firingMode = setting.getInt(TcpServer.JSON_FIRING_MODE);
                    if (!Globals.isValidPlayerID(rawPlayerID)
                            || !Globals.isValidPlayerSettings(health, reloadShots, reloadTime, spawnTime,
                            damage, lives, allowSingle, allowBurst, allowAuto, firingMode)) {
                        Log.w(TAG, "Ignoring invalid player settings from server");
                        continue;
                    }
                    byte playerID = (byte) rawPlayerID;
                    Globals.PlayerSettings playerSettings = Globals.getInstance().mPlayerSettings.get(playerID);
                    if (playerSettings == null) {
                        playerSettings = new Globals.PlayerSettings();
                        Globals.getInstance().mPlayerSettings.put(playerID, playerSettings);
                    }
                    playerSettings.health = health;
                    playerSettings.shots = (byte) reloadShots;
                    playerSettings.reloadTime = reloadTime;
                    playerSettings.reloadOnEmpty = reloadOnEmpty;
                    playerSettings.spawnTime = spawnTime;
                    playerSettings.damage = damage;
                    playerSettings.overrideLives = overrideLives;
                    playerSettings.lives = lives;
                    playerSettings.allowShotModeSingle = allowSingle;
                    playerSettings.allowShotModeBurst3 = allowBurst;
                    playerSettings.allowShotModeAuto = allowAuto;
                    playerSettings.firingMode = firingMode;
                    //TODo checks
                  //  playerSettings.playerPreset = setting.getInt(TcpServer.JSON_PLAYER_PRESET);
                   // playerSettings.weaponPreset = setting.getInt(TcpServer.JSON_WEAPON_PRESET);

                    if (playerID == Globals.getInstance().mPlayerID) {
                        Globals.getInstance().mFullHealth = playerSettings.health;
                        Globals.getInstance().mFullReload = playerSettings.shots;
                        Globals.getInstance().mReloadTime = playerSettings.reloadTime;
                        Globals.getInstance().mReloadOnEmpty = playerSettings.reloadOnEmpty;
                        Globals.getInstance().mRespawnTime = playerSettings.spawnTime;
                        Globals.getInstance().mDamage = playerSettings.damage;
                        Globals.getInstance().mOverrideLives = playerSettings.overrideLives;
                        Globals.getInstance().mOverrideLivesVal = playerSettings.lives;
                        Globals.getInstance().mAllowSingleShotMode = playerSettings.allowShotModeSingle;
                        Globals.getInstance().mAllowBurst3ShotMode = playerSettings.allowShotModeBurst3;
                        Globals.getInstance().mAllowAutoShotMode = playerSettings.allowShotModeAuto;
                        Globals.getInstance().mCurrentFiringMode = playerSettings.firingMode;
                        //TODO checks
                     //   Globals.getInstance().mCurrentPlayerPreset = playerSettings.playerPreset;
                       // Globals.getInstance().mCurrentWeaponPreset = playerSettings.weaponPreset;
                    }
                }
                gotPlayerSettingsSemaphore = false;
                Globals.getInstance().mPlayerSettingsSemaphore.release();
                Globals.getInstance().mAllowPlayerSettings = game.getBoolean(TcpServer.JSON_ALLOWPLAYERSETTINGS);
                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
            }
            if (game.has(TcpServer.JSON_PLAYERS)) {
                Globals.getmTeamPlayerNameSemaphore();
                Globals.getmTeamIPMapSemaphore();
                Globals.getmIPTeamMapSemaphore();
                gotPlayerSemaphores = true;
                Globals.getInstance().mTeamIPMap.clear();
                Globals.getInstance().mIPTeamMap.clear();
                Globals.getInstance().mTeamPlayerNameMap.clear();
                Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_NONE;
                JSONArray players = game.getJSONArray(TcpServer.JSON_PLAYERS);
                for (int x = 0; x < players.length(); x++) {
                    JSONObject player = players.getJSONObject(x);
                    int rawPlayerID = player.getInt(TcpServer.JSON_PLAYERID);
                    if (!Globals.isValidPlayerID(rawPlayerID)) {
                        Log.w(TAG, "Ignoring player with invalid ID " + rawPlayerID);
                        continue;
                    }
                    byte playerID = (byte) rawPlayerID;
                    if (playerID != Globals.getInstance().mPlayerID) {
                        InetAddress playerIP = null;
                        String ip = player.getString(TcpServer.JSON_PLAYERIP);
                        if (ip.startsWith("/")) ip = ip.substring(1);
                        try {
                            playerIP = InetAddress.getByName(ip);
                        } catch (UnknownHostException e) {
                            e.printStackTrace();
                        }
                        if (playerIP != null) {
                            String playerName = player.getString(TcpServer.JSON_PLAYERNAME);
                            Globals.getInstance().mTeamIPMap.put(playerID, playerIP);
                            Globals.getInstance().mIPTeamMap.put(playerIP, playerID);
                            Globals.getInstance().mTeamPlayerNameMap.put(playerID, playerName);
                            Log.d(TAG, "player '" + playerName + "' (" + playerID + ") found at " + playerIP.toString());
                        }
                    }
                }
                gotPlayerSemaphores = false;
                Globals.getInstance().mTeamIPMapSemaphore.release();
                Globals.getInstance().mIPTeamMapSemaphore.release();
                Globals.getInstance().mTeamPlayerNameSemaphore.release();
                Log.d(TAG, "Found " + players.length() + " players");
                JSONObject limits = game.getJSONObject(TcpServer.JSON_LIMITS);
                if (limits.has(TcpServer.JSON_TIMELIMIT)) {
                    int timeLimit = limits.getInt(TcpServer.JSON_TIMELIMIT);
                    if (timeLimit > 0 && Globals.isValidGameLimit(timeLimit)) {
                        Globals.getInstance().mGameLimit |= Globals.GAME_LIMIT_TIME;
                        Globals.getInstance().mTimeLimit = timeLimit;
                    } else {
                        Log.w(TAG, "Ignoring invalid time limit from server: " + timeLimit);
                    }
                }
                if (limits.has(TcpServer.JSON_LIVESLIMIT)) {
                    int livesLimit = limits.getInt(TcpServer.JSON_LIVESLIMIT);
                    if (livesLimit > 0 && Globals.isValidGameLimit(livesLimit)) {
                        Globals.getInstance().mGameLimit |= Globals.GAME_LIMIT_LIVES;
                        Globals.getInstance().mLivesLimit = livesLimit;
                    } else {
                        Log.w(TAG, "Ignoring invalid lives limit from server: " + livesLimit);
                    }
                }
                if (limits.has(TcpServer.JSON_SCORELIMIT)) {
                    int scoreLimit = limits.getInt(TcpServer.JSON_SCORELIMIT);
                    if (scoreLimit > 0 && Globals.isValidGameLimit(scoreLimit)) {
                        Globals.getInstance().mGameLimit |= Globals.GAME_LIMIT_SCORE;
                        Globals.getInstance().mScoreLimit = scoreLimit;
                    } else {
                        Log.w(TAG, "Ignoring invalid score limit from server: " + scoreLimit);
                    }
                }
                int gameMode = game.getInt(TcpServer.JSON_GAMEMODE);
                if (Globals.isValidGameMode(gameMode)) {
                    Globals.getInstance().mGameMode = gameMode;
                } else {
                    Log.w(TAG, "Ignoring invalid game mode from server: " + gameMode);
                }
                Globals.getInstance().mUseGPS = game.has(TcpServer.JSON_USEGPS);
                if (Globals.getInstance().mUseGPS) {
                    int gpsMode = game.getInt(TcpServer.JSON_USEGPS);
                    if (Globals.isValidGPSMode(gpsMode) && gpsMode != Globals.GPS_DISABLED) {
                        Globals.getInstance().mGPSMode = gpsMode;
                    } else {
                        Log.w(TAG, "Ignoring invalid GPS mode from server: " + gpsMode);
                        Globals.getInstance().mUseGPS = false;
                    }
                }
                Globals.getInstance().mOnlyServerSettings = game.getBoolean(TcpServer.JSON_ONLY_SERVER_SETTINGS);
                Intent intent = new Intent(NetMsg.NETMSG_LISTPLAYERS);
                if (game.has(TcpServer.JSON_PLAYERGAMEUPDATE)) {
                    intent.putExtra(NetMsg.INTENT_HASGAMEUPDATE, true);
                    JSONObject playerGameUpdate = game.getJSONObject(TcpServer.JSON_PLAYERGAMEUPDATE);
                    intent.putExtra(NetMsg.INTENT_SCORE, playerGameUpdate.getInt(TcpServer.JSON_PLAYERPOINTS));
                    intent.putExtra(NetMsg.INTENT_ELIMINATIONS, playerGameUpdate.getInt(TcpServer.JSON_PLAYERELIMINATED));
                    if (playerGameUpdate.has(TcpServer.JSON_TEAMPOINTS))
                        intent.putExtra(NetMsg.INTENT_TEAMSCORE, playerGameUpdate.getInt(TcpServer.JSON_TEAMPOINTS));
                    if (playerGameUpdate.has(TcpServer.JSON_TIMEREMAINING)) {
                        long timeRemaining = playerGameUpdate.getLong(TcpServer.JSON_TIMEREMAINING);
                        long maxGameTimeSeconds = (long) Globals.MAX_GAME_LIMIT * 60 + Globals.MAX_RESPAWN_TIME_SECONDS;
                        if (timeRemaining >= 0 && timeRemaining <= maxGameTimeSeconds)
                            intent.putExtra(NetMsg.INTENT_TIMEREMAINING, timeRemaining);
                        else
                            Log.w(TAG, "Ignoring invalid remaining game time from server: " + timeRemaining);
                    }
                }
                mIsDedicatedServer = game.has(TcpServer.JSON_DEDICATED);
                if (mIsDedicatedServer) {
                    int gameState = game.getInt(TcpServer.JSON_GAMESTATE);
                    if (gameState >= Globals.GAME_STATE_NONE && gameState <= Globals.GAME_STATE_ELIMINATED)
                        intent.putExtra(NetMsg.INTENT_GAMESTATE, gameState);
                    else
                        Log.w(TAG, "Ignoring invalid game state from server: " + gameState);
                }
                sendBroadcast(intent);
            }
        } catch (JSONException | RuntimeException e) {
            e.printStackTrace();
            if (gotPlayerSemaphores) {
                Globals.getInstance().mTeamIPMapSemaphore.release();
                Globals.getInstance().mIPTeamMapSemaphore.release();
                Globals.getInstance().mTeamPlayerNameSemaphore.release();
            }
            if (gotGPSSemaphore) Globals.getInstance().mGPSDataSemaphore.release();
            if (gotPlayerSettingsSemaphore) Globals.getInstance().mPlayerSettingsSemaphore.release();
            if (gotGrenadePairingsSemaphore) Globals.getInstance().mGrenadePairingsSemaphore.release();
        }
    }

    public boolean isDedicatedServer() { return mIsDedicatedServer; }

    private final BroadcastReceiver mGPSUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null)
                return;
            Log.e(TAG, action);
            if (NetMsg.NETMSG_GPSLOCUPDATE.equals(action)) {
                JSONObject gpsUpdate = new JSONObject();
                try {
                    gpsUpdate.put(TcpServer.JSON_GPSLONGITUDE, intent.getDoubleExtra(NetMsg.INTENT_LONGITUDE, 0));
                    gpsUpdate.put(TcpServer.JSON_GPSLATITUDE, intent.getDoubleExtra(NetMsg.INTENT_LATITUDE, 0));
                    sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + gpsUpdate.toString());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
    };
}
