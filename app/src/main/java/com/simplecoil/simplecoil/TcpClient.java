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
import android.os.SystemClock;
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
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

public class TcpClient extends Service {
    private static final String TAG = "TCPClient";

    public static final String TCP_CLIENT_PONG = "pong";
    private static final int MAX_REJOIN_TRIES = 3;
    private static final int CONNECTION_TIMEOUT_MS = 1000;
    private static final int RECONNECT_RETRY_DELAY_MS = 1000;
    private static final int SHUTDOWN_FLUSH_TIMEOUT_MS = 1000;
    // A disconnected player can retain elimination reports until it rejoins.
    // Keep that recovery buffer finite on memory-constrained game phones.
    static final int MAX_QUEUED_PERSISTENT_MESSAGES = 64;
    // A blocked socket must not let pings or gameplay updates accumulate an
    // unbounded executor queue on a client phone.
    static final int MAX_PENDING_SEND_TASKS = 64;
    private static final int MAX_PENDING_PRIORITY_SEND_TASKS = 4;
    private static final int MAX_PENDING_PERSISTENT_DRAINS = 4;
    // Scoreboards sum values by team, so keep every untrusted row low enough
    // that a supported lobby cannot overflow an integer total.
    static final int MAX_SCOREBOARD_VALUE = Globals.MAX_SCOREBOARD_VALUE;
    static final String EXTRA_TERMINAL_EVENT_ID = "com.simplecoil.simplecoil.TCP_TERMINAL_EVENT_ID";
    static final String EXTRA_START_EVENT_ID = "com.simplecoil.simplecoil.TCP_START_EVENT_ID";
    private static final AtomicLong terminalEventIds = new AtomicLong();

    private volatile boolean keepListening = false;
    private volatile boolean isListening = false;
    private volatile boolean mIsDedicatedServer = false;
    private boolean mDestroyed;
    private long mSessionGeneration;
    // A takeover receives the new UDP endpoint before it is safe to start a
    // second TCP reader. Let the retiring reader finish, then open one fresh
    // session to the replacement host.
    private boolean mRestartAfterHostTakeover;
    private boolean mReceiverRegistered;
    private Socket mActiveSocket;
    private Thread mClientThread;
    private Intent mPendingTerminalEvent;
    private final GameClock mGameClock = new GameClock();
    private boolean mClockSynchronized;
    private boolean mClockRequestQueued;
    private long mPendingClockRequest = -1;
    private long mLastClockSync;
    private long mLastStartRound;
    private JSONObject mPendingStartInfo;
    private Intent mPendingStartIntent;
    private Intent mPendingGameStartEvent;
    private static final long CLOCK_REFRESH_MS = 30000;
    // Once a shared countdown has been announced, take one inexpensive probe
    // per second.  The probe only improves an already-good NTP estimate; it
    // never delays the countdown or floods the dedicated host with a new
    // calibration burst.
    private static final long COUNTDOWN_CLOCK_REFRESH_MS = 1000;
    private static final long START_CLOCK_ADJUSTMENT_THRESHOLD_MS = 50;
    // GPS time is optional and only refines an NTP-derived start when both
    // estimates already agree within the game's one-second sync target.
    private static final long GPS_START_REFINEMENT_LIMIT_MS = 1000;
    private long mActiveHostStartAt = -1;
    private long mActiveStartDuration;
    private long mActiveLocalStartAt = -1;
    private long mActiveStartRound;
    private String mActiveStartToken;

