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
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedOutputStream;
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
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;

public class TcpServer extends Service {
    private static final String TAG = "TCPServer";

    public static final int SEND_ALL = -100;
    // Rejoin feedback is useful, but a disconnected player must not retain an
    // unlimited round's worth of game events in the host process.
    static final int MAX_QUEUED_CLIENT_EVENTS = 64;

    public static final int TCP_SERVER_PORT = 17510;
    // Amount of time to sleep before checking if data is available. Lower is more responsive but may gobble up more CPU time
    public static final int TCP_READ_WAIT_MS = 200;
    public static final int TCP_DEDICATED_READ_WAIT_MS = 100;
    static final int CLOCK_READ_WAIT_MS = 10;
    // A valid NTP sample is capped at 500 ms, so one second is ample for the
    // initial five-exchange burst.  Do not hold every socket in a 10 ms polling
    // loop for the former five-second round-trip timeout after a single probe.
    private static final long CLOCK_SYNC_BURST_WINDOW_MS = 1000;
    // Every network snapshot is generated on a separate task so the caller
    // never blocks on a slow socket. Bound queued tasks to prevent a chatty
    // client from exhausting the dedicated host's threads while writes stall.
    static final int MAX_PENDING_CLIENT_TASKS = 32;
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
    public static final String JSON_TOURNAMENT_MODE = "tournamentmode";
    public static final String JSON_PAIRED_GRENADE_ID = "pairedgrenadeID";
    public static final String JSON_GRENADE_PAIRINGS = "grenadepairings";

    public static final String JSON_GPSLONGITUDE = "gpslongitude";
    public static final String JSON_GPSLATITUDE = "gpslatitude";
    public static final String JSON_GPSUPDATE = "gpsupdate";
    public static final String JSON_GPSFULLUPDATE = "gpsfullupdate";

    public static final String JSON_PLAYERDATA = "playerdata";
    public static final String JSON_PLAYERPOINTS = "points";
    public static final String JSON_PLAYERELIMINATED = "eliminated";
    // Dedicated hosts may receive lightweight combat telemetry from phones. It is
    // separate from authoritative ELIMINATED messages: shots and hits are display
    // statistics, while kills remain validated by the victim's report.
    public static final String JSON_PLAYER_SHOTS = "shots";
    public static final String JSON_PLAYER_HITS = "hits";
    public static final String JSON_TELEMETRY = "telemetry";
    public static final String JSON_TELEMETRY_COUNT = "count";
    public static final String JSON_TELEMETRY_ATTACKER = "attacker";
    public static final String JSON_TELEMETRY_SHOT = "shot";
    public static final String JSON_TELEMETRY_HIT = "hit";
    public static final String JSON_TEAMPOINTS = "teampoints";
    public static final String JSON_TIMEREMAINING = "timeremaining";
    static final String JSON_CLOCK_REQUEST = "clocksync";
    static final String JSON_CLOCK_RECEIVE = "clockreceive";
    static final String JSON_CLOCK_SEND = "clocksend";
    static final String JSON_CLOCK_READY = "clockready";
    static final String JSON_GAMESTART = "gamestart";
    static final String JSON_GAMEDURATION = "gameduration";
    static final String JSON_ROUND_ID = "roundid";
    static final String JSON_ROUND_TOKEN = "roundtoken";
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
    // True only after this instance has bound its listening socket and reset the
    // shared lobby state for the current server session.
    private boolean mTcpServerReady;
    // Guarded by mServerStateLock from scheduling through cleanup completion.
    private boolean mEndingGame;
    private boolean mStartingGame;
    // Retained until round cleanup, including the gap before the UI receives STARTGAME.
    private boolean mStartAnnounced;
    private long mScheduledStart = -1;
    private long mScheduledDuration;
    private long mRoundSequence;
    // Unlike the sequence, this nonce remains unique when a new peer host is
    // created for the next lobby. It protects UDP messages that can outlive a
    // closed socket or a prior game session.
    private String mRoundToken;
    private volatile long mClockSamplingUntil;
    private ServerSocket mListenSocket;
    private volatile Thread mServerThread = null;
    private volatile Thread mClientThread = null;
    private final Semaphore mClientDataSemaphore = new Semaphore(1);
    private final Semaphore mClientTaskSlots = new Semaphore(MAX_PENDING_CLIENT_TASKS);
    // Guarded by mServerStateLock, including registration before Thread.start().
    private final Set<Thread> mClientTasks = new HashSet<>();
    // Cancellation gets a bounded opportunity to notify clients before sockets
    // are closed. Both fields are guarded by mServerStateLock.
    private Thread mCancellationThread;
    private Runnable mCancellationTimeout;
    private final Handler mShutdownHandler = new Handler(Looper.getMainLooper());
    private static final long CANCEL_FLUSH_TIMEOUT_MS = 1000;
    private volatile Map<Integer, ClientData> mClientData = null;
    // Explicitly leaving must not reset a player's score or spent lives in this round.
    private final Map<Byte, ScoreData> mDepartedScores = new ConcurrentHashMap<>();
    private volatile boolean mIsDedicated = false;
    private boolean mGPSRunning = false;

    // GPS snapshots are JSON-heavy and can fan out to every player.  Keep that work away from
    // the activity/main looper so a busy dedicated host remains responsive to its UI.
    Handler mGPSHandler;
    private HandlerThread mGPSWorkerThread;
    Runnable mGPSRunnable = null;
    // A dedicated dashboard benefits from sub-second position forwarding. The
    // publisher remains change-driven, so stationary players do not create a
    // full roster frame every quarter second.
    private static final long GPS_UPDATE_INTERVAL = 250;
    private static final int SEND_ALL_GPS_INTERVAL = 20; // We will send all GPS data regardless of whether there was a GPS change every xx intervals
    private volatile int mGPSIntervalCount = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A server cannot safely resume after its owning activity and lobby state
        // have gone away.
        return START_NOT_STICKY;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        synchronized (mServerStateLock) {
            mDestroyed = true;
            stopGPSDataLocked();
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

    /** Immutable GPS data copied while the shared location table is locked. */
    private static final class GPSUpdate {
        final byte playerID;
        final int team;
        final double longitude;
        final double latitude;

        GPSUpdate(byte playerID, int team, double longitude, double latitude) {
            this.playerID = playerID;
            this.team = team;
            this.longitude = longitude;
            this.latitude = latitude;
        }
    }

    /** A player whom the dedicated host can manually return to the round. */
    public static final class RespawnCandidate {
        public final byte playerID;
        // Zero denotes free-for-all, where a team label would be misleading.
        public final int team;

        private RespawnCandidate(byte playerID, int team) {
            this.playerID = playerID;
            this.team = team;
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
            return keepListening && !mDestroyed && !Thread.currentThread().isInterrupted()
                    && (mCancellationThread == null || mCancellationThread == Thread.currentThread());
        }
    }

    private enum RoundTask { NONE, START, END, CANCEL }

    private boolean runClientTask(Runnable action) {
        return runClientTask(action, RoundTask.NONE);
    }

    private boolean runClientTask(Runnable action, RoundTask roundTask) {
        final boolean endsRound = roundTask == RoundTask.END;
        final boolean startsRound = roundTask == RoundTask.START;
        final boolean cancelsServer = roundTask == RoundTask.CANCEL;
        synchronized (mServerStateLock) {
            if (!keepListening || mDestroyed || mCancellationThread != null || (endsRound && mEndingGame))
                return false;
            // An end or cancellation is the bounded escape hatch for all other
            // work. It must still run when ordinary snapshot slots are full.
            final boolean usesTaskSlot = mClientTaskSlots.tryAcquire();
            if (!usesTaskSlot && !endsRound && !cancelsServer) {
                Log.w(TAG, "Dropping stale TCP send because the task backlog is full");
                return false;
            }
            if (endsRound)
                mEndingGame = true;
            if (startsRound)
                mStartingGame = true;
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
                    if (cancelsServer && acquired) {
                        // Cleanup needs exclusive roster access. If cancellation
                        // timed out before it acquired that access, the timeout
                        // has already closed the session and this task must exit
                        // instead of waiting forever behind the stalled owner.
                        boolean interrupted = Thread.interrupted();
                        try {
                            clearCancelledSessionState();
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Failed to clear cancelled TCP session", e);
                        } finally {
                            if (interrupted)
                                Thread.currentThread().interrupt();
                        }
                        stopTcpServer(Thread.currentThread());
                    }
                    if (acquired)
                        mClientDataSemaphore.release();
                    if (usesTaskSlot)
                        mClientTaskSlots.release();
                    synchronized (mServerStateLock) {
                        // Cancellation while waiting for the lock must also
                        // release this reservation so a later round can start.
                        if (endsRound)
                            mEndingGame = false;
                        if (startsRound)
                            mStartingGame = false;
                        if (cancelsServer && mCancellationThread == Thread.currentThread()) {
                            if (mCancellationTimeout != null)
                                mShutdownHandler.removeCallbacks(mCancellationTimeout);
                            mCancellationTimeout = null;
                            mCancellationThread = null;
                        }
                        mClientTasks.remove(Thread.currentThread());
                    }
                }
            }, "SimpleCoil TCP send");
            mClientTasks.add(task);
            if (cancelsServer) {
                mCancellationThread = task;
                // A stalled writer or held client lock must not keep shutdown
                // alive forever. Identity-check the timeout against this task.
                mCancellationTimeout = () -> stopTcpServer(task);
                mShutdownHandler.postDelayed(mCancellationTimeout, CANCEL_FLUSH_TIMEOUT_MS);
            }
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

