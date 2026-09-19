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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class TcpClient extends Service {
    private static final String TAG = "TCPClient";

    public static final String TCP_CLIENT_PONG = "pong";
    private static final int MAX_REJOIN_TRIES = 3;
    private static final int CONNECTION_TIMEOUT_MS = 1000;
    private static final int RECONNECT_RETRY_DELAY_MS = 1000;
    private static final int SHUTDOWN_FLUSH_TIMEOUT_MS = 1000;
    static final String EXTRA_TERMINAL_EVENT_ID = "com.simplecoil.simplecoil.TCP_TERMINAL_EVENT_ID";
    private static final AtomicLong terminalEventIds = new AtomicLong();

    private volatile boolean keepListening = false;
    private volatile boolean isListening = false;
    private volatile boolean mIsDedicatedServer = false;
    private boolean mDestroyed;
    private long mSessionGeneration;
    private boolean mReceiverRegistered;
    private Socket mActiveSocket;
    private Thread mClientThread;
    private Intent mPendingTerminalEvent;

    private volatile DataOutputStream out = null;
    private Queue<String> messageQueue = new ConcurrentLinkedQueue<>();
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
        mReceiverRegistered = true;
    }

    @Override
    public void onDestroy() {
        final Socket socket;
        final Thread clientThread;
        synchronized (this) {
            mDestroyed = true;
            mSessionGeneration++;
            keepListening = false;
            out = null;
            messageQueue = new ConcurrentLinkedQueue<>();
            mPendingTerminalEvent = null;
            socket = mActiveSocket;
            clientThread = mClientThread;
        }
        // Closing the socket also interrupts any sender blocked in a write.
        try { if (socket != null) socket.close(); } catch (IOException e) { /* ignored */ }
        if (clientThread != null)
            clientThread.interrupt();
        sendExecutor.shutdownNow();
        if (mReceiverRegistered) {
            unregisterReceiver(mGPSUpdateReceiver);
            mReceiverRegistered = false;
        }
        super.onDestroy();
    }

    public void sendTCPMessage(final String message) {
        sendTCPMessage(message, false);
    }

    public synchronized void sendTCPMessage(final String message, final boolean queueMessage) {
        if (mDestroyed)
            return;
        if (queueMessage) {
            // Record persistent events before scheduling work, so a reconnect
            // cannot drain the queue before an old writer reports its failure.
            messageQueue.offer(message);
            sendQueuedMessages();
            return;
        }
        final DataOutputStream writer = out;
        if (writer == null) {
            Log.d(TAG, "Writer is null");
            return;
        }
        if (sendExecutor.isShutdown())
            return;
        try {
            sendExecutor.execute(() -> {
                if (writer == out)
                    writeMessage(writer, message);
            });
        } catch (RejectedExecutionException e) {
            // The service can be torn down after the isShutdown() check above.
            Log.w(TAG, "TCP sender is shutting down", e);
        }
    }

    private synchronized void sendQueuedMessages() {
        final DataOutputStream writer = out;
        final Queue<String> pending = messageQueue;
        if (writer == null || sendExecutor.isShutdown())
            return;
        try {
            sendExecutor.execute(() -> {
                while (writer == out) {
                    String message = pending.peek();
                    if (message == null || !writeMessage(writer, message))
                        return;
                    pending.poll();
                }
            });
        } catch (RejectedExecutionException e) {
            // Nothing was removed from the queue; a later connection can retry.
            Log.w(TAG, "TCP sender is shutting down", e);
        }
    }

    private boolean writeMessage(DataOutputStream writer, String message) {
        try {
            writer.writeUTF(message);
            writer.flush();
            if (!message.equals(TCP_CLIENT_PONG))
                Log.i(TAG, "sent: " + message);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "TCP write failed", e);
            synchronized (this) {
                // A failed write may have left a partial frame on the wire. Do
                // not send later events (or events after a failed registration)
                // on that stream; the reader loop will reconnect instead.
                if (out == writer)
                    out = null;
            }
            return false;
        }
    }

    void startTcpClient() {
        synchronized (this) {
            if (mDestroyed || sendExecutor.isShutdown()) {
                Log.d(TAG, "Cannot start a destroyed TCP client");
                return;
            }
            if (keepListening) {
                Log.d(TAG, "Client is already listening");
                return;
            }
            if (isListening) {
                Log.e(TAG, "Please wait for client to stop listening");
                return;
            }
            final InetAddress serverAddress = Globals.getInstance().mServerIP;
            if (serverAddress == null) {
                Log.w(TAG, "Cannot start TCP client without a server address");
                return;
            }
            // An explicit start joins a new session. Only automatic reconnects
            // inside runTcpClientSession may replay events from the previous link.
            messageQueue = new ConcurrentLinkedQueue<>();
            mPendingTerminalEvent = null;
            mIsDedicatedServer = false;
            mSessionGeneration++;
            keepListening = true;
            isListening = true;
            mClientThread = new Thread(() -> runTcpClient(serverAddress), "SimpleCoil TCP client");
            mClientThread.start();
        }
    }

    private void runTcpClient(InetAddress serverAddress) {
        try {
            runTcpClientSession(serverAddress);
        } catch (RuntimeException e) {
            // Shutdown may interrupt acquisition of a shared state lock before
            // the socket loop is entered. Never leave the service marked running.
            if (keepListening) {
                Log.e(TAG, "TCP client stopped unexpectedly", e);
                finishServerSession(NetMsg.NETMSG_SERVERCANCEL);
            }
        } finally {
            synchronized (this) {
                keepListening = false;
                isListening = false;
                mClientThread = null;
            }
        }
    }

    private void runTcpClientSession(InetAddress serverAddress) {
        Globals.getmGPSDataSemaphore();
        try {
            synchronized (this) {
                if (!keepListening || mDestroyed)
                    return;
                if (Globals.getInstance().mGPSData == null)
                    Globals.getInstance().mGPSData = new HashMap<>();
                else
                    Globals.getInstance().mGPSData.clear();
            }
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
                synchronized (this) {
                    if (!keepListening || mDestroyed)
                        break;
                    mActiveSocket = s;
                }
                // Discovery may find another host while this session is reconnecting.
                // Pending events belong only to the server selected at explicit start.
                s.connect(new InetSocketAddress(serverAddress, TcpServer.TCP_SERVER_PORT), CONNECTION_TIMEOUT_MS);
                is = s.getInputStream();
                in = new DataInputStream(is);
                TcpMessageReader messageReader = new TcpMessageReader();
                os = s.getOutputStream();
                connectionOut = new DataOutputStream(new BufferedOutputStream(os));
                synchronized (this) {
                    if (!keepListening || mDestroyed)
                        break;
                    // Publish the writer and enqueue registration as one operation,
                    // before gameplay threads can enqueue events for this connection.
                    out = connectionOut;
                    sendPlayerInfo(rejoin);
                }
                wasConnected = true;
                if (rejoin)
                    sendBroadcast(new Intent(NetMsg.NETMSG_NETWORKCONNECTED));
                rejoin = true;
                int noReadCount = 0;
                retryCount = MAX_REJOIN_TRIES;
                while (keepListening && out == connectionOut) {
                    String message = messageReader.poll(in);
                    if (message != null) {
                        noReadCount = 0;
                        if (message.equals(TcpServer.TCP_SERVER_PING))
                            sendTCPMessage(TCP_CLIENT_PONG);
                        else {
                            Log.i(TAG, "received: '" + message + "'");
                            if (message.startsWith(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON)) {
                                message = message.substring(TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_JSON.length());
                                parseGameInfo(message);
                            } else if (message.startsWith(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG)) {
                                message = message.substring(TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_MESG.length());
                                if (message.startsWith(NetMsg.NETMSG_ELIMINATED)) {
                                    message = message.substring(NetMsg.NETMSG_ELIMINATED.length());
                                    final int playerID;
                                    try {
                                        playerID = Integer.parseInt(message);
                                    } catch (NumberFormatException e) {
                                        Log.w(TAG, "Ignoring malformed elimination player ID");
                                        continue;
                                    }
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
                                    finishServerSession(NetMsg.NETMSG_ENDGAME);
                                    break;
                                } else if (message.equals(NetMsg.NETMSG_STARTGAME)) {
                                    sendBroadcast(new Intent(NetMsg.NETMSG_STARTGAME));
                                } else if (message.equals(NetMsg.NETMSG_SERVERCANCEL)) {
                                    finishServerSession(NetMsg.NETMSG_SERVERCANCEL);
                                    break;
                                }
                            } else if (message.equals(NetMsg.NETMSG_VERSIONERROR)) {
                                finishServerSession(NetMsg.NETMSG_VERSIONERROR);
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
                if (!keepListening)
                    flushBeforeDisconnect(connectionOut);
                try { if (s != null) s.close(); } catch (Exception e) { /* ignored */ }
                try { if (is != null) is.close(); } catch (Exception e) { /* ignored */ }
                try { if (in != null) in.close(); } catch (Exception e) { /* ignored */ }
                try { if (os != null) os.close(); } catch (Exception e) { /* ignored */ }
                try { if (connectionOut != null) connectionOut.close(); } catch (Exception e) { /* ignored */ }
                synchronized (this) {
                    if (out == connectionOut)
                        out = null;
                    if (mActiveSocket == s)
                        mActiveSocket = null;
                }
            }
        }
        if (retryCount == 0 && keepListening) {
            finishServerSession(NetMsg.NETMSG_SERVERCANCEL);
        }
        Log.d(TAG, "Client stopping");
    }

    private void finishServerSession(String action) {
        final Intent notification;
        synchronized (this) {
            if (mDestroyed)
                return;
            // Terminal messages must stop reconnecting even while the activity
            // is paused and its broadcast receiver is not registered.
            keepListening = false;
            mSessionGeneration++;
            out = null;
            messageQueue = new ConcurrentLinkedQueue<>();
            notification = new Intent(action).putExtra(EXTRA_TERMINAL_EVENT_ID, terminalEventIds.incrementAndGet());
            mPendingTerminalEvent = notification;
        }
        sendBroadcast(new Intent(notification));
    }

    public Intent consumePendingTerminalEvent() {
        return consumePendingTerminalEvent(0);
    }

    public synchronized Intent consumePendingTerminalEvent(long eventId) {
        if (mPendingTerminalEvent == null || (eventId != 0
                && mPendingTerminalEvent.getLongExtra(EXTRA_TERMINAL_EVENT_ID, 0) != eventId))
            return null;
        Intent event = new Intent(mPendingTerminalEvent);
        event.removeExtra(EXTRA_TERMINAL_EVENT_ID);
        mPendingTerminalEvent = null;
        return event;
    }

    private void flushBeforeDisconnect(DataOutputStream writer) {
        synchronized (this) {
            // A rejected/ended session or destroyed service should close immediately.
            if (writer == null || writer != out || mDestroyed || sendExecutor.isShutdown())
                return;
        }
        Future<?> barrier = null;
        try {
            // This runs only on the reader thread. UI shutdown never waits for
            // networking, and the socket stays open for already queued messages.
            barrier = sendExecutor.submit(() -> { });
            barrier.get(SHUTDOWN_FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (barrier != null) barrier.cancel(false);
            Log.w(TAG, "Closing TCP connection after sender shutdown timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RejectedExecutionException e) {
            Log.w(TAG, "TCP sender stopped before pending writes completed", e);
        }
    }

    public void sendPlayerNameChange() {
        try {
            JSONObject playerInfo = new JSONObject();
            playerInfo.put(TcpServer.JSON_PLAYERID, Globals.getInstance().mPlayerID);
            playerInfo.put(TcpServer.JSON_PLAYERNAMECHANGE, Globals.getInstance().mPlayerName);
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + playerInfo.toString();
            sendTCPMessage(message);
            sendQueuedMessages();
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
            sendQueuedMessages();
        } catch (JSONException e) {
            e.printStackTrace();
        }
        // A disarm can happen while offline. Re-publish zero as well as paired
        // IDs on registration so reconnecting cannot resurrect stale ownership.
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
        final Socket connectingSocket;
        final Thread connectingThread;
        synchronized (this) {
            mSessionGeneration++;
            keepListening = false;
            // Established connections are drained by the reader before closing.
            // Startup/retry waits have no outgoing writer and can be interrupted now.
            connectingSocket = out == null ? mActiveSocket : null;
            connectingThread = out == null ? mClientThread : null;
        }
        try { if (connectingSocket != null) connectingSocket.close(); } catch (IOException e) { /* ignored */ }
        if (connectingThread != null)
            connectingThread.interrupt();
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            keepListening = false;
        }
    }

    private void waitBeforeReconnect() {
        if (keepListening)
            sleep(RECONNECT_RETRY_DELAY_MS);
    }

    private void parseGameInfo(String message) {
        final long generation;
        synchronized (this) {
            if (mDestroyed || (isListening && !keepListening))
                return;
            generation = mSessionGeneration;
        }
        try {
            JSONObject game = new JSONObject(message);
            if (game.has(TcpServer.JSON_GRENADE_PAIRINGS)) {
                int[] pairings = new int[Globals.MAX_GRENADE_IDS];
                Arrays.fill(pairings, Globals.INVALID_PLAYER_ID);
                JSONArray grenadePairings = game.getJSONArray(TcpServer.JSON_GRENADE_PAIRINGS);
                for (int x = 0; x < grenadePairings.length(); x++) {
                    JSONObject grenadePairing = grenadePairings.getJSONObject(x);
                    int grenadeID = grenadePairing.getInt(TcpServer.JSON_PAIRED_GRENADE_ID);
                    int playerID = grenadePairing.getInt(TcpServer.JSON_PLAYERID);
                    if (!Globals.isValidGrenadeID(grenadeID) || !Globals.isValidPlayerID(playerID) || playerID <= 0)
                        throw new JSONException("Invalid grenade pairing snapshot");
                    pairings[grenadeID] = playerID;
                }
                // Parse the complete snapshot before replacing any live state.
                Globals.getmGrenadePairingsSemaphore();
                try {
                    synchronized (this) {
                        if (!isCurrentSession(generation))
                            return;
                        System.arraycopy(pairings, 0, Globals.getInstance().mGrenadePairings, 0, pairings.length);
                    }
                } finally {
                    Globals.getInstance().mGrenadePairingsSemaphore.release();
                }
                return;
            }
            if (game.has(TcpServer.JSON_GPSUPDATE)) {
                JSONArray updates = game.getJSONArray(TcpServer.JSON_GPSUPDATE);
                Map<Byte, Globals.GPSData> locations = new HashMap<>();
                boolean fullUpdate = game.has(TcpServer.JSON_GPSFULLUPDATE)
                        && game.getBoolean(TcpServer.JSON_GPSFULLUPDATE);
                for (int x = 0; x < updates.length(); x++) {
                    JSONObject update = updates.getJSONObject(x);
                    int rawPlayerID = update.getInt(TcpServer.JSON_PLAYERID);
                    if (!Globals.isValidPlayerID(rawPlayerID) || rawPlayerID <= 0)
                        throw new JSONException("Invalid GPS player ID " + rawPlayerID);
                    byte playerID = (byte) rawPlayerID;
                    if (playerID != Globals.getInstance().mPlayerID) {
                        double longitude = update.getDouble(TcpServer.JSON_GPSLONGITUDE);
                        double latitude = update.getDouble(TcpServer.JSON_GPSLATITUDE);
                        if (!Globals.isValidCoordinates(longitude, latitude))
                            throw new JSONException("Invalid GPS coordinates");
                        Globals.GPSData gps = new Globals.GPSData();
                        gps.longitude = longitude;
                        gps.latitude = latitude;
                        // Refresh team membership too: the host may have changed game modes.
                        gps.team = update.getInt(TcpServer.JSON_TEAM);
                        gps.hasUpdate = true; // Anything that the server sends us is considered an update
                        locations.put(playerID, gps);
                    }
                }
                Globals.getmGPSDataSemaphore();
                try {
                    synchronized (this) {
                        if (!isCurrentSession(generation))
                            return;
                        if (fullUpdate)
                            Globals.getInstance().mGPSData.clear();
                        Globals.getInstance().mGPSData.putAll(locations);
                    }
                } finally {
                    Globals.getInstance().mGPSDataSemaphore.release();
                }
                Intent intent = new Intent(NetMsg.NETMSG_GPSDATAUPDATE);
                intent.putExtra(NetMsg.INTENT_FULLUPDATE, fullUpdate);
                broadcastIfCurrentSession(generation, intent);
                return;
            }
            if (game.has(TcpServer.JSON_PLAYERDATA)) {
                Intent intent = new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE);
                intent.putExtra(NetMsg.INTENT_PLAYERDATA, message);
                broadcastIfCurrentSession(generation, intent);
                return;
            }
            Map<Byte, Globals.PlayerSettings> settingsUpdate = null;
            boolean allowPlayerSettings = false;
            if (game.has(TcpServer.JSON_PLAYERSETTINGS)) {
                JSONArray settings = game.getJSONArray(TcpServer.JSON_PLAYERSETTINGS);
                settingsUpdate = new HashMap<>();
                allowPlayerSettings = game.getBoolean(TcpServer.JSON_ALLOWPLAYERSETTINGS);
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
                    if (!Globals.isValidPlayerID(rawPlayerID) || rawPlayerID <= 0
                            || !Globals.isValidPlayerSettings(health, reloadShots, reloadTime, spawnTime,
                            damage, lives, allowSingle, allowBurst, allowAuto, firingMode)) {
                        throw new JSONException("Invalid player settings from server");
                    }
                    byte playerID = (byte) rawPlayerID;
                    if (settingsUpdate.containsKey(playerID))
                        throw new JSONException("Conflicting player settings in snapshot");
                    Globals.PlayerSettings playerSettings = new Globals.PlayerSettings();
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
                    settingsUpdate.put(playerID, playerSettings);
                }
            }
            Intent rosterIntent = null;
            if (game.has(TcpServer.JSON_PLAYERS)) {
                Map<Byte, InetAddress> teamIPs = new HashMap<>();
                Map<InetAddress, Byte> ipTeams = new HashMap<>();
                Map<Byte, String> playerNames = new HashMap<>();
                JSONArray players = game.getJSONArray(TcpServer.JSON_PLAYERS);
                for (int x = 0; x < players.length(); x++) {
                    JSONObject player = players.getJSONObject(x);
                    int rawPlayerID = player.getInt(TcpServer.JSON_PLAYERID);
                    if (!Globals.isValidPlayerID(rawPlayerID) || rawPlayerID <= 0)
                        throw new JSONException("Invalid roster player ID " + rawPlayerID);
                    byte playerID = (byte) rawPlayerID;
                    if (playerID != Globals.getInstance().mPlayerID) {
                        InetAddress playerIP;
                        String ip = player.getString(TcpServer.JSON_PLAYERIP);
                        if (ip.startsWith("/")) ip = ip.substring(1);
                        if (ip.trim().isEmpty())
                            throw new JSONException("Empty player address");
                        try {
                            // Resolve before taking shared map locks. DNS can block.
                            playerIP = InetAddress.getByName(ip);
                        } catch (UnknownHostException e) {
                            throw new JSONException("Invalid player address " + ip);
                        }
                        if (teamIPs.containsKey(playerID) || ipTeams.containsKey(playerIP))
                            throw new JSONException("Conflicting player IDs or addresses in roster");
                        String playerName = player.getString(TcpServer.JSON_PLAYERNAME);
                        teamIPs.put(playerID, playerIP);
                        ipTeams.put(playerIP, playerID);
                        playerNames.put(playerID, playerName);
                    }
                }
                // Validate all metadata before replacing the live roster or limits.
                Globals globals = Globals.getInstance();
                int gameLimit = Globals.GAME_LIMIT_NONE;
                int timeLimit = globals.mTimeLimit;
                int livesLimit = globals.mLivesLimit;
                int scoreLimit = globals.mScoreLimit;
                JSONObject limits = game.getJSONObject(TcpServer.JSON_LIMITS);
                if (limits.has(TcpServer.JSON_TIMELIMIT)) {
                    int value = limits.getInt(TcpServer.JSON_TIMELIMIT);
                    if (value > 0 && Globals.isValidGameLimit(value)) {
                        gameLimit |= Globals.GAME_LIMIT_TIME;
                        timeLimit = value;
                    } else {
                        Log.w(TAG, "Ignoring invalid time limit from server: " + value);
                    }
                }
                if (limits.has(TcpServer.JSON_LIVESLIMIT)) {
                    int value = limits.getInt(TcpServer.JSON_LIVESLIMIT);
                    if (value > 0 && Globals.isValidGameLimit(value)) {
                        gameLimit |= Globals.GAME_LIMIT_LIVES;
                        livesLimit = value;
                    } else {
                        Log.w(TAG, "Ignoring invalid lives limit from server: " + value);
                    }
                }
                if (limits.has(TcpServer.JSON_SCORELIMIT)) {
                    int value = limits.getInt(TcpServer.JSON_SCORELIMIT);
                    if (value > 0 && Globals.isValidGameLimit(value)) {
                        gameLimit |= Globals.GAME_LIMIT_SCORE;
                        scoreLimit = value;
                    } else {
                        Log.w(TAG, "Ignoring invalid score limit from server: " + value);
                    }
                }
                int gameMode = game.getInt(TcpServer.JSON_GAMEMODE);
                if (!Globals.isValidGameMode(gameMode)) {
                    Log.w(TAG, "Ignoring invalid game mode from server: " + gameMode);
                    gameMode = globals.mGameMode;
                }
                boolean useGPS = game.has(TcpServer.JSON_USEGPS);
                int gpsMode = globals.mGPSMode;
                if (useGPS) {
                    int value = game.getInt(TcpServer.JSON_USEGPS);
                    if (Globals.isValidGPSMode(value) && value != Globals.GPS_DISABLED) {
                        gpsMode = value;
                    } else {
                        Log.w(TAG, "Ignoring invalid GPS mode from server: " + value);
                        useGPS = false;
                    }
                }
                boolean onlyServerSettings = game.getBoolean(TcpServer.JSON_ONLY_SERVER_SETTINGS);
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
                boolean dedicatedServer = game.has(TcpServer.JSON_DEDICATED) && game.getBoolean(TcpServer.JSON_DEDICATED);
                if (dedicatedServer) {
                    int gameState = game.getInt(TcpServer.JSON_GAMESTATE);
                    if (gameState >= Globals.GAME_STATE_NONE && gameState <= Globals.GAME_STATE_ELIMINATED)
                        intent.putExtra(NetMsg.INTENT_GAMESTATE, gameState);
                    else
                        Log.w(TAG, "Ignoring invalid game state from server: " + gameState);
                }
                Globals.getmTeamPlayerNameSemaphore();
                try {
                    Globals.getmTeamIPMapSemaphore();
                    try {
                        Globals.getmIPTeamMapSemaphore();
                        try {
                            // Settings can share this message with the roster. Validate
                            // both, and acquire every needed lock, before changing either.
                            if (settingsUpdate != null)
                                Globals.getmPlayerSettingsSemaphore();
                            try {
                                // Never wait for a shared-state lock while holding the
                                // service monitor: stop/destroy must remain responsive.
                                synchronized (this) {
                                    if (!isCurrentSession(generation))
                                        return;
                                    if (settingsUpdate != null)
                                        applyPlayerSettingsLocked(settingsUpdate, allowPlayerSettings);
                                    globals.mTeamIPMap.clear();
                                    globals.mTeamIPMap.putAll(teamIPs);
                                    globals.mIPTeamMap.clear();
                                    globals.mIPTeamMap.putAll(ipTeams);
                                    globals.mTeamPlayerNameMap.clear();
                                    globals.mTeamPlayerNameMap.putAll(playerNames);
                                    globals.mGameLimit = gameLimit;
                                    globals.mTimeLimit = timeLimit;
                                    globals.mLivesLimit = livesLimit;
                                    globals.mScoreLimit = scoreLimit;
                                    globals.mGameMode = gameMode;
                                    globals.mUseGPS = useGPS;
                                    globals.mGPSMode = gpsMode;
                                    globals.mOnlyServerSettings = onlyServerSettings;
                                    mIsDedicatedServer = dedicatedServer;
                                }
                            } finally {
                                if (settingsUpdate != null)
                                    globals.mPlayerSettingsSemaphore.release();
                            }
                        } finally {
                            globals.mIPTeamMapSemaphore.release();
                        }
                    } finally {
                        globals.mTeamIPMapSemaphore.release();
                    }
                } finally {
                    globals.mTeamPlayerNameSemaphore.release();
                }
                Log.d(TAG, "Found " + players.length() + " players");
                rosterIntent = intent;
            } else if (settingsUpdate != null) {
                Globals.getmPlayerSettingsSemaphore();
                try {
                    synchronized (this) {
                        if (!isCurrentSession(generation))
                            return;
                        applyPlayerSettingsLocked(settingsUpdate, allowPlayerSettings);
                    }
                } finally {
                    Globals.getInstance().mPlayerSettingsSemaphore.release();
                }
            }
            if (settingsUpdate != null)
                broadcastIfCurrentSession(generation, new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
            if (rosterIntent != null)
                broadcastIfCurrentSession(generation, rosterIntent);
        } catch (JSONException | RuntimeException e) {
            e.printStackTrace();
        }
    }

    // Caller holds both the service monitor and the player-settings semaphore.
    private void applyPlayerSettingsLocked(Map<Byte, Globals.PlayerSettings> settings, boolean allowPlayerSettings) {
        Globals globals = Globals.getInstance();
        globals.mPlayerSettings.putAll(settings);
        Globals.PlayerSettings local = settings.get(globals.mPlayerID);
        if (local != null) {
            globals.mFullHealth = local.health;
            globals.mFullReload = local.shots;
            globals.mReloadTime = local.reloadTime;
            globals.mReloadOnEmpty = local.reloadOnEmpty;
            globals.mRespawnTime = local.spawnTime;
            globals.mDamage = local.damage;
            globals.mOverrideLives = local.overrideLives;
            globals.mOverrideLivesVal = local.lives;
            globals.mAllowSingleShotMode = local.allowShotModeSingle;
            globals.mAllowBurst3ShotMode = local.allowShotModeBurst3;
            globals.mAllowAutoShotMode = local.allowShotModeAuto;
            globals.mCurrentFiringMode = local.firingMode;
        }
        globals.mAllowPlayerSettings = allowPlayerSettings;
    }

    // Caller holds the service monitor. Parsing or DNS can outlive a session;
    // recheck only after acquiring shared-state locks, immediately before commit.
    private boolean isCurrentSession(long generation) {
        return !mDestroyed && generation == mSessionGeneration;
    }

    private synchronized void broadcastIfCurrentSession(long generation, Intent intent) {
        if (isCurrentSession(generation))
            sendBroadcast(intent);
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
                double longitude = intent.getDoubleExtra(NetMsg.INTENT_LONGITUDE, Double.NaN);
                double latitude = intent.getDoubleExtra(NetMsg.INTENT_LATITUDE, Double.NaN);
                if (!Globals.isValidCoordinates(longitude, latitude)) {
                    Log.w(TAG, "Ignoring local GPS update without a usable fix");
                    return;
                }
                JSONObject gpsUpdate = new JSONObject();
                try {
                    gpsUpdate.put(TcpServer.JSON_GPSLONGITUDE, longitude);
                    gpsUpdate.put(TcpServer.JSON_GPSLATITUDE, latitude);
                    sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + gpsUpdate.toString());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
    };
}