    private volatile DataOutputStream out = null;
    private Queue<String> messageQueue = new ConcurrentLinkedQueue<>();
    private final Semaphore mSendTaskSlots = new Semaphore(MAX_PENDING_SEND_TASKS);
    // Session control frames retain reserved capacity when ordinary updates
    // already fill their bounded backlog.
    private final Semaphore mPrioritySendTaskSlots = new Semaphore(MAX_PENDING_PRIORITY_SEND_TASKS);
    // Persistent events are retained separately, so reserve only a few drain
    // tasks. A reconnect must retain its position behind registration but ahead
    // of follow-up state, even while a stale connection is still unwinding.
    private final Semaphore mPersistentDrainTaskSlots = new Semaphore(MAX_PENDING_PERSISTENT_DRAINS);
    private boolean mPersistentDrainRequested;
    private boolean mPongQueued;
    private final ExecutorService sendExecutor = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_PENDING_SEND_TASKS
            + MAX_PENDING_PRIORITY_SEND_TASKS + MAX_PENDING_PERSISTENT_DRAINS),
            runnable -> new Thread(runnable, "SimpleCoil TCP send"),
            new ThreadPoolExecutor.AbortPolicy());

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Reconnecting after a process restart must be initiated by the UI so it
        // uses the current lobby and player state.
        return START_NOT_STICKY;
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
            mPersistentDrainRequested = false;
            mPendingTerminalEvent = null;
            mIsDedicatedServer = false;
            resetClockSyncLocked();
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
            while (messageQueue.size() >= MAX_QUEUED_PERSISTENT_MESSAGES)
                messageQueue.poll();
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
        scheduleSendTask(() -> {
            if (writer == out)
                writeMessage(writer, message);
        }, mSendTaskSlots);
    }

    private synchronized void sendPriorityTCPMessage(final String message) {
        if (mDestroyed)
            return;
        final DataOutputStream writer = out;
        if (writer == null || sendExecutor.isShutdown())
            return;
        scheduleSendTask(() -> {
            if (writer == out)
                writeMessage(writer, message);
        }, mPrioritySendTaskSlots);
    }

    private boolean scheduleSendTask(Runnable task, Semaphore slots) {
        if (!slots.tryAcquire())
            return false;
        try {
            sendExecutor.execute(() -> {
                try {
                    task.run();
                } finally {
                    slots.release();
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            // The service can be torn down after the caller checked its state.
            slots.release();
            Log.w(TAG, "TCP sender is shutting down", e);
            return false;
        }
    }

    private void sendPong() {
        synchronized (this) {
            if (mDestroyed || mPongQueued || out == null || sendExecutor.isShutdown())
                return;
            final DataOutputStream writer = out;
            mPongQueued = true;
            if (!scheduleSendTask(() -> {
                try {
                    if (writer == out)
                        writeMessage(writer, TCP_CLIENT_PONG);
                } finally {
                    synchronized (TcpClient.this) {
                        mPongQueued = false;
                    }
                }
            }, mSendTaskSlots)) {
                mPongQueued = false;
            }
        }
    }

    private synchronized void sendQueuedMessages() {
        mPersistentDrainRequested = true;
        scheduleQueuedMessagesLocked();
    }

    private void scheduleQueuedMessagesLocked() {
        final DataOutputStream writer = out;
        final Queue<String> pending = messageQueue;
        if (!mPersistentDrainRequested || writer == null || sendExecutor.isShutdown())
            return;
        if (!mPersistentDrainTaskSlots.tryAcquire())
            return;
        mPersistentDrainRequested = false;
        try {
            sendExecutor.execute(() -> {
                try {
                    while (writer == out) {
                        String message = pending.peek();
                        if (message == null || !writeMessage(writer, message))
                            return;
                        pending.poll();
                    }
                } finally {
                    mPersistentDrainTaskSlots.release();
                    synchronized (TcpClient.this) {
                        scheduleQueuedMessagesLocked();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            mPersistentDrainTaskSlots.release();
            mPersistentDrainRequested = true;
            Log.w(TAG, "TCP sender is shutting down", e);
        }
    }

    private boolean writeMessage(DataOutputStream writer, String message) {
        try {
            writer.writeUTF(message);
            writer.flush();
            if (BuildConfig.DEBUG && !message.equals(TCP_CLIENT_PONG))
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

    private void resetClockSyncLocked() {
        mGameClock.reset();
        mClockSynchronized = false;
        mClockRequestQueued = false;
        mPendingClockRequest = -1;
        mLastClockSync = 0;
        mLastStartRound = 0;
        mPendingStartInfo = null;
        mPendingStartIntent = null;
        mPendingGameStartEvent = null;
        clearActiveStartLocked();
        mPongQueued = false;
    }

    public synchronized boolean isClockSynchronized() { return mClockSynchronized; }

    private void clearActiveStartLocked() {
        mActiveHostStartAt = -1;
        mActiveStartDuration = 0;
        mActiveLocalStartAt = -1;
        mActiveStartRound = 0;
        mActiveStartToken = null;
    }

    /**
     * The initial calibration burst establishes the start deadline. While
     * that deadline is still in the future, small follow-up probes can replace
     * it only with a materially better clock estimate.  Once the deadline has
     * passed, changing it would make an already-started round jump backwards.
     */
    private boolean hasActiveStartCountdownLocked(long now) {
        if (mActiveHostStartAt < 0 || mActiveLocalStartAt < 0)
            return false;
        if (now < mActiveLocalStartAt)
            return true;
        clearActiveStartLocked();
        return false;
    }

    private synchronized void requestClockSync() {
        if (mDestroyed || !keepListening || out == null || sendExecutor.isShutdown() || mClockRequestQueued)
            return;
        long now = SystemClock.elapsedRealtime();
        if (mPendingClockRequest >= 0 && now - mPendingClockRequest < GameClock.MAX_ROUND_TRIP_MS)
            return;
        if (mGameClock.samples() >= GameClock.SAMPLES_PER_SYNC) {
            boolean countdownActive = hasActiveStartCountdownLocked(now);
            long refreshInterval = countdownActive ? COUNTDOWN_CLOCK_REFRESH_MS : CLOCK_REFRESH_MS;
            if (now - mLastClockSync < refreshInterval)
                return;
            // The initial synchronization and ordinary background refreshes
            // use a fresh low-latency sample batch. A running countdown uses one
            // probe against the current best sample instead, which lets a
            // better low-latency exchange refine the deadline without making
            // every phone repeatedly enter a high-rate sampling burst.
            if (!countdownActive)
                mGameClock.beginSampling();
        }
        final DataOutputStream writer = out;
        final long generation = mSessionGeneration;
        mClockRequestQueued = true;
        if (!scheduleSendTask(() -> {
                final long sentAt;
                synchronized (TcpClient.this) {
                    if (!isCurrentSession(generation) || writer != out)
                        return;
                    // Timestamp at the writer, not when work was queued behind other frames.
                    sentAt = SystemClock.elapsedRealtime();
                    mPendingClockRequest = sentAt;
                    mClockRequestQueued = false;
                }
                try {
                    JSONObject request = new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, sentAt);
                    long gpsTime = Globals.getInstance().mGpsGameTime.utcTimeAtElapsed(sentAt, sentAt);
                    if (GpsGameTime.isValidUtcTime(gpsTime))
                        request.put(TcpServer.JSON_GPS_CLOCK, gpsTime);
                    writeMessage(writer, TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + request);
                } catch (JSONException e) {
                    Log.w(TAG, "Unable to request clock synchronization", e);
                }
            }, mPrioritySendTaskSlots)) {
            mClockRequestQueued = false;
        }
    }

    private void receiveClockSync(JSONObject reply, long receivedAt, long generation) throws JSONException {
        long sentAt = TcpJson.getLong(reply, TcpServer.JSON_CLOCK_REQUEST);
        long hostReceived = TcpJson.getLong(reply, TcpServer.JSON_CLOCK_RECEIVE);
        long hostSent = TcpJson.getLong(reply, TcpServer.JSON_CLOCK_SEND);
        boolean ready;
        boolean announceReady = false;
        synchronized (this) {
            if (!isCurrentSession(generation) || sentAt != mPendingClockRequest || sentAt < 0)
                return;
            mPendingClockRequest = -1;
            if (!mGameClock.record(sentAt, hostReceived, hostSent, receivedAt)) {
                // A follow-up probe can be rejected for excessive delay.  Keep
                // the last good estimate, but do not immediately retry in a
                // tight loop while a busy Wi-Fi link is recovering.
                if (mClockSynchronized)
                    mLastClockSync = receivedAt;
                return;
            }
            ready = mGameClock.samples() >= GameClock.SAMPLES_PER_SYNC;
            if (ready) {
                announceReady = !mClockSynchronized;
                mClockSynchronized = true;
                mLastClockSync = receivedAt;
                if (announceReady) {
                    sendPriorityTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON
                            + new JSONObject().put(TcpServer.JSON_CLOCK_READY, true));
                }
            }
        }
        if (ready) {
            publishPendingStart(generation);
            publishClockAdjustedStart(generation);
        } else {
            requestClockSync();
        }
    }

    private static JSONObject readStartInfo(JSONObject message) throws JSONException {
        long roundID = TcpJson.getLong(message, TcpServer.JSON_ROUND_ID);
        Object roundTokenValue = message.get(TcpServer.JSON_ROUND_TOKEN);
        if (!(roundTokenValue instanceof String))
            throw new JSONException("Invalid synchronized game round token");
        String roundToken = (String) roundTokenValue;
        long startAt = TcpJson.getLong(message, TcpServer.JSON_GAMESTART);
        long duration = TcpJson.getLong(message, TcpServer.JSON_GAMEDURATION);
        long gpsStartTime = GpsGameTime.INVALID_TIME;
        if (message.has(TcpServer.JSON_GPS_START_TIME)) {
            gpsStartTime = TcpJson.getLong(message, TcpServer.JSON_GPS_START_TIME);
            if (!GpsGameTime.isValidUtcTime(gpsStartTime))
                throw new JSONException("Invalid GPS synchronized start");
        }
        if (roundID <= 0 || !GameClock.validTimestamp(roundID) || !GameClock.validTimestamp(startAt)
                || duration < 0 || duration > Globals.MAX_GAME_LIMIT * 60000L
                || !TcpServer.isValidRoundToken(roundToken))
            throw new JSONException("Invalid synchronized game start");
        return TcpServer.createStartInfo(roundID, startAt, duration, roundToken, gpsStartTime);
    }

    private void queueSynchronizedStart(long generation, JSONObject startInfo, Intent intent) throws JSONException {
        synchronized (this) {
            if (!isCurrentSession(generation))
                return;
            long roundID = TcpJson.getLong(startInfo, TcpServer.JSON_ROUND_ID);
            // Clock sync can delay publication of a start. Keep the newest plan
            // during that delay; otherwise a late packet for an older round can
            // replace a newer pending plan and start the wrong game.
            long pendingRoundID = mPendingStartInfo == null ? 0
                    : TcpJson.getLong(mPendingStartInfo, TcpServer.JSON_ROUND_ID);
            if (roundID < mLastStartRound || (roundID == mLastStartRound
                    && NetMsg.NETMSG_STARTGAME.equals(intent.getAction()))
                    || roundID < pendingRoundID)
                return;
            mPendingStartInfo = startInfo;
            mPendingStartIntent = intent;
        }
        publishPendingStart(generation);
    }

    private synchronized void publishPendingStart(long generation) throws JSONException {
        if (!isCurrentSession(generation) || !mClockSynchronized || mPendingStartInfo == null)
            return;
        long hostStartAt = TcpJson.getLong(mPendingStartInfo, TcpServer.JSON_GAMESTART);
        long startAt = mGameClock.toLocalTime(hostStartAt);
        if (mPendingStartInfo.has(TcpServer.JSON_GPS_START_TIME)) {
            long gpsStartTime = TcpJson.getLong(mPendingStartInfo, TcpServer.JSON_GPS_START_TIME);
            long gpsStartAt = Globals.getInstance().mGpsGameTime.elapsedTimeForUtc(gpsStartTime,
                    SystemClock.elapsedRealtime());
            if (gpsStartAt >= 0 && Math.abs(gpsStartAt - startAt) <= GPS_START_REFINEMENT_LIMIT_MS)
                startAt = gpsStartAt;
        }
        long duration = TcpJson.getLong(mPendingStartInfo, TcpServer.JSON_GAMEDURATION);
        long roundID = TcpJson.getLong(mPendingStartInfo, TcpServer.JSON_ROUND_ID);
        Object roundTokenValue = mPendingStartInfo.get(TcpServer.JSON_ROUND_TOKEN);
        if (!(roundTokenValue instanceof String))
            throw new JSONException("Invalid pending game round token");
        String roundToken = (String) roundTokenValue;
        long now = SystemClock.elapsedRealtime();
        Intent intent = mPendingStartIntent;
        mPendingStartInfo = null;
        mPendingStartIntent = null;
        if (startAt - now > Globals.MAX_RESPAWN_TIME_SECONDS * 1000 + GameClock.MAX_ROUND_TRIP_MS)
            throw new JSONException("Synchronized start is too far in the future");
        mLastStartRound = roundID;
        if (duration > 0 && startAt + duration <= now) {
            // A start plan may reach a paused client after its timed round has
            // already expired. An active TCP session must retire normally; the
            // parser is also used before a worker has been started, where
            // finishServerSession intentionally ignores inactive sessions.
            // In that latter case still tell the UI not to start a fresh
            // countdown from a finished round.
            if (keepListening)
                finishServerSession(NetMsg.NETMSG_ENDGAME);
            else
                broadcastIfCurrentSession(generation, new Intent(NetMsg.NETMSG_ENDGAME));
            return;
        }
        mActiveHostStartAt = hostStartAt;
        mActiveStartDuration = duration;
        mActiveLocalStartAt = startAt;
        mActiveStartRound = roundID;
        mActiveStartToken = roundToken;
        intent.putExtra(NetMsg.INTENT_START_AT, startAt)
                .putExtra(NetMsg.INTENT_END_AT, duration == 0 ? 0 : startAt + duration)
                .putExtra(NetMsg.INTENT_ROUND_ID, roundID)
                .putExtra(NetMsg.INTENT_ROUND_TOKEN, roundToken)
                .putExtra(NetMsg.INTENT_PEER_GAME, !mIsDedicatedServer)
                .putExtra(EXTRA_START_EVENT_ID, terminalEventIds.incrementAndGet());
        mPendingGameStartEvent = new Intent(intent);
        broadcastIfCurrentSession(generation, intent);
        // Peer hosts close TCP after announcing a round. Stop reconnecting here,
        // even if the activity is paused, and retain the start until it resumes.
        if (!mIsDedicatedServer)
            keepListening = false;
    }

    /**
     * Broadcast a local-only adjustment for a start that the activity is
     * already counting down to.  The host deadline itself remains unchanged;
     * only this phone's conversion from the host monotonic clock is refined.
     */
    private synchronized void publishClockAdjustedStart(long generation) {
        if (!isCurrentSession(generation) || !mClockSynchronized
                || mActiveHostStartAt < 0 || mActiveStartToken == null)
            return;
        long now = SystemClock.elapsedRealtime();
        if (!hasActiveStartCountdownLocked(now))
            return;
        final long adjustedStartAt;
        try {
            adjustedStartAt = mGameClock.toLocalTime(mActiveHostStartAt);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (Math.abs(adjustedStartAt - mActiveLocalStartAt) < START_CLOCK_ADJUSTMENT_THRESHOLD_MS)
            return;
        long adjustedEndAt = mActiveStartDuration == 0 ? 0 : adjustedStartAt + mActiveStartDuration;
        Intent adjustment = new Intent(NetMsg.NETMSG_STARTGAME)
                .putExtra(NetMsg.INTENT_START_TIME_ADJUSTMENT, true)
                .putExtra(NetMsg.INTENT_START_AT, adjustedStartAt)
                .putExtra(NetMsg.INTENT_END_AT, adjustedEndAt)
                .putExtra(NetMsg.INTENT_ROUND_ID, mActiveStartRound)
                .putExtra(NetMsg.INTENT_ROUND_TOKEN, mActiveStartToken)
                .putExtra(NetMsg.INTENT_PEER_GAME, !mIsDedicatedServer);
        mActiveLocalStartAt = adjustedStartAt;
        broadcastIfCurrentSession(generation, adjustment);
    }

    public synchronized Intent consumePendingGameStart(long eventId) {
        if (mPendingGameStartEvent == null || (eventId != 0
                && mPendingGameStartEvent.getLongExtra(EXTRA_START_EVENT_ID, 0) != eventId))
            return null;
        Intent event = new Intent(mPendingGameStartEvent);
        event.removeExtra(EXTRA_START_EVENT_ID);
        mPendingGameStartEvent = null;
        return event;
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
            mPersistentDrainRequested = false;
            mPendingTerminalEvent = null;
            mIsDedicatedServer = false;
            resetClockSyncLocked();
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
            final boolean restart;
            synchronized (this) {
                keepListening = false;
                isListening = false;
                mClientThread = null;
                restart = mRestartAfterHostTakeover && !mDestroyed;
                mRestartAfterHostTakeover = false;
            }
            if (restart)
                startTcpClient();
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
                s.setTcpNoDelay(true);
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
                    resetClockSyncLocked();
                    sendPlayerInfo(rejoin);
                }
                wasConnected = true;
                if (rejoin)
                    sendBroadcast(new Intent(NetMsg.NETMSG_NETWORKCONNECTED));
                rejoin = true;
                int noReadCount = 0;
                long idleTick = SystemClock.elapsedRealtime();
                retryCount = MAX_REJOIN_TRIES;
                while (keepListening && out == connectionOut) {
                    requestClockSync();
                    String message = messageReader.poll(in);
                    if (message != null) {
                        noReadCount = 0;
                        idleTick = SystemClock.elapsedRealtime();
                        if (message.equals(TcpServer.TCP_SERVER_PING))
                            sendPong();
                        else {
                            if (BuildConfig.DEBUG)
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
                                } else if (message.equals(NetMsg.NETMSG_RESPAWNGRANTED)) {
                                    sendBroadcast(new Intent(NetMsg.NETMSG_RESPAWNGRANTED));
                                } else if (message.equals(NetMsg.NETMSG_ENDGAME)) {
                                    finishServerSession(NetMsg.NETMSG_ENDGAME);
                                    break;
                                } else if (message.equals(NetMsg.NETMSG_STARTGAME)) {
                                    Log.w(TAG, "Ignoring a start without a synchronized deadline");
                                } else if (message.equals(NetMsg.NETMSG_CLOCKSYNCWAITING)) {
                                    sendBroadcast(new Intent(NetMsg.NETMSG_CLOCKSYNCWAITING));
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
                        int readWait = mIsDedicatedServer ? TcpServer.TCP_DEDICATED_READ_WAIT_MS : TcpServer.TCP_READ_WAIT_MS;
                        boolean sampling;
                        synchronized (this) { sampling = mGameClock.samples() < GameClock.SAMPLES_PER_SYNC; }
                        sleep(sampling ? TcpServer.CLOCK_READ_WAIT_MS : readWait);
                        long now = SystemClock.elapsedRealtime();
                        if (now - idleTick >= readWait) {
                            noReadCount += (int) ((now - idleTick) / readWait);
                            idleTick = now;
                        }
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
            // A terminal frame can already be parsed when the user leaves or
            // stops the client. Do not let that retired session restore an
            // ENDGAME/CANCEL event after its state was deliberately cleared.
            if (mDestroyed || !keepListening)
                return;
            // Terminal messages must stop reconnecting even while the activity
            // is paused and its broadcast receiver is not registered.
            keepListening = false;
            mSessionGeneration++;
            out = null;
            messageQueue = new ConcurrentLinkedQueue<>();
            mPersistentDrainRequested = false;
            // This terminal event ends the server relationship. Retaining the
            // old dedicated flag makes the next peer-host lobby send its
            // gameplay traffic down a stopped TCP connection.
            mIsDedicatedServer = false;
            notification = new Intent(action).putExtra(EXTRA_TERMINAL_EVENT_ID, terminalEventIds.incrementAndGet());
            mPendingTerminalEvent = notification;
            resetClockSyncLocked();
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
            sendPriorityTCPMessage(message);
            // The registration frame establishes the player ID that authorizes
            // the settings frame. Send saved settings immediately afterward so
            // a player does not silently use server defaults until opening the
            // settings dialog again.
            sendPlayerSettings(true);
            sendQueuedMessages();
        } catch (JSONException e) {
            e.printStackTrace();
        }
        // A disarm can happen while offline. Re-publish zero as well as paired
        // IDs on registration so reconnecting cannot resurrect stale ownership.
        sendPlayerGrenade(true);
    }
//TODO player presets
    public void sendPlayerSettings() {
        sendPlayerSettings(false);
    }

    private void sendPlayerSettings(boolean priority) {
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
            if (priority)
                sendPriorityTCPMessage(message);
            else
                sendTCPMessage(message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void sendPlayerGrenade() {
        sendPlayerGrenade(false);
    }

    /**
     * Report a confirmed blaster shot to the host's optional live dashboard.
     * This is intentionally non-persistent: a delayed shot from a prior life
     * or round must never inflate the next round's accuracy.
     */
    public void reportHostedShots(int count) {
        if (!mIsDedicatedServer || count < 1 || count > Globals.MAX_RELOAD_COUNT)
            return;
        try {
            JSONObject telemetry = new JSONObject();
            telemetry.put(TcpServer.JSON_TELEMETRY, TcpServer.JSON_TELEMETRY_SHOT);
            telemetry.put(TcpServer.JSON_TELEMETRY_COUNT, count);
            sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + telemetry.toString());
        } catch (JSONException e) {
            Log.w(TAG, "Unable to encode hosted shot telemetry", e);
        }
    }

    /**
     * The receiving phone is the one that can verify an IR hit. Reporting the
     * attacker's ID lets any host draw a verified trace, count a hit, and grant
     * the two involved opponents a short-lived GPS reveal without trusting a
     * self-reported miss or kill.
     */
    public void reportHostedHit(int attackerID) {
        // The receiving blaster is the authoritative source of a hit in both
        // phone-hosted and dedicated games.  A normal phone host uses this
        // report for the short enemy-GPS reveal as well as the dashboard.
        if (attackerID <= 0 || !Globals.isValidPlayerID(attackerID))
            return;
        try {
            JSONObject telemetry = new JSONObject();
            telemetry.put(TcpServer.JSON_TELEMETRY, TcpServer.JSON_TELEMETRY_HIT);
            telemetry.put(TcpServer.JSON_TELEMETRY_ATTACKER, attackerID);
            sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + telemetry.toString());
        } catch (JSONException e) {
            Log.w(TAG, "Unable to encode hosted hit telemetry", e);
        }
    }

    private void sendPlayerGrenade(boolean priority) {
        try {
            JSONObject playerGrenade = new JSONObject();
            playerGrenade.put(TcpServer.JSON_PLAYERID, Globals.getInstance().mPlayerID);
            playerGrenade.put(TcpServer.JSON_PAIRED_GRENADE_ID, Globals.getInstance().mPairedGrenadeID);
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + playerGrenade.toString();
            if (priority)
                sendPriorityTCPMessage(message);
            else
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
            mRestartAfterHostTakeover = false;
            // A later lobby can be peer-hosted. Do not let a prior dedicated
            // roster influence its score, grenade, or leave routing.
            mIsDedicatedServer = false;
            resetClockSyncLocked();
            // Established connections are drained by the reader before closing.
            // Startup/retry waits have no outgoing writer and can be interrupted now.
            connectingSocket = out == null ? mActiveSocket : null;
            connectingThread = out == null ? mClientThread : null;
        }
        try { if (connectingSocket != null) connectingSocket.close(); } catch (IOException e) { /* ignored */ }
        if (connectingThread != null)
            connectingThread.interrupt();
    }

    /**
     * Retire the current TCP session and connect to the current
     * {@link Globals#mServerIP} once its reader has completely stopped. This
     * is used by an explicit pre-game host takeover, where immediately starting
     * a second reader can otherwise leave the old session owning the client.
     */
    public void restartAfterHostTakeover() {
        final Socket socket;
        final Thread clientThread;
        final boolean startImmediately;
        synchronized (this) {
            if (mDestroyed || sendExecutor.isShutdown())
                return;
            mSessionGeneration++;
            keepListening = false;
            mIsDedicatedServer = false;
            resetClockSyncLocked();
            out = null;
            messageQueue = new ConcurrentLinkedQueue<>();
            mPersistentDrainRequested = false;
            mPendingTerminalEvent = null;
            socket = mActiveSocket;
            clientThread = mClientThread;
            startImmediately = !isListening;
            mRestartAfterHostTakeover = !startImmediately;
        }
        try { if (socket != null) socket.close(); } catch (IOException e) { /* ignored */ }
        if (clientThread != null)
            clientThread.interrupt();
        if (startImmediately)
            startTcpClient();
    }

    public void leaveServer() {
        if (keepListening) {
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_LEAVE;
            sendPriorityTCPMessage(message);
            stopTcpClient();
        }
    }

    /** Leave a dedicated game without requesting that the host end its round. */
    public void quitGame() {
        if (keepListening) {
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_QUIT;
            sendPriorityTCPMessage(message);
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

    private static boolean isSameGPSData(Globals.GPSData current, Globals.GPSData incoming) {
        return current != null && current.team == incoming.team
                && Double.compare(current.longitude, incoming.longitude) == 0
                && Double.compare(current.latitude, incoming.latitude) == 0;
    }

    private void parseGameInfo(String message) {
        final long receivedAt = SystemClock.elapsedRealtime();
        final long generation;
        synchronized (this) {
            if (mDestroyed || (isListening && !keepListening))
                return;
            generation = mSessionGeneration;
        }
        try {
            JSONObject game = TcpJson.parseObject(message);
            if (game.has(TcpServer.JSON_CLOCK_REQUEST)) {
                receiveClockSync(game, receivedAt, generation);
                return;
            }
            JSONObject startInfo = game.has(TcpServer.JSON_GAMESTART) ? readStartInfo(game) : null;
            if (startInfo != null && !game.has(TcpServer.JSON_PLAYERS)) {
                queueSynchronizedStart(generation, startInfo, new Intent(NetMsg.NETMSG_STARTGAME));
                return;
            }
            if (game.has(TcpServer.JSON_GRENADE_PAIRINGS)) {
                int[] pairings = new int[Globals.MAX_GRENADE_IDS];
                boolean[] pairedGrenades = new boolean[Globals.MAX_GRENADE_IDS];
                boolean[] pairedPlayers = new boolean[Globals.MAX_PLAYER_ID + 1];
                Arrays.fill(pairings, Globals.INVALID_PLAYER_ID);
                JSONArray grenadePairings = game.getJSONArray(TcpServer.JSON_GRENADE_PAIRINGS);
                if (grenadePairings.length() > Globals.MAX_GRENADE_IDS)
                    throw new JSONException("Too many grenade pairings in snapshot");
                for (int x = 0; x < grenadePairings.length(); x++) {
                    JSONObject grenadePairing = grenadePairings.getJSONObject(x);
                    int grenadeID = TcpJson.getInt(grenadePairing, TcpServer.JSON_PAIRED_GRENADE_ID);
                    int playerID = TcpJson.getInt(grenadePairing, TcpServer.JSON_PLAYERID);
                    if (!Globals.isValidGrenadeID(grenadeID) || grenadeID <= 0
                            || !Globals.isValidPlayerID(playerID) || playerID <= 0)
                        throw new JSONException("Invalid grenade pairing snapshot");
                    if (pairedGrenades[grenadeID] || pairedPlayers[playerID])
                        throw new JSONException("Conflicting grenade pairing snapshot");
                    pairedGrenades[grenadeID] = true;
                    pairedPlayers[playerID] = true;
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
                if (updates.length() > Globals.MAX_PLAYER_ID)
                    throw new JSONException("Too many GPS updates in snapshot");
                Map<Byte, Globals.GPSData> locations = new HashMap<>();
                boolean[] seenPlayers = new boolean[Globals.MAX_PLAYER_ID + 1];
                boolean fullUpdate = game.has(TcpServer.JSON_GPSFULLUPDATE)
                        && game.getBoolean(TcpServer.JSON_GPSFULLUPDATE);
                for (int x = 0; x < updates.length(); x++) {
                    JSONObject update = updates.getJSONObject(x);
                    int rawPlayerID = TcpJson.getInt(update, TcpServer.JSON_PLAYERID);
                    if (!Globals.isValidPlayerID(rawPlayerID) || rawPlayerID <= 0)
                        throw new JSONException("Invalid GPS player ID " + rawPlayerID);
                    if (seenPlayers[rawPlayerID])
                        throw new JSONException("Conflicting GPS player snapshot");
                    seenPlayers[rawPlayerID] = true;
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
                        gps.team = TcpJson.getInt(update, TcpServer.JSON_TEAM);
                        gps.hasUpdate = true; // Anything that the server sends us is considered an update
                        locations.put(playerID, gps);
                    }
                }
                Globals.getmGPSDataSemaphore();
                try {
                    synchronized (this) {
                        if (!isCurrentSession(generation))
                            return;
                        Map<Byte, Globals.GPSData> currentLocations = Globals.getInstance().mGPSData;
                        if (currentLocations == null) {
                            currentLocations = new HashMap<>();
                            Globals.getInstance().mGPSData = currentLocations;
                        }
                        if (fullUpdate) {
                            // Reconcile rather than clear-and-recreate unchanged entries.  That
                            // lets the map retain its native marker objects during the periodic
                            // full snapshot, while still removing players absent from it.
                            Iterator<Map.Entry<Byte, Globals.GPSData>> existing
                                    = currentLocations.entrySet().iterator();
                            while (existing.hasNext()) {
                                if (!locations.containsKey(existing.next().getKey()))
                                    existing.remove();
                            }
                        }
                        for (Map.Entry<Byte, Globals.GPSData> entry : locations.entrySet()) {
                            Globals.GPSData current = currentLocations.get(entry.getKey());
                            if (!isSameGPSData(current, entry.getValue())) {
                                currentLocations.put(entry.getKey(), entry.getValue());
                            }
                            // If the previous event is still waiting for the UI, preserve its
                            // update marker; otherwise identical data needs no map rebuild.
                        }
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
                JSONArray playerData = game.getJSONArray(TcpServer.JSON_PLAYERDATA);
                if (playerData.length() > Globals.MAX_PLAYER_ID)
                    throw new JSONException("Too many players in scoreboard snapshot");
                boolean[] seenPlayers = new boolean[Globals.MAX_PLAYER_ID + 1];
                for (int x = 0; x < playerData.length(); x++) {
                    JSONObject player = playerData.getJSONObject(x);
                    int playerID = TcpJson.getInt(player, TcpServer.JSON_PLAYERID);
                    int points = TcpJson.getInt(player, TcpServer.JSON_PLAYERPOINTS);
                    int eliminated = TcpJson.getInt(player, TcpServer.JSON_PLAYERELIMINATED);
                    if (!Globals.isValidPlayerID(playerID) || playerID <= 0 || seenPlayers[playerID]
                            || points < 0 || points > MAX_SCOREBOARD_VALUE
                            || eliminated < 0 || eliminated > MAX_SCOREBOARD_VALUE) {
                        throw new JSONException("Invalid player-data scoreboard snapshot");
                    }
                    TcpJson.getPlayerName(player, TcpServer.JSON_PLAYERNAME);
                    seenPlayers[playerID] = true;
                }
                Intent intent = new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE);
                intent.putExtra(NetMsg.INTENT_PLAYERDATA, message);
                broadcastIfCurrentSession(generation, intent);
                return;
            }
            Map<Byte, Globals.PlayerSettings> settingsUpdate = null;
            boolean allowPlayerSettings = false;
            final boolean tournamentModeSpecified = game.has(TcpServer.JSON_TOURNAMENT_MODE);
            final boolean tournamentMode = tournamentModeSpecified
                    && TcpJson.getBoolean(game, TcpServer.JSON_TOURNAMENT_MODE);
            final Boolean onlyServerSettingsUpdate = game.has(TcpServer.JSON_ONLY_SERVER_SETTINGS)
                    ? TcpJson.getBoolean(game, TcpServer.JSON_ONLY_SERVER_SETTINGS) : null;
            if (game.has(TcpServer.JSON_PLAYERSETTINGS)) {
                JSONArray settings = game.getJSONArray(TcpServer.JSON_PLAYERSETTINGS);
                if (settings.length() > Globals.MAX_PLAYER_ID)
                    throw new JSONException("Too many player settings in snapshot");
                settingsUpdate = new HashMap<>();
                allowPlayerSettings = game.getBoolean(TcpServer.JSON_ALLOWPLAYERSETTINGS);
                for (int x = 0; x < settings.length(); x++) {
                    JSONObject setting = settings.getJSONObject(x);
                    int rawPlayerID = TcpJson.getInt(setting, TcpServer.JSON_PLAYERID);
                    int health = TcpJson.getInt(setting, TcpServer.JSON_HEALTH);
                    int reloadShots = TcpJson.getInt(setting, TcpServer.JSON_RELOAD_SHOTS);
                    long reloadTime = TcpJson.getLong(setting, TcpServer.JSON_RELOAD_TIME);
                    boolean reloadOnEmpty = setting.getBoolean(TcpServer.JSON_RELOAD_ON_EMPTY);
                    long spawnTime = TcpJson.getLong(setting, TcpServer.JSON_SPAWN_TIME);
                    int damage = TcpJson.getInt(setting, TcpServer.JSON_DAMAGE);
                    boolean overrideLives = setting.has(TcpServer.JSON_LIVESLIMIT);
                    int lives = overrideLives ? TcpJson.getInt(setting, TcpServer.JSON_LIVESLIMIT) : 0;
                    boolean allowSingle = setting.getBoolean(TcpServer.JSON_SHOT_MODE_SINGLE);
                    boolean allowBurst = setting.getBoolean(TcpServer.JSON_SHOT_MODE_BURST3);
                    boolean allowAuto = setting.getBoolean(TcpServer.JSON_SHOT_MODE_AUTO);
                    int firingMode = TcpJson.getInt(setting, TcpServer.JSON_FIRING_MODE);
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
                if (players.length() > Globals.MAX_PLAYER_ID)
                    throw new JSONException("Too many players in roster snapshot");
                boolean[] seenPlayers = new boolean[Globals.MAX_PLAYER_ID + 1];
                for (int x = 0; x < players.length(); x++) {
                    JSONObject player = players.getJSONObject(x);
                    int rawPlayerID = TcpJson.getInt(player, TcpServer.JSON_PLAYERID);
                    if (!Globals.isValidPlayerID(rawPlayerID) || rawPlayerID <= 0)
                        throw new JSONException("Invalid roster player ID " + rawPlayerID);
                    if (seenPlayers[rawPlayerID])
                        throw new JSONException("Conflicting player roster snapshot");
                    seenPlayers[rawPlayerID] = true;
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
                        String playerName = TcpJson.getPlayerName(player, TcpServer.JSON_PLAYERNAME);
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
                    int value = TcpJson.getInt(limits, TcpServer.JSON_TIMELIMIT);
                    if (value > 0 && Globals.isValidGameLimit(value)) {
                        gameLimit |= Globals.GAME_LIMIT_TIME;
                        timeLimit = value;
                    } else {
                        Log.w(TAG, "Ignoring invalid time limit from server: " + value);
                    }
                }
                if (limits.has(TcpServer.JSON_LIVESLIMIT)) {
                    int value = TcpJson.getInt(limits, TcpServer.JSON_LIVESLIMIT);
                    if (value > 0 && Globals.isValidGameLimit(value)) {
                        gameLimit |= Globals.GAME_LIMIT_LIVES;
                        livesLimit = value;
                    } else {
                        Log.w(TAG, "Ignoring invalid lives limit from server: " + value);
                    }
                }
                if (limits.has(TcpServer.JSON_SCORELIMIT)) {
                    int value = TcpJson.getInt(limits, TcpServer.JSON_SCORELIMIT);
                    if (value > 0 && Globals.isValidGameLimit(value)) {
                        gameLimit |= Globals.GAME_LIMIT_SCORE;
                        scoreLimit = value;
                    } else {
                        Log.w(TAG, "Ignoring invalid score limit from server: " + value);
                    }
                }
                int gameMode = TcpJson.getInt(game, TcpServer.JSON_GAMEMODE);
                if (!Globals.isValidGameMode(gameMode)) {
                    Log.w(TAG, "Ignoring invalid game mode from server: " + gameMode);
                    gameMode = globals.mGameMode;
                }
                boolean useGPS = game.has(TcpServer.JSON_USEGPS);
                int gpsMode = globals.mGPSMode;
                if (useGPS) {
                    int value = TcpJson.getInt(game, TcpServer.JSON_USEGPS);
                    if (Globals.isValidGPSMode(value) && value != Globals.GPS_DISABLED) {
                        gpsMode = value;
                    } else {
                        Log.w(TAG, "Ignoring invalid GPS mode from server: " + value);
                        useGPS = false;
                    }
                }
                if (onlyServerSettingsUpdate == null)
                    throw new JSONException("Missing server settings policy");
                boolean onlyServerSettings = onlyServerSettingsUpdate;
                if (tournamentMode) {
                    if (gameMode != Globals.GAME_MODE_2TEAMS)
                        throw new JSONException("Tournament mode must use two teams");
                    // These rules are deliberately enforced at both ends.  A future server
                    // change cannot accidentally advertise a tournament while permitting a
                    // player-specific auto/burst profile.
                    onlyServerSettings = true;
                    allowPlayerSettings = false;
                }
                Intent intent = new Intent(NetMsg.NETMSG_LISTPLAYERS);
                if (game.has(TcpServer.JSON_PLAYERGAMEUPDATE)) {
                    intent.putExtra(NetMsg.INTENT_HASGAMEUPDATE, true);
                    JSONObject playerGameUpdate = game.getJSONObject(TcpServer.JSON_PLAYERGAMEUPDATE);
                    int points = TcpJson.getInt(playerGameUpdate, TcpServer.JSON_PLAYERPOINTS);
                    int eliminations = TcpJson.getInt(playerGameUpdate, TcpServer.JSON_PLAYERELIMINATED);
                    if (points < 0 || points > MAX_SCOREBOARD_VALUE
                            || eliminations < 0 || eliminations > MAX_SCOREBOARD_VALUE)
                        throw new JSONException("Invalid player game scores from server");
                    intent.putExtra(NetMsg.INTENT_SCORE, points);
                    intent.putExtra(NetMsg.INTENT_ELIMINATIONS, eliminations);
                    if (playerGameUpdate.has(TcpServer.JSON_TEAMPOINTS)) {
                        int teamPoints = TcpJson.getInt(playerGameUpdate, TcpServer.JSON_TEAMPOINTS);
                        if (teamPoints < 0 || teamPoints > Globals.MAX_TEAM_SCOREBOARD_VALUE)
                            throw new JSONException("Invalid team game score from server");
                        intent.putExtra(NetMsg.INTENT_TEAMSCORE, teamPoints);
                    }
                    if (playerGameUpdate.has(TcpServer.JSON_TIMEREMAINING)) {
                        long timeRemaining = TcpJson.getLong(playerGameUpdate, TcpServer.JSON_TIMEREMAINING);
                        long maxGameTimeSeconds = (long) Globals.MAX_GAME_LIMIT * 60 + Globals.MAX_RESPAWN_TIME_SECONDS;
                        if (timeRemaining >= 0 && timeRemaining <= maxGameTimeSeconds)
                            intent.putExtra(NetMsg.INTENT_TIMEREMAINING, timeRemaining);
                        else
                            Log.w(TAG, "Ignoring invalid remaining game time from server: " + timeRemaining);
                    }
                }
                boolean dedicatedServer = game.has(TcpServer.JSON_DEDICATED) && game.getBoolean(TcpServer.JSON_DEDICATED);
                if (dedicatedServer) {
                    int gameState = TcpJson.getInt(game, TcpServer.JSON_GAMESTATE);
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
                                    globals.mTournamentMode = tournamentMode;
                                    if (tournamentMode)
                                        globals.applyTournamentRules();
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
                        if (tournamentModeSpecified) {
                            Globals.getInstance().mTournamentMode = tournamentMode;
                            if (tournamentMode) {
                                Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
                                Globals.getInstance().mOnlyServerSettings = true;
                                Globals.getInstance().applyTournamentRules();
                                allowPlayerSettings = false;
                            } else if (onlyServerSettingsUpdate != null) {
                                Globals.getInstance().mOnlyServerSettings = onlyServerSettingsUpdate;
                            }
                        } else if (onlyServerSettingsUpdate != null) {
                            Globals.getInstance().mOnlyServerSettings = onlyServerSettingsUpdate;
                        }
                        applyPlayerSettingsLocked(settingsUpdate, allowPlayerSettings);
                    }
                } finally {
                    Globals.getInstance().mPlayerSettingsSemaphore.release();
                }
            }
            if (settingsUpdate != null)
                broadcastIfCurrentSession(generation, new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
            if (rosterIntent != null) {
                if (startInfo != null)
                    queueSynchronizedStart(generation, startInfo, rosterIntent);
                else
                    broadcastIfCurrentSession(generation, rosterIntent);
            }
        } catch (JSONException | RuntimeException e) {
            e.printStackTrace();
        }
    }

    // Caller holds both the service monitor and the player-settings semaphore.
    private void applyPlayerSettingsLocked(Map<Byte, Globals.PlayerSettings> settings, boolean allowPlayerSettings) {
        Globals globals = Globals.getInstance();
        // Server messages contain a complete settings snapshot. Retaining entries
        // that are absent here carries old lobby damage or lives settings into a
        // new game when the same player ID is reused.
        globals.mPlayerSettings.clear();
        globals.mPlayerSettings.putAll(settings);
        if (globals.mTournamentMode) {
            for (Globals.PlayerSettings playerSettings : globals.mPlayerSettings.values())
                Globals.applyTournamentRules(playerSettings);
            globals.applyTournamentRules();
            return;
        }
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