    private boolean sendTCPMessageID(final String message, final byte playerID, final boolean queueMessage) {
        final List<ClientRecipient> recipients = getClientRecipients();
        if (recipients.isEmpty())
            return false;
        return runClientTask(() -> {
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
                if (!recipient.client.clockSynchronized)
                    return false;
                hasPlayers = true;
            }
        }
        if (!hasPlayers)
            return false;
        synchronized (mServerStateLock) {
            if (!keepListening || mDestroyed || mEndingGame || mCancellationThread != null
                    || Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                return false;
            // Repeated clicks and remote requests share the pending start instead
            // of scheduling duplicate sends or resetting a confirmed countdown.
            if (mStartingGame || mStartAnnounced)
                return true;
            return runClientTask(() -> {
                // Send to clients before confirming the start locally. A queued
                // end takes precedence over a start that has not been delivered.
                Set<ClientData> startPlayers = new HashSet<>();
                for (ClientRecipient recipient : recipients) {
                    if (recipient.canStartGame())
                        startPlayers.add(recipient.client);
                }
                // A join, registration, or replacement may have completed while
                // this start waited for the client lock. Do not start only the
                // old recipients and leave another connected player behind.
                // The host can retry against the new roster; registration stays
                // locked out from this check through delivery of the start.
                for (ClientRecipient recipient : getClientRecipients()) {
                    if (recipient.canStartGame()) {
                        if (!startPlayers.contains(recipient.client)) {
                            Log.d(TAG, "Cancelling queued start after the player roster changed");
                            return;
                        }
                        if (!recipient.client.clockSynchronized)
                            return;
                    }
                }
                final long startAt;
                final long duration;
                final long roundID;
                final String roundToken;
                synchronized (mServerStateLock) {
                    if (!isClientTaskActive() || mEndingGame
                            || Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                        return;
                    // A shared round start always leaves enough time for the
                    // complete spoken 10-to-0 countdown, even if an operator
                    // configured a shorter per-player respawn delay.  Longer
                    // configured delays are still honored.
                    long countdown = Math.max(Globals.RESPAWN_TIME_SECONDS,
                            Math.max(Globals.MIN_RESPAWN_TIME_SECONDS,
                                    Math.min(Globals.MAX_RESPAWN_TIME_SECONDS,
                                            Globals.getInstance().mRespawnTime)));
                    startAt = SystemClock.elapsedRealtime() + countdown * 1000;
                    duration = (Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0
                            ? Math.max(0, Math.min(Globals.MAX_GAME_LIMIT, Globals.getInstance().mTimeLimit)) * 60000L : 0;
                    roundID = ++mRoundSequence;
                    roundToken = createRoundToken();
                    mRoundToken = roundToken;
                }
                String message = TCPMESSAGE_PREFIX + TCPPREFIX_JSON
                        + createStartInfo(roundID, startAt, duration, roundToken);
                boolean delivered = false;
                for (ClientRecipient recipient : recipients) {
                    synchronized (mServerStateLock) {
                        if (!isClientTaskActive() || mEndingGame
                                || Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                            return;
                    }
                    if (recipient.canStartGame())
                        delivered = recipient.client.sendTCPMessage(message) || delivered;
                }
                if (!delivered)
                    return;
                synchronized (mServerStateLock) {
                    if (!isClientTaskActive() || mEndingGame
                            || Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                        return;
                    mScheduledStart = startAt;
                    mScheduledDuration = duration;
                    mStartAnnounced = true;
                    sendBroadcast(getScheduledGameStart());
                    if (!mIsDedicated) {
                        keepListening = false;
                        mTcpServerReady = false;
                    }
                }
            }, RoundTask.START);
        }
    }

    public boolean arePlayerClocksSynchronized() {
        for (ClientRecipient recipient : getClientRecipients()) {
            if (recipient.canStartGame() && !recipient.client.clockSynchronized)
                return false;
        }
        return true;
    }

    private boolean isDedicatedRespawnRoundRunning() {
        synchronized (mServerStateLock) {
            return mIsDedicated && keepListening && !mDestroyed && !mEndingGame
                    && Globals.getInstance().mGameState == Globals.GAME_STATE_RUNNING
                    && (mScheduledStart < 0 || SystemClock.elapsedRealtime() >= mScheduledStart);
        }
    }

    private boolean clearAwaitingRespawn(ClientData client) {
        synchronized (client) {
            if (!client.awaitingRespawn)
                return false;
            client.awaitingRespawn = false;
            return true;
        }
    }

    /**
     * Returns the current, connected players whose valid elimination has not
     * yet been resolved. The host UI receives a snapshot so it never holds a
     * socket or roster lock while displaying its dialog.
     */
    public List<RespawnCandidate> getGameMasterRespawnCandidates() {
        List<RespawnCandidate> candidates = new ArrayList<>();
        if (!isDedicatedRespawnRoundRunning())
            return candidates;
        Globals globals = Globals.getInstance();
        for (ClientRecipient recipient : getClientRecipients()) {
            if (!recipient.canStartGame())
                continue;
            boolean awaiting;
            synchronized (recipient.client) {
                awaiting = recipient.client.awaitingRespawn;
            }
            if (awaiting) {
                int team = globals.mGameMode == Globals.GAME_MODE_FFA ? 0
                        : globals.calcNetworkTeam(recipient.playerID);
                candidates.add(new RespawnCandidate(recipient.playerID, team));
            }
        }
        return candidates;
    }

    /**
     * Grants one selected player a respawn. The boolean result lets the host
     * UI handle a player who revived or disconnected while its dialog was open.
     */
    public boolean grantGameMasterRespawn(byte playerID) {
        if (!Globals.isValidPlayerID(playerID) || playerID <= 0 || !isDedicatedRespawnRoundRunning())
            return false;
        for (ClientRecipient recipient : getClientRecipients()) {
            if (recipient.playerID != playerID || !recipient.canStartGame())
                continue;
            if (!clearAwaitingRespawn(recipient.client))
                return false;
            if (!sendTCPMessageID(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_RESPAWNGRANTED,
                    playerID, true)) {
                // Do not mark a player alive if the bounded sender could not
                // accept their grant. They remain available to try again.
                synchronized (recipient.client) {
                    if (recipient.isCurrent())
                        recipient.client.awaitingRespawn = true;
                }
                return false;
            }
            sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
            return true;
        }
        return false;
    }

    Intent getScheduledGameStart() {
        synchronized (mServerStateLock) {
            if (mDestroyed || !mStartAnnounced || mScheduledStart < 0)
                return null;
            String roundToken = getRoundTokenLocked();
            return new Intent(NetMsg.NETMSG_STARTGAME)
                    .putExtra(NetMsg.INTENT_START_AT, mScheduledStart)
                    .putExtra(NetMsg.INTENT_END_AT, mScheduledDuration == 0 ? 0 : mScheduledStart + mScheduledDuration)
                    .putExtra(NetMsg.INTENT_ROUND_ID, mRoundSequence)
                    .putExtra(NetMsg.INTENT_ROUND_TOKEN, roundToken)
                    .putExtra(NetMsg.INTENT_PEER_GAME, !mIsDedicated);
        }
    }

    void clearScheduledStart() {
        synchronized (mServerStateLock) {
            if (Globals.getInstance().mGameState == Globals.GAME_STATE_NONE) {
                mStartAnnounced = false;
                mScheduledStart = -1;
                mScheduledDuration = 0;
                mRoundToken = null;
            }
        }
    }

    private boolean hasRoundStarted() {
        synchronized (mServerStateLock) {
            return mScheduledStart < 0 || SystemClock.elapsedRealtime() >= mScheduledStart;
        }
    }

    static String createRoundToken() {
        return UUID.randomUUID().toString();
    }

    static boolean isValidRoundToken(String token) {
        if (token == null || token.length() != 36)
            return false;
        for (int index = 0; index < token.length(); index++) {
            if (index == 8 || index == 13 || index == 18 || index == 23) {
                if (token.charAt(index) != '-')
                    return false;
            } else if (Character.digit(token.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private String getRoundTokenLocked() {
        if (!isValidRoundToken(mRoundToken))
            mRoundToken = createRoundToken();
        return mRoundToken;
    }

    static JSONObject createStartInfo(long roundID, long startAt, long duration) {
        return createStartInfo(roundID, startAt, duration, createRoundToken());
    }

    static JSONObject createStartInfo(long roundID, long startAt, long duration, String roundToken) {
        if (!isValidRoundToken(roundToken))
            throw new IllegalArgumentException("Invalid round token");
        try {
            return new JSONObject().put(JSON_ROUND_ID, roundID).put(JSON_ROUND_TOKEN, roundToken)
                    .put(JSON_GAMESTART, startAt).put(JSON_GAMEDURATION, duration);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid game start", e);
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
            if (!isClientTaskActive())
                return;
            clearSharedRosterState();
            synchronized (mServerStateLock) {
                if (isClientTaskActive()) {
                    mStartAnnounced = false;
                    mScheduledStart = -1;
                    mScheduledDuration = 0;
                    mRoundToken = null;
                    sendBroadcast(new Intent(NetMsg.NETMSG_ENDGAME));
                }
            }
        }, RoundTask.END);
    }

    private void clearCancelledSessionState() {
        if (mClientData != null) {
            for (ClientData client : mClientData.values())
                client.close();
            mClientData.clear();
        }
        mDepartedScores.clear();
        clearSharedRosterState();
    }

    private void clearSharedRosterState() {
        Globals globals = Globals.getInstance();
        Globals.getmGPSDataSemaphore();
        try {
            if (globals.mGPSData == null)
                globals.mGPSData = new HashMap<>();
            else
                globals.mGPSData.clear();
        } finally {
            globals.mGPSDataSemaphore.release();
        }
        Globals.getmTeamPlayerNameSemaphore();
        try {
            globals.mTeamPlayerNameMap.clear();
        } finally {
            globals.mTeamPlayerNameSemaphore.release();
        }
        Globals.getmTeamIPMapSemaphore();
        try {
            globals.mTeamIPMap.clear();
        } finally {
            globals.mTeamIPMapSemaphore.release();
        }
        Globals.getmIPTeamMapSemaphore();
        try {
            globals.mIPTeamMap.clear();
        } finally {
            globals.mIPTeamMapSemaphore.release();
        }
        // A player's physical grenade pairing remains valid across rounds, but the
        // server's ownership table must not leak into the next round.
        Globals.getmGrenadePairingsSemaphore();
        try {
            Globals.ClearGrenadePairings(false);
        } finally {
            globals.mGrenadePairingsSemaphore.release();
        }
    }

    // Player-specific settings are valid for later rounds on this listener, but
    // must not be inherited by a new listener whose players happen to reuse IDs.
    private void clearPlayerSettingsForNewListener() {
        Globals globals = Globals.getInstance();
        Globals.getmPlayerSettingsSemaphore();
        try {
            globals.mPlayerSettings.clear();
            // A peer host is also a player, but it never registers through its
            // own TCP listener. Seed its current local settings so clients use
            // its configured damage, reload, and lives rules from the first
            // lobby snapshot. A dedicated host has no player ID to publish.
            byte localPlayerID = globals.mPlayerID;
            if (!mIsDedicated && localPlayerID > 0 && Globals.isValidPlayerID(localPlayerID)) {
                Globals.PlayerSettings localSettings = localPlayerSettingsSnapshot(globals);
                int lives = localSettings.overrideLives ? localSettings.lives : 0;
                if (Globals.isValidPlayerSettings(localSettings.health, localSettings.shots & 0xff,
                        localSettings.reloadTime, localSettings.spawnTime, localSettings.damage, lives,
                        localSettings.allowShotModeSingle, localSettings.allowShotModeBurst3,
                        localSettings.allowShotModeAuto, localSettings.firingMode)) {
                    globals.mPlayerSettings.put(localPlayerID, localSettings);
                } else {
                    Log.w(TAG, "Not publishing invalid local host settings");
                }
            }
        } finally {
            globals.mPlayerSettingsSemaphore.release();
        }
    }

    static Globals.PlayerSettings localPlayerSettingsSnapshot(Globals globals) {
        Globals.PlayerSettings settings = new Globals.PlayerSettings();
        settings.health = globals.mFullHealth;
        settings.shots = globals.mFullReload;
        settings.reloadTime = globals.mReloadTime;
        settings.reloadOnEmpty = globals.mReloadOnEmpty;
        settings.spawnTime = globals.mRespawnTime;
        settings.damage = globals.mDamage;
        settings.overrideLives = globals.mOverrideLives;
        settings.lives = globals.mOverrideLivesVal;
        settings.allowShotModeSingle = globals.mAllowSingleShotMode;
        settings.allowShotModeBurst3 = globals.mAllowBurst3ShotMode;
        settings.allowShotModeAuto = globals.mAllowAutoShotMode;
        settings.firingMode = globals.mCurrentFiringMode;
        return settings;
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
        runClientTask(() -> sendGameInfo(id, recipients));
    }

    // Called with the client lock held, like start/end and registration. Build
    // snapshots in dispatch order: preparing a lobby snapshot before waiting for
    // the lock could send GAME_STATE_NONE after a queued start was announced, or
    // roll back a later score/roster update. Keep the original recipient identities
    // so a new connection cannot inherit work queued for a previous player.
    private void sendGameInfo(int id, List<ClientRecipient> recipients) {
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
                player.put(JSON_PLAYERIP, Globals.getIPAddressStr(getApplicationContext()));
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
            game.put(JSON_TOURNAMENT_MODE, Globals.getInstance().mTournamentMode);
            synchronized (mServerStateLock) {
                if (mScheduledStart >= 0 && (mStartAnnounced
                        || Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)) {
                    game.put(JSON_ROUND_ID, mRoundSequence);
                    game.put(JSON_ROUND_TOKEN, getRoundTokenLocked());
                    game.put(JSON_GAMESTART, mScheduledStart);
                    game.put(JSON_GAMEDURATION, mScheduledDuration);
                    // The UI broadcast may not have run yet. A committed start
                    // must not be overwritten by an apparent lobby snapshot.
                    if (mIsDedicated)
                        game.put(JSON_GAMESTATE, Globals.GAME_STATE_RUNNING);
                }
            }
            final String allMessage = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
            String idMessage = allMessage;
            if (id != SEND_ALL) {
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
                idMessage = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
            }
            for (ClientRecipient recipient : recipients) {
                if (!isClientTaskActive())
                    return;
                if (recipient.isCurrent())
                    recipient.client.sendTCPMessage(recipient.playerID == id ? idMessage : allMessage, false);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void ensureGPSWorkerLocked() {
        if (mGPSHandler != null)
            return;
        mGPSWorkerThread = new HandlerThread("SimpleCoil GPS publisher");
        mGPSWorkerThread.start();
        mGPSHandler = new Handler(mGPSWorkerThread.getLooper());
    }

    /** Builds one GPS frame, optionally restricted to the requested recipient team. */
    private String createGPSMessage(List<GPSUpdate> updates, boolean fullUpdate, int recipientTeam) {
        try {
            JSONArray players = new JSONArray();
            for (GPSUpdate update : updates) {
                if (recipientTeam != SEND_ALL && update.team != recipientTeam)
                    continue;
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERID, update.playerID);
                player.put(JSON_TEAM, update.team);
                player.put(JSON_GPSLONGITUDE, update.longitude);
                player.put(JSON_GPSLATITUDE, update.latitude);
                players.put(player);
            }
            // An empty full snapshot intentionally clears departed or hidden markers.
            if (players.length() == 0 && !fullUpdate)
                return null;
            JSONObject game = new JSONObject();
            game.put(JSON_GPSUPDATE, players);
            if (fullUpdate)
                game.put(JSON_GPSFULLUPDATE, true);
            return TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
        } catch (JSONException e) {
            Log.e(TAG, "Unable to encode GPS update", e);
            return null;
        }
    }

    /**
     * Team-only GPS used to broadcast every position and let clients hide enemies locally.
     * Send each team only its own data instead, saving bandwidth and avoiding needless JSON/map
     * work on up to twenty phones.
     */
    private void sendTeamGPSData(final List<GPSUpdate> updates, final boolean fullUpdate) {
        runClientTask(() -> {
            if (!Globals.getInstance().mUseGPS
                    || Globals.getInstance().mGPSMode != Globals.GPS_TEAMMATE)
                return;
            Map<Integer, String> messages = new HashMap<>();
            for (ClientRecipient recipient : getClientRecipients()) {
                if (!recipient.isCurrent() || recipient.playerID <= 0
                        || !Globals.isValidPlayerID(recipient.playerID))
                    continue;
                int team = recipient.team;
                String message;
                if (messages.containsKey(team)) {
                    message = messages.get(team);
                } else {
                    message = createGPSMessage(updates, fullUpdate, team);
                    messages.put(team, message);
                }
                if (message != null)
                    recipient.client.sendTCPMessage(message, false);
            }
        });
    }

    public void sendGPSData() {
        synchronized (mServerStateLock) {
            if (mGPSRunning || mDestroyed || !keepListening || !Globals.getInstance().mUseGPS)
                return;
            ensureGPSWorkerLocked();
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

                    final List<GPSUpdate> updates = new ArrayList<>();
                    final int gpsMode;
                    Globals.getmGPSDataSemaphore();
                    try {
                        synchronized (mServerStateLock) {
                            // Cancellation can happen while this tick waits for the location
                            // lock. Never consume a newer session's updates in that case.
                            if (!isCurrentGPSCallbackLocked(this))
                                return;
                            gpsMode = Globals.getInstance().mGPSMode;
                            if (Globals.getInstance().mGPSData != null) {
                                for (Map.Entry<Byte, Globals.GPSData> entry
                                        : Globals.getInstance().mGPSData.entrySet()) {
                                    Byte playerID = entry.getKey();
                                    Globals.GPSData gps = entry.getValue();
                                    if (gps == null)
                                        continue;
                                    if ((gps.hasUpdate || fullUpdate)
                                            && playerID != null && playerID > 0
                                            && Globals.isValidPlayerID(playerID)
                                            && Globals.isValidCoordinates(gps.longitude, gps.latitude)) {
                                        // A stationary player's cached entry can predate a
                                        // lobby game-mode change; use the current layout.
                                        updates.add(new GPSUpdate(playerID,
                                                Globals.getInstance().calcNetworkTeam(playerID),
                                                gps.longitude, gps.latitude));
                                    }
                                    // Invalid or stale entries must not make every periodic
                                    // tick reattempt the same unusable update.
                                    gps.hasUpdate = false;
                                }
                            }
                        }
                    } finally {
                        Globals.getInstance().mGPSDataSemaphore.release();
                    }

                    // JSON construction and network dispatch happen on the GPS worker.  A
                    // normal all-player update retains the existing broadcast path; team mode
                    // emits one compact frame per team instead.
                    if (gpsMode != Globals.GPS_DISABLED && (!updates.isEmpty() || fullUpdate)) {
                        if (gpsMode == Globals.GPS_TEAMMATE
                                && Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                            sendTeamGPSData(updates, fullUpdate);
                        } else {
                            String message = createGPSMessage(updates, fullUpdate, SEND_ALL);
                            if (message != null)
                                sendTCPMessageAll(message, false);
                        }
                    }
                    synchronized (mServerStateLock) {
                        if (isCurrentGPSCallbackLocked(this) && mGPSHandler != null)
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
        Handler handler = mGPSHandler;
        HandlerThread worker = mGPSWorkerThread;
        if (mGPSRunnable != null && handler != null)
            handler.removeCallbacks(mGPSRunnable);
        mGPSRunnable = null;
        mGPSRunning = false;
        mGPSHandler = null;
        mGPSWorkerThread = null;
        // A dedicated host can remain open after GPS is disabled.  Do not retain an otherwise
        // idle worker thread for the rest of that lobby; it is recreated on the next enable.
        if (worker != null)
            worker.quitSafely();
    }

    private void requestFullGPSUpdate() {
        synchronized (mServerStateLock) {
            mGPSIntervalCount = SEND_ALL_GPS_INTERVAL;
        }
    }

    /** Request a complete snapshot after a host changes the GPS visibility policy. */
    public void requestGPSRefresh() {
        requestFullGPSUpdate();
    }

    public void sendPlayerData(int playerID) {
        if (mClientData == null || mClientData.size() == 0)
            return;
        try {
            JSONArray players = new JSONArray();
            Map<Byte, InetAddress> teamIPMap = getTeamIPMapSnapshot();
            if (teamIPMap.isEmpty() && mDepartedScores.isEmpty())
                return;
            // Scoreboards describe the whole round, not just its current lobby.
            // Keep departed players' results without restoring their endpoints.
            for (byte id = 1; id <= Globals.MAX_PLAYER_ID; id++) {
                ScoreData scoreData = getScore(id);
                boolean inRoster = teamIPMap.containsKey(id);
                if (!inRoster && scoreData == null)
                    continue;
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERNAME, inRoster ? Globals.getInstance().getPlayerName(id) : scoreData.playerName);
                player.put(JSON_PLAYERID, id);
                if (inRoster)
                    player.put(JSON_PLAYERIP, teamIPMap.get(id));
                player.put(JSON_PLAYERPOINTS, scoreData == null ? 0 : scoreData.points);
                player.put(JSON_PLAYERELIMINATED, scoreData == null ? 0 : scoreData.eliminated);
                player.put(JSON_PLAYER_SHOTS, scoreData == null ? 0 : scoreData.shots);
                player.put(JSON_PLAYER_HITS, scoreData == null ? 0 : scoreData.hits);
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
            ensureServerControlledSettingsLocked();
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

    // Caller holds the player-settings semaphore.  A disabled client-settings
    // policy must be authoritative even when the operator has not opened an
    // individual player's dialog yet.  Without a record here, an empty
    // snapshot leaves a newly joined phone using its own old weapon settings.
    private void ensureServerControlledSettingsLocked() {
        Globals globals = Globals.getInstance();
        if (globals.mTournamentMode) {
            // Normalize both remembered IDs and currently connected clients: a reconnect must
            // not receive a custom profile retained from a previous lobby, even briefly.
            for (Globals.PlayerSettings settings : globals.mPlayerSettings.values())
                Globals.applyTournamentRules(settings);
            if (mClientData != null) {
                for (ClientData client : mClientData.values()) {
                    byte playerID = client.mPlayerID;
                    if (playerID > 0 && Globals.isValidPlayerID(playerID)) {
                        Globals.PlayerSettings settings = globals.mPlayerSettings.get(playerID);
                        if (settings == null) {
                            settings = new Globals.PlayerSettings();
                            globals.mPlayerSettings.put(playerID, settings);
                        }
                        Globals.applyTournamentRules(settings);
                    }
                }
            }
            return;
        }
        if ((globals.mAllowPlayerSettings && !globals.mOnlyServerSettings) || mClientData == null)
            return;
        for (ClientData client : mClientData.values()) {
            byte playerID = client.mPlayerID;
            if (playerID > 0 && Globals.isValidPlayerID(playerID)
                    && !globals.mPlayerSettings.containsKey(playerID)) {
                globals.mPlayerSettings.put(playerID, new Globals.PlayerSettings());
            }
        }
    }

    public void sendPlayerSettings(int playerID, boolean applyAll, boolean allowPlayerSettings) {
        // A host policy change is local state even when nobody is connected yet.
        Globals globals = Globals.getInstance();
        if (globals.mTournamentMode) {
            // Individual dialog saves must not loosen tournament rules between its local
            // mutation and the outbound snapshot.
            globals.mAllowPlayerSettings = false;
            globals.mOnlyServerSettings = true;
            applyAll = true;
        } else {
            globals.mAllowPlayerSettings = allowPlayerSettings;
        }
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
            game.put(JSON_ONLY_SERVER_SETTINGS, Globals.getInstance().mOnlyServerSettings);
            game.put(JSON_TOURNAMENT_MODE, Globals.getInstance().mTournamentMode);
            String message = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
            sendTCPMessageAll(message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * Enable or disable the dedicated tournament ruleset.  The host activity restores the
     * operator's previous settings policy when it disables the mode; the server owns the
     * authoritative parts so a stale or forged client setting cannot create an advantage.
     */
    public void setTournamentMode(boolean enabled) {
        Globals globals = Globals.getInstance();
        globals.mTournamentMode = enabled;
        if (enabled) {
            globals.mGameMode = Globals.GAME_MODE_2TEAMS;
            globals.mAllowPlayerSettings = false;
            globals.mOnlyServerSettings = true;
            Globals.getmPlayerSettingsSemaphore();
            try {
                ensureServerControlledSettingsLocked();
            } finally {
                globals.mPlayerSettingsSemaphore.release();
            }
        }
        // Send both a complete lobby snapshot and a settings update.  A client joining during
        // this transition therefore learns the two-team flag, the locked policy, and the
        // single-shot profile regardless of which queued frame reaches it first.
        sendAllGameInfo(SEND_ALL);
        sendPlayerSettingsUpdate(SEND_ALL, false);
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
            mTcpServerReady = false;
            mStartAnnounced = false;
            mScheduledStart = -1;
            mScheduledDuration = 0;
            mRoundToken = null;
            mServerThread = new Thread(this::runTcpServer, "SimpleCoil TCP server");
            mServerThread.start();
        }
    }

    private void runTcpServer() {
        ServerSocket ss = null;
        Socket pendingSocket = null;
        int clientID = 0;
        boolean startupReady = false;
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
            // Once bound, this listener owns a fresh lobby: stopTcpServer() is
            // also used for non-destructive lifecycle shutdown, so its old
            // roster must not be allowed to appear in this new session.
            mDepartedScores.clear();
            if (mClientData == null)
                mClientData = new ConcurrentHashMap<>();
            else
                mClientData.clear();
            clearSharedRosterState();
            clearPlayerSettingsForNewListener();
            if (!keepListening)
                return;
            synchronized (mServerStateLock) {
                if (!keepListening || mDestroyed)
                    return;
                mTcpServerReady = true;
                startupReady = true;
            }
            sendBroadcast(new Intent(NetMsg.NETMSG_TCPSERVERREADY));
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
            boolean notifyStartupFailure = false;
            synchronized (mServerStateLock) {
                if (mListenSocket == ss)
                    mListenSocket = null;
                if (Thread.currentThread() == mServerThread) {
                    notifyStartupFailure = !startupReady && keepListening && !mDestroyed;
                    mTcpServerReady = false;
                    keepListening = false;
                    stopGPSDataLocked();
                    mServerThread = null;
                }
            }
            if (notifyStartupFailure)
                sendBroadcast(new Intent(NetMsg.NETMSG_TCPSERVERFAILED));
            Log.d(TAG, "TCP Server done");
        }
    }

    boolean isTcpServerReady() {
        synchronized (mServerStateLock) {
            return keepListening && !mDestroyed && mTcpServerReady;
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
        // The dedicated-host UI can render before the asynchronous listener has
        // initialized its client map. Treat that empty startup window like an
        // empty scoreboard instead of dereferencing a null map.
        Map<Integer, ClientData> clients = mClientData;
        ClientData client = clientID < 0 || clients == null ? null : clients.get(clientID);
        ScoreData scoreData = new ScoreData();
        if (client != null) {
            // These fields are volatile. The client monitor also guards socket
            // writes, which must never block the host UI's scoreboard reads.
            scoreData.points = client.points;
            scoreData.eliminated = client.eliminated;
            scoreData.shots = client.shots;
            scoreData.hits = client.hits;
            scoreData.isConnected = client.out != null;
        } else {
            ScoreData departed = mDepartedScores.get(playerID);
            if (departed == null)
                return null;
            scoreData.points = departed.points;
            scoreData.eliminated = departed.eliminated;
            scoreData.shots = departed.shots;
            scoreData.hits = departed.hits;
            scoreData.playerName = departed.playerName;
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
        int shots = 0;
        int hits = 0;
        boolean isConnected = false;
        // Retained when a player leaves and their name is removed from the lobby.
        String playerName = "";
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
        private volatile boolean clockSynchronized;
        // A ready acknowledgement is meaningful only after this connection has
        // received the exchanges used to calculate its clock offset.
        private int clockSamples;
        private long idleTick;
        private Queue<String> messageQueue;
        private volatile int points = 0;
        private volatile int eliminated = 0;
        private volatile int shots = 0;
        private volatile int hits = 0;
        // Dedicated-host state: only a server-observed death may be revived.
        private volatile boolean awaitingRespawn;

        private int getNetworkTeam() {
            // The host can change the game mode after players have joined the lobby.
            return Globals.getInstance().calcNetworkTeam(mPlayerID);
        }

        public synchronized boolean initialize(Socket s, int cID) {
            clientSocket = s;
            clientID = cID;
            noReadCount = 0;
            clockSynchronized = false;
            clockSamples = 0;
            idleTick = 0;
            messageReader = new TcpMessageReader();
            if (messageQueue == null)
                messageQueue = new LinkedList<>();
            try {
                // Gameplay frames are short and latency-sensitive.  Buffer the UTF framing into
                // one write while disabling Nagle's delay for immediate per-player delivery.
                clientSocket.setTcpNoDelay(true);
                is = clientSocket.getInputStream();
                in = new DataInputStream(is);
                os = clientSocket.getOutputStream();
                out = new DataOutputStream(new BufferedOutputStream(os));
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
                if (queueFailed)
                    queueMessage(message);
                return false;
            }
            try {
                out.writeUTF(message);
                out.flush();
                if (BuildConfig.DEBUG && !message.equals(TcpServer.TCP_SERVER_PING))
                    Log.i(TAG, "sent(" + clientID + "): " + message);
                return true;
            } catch (IOException e) {
                //Log.e(TAG, "IO Error:", e);
                e.printStackTrace();
                if (queueFailed)
                    queueMessage(message);
                // The failed write may have emitted only part of its frame.
                // Keep pending events, but never append to that damaged stream.
                close();
                connectionFailed = true;
                return false;
            }
        }

        private void queueMessage(String message) {
            if (messageQueue == null)
                messageQueue = new LinkedList<>();
            while (messageQueue.size() >= MAX_QUEUED_CLIENT_EVENTS)
                messageQueue.poll();
            messageQueue.offer(message);
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
                if (BuildConfig.DEBUG)
                    Log.d(TAG, "sending queued event to " + clientID);
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
                long startTime = SystemClock.elapsedRealtime();
                try {
                    if (!keepListening)
                        return;
                    for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                        if (!isClientTaskActive())
                            break;
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
                                entry.getValue().idleTick = SystemClock.elapsedRealtime();
                                if (!message.equals(TcpClient.TCP_CLIENT_PONG)) {
                                    if (BuildConfig.DEBUG)
                                        Log.i(TAG, "received: '" + message + "' from "
                                                + entry.getValue().clientID);
                                    if (message.startsWith(TCPMESSAGE_PREFIX)) {
                                        if (message.startsWith(TCPPREFIX_JSON, TCPMESSAGE_PREFIX.length())) {
                                            // Handle JSON Data
                                            message = message.substring(TCPMESSAGE_PREFIX.length() + TCPPREFIX_JSON.length());
                                            parsePlayerInfo(message, entry.getValue());
                                            // Service every client once per pass. A busy early
                                            // client must not starve later players' clock sync,
                                            // GPS updates, or heartbeats in a large lobby.
                                            continue;
                                        } else if (message.startsWith(TCPPREFIX_MESG, TCPMESSAGE_PREFIX.length())) {
                                            // Handle messages
                                            message = message.substring(TCPMESSAGE_PREFIX.length() + TCPPREFIX_MESG.length());
                                            if (entry.getValue().mPlayerID <= 0
                                                    || !Globals.isValidPlayerID(entry.getValue().mPlayerID)) {
                                                // A socket is not a player until registration succeeds.
                                                // Its departure must not end an existing players' round.
                                                if (message.equals(NetMsg.NETMSG_LEAVE)
                                                        || message.equals(NetMsg.NETMSG_QUIT))
                                                    removeClient(entry.getValue(), entry.getKey(), true);
                                                Log.w(TAG, "Ignoring game control from an unregistered connection");
                                                continue;
                                            }
                                            if (message.equals(NetMsg.NETMSG_LEAVE)
                                                    || message.equals(NetMsg.NETMSG_QUIT)) {
                                                // Tournament clients can exit, but never use a
                                                // departure packet to end the host's round.
                                                boolean endsRoundWhenLastPlayer = message.equals(NetMsg.NETMSG_LEAVE)
                                                        && !Globals.getInstance().mTournamentMode;
                                                removeClient(entry.getValue(), entry.getKey(), true,
                                                        endsRoundWhenLastPlayer ? NetMsg.NETMSG_LEAVE : NetMsg.NETMSG_QUIT);
                                                if (endsRoundWhenLastPlayer && mClientData.size() <= 1
                                                        && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                                                    endGame();
                                                break;
                                            } else if (message.startsWith(NetMsg.NETMSG_ELIMINATED)) {
                                                if (Globals.getInstance().mGameState != Globals.GAME_STATE_RUNNING
                                                        || !hasRoundStarted()
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
                                                if (mIsDedicated) {
                                                    synchronized (entry.getValue()) {
                                                        // A TCP reconnect or replay must not grant another
                                                        // kill while this player is still dead.
                                                        if (entry.getValue().awaitingRespawn) {
                                                            Log.w(TAG, "Ignoring duplicate elimination report from player "
                                                                    + entry.getValue().mPlayerID);
                                                            continue;
                                                        }
                                                        entry.getValue().awaitingRespawn = true;
                                                    }
                                                }
                                                // Keep server-created scoreboards within the same
                                                // range clients accept. A bad or repeated event must
                                                // not wrap an int negative and disconnect every peer.
                                                entry.getValue().eliminated = Math.min(Globals.MAX_SCOREBOARD_VALUE,
                                                        entry.getValue().eliminated + 1);
                                                scoringPlayer.points = Math.min(Globals.MAX_SCOREBOARD_VALUE,
                                                        scoringPlayer.points + 1);
                                                sendTCPMessageID(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ELIMINATED + entry.getValue().mPlayerID, id, true);
                                                if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                                                    // Send a message to all teammates about the score increase
                                                    sendTCPMessageTeam(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_TEAMELIMINATED, id, false, true);
                                                }
                                                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                                                if (hasReachedScoreLimit(scoringPlayer))
                                                    endGame();
                                            } else if (message.equals(NetMsg.NETMSG_RESPAWNREQUEST)) {
                                                // A QR checkpoint is a team-game convenience. The server
                                                // still verifies that this exact registered player is dead.
                                                if (!mIsDedicated || Globals.getInstance().mGameMode == Globals.GAME_MODE_FFA
                                                        || !isDedicatedRespawnRoundRunning()) {
                                                    Log.w(TAG, "Ignoring respawn request outside a dedicated team round");
                                                    continue;
                                                }
                                                if (clearAwaitingRespawn(entry.getValue())) {
                                                    if (sendTCPMessageID(TCPMESSAGE_PREFIX + TCPPREFIX_MESG
                                                            + NetMsg.NETMSG_RESPAWNGRANTED,
                                                            entry.getValue().mPlayerID, true)) {
                                                        sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                                                    } else {
                                                        synchronized (entry.getValue()) {
                                                            entry.getValue().awaitingRespawn = true;
                                                        }
                                                        Log.w(TAG, "Unable to queue respawn grant for player "
                                                                + entry.getValue().mPlayerID);
                                                    }
                                                } else {
                                                    Log.w(TAG, "Ignoring respawn request from a player who is not waiting");
                                                }
                                            } else if (message.equals(NetMsg.NETMSG_RESPAWNCOMPLETE)) {
                                                // The local three-minute fallback has already revived the
                                                // player; clear only the matching pending death on the host.
                                                if (mIsDedicated && isDedicatedRespawnRoundRunning()
                                                        && clearAwaitingRespawn(entry.getValue()))
                                                    sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                                            } else if (message.equals(NetMsg.NETMSG_PLAYERDATAREQUEST)) {
                                                sendPlayerData(entry.getValue().mPlayerID);
                                            } else if (message.equals(NetMsg.NETMSG_STARTGAME)) {
                                                if (Globals.getInstance().mOnlyServerSettings
                                                        || Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
                                                    Log.w(TAG, "Ignoring disallowed or duplicate game-start request");
                                                } else {
                                                    if (!startGame() && !arePlayerClocksSynchronized())
                                                        entry.getValue().sendTCPMessage(TCPMESSAGE_PREFIX + TCPPREFIX_MESG
                                                                + NetMsg.NETMSG_CLOCKSYNCWAITING);
                                                }
                                            } else if (message.equals(NetMsg.NETMSG_ENDGAME)) {
                                                if (Globals.getInstance().mTournamentMode) {
                                                    // The dedicated host is the sole tournament
                                                    // authority. Treat any client end request as
                                                    // a voluntary exit instead of ending the match.
                                                    removeClient(entry.getValue(), entry.getKey(), true,
                                                            NetMsg.NETMSG_QUIT);
                                                    break;
                                                }
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
                                // Faster clock sampling must not speed up ping/disconnect timeouts.
                                long now = SystemClock.elapsedRealtime();
                                int readWait = mIsDedicated ? TCP_DEDICATED_READ_WAIT_MS : TCP_READ_WAIT_MS;
                                if (now - entry.getValue().idleTick < readWait)
                                    continue;
                                entry.getValue().idleTick = now;
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
                int readWait = mIsDedicated ? TCP_DEDICATED_READ_WAIT_MS : TCP_READ_WAIT_MS;
                if (SystemClock.elapsedRealtime() < mClockSamplingUntil)
                    readWait = CLOCK_READ_WAIT_MS;
                long sleepTime = readWait - (SystemClock.elapsedRealtime() - startTime);
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
            final long receivedAt = SystemClock.elapsedRealtime();
            JSONObject player;
            try {
                player = TcpJson.parseObject(message);
            } catch (JSONException e) {
                e.printStackTrace();
                return;
            }
            if (player.has(JSON_CLOCK_REQUEST) || player.has(JSON_CLOCK_READY)) {
                if (client.mPlayerID <= 0 || !Globals.isValidPlayerID(client.mPlayerID)
                        || client.clientSocket == null)
                    return;
                try {
                    if (player.has(JSON_CLOCK_REQUEST)) {
                        long sentAt = TcpJson.getLong(player, JSON_CLOCK_REQUEST);
                        if (!GameClock.validTimestamp(sentAt))
                            return;
                        // Only the initial five exchanges need fast polling.
                        // Later countdown probes are deliberately lightweight
                        // and should not keep a 20-phone dedicated host busy.
                        if (client.clockSamples < GameClock.SAMPLES_PER_SYNC)
                            mClockSamplingUntil = Math.max(mClockSamplingUntil,
                                    receivedAt + CLOCK_SYNC_BURST_WINDOW_MS);
                        JSONObject reply = new JSONObject().put(JSON_CLOCK_REQUEST, sentAt)
                                .put(JSON_CLOCK_RECEIVE, receivedAt)
                                .put(JSON_CLOCK_SEND, SystemClock.elapsedRealtime());
                        if (client.sendTCPMessage(TCPMESSAGE_PREFIX + TCPPREFIX_JSON + reply))
                            client.clockSamples = Math.min(GameClock.SAMPLES_PER_SYNC,
                                    client.clockSamples + 1);
                    } else if (Boolean.TRUE.equals(player.get(JSON_CLOCK_READY))) {
                        if (client.clockSamples >= GameClock.SAMPLES_PER_SYNC)
                            client.clockSynchronized = true;
                        else
                            Log.w(TAG, "Ignoring clock-ready acknowledgement before sampling completed");
                    }
                } catch (JSONException e) {
                    Log.w(TAG, "Ignoring invalid clock synchronization request", e);
                }
                return;
            }
            if (player.has(JSON_TELEMETRY)) {
                // Telemetry is intentionally best-effort. It feeds hosted-game
                // scoreboards and visualizers, but never decides who was killed
                // or whether a respawn is allowed.
                if (!mIsDedicated || client.mPlayerID <= 0 || !Globals.isValidPlayerID(client.mPlayerID)
                        || Globals.getInstance().mGameState != Globals.GAME_STATE_RUNNING
                        || !hasRoundStarted()) {
                    return;
                }
                try {
                    Object eventValue = player.get(JSON_TELEMETRY);
                    if (!(eventValue instanceof String))
                        return;
                    String event = (String) eventValue;
                    if (JSON_TELEMETRY_SHOT.equals(event)) {
                        int count = TcpJson.getInt(player, JSON_TELEMETRY_COUNT);
                        // The blaster reports at most one magazine's worth of
                        // decrements in a telemetry frame. Bound the value so a
                        // forged JSON frame cannot overflow a leaderboard.
                        if (count < 1 || count > Globals.MAX_RELOAD_COUNT)
                            return;
                        client.shots = Math.min(Globals.MAX_SCOREBOARD_VALUE, client.shots + count);
                        sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                    } else if (JSON_TELEMETRY_HIT.equals(event)) {
                        int rawAttackerID = TcpJson.getInt(player, JSON_TELEMETRY_ATTACKER);
                        if (!Globals.isValidPlayerID(rawAttackerID) || rawAttackerID <= 0)
                            return;
                        byte attackerID = (byte) rawAttackerID;
                        ClientData attacker = mClientData.get(clientIDFromPlayerID(attackerID));
                        if (attacker == null || attacker == client
                                || (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA
                                && attacker.getNetworkTeam() == client.getNetworkTeam())) {
                            return;
                        }
                        attacker.hits = Math.min(Globals.MAX_SCOREBOARD_VALUE, attacker.hits + 1);
                        sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                    }
                } catch (JSONException e) {
                    Log.w(TAG, "Ignoring malformed hosted-game telemetry", e);
                }
                return;
            }
            if (player.has(JSON_PAIRED_GRENADE_ID)) {
                final int grenadeID;
                final int playerID;
                try {
                    grenadeID = TcpJson.getInt(player, JSON_PAIRED_GRENADE_ID);
                    playerID = TcpJson.getInt(player, JSON_PLAYERID);
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
                if (Globals.getInstance().mTournamentMode || !Globals.getInstance().mAllowPlayerSettings || Globals.getInstance().mOnlyServerSettings
                        || client.mPlayerID <= 0 || !Globals.isValidPlayerID(client.mPlayerID)) {
                    Log.e(TAG, "Player " + client.mPlayerID + "not allowed to send player settings");
                    sendPlayerSettingsUpdate(SEND_ALL, false);
                    return;
                }
                boolean applied = false;
                try {
                    int playerID = TcpJson.getInt(player, JSON_PLAYERID);
                    int health = TcpJson.getInt(player, JSON_HEALTH);
                    int reloadShots = TcpJson.getInt(player, JSON_RELOAD_SHOTS);
                    long reloadTime = TcpJson.getLong(player, JSON_RELOAD_TIME);
                    boolean reloadOnEmpty = player.getBoolean(JSON_RELOAD_ON_EMPTY);
                    long spawnTime = TcpJson.getLong(player, JSON_SPAWN_TIME);
                    int damage = TcpJson.getInt(player, JSON_DAMAGE);
                    boolean overrideLives = player.has(JSON_LIVESLIMIT);
                    int lives = overrideLives ? TcpJson.getInt(player, JSON_LIVESLIMIT) : 0;
                    boolean allowAuto = player.getBoolean(JSON_SHOT_MODE_AUTO);
                    boolean allowBurst = player.getBoolean(JSON_SHOT_MODE_BURST3);
                    boolean allowSingle = player.getBoolean(JSON_SHOT_MODE_SINGLE);
                    int firingMode = TcpJson.getInt(player, JSON_FIRING_MODE);
                    if (playerID != client.mPlayerID || !Globals.isValidPlayerSettings(health, reloadShots,
                            reloadTime, spawnTime, damage, lives, allowSingle, allowBurst, allowAuto, firingMode)) {
                        Log.w(TAG, "Ignoring invalid player settings from " + client.mPlayerID);
                        sendPlayerSettingsUpdate(SEND_ALL, false);
                        return;
                    }
                    Globals.getmPlayerSettingsSemaphore();
                    try {
                        // The host can revoke permission while this request waits for the lock.
                        if (Globals.getInstance().mTournamentMode || !Globals.getInstance().mAllowPlayerSettings || Globals.getInstance().mOnlyServerSettings
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
                    id = TcpJson.getInt(player, JSON_PLAYERID);
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
                // Presence alone is not a reconnect request. In particular, a
                // normal second player can send {"rejoin":false}; treating that
                // as true lets it replace an already connected player's socket.
                Object rejoinValue = player.has(JSON_REJOIN) ? player.get(JSON_REJOIN) : null;
                if (rejoinValue != null && !(rejoinValue instanceof Boolean)) {
                    Log.w(TAG, "Ignoring registration with an invalid rejoin flag");
                    return;
                }
                rejoin = Boolean.TRUE.equals(rejoinValue);
                int rawPlayerID = TcpJson.getInt(player, JSON_PLAYERID);
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
            for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                if (entry.getValue() != client && entry.getValue().mPlayerID == id) {
                    // An explicit reconnect may replace a dead link that has not
                    // reached its heartbeat timeout yet. A normal registration
                    // with a duplicate ID must leave the connected player alone.
                    if (!rejoin && entry.getValue().clientSocket != null) {
                        Log.w(TAG, "Ignoring duplicate registration for connected player " + id);
                        client.close();
                        mClientData.remove(client.clientID);
                        return;
                    }
                    Log.d(TAG, "rejoining " + client.clientID + " to " + entry.getValue().clientID);
                    entry.getValue().rejoin(client.clientSocket);
                    mClientData.remove(client.clientID);
                    client = entry.getValue();
                    break;
                }
            }
            if (client.clientSocket == null)
                return; // A replacement socket can fail while its streams are opened.
            InetAddress inetAddress = client.clientSocket.getInetAddress();
            if (inetAddress == null) {
                Log.w(TAG, "Ignoring registration without a peer address");
                client.close();
                mClientData.remove(client.clientID);
                return;
            }
            // UDP gameplay messages are attributed by source address. One
            // address therefore cannot safely represent two live player IDs:
            // accepting that registration overwrites the reverse map while
            // leaving the old player mapped to the same endpoint.
            Byte endpointPlayerID;
            Globals.getmIPTeamMapSemaphore();
            try {
                endpointPlayerID = Globals.getInstance().mIPTeamMap.get(inetAddress);
            } finally {
                Globals.getInstance().mIPTeamMapSemaphore.release();
            }
            if (endpointPlayerID != null && endpointPlayerID.byteValue() != id) {
                Log.w(TAG, "Ignoring registration from an endpoint already owned by player "
                        + endpointPlayerID);
                client.close();
                mClientData.remove(client.clientID);
                return;
            }
            boolean newRegistration = client.mPlayerID == 0;
            if (rejoin && newRegistration)
                Log.d(TAG, "rejoined client " + client.clientID + " not present so adding as a new player");
            client.mPlayerID = id;
            requestFullGPSUpdate(); // Send all GPS info because of the accepted client
            ScoreData departed = mDepartedScores.remove(id);
            if (newRegistration && departed != null) {
                client.points = departed.points;
                client.eliminated = departed.eliminated;
                client.shots = departed.shots;
                client.hits = departed.hits;
            }
            Log.d(TAG, "network team is " + client.getNetworkTeam());
            Log.d(TAG, "client " + client.clientID + " player '" + playerName + "' (" + client.mPlayerID + ") found at " + inetAddress.toString());
            updatePlayerEndpoint(client.mPlayerID, inetAddress);
            updatePlayerName(client.mPlayerID, playerName);

            sendAllGameInfo(id);
            // Synchronize a joining client even when no grenade is currently paired.
            sendGrenadePairings(true);
            sendBroadcast(new Intent(newRegistration ? NetMsg.NETMSG_JOIN : NetMsg.NETMSG_PLAYERDATAUPDATE));
        }

        private void removeClient(ClientData client, Integer clientID, boolean alwaysRemove) {
            removeClient(client, clientID, alwaysRemove, NetMsg.NETMSG_LEAVE);
        }

        private void removeClient(ClientData client, Integer clientID, boolean alwaysRemove,
                                  String departureAction) {
            if (mClientData.get(clientID) != client)
                return;
            if (client.mPlayerID != 0) {
                if (alwaysRemove || Globals.getInstance().mGameState == Globals.GAME_STATE_NONE) {
                    if (mIsDedicated && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
                        ScoreData score = getScore(client.mPlayerID);
                        if (score != null) {
                            score.isConnected = false;
                            score.playerName = Globals.getInstance().getPlayerName(client.mPlayerID);
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
                    } finally {
                        Globals.getInstance().mGPSDataSemaphore.release();
                    }
                    // The periodic publisher takes the server-state lock before the GPS lock.
                    // Do not invert that order while removing a player, or a disconnect that
                    // races a GPS tick can leave both workers waiting forever.
                    requestFullGPSUpdate(); // Force a full GPS update when someone leaves
                    client.close();
                    mClientData.remove(clientID);
                    sendAllGameInfo(SEND_ALL);
                    sendBroadcast(new Intent(departureAction));
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

    public void cancelServer() {
        synchronized (mServerStateLock) {
            if (mCancellationThread != null)
                return;
            // Also retire a peer host's start retained after its listener stopped.
            mStartAnnounced = false;
            mScheduledStart = -1;
            mScheduledDuration = 0;
            mRoundToken = null;
            if (!keepListening || mDestroyed) {
                stopTcpServer();
                return;
            }
            runClientTask(() -> {
                String message = TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERCANCEL;
                // Snapshot only after acquiring the client lock: a registration
                // or rejoin already in progress also needs the terminal notice.
                for (ClientRecipient recipient : getClientRecipients()) {
                    if (!isClientTaskActive())
                        return;
                    if (recipient.isCurrent())
                        recipient.client.sendTCPMessage(message);
                }
            }, RoundTask.CANCEL);
        }
    }

    public void stopTcpServer() { stopTcpServer(null); }

    private void stopTcpServer(Thread cancellationOwner) {
        final ServerSocket listener;
        final Thread worker;
        final Thread clients;
        final Thread[] tasks;
        final List<Socket> sockets = new ArrayList<>();
        synchronized (mServerStateLock) {
            if (cancellationOwner != null && mCancellationThread != cancellationOwner)
                return;
            keepListening = false;
            mTcpServerReady = false;
            if (mCancellationTimeout != null) {
                mShutdownHandler.removeCallbacks(mCancellationTimeout);
                mCancellationTimeout = null;
            }
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
