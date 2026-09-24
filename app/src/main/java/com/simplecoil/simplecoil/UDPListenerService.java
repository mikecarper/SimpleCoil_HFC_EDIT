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
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class UDPListenerService extends Service {
    private static final String TAG = "UDPSvc";

    private static final Integer LISTEN_PORT = 17500;
    private static final Integer LISTEN_TIMEOUT_MS = 1000;
    // Avoid IPv4 fragmentation on a normal 1500-byte Wi-Fi/Ethernet MTU.
    private static final int RECEIVE_BUFFER_SIZE = PeerStatePacket.MAX_UDP_PAYLOAD_BYTES;

    private static ScheduledThreadPoolExecutor createCombatExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
                runnable -> new Thread(runnable, "SimpleCoil combat send"));
        // ACKs continually replace one shared retry alarm. Remove cancelled
        // alarms immediately so a long round cannot retain stale timer nodes.
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private volatile DatagramSocket mSocket;
    // Outbound events use one socket for the lifetime of the service.  Creating a fresh UDP
    // socket for every recipient burns file descriptors and kernel work during a firefight.
    // Access is serialized by mSendLock.
    // Volatile lets destruction close an in-flight socket without waiting for a
    // sender that is blocked on mSendLock. DatagramSocket.close() is safe from
    // another thread and unblocks a concurrent send.
    private volatile DatagramSocket mSendSocket;

    WifiManager wm = null;
    WifiManager.MulticastLock multicastLock = null;
    WifiManager.WifiLock wifiLock = null;

    private InetAddress mMyIP = null;
    private InetAddress mBroadcastAddress = null;

    private static final long LISTENER_START_TIMEOUT_MS = 5000;
    // An invite waits ten seconds on the receiving phone before it joins. Keep
    // discovery open long enough for that choice and the UDP/TCP hand-off.
    private static final long GAME_INVITE_JOIN_WINDOW_MS = 20_000;
    private static final int GAME_INVITE_BROADCAST_REPETITIONS = 3;
    private static final long INVITE_LISTENER_RETRY_MS = 100;
    // Combat messages can arrive much faster than a congested Wi-Fi link can
    // transmit them. Keep the sender bounded so a stalled socket cannot create
    // an unbounded number of Java threads or queued packet snapshots.
    static final int MAX_PENDING_DATAGRAM_SENDS = 64;
    private final Object mSendLock = new Object();
    // Retain one worker once it has been used.  Combat bursts commonly arrive more than
    // 100 ms apart, so allowing the old core-zero executor to time out recreated a thread for
    // nearly every burst.
    private final ThreadPoolExecutor mSendExecutor = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING_DATAGRAM_SENDS),
            runnable -> new Thread(runnable, "SimpleCoil UDP send"),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    // Combat never waits behind a 32-recipient state-tick fan-out or the UI
    // looper. One persistent worker owns both sends and the shared retry alarm;
    // this is one thread per service, not one thread/timer per packet.
    private final ScheduledThreadPoolExecutor mCombatExecutor = createCombatExecutor();
    private final AtomicInteger mQueuedCombatSends = new AtomicInteger();
    // A manual join can require DNS. Keep an unresolved lookup from creating a
    // new thread for every tap, while retaining the most recent address request.
    static final int MAX_PENDING_SERVER_LOOKUPS = 1;
    private final ThreadPoolExecutor mLookupExecutor = new ThreadPoolExecutor(0, 1,
            100, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING_SERVER_LOOKUPS),
            runnable -> new Thread(runnable, "SimpleCoil UDP lookup"),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile boolean keepListening = false;
    private volatile boolean doneListening = true;
    private volatile boolean mIsListService = false;
    private volatile boolean mGameRunning;
    // Dedicated games keep their roster authoritative over TCP.  A peer-hosted
    // round closes that TCP listener after the start announcement, so a player
    // leaving mid-round must be reflected by a validated UDP LEAVE instead.
    private volatile boolean mPeerGame;
    // A fresh synchronized round gets a nonce from the TCP start announcement.
    // UDP endpoint membership alone cannot distinguish a delayed prior-round
    // packet from a current one.
    private String mPeerRoundToken;
    // State gossip is active for both peer-hosted and dedicated rounds. The
    // peerGame flag still controls who may author gameplay events and ENDGAME.
    private boolean mStateGossipRound;
    private boolean mStateAuthority;
    private String mStateRoundToken;
    private UUID mStateRoundUuid;
    // A synchronized peer start can reach TcpClient while the activity is
    // paused. Keep its expected token separately until the activity makes the
    // round active, so an immediate matching peer ENDGAME is not discarded.
    private String mPendingPeerRoundToken;
    // The activity unregisters its UDP receiver while paused. Retain the one
    // terminal event that must still be applied when it returns, but never let
    // it survive a replacement peer round or a stopped listener.
    private Intent mPendingPeerEndGame;
    private static final int PEER_GRENADE_UPDATE_REPETITIONS = 3;
    private static final int PEER_SCORE_UPDATE_REPETITIONS = 3;
    private final Object mPeerGrenadeLock = new Object();
    // Player IDs are bounded, so a fixed snapshot is cheaper and safer than a
    // map fed by UDP input. Sequence zero means no update from that player yet.
    private final long[] mLastPeerGrenadeSequences = new long[Globals.MAX_PLAYER_ID + 1];
    private long mNextPeerGrenadeSequence;
    private final Object mPeerScoreLock = new Object();
    // An elimination sequence belongs to the eliminated player. Team score
    // relays retain that source sequence, so each scorer/victim pair has a
    // compact, bounded deduplication slot.
    private final long[] mLastPeerEliminationSequences = new long[Globals.MAX_PLAYER_ID + 1];
    private final long[][] mLastPeerTeamEliminationSequences =
            new long[Globals.MAX_PLAYER_ID + 1][Globals.MAX_PLAYER_ID + 1];
    // A final kill and its LEAVE can be reordered by UDP. Retain only peers
    // that were authenticated in this round so that final sequenced score
    // events remain attributable after roster removal.
    private final Map<InetAddress, Byte> mDepartedPeerScoreSources = new HashMap<>();
    private long mNextPeerEliminationSequence;
    static final long GAME_TICK_INTERVAL_MS = 1000;
    static final long GAME_TICK_DRIFT_GRACE_MS = GAME_TICK_INTERVAL_MS / 5;
    private final Object mPeerRuntimeLock = new Object();
    private final PeerStatePacket.PlayerState[] mPeerStates =
            new PeerStatePacket.PlayerState[PeerStatePacket.PLAYER_CAPACITY + 1];
    // Last cumulative rows accepted from an authoritative host tick, or from
    // the gossip fallback after its 20% grace window elapsed.
    private final PeerStatePacket.PlayerState[] mAppliedPeerStates =
            new PeerStatePacket.PlayerState[PeerStatePacket.PLAYER_CAPACITY + 1];
    private final long[] mLastPeerSnapshotSequences =
            new long[PeerStatePacket.PLAYER_CAPACITY + 1];
    // A 64-event window accepts distinct combat packets that Wi-Fi reordered,
    // while dropping retries without maps, boxing, or allocations.
    private final long[] mCombatHighestSequences =
            new long[PeerStatePacket.PLAYER_CAPACITY + 1];
    private final long[] mCombatSeenMasks =
            new long[PeerStatePacket.PLAYER_CAPACITY + 1];
    private static final int MAX_PENDING_COMBAT_ACKS = 64;
    private static final long[] COMBAT_RETRY_OFFSETS_MS = {20, 60};
    private final PendingCombatEvent[] mPendingCombatEvents =
            new PendingCombatEvent[MAX_PENDING_COMBAT_ACKS];
    // Guarded by mListenerStateLock.
    private ScheduledFuture<?> mCombatRetryFuture;
    private long mNextPeerSnapshotSequence;
    private long mNextAuthorityTickSequence;
    private long mLastAuthorityTickSequence;
    private long mLastAuthorityTickAt;
    private long mNextLocalPeerStateSequence;
    private long mNextLocalPeerEventSequence;
    private long mLocalGpsUpdatedAt;
    private int mLastPublishedTeamScore = -1;
    private volatile int mReadyToScan = 0;

    // UDP discovery happens before TCP owns a roster endpoint. Reserve a
    // proposed ID briefly so several phones with the same saved ID cannot all
    // receive the same apparent free slot during that gap.
    private static final long JOIN_ASSIGNMENT_TIMEOUT_MS = 10_000;
    private final Object mJoinAssignmentLock = new Object();
    private final Map<InetAddress, PendingJoinAssignment> mPendingJoinAssignments = new HashMap<>();

    private volatile boolean mScanRunning = false;
    private final Object mListenerStateLock = new Object();
    private boolean mDestroyed;
    // Idle player screens keep a lightweight listener for GAMEINVITE packets.
    // It is deliberately separate from a real discovery scan: an invite must
    // never make a phone look as though it has joined a lobby already.
    private volatile boolean mPassiveInviteListener;
    private boolean mInviteListenerRequested;
    private Runnable mInviteListenerRetry;
    // A host can temporarily reopen discovery for an invitation without
    // overriding an operator's persistent "allow late joins" choice.
    private long mInviteWindowGeneration;
    private boolean mInviteTemporarilyAllowsJoin;
    // Queued sends may drain after stopListen(), but not into a replacement lobby.
    // Separate from discovery's generation, which changes on a successful reply.
    private long mSendGeneration;
    private volatile long mJoinGeneration;
    private InetAddress mJoinAddress;
    private boolean mBroadcastScan;
    private CountDownTimer mJoinTimer;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private boolean mPeerStartReceiverRegistered;
    private final Runnable mPeerStateHeartbeat = new Runnable() {
        @Override public void run() {
            synchronized (mListenerStateLock) {
                if (mDestroyed || !mStateGossipRound)
                    return;
            }
            broadcastPeerState(1);
            mMainHandler.postDelayed(this, GAME_TICK_INTERVAL_MS);
        }
    };
    private final Runnable mAuthorityTickHeartbeat = new Runnable() {
        @Override public void run() {
            synchronized (mListenerStateLock) {
                if (mDestroyed || !mStateGossipRound || !mStateAuthority)
                    return;
            }
            broadcastAuthorityTick();
            mMainHandler.postDelayed(this, GAME_TICK_INTERVAL_MS);
        }
    };
    private final Runnable mGossipRecovery = new Runnable() {
        @Override public void run() {
            final String roundToken;
            synchronized (mListenerStateLock) {
                if (mDestroyed || !mStateGossipRound || mStateAuthority)
                    return;
                long now = SystemClock.elapsedRealtime();
                long deadline = mLastAuthorityTickAt + GAME_TICK_INTERVAL_MS
                        + GAME_TICK_DRIFT_GRACE_MS;
                if (now < deadline) {
                    mMainHandler.postDelayed(this, deadline - now);
                    return;
                }
                roundToken = mStateRoundToken;
                // Continue reconstructing once per missing tick until the
                // host cadence resumes. Each pass still applies newer rows only.
                mLastAuthorityTickAt += GAME_TICK_INTERVAL_MS;
            }
            recoverStateFromGossip(roundToken);
            synchronized (mListenerStateLock) {
                if (!mDestroyed && mStateGossipRound && !mStateAuthority
                        && roundToken != null && roundToken.equals(mStateRoundToken))
                    mMainHandler.postDelayed(this, GAME_TICK_INTERVAL_MS);
            }
        }
    };
    private final Runnable mCombatRetrySweep = new Runnable() {
        @Override public void run() {
            final List<PendingCombatEvent> retries = new ArrayList<>();
            final long generation;
            synchronized (mListenerStateLock) {
                mCombatRetryFuture = null;
                if (mDestroyed || !mStateGossipRound)
                    return;
                generation = mSendGeneration;
                long now = SystemClock.elapsedRealtime();
                for (int index = 0; index < mPendingCombatEvents.length; index++) {
                    PendingCombatEvent pending = mPendingCombatEvents[index];
                    if (pending == null || pending.nextRetryAt > now)
                        continue;
                    retries.add(pending);
                    pending.nextRetryIndex++;
                    if (pending.nextRetryIndex >= COMBAT_RETRY_OFFSETS_MS.length) {
                        // Initial transmission plus these two retries is the
                        // promised three-attempt ceiling.
                        mPendingCombatEvents[index] = null;
                    } else {
                        pending.nextRetryAt = pending.createdAt
                                + COMBAT_RETRY_OFFSETS_MS[pending.nextRetryIndex];
                    }
                }
                scheduleCombatRetryLocked(now);
            }
            for (PendingCombatEvent retry : retries) {
                if (retry.targetEndpoint != null) {
                    sendCombatDatagrams(retry.payload,
                            Collections.singletonList(retry.targetEndpoint), generation);
                } else {
                    // A transiently incomplete roster still gets the original
                    // broadcast behavior rather than silently losing retries.
                    sendCombatBroadcast(retry.payload, generation);
                }
            }
        }
    };

    private final BroadcastReceiver mPeerStartReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null)
                return;
            if (NetMsg.NETMSG_GPSLOCUPDATE.equals(intent.getAction())) {
                publishPeerLocation(intent.getDoubleExtra(NetMsg.INTENT_LONGITUDE, Double.NaN),
                        intent.getDoubleExtra(NetMsg.INTENT_LATITUDE, Double.NaN));
            } else if (NetMsg.NETMSG_STARTGAME.equals(intent.getAction())
                    && intent.getBooleanExtra(NetMsg.INTENT_PEER_GAME, false)) {
                preparePeerRound(intent.getStringExtra(NetMsg.INTENT_ROUND_TOKEN));
            }
        }
    };

    public static final String INTENT_PLAYERID = "playerid";
    public static final String INTENT_MESSAGE = "message";
    public static final String INTENT_SERVERIP = "serverip";
    public static final String INTENT_GAME_INVITE_TOKEN = "gameinvitetoken";

    private void listenForMessage(InetAddress ip, Integer port, Integer timeout) throws Exception {
        byte[] recvBuf = new byte[RECEIVE_BUFFER_SIZE];
        DatagramSocket socket = mSocket;
        try {
            if (socket == null || socket.isClosed()) {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.setBroadcast(true);
                socket.bind(new InetSocketAddress(port));
            }
            socket.setSoTimeout(timeout);
            DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
            if (BuildConfig.DEBUG)
                Log.d(TAG, "Waiting for UDP messages on " + ip + ":" + port);
            synchronized (mListenerStateLock) {
                if (!keepListening || mDestroyed)
                    return;
                mSocket = socket;
                // There is only one listener thread.  Readiness describes the
                // current bound socket, not how many times a failed listener
                // has retried, so a stale successful bind cannot keep a host
                // marked ready after its socket has gone away.
                mReadyToScan = 1;
                doneListening = false;
            }
            while (keepListening) {
                try {
                    packet.setLength(recvBuf.length);
                    socket.receive(packet);
                    if (CombatPacket.looksLikeCombat(packet.getData(), packet.getOffset(),
                            packet.getLength())) {
                        processCombatPacket(packet.getAddress(), packet.getData(),
                                packet.getOffset(), packet.getLength());
                    } else if (PeerStatePacket.looksLikePeerState(packet.getData(), packet.getOffset(),
                            packet.getLength())) {
                        processPeerStatePacket(packet.getAddress(), packet.getData(),
                                packet.getOffset(), packet.getLength());
                    } else {
                        String message = new String(packet.getData(), packet.getOffset(),
                                packet.getLength(), StandardCharsets.UTF_8);
                        processMessage(packet.getAddress(), message);
                    }
                } catch (SocketTimeoutException e) {
                    // do nothing
                } catch (RuntimeException e) {
                    Log.w(TAG, "Ignoring malformed UDP message", e);
                }
            }
        } finally {
            if (socket != null)
                socket.close();
            synchronized (mListenerStateLock) {
                if (mSocket == socket) {
                    mSocket = null;
                    mReadyToScan = 0;
                }
            }
        }
    }

    private boolean isListenerReady() {
        synchronized (mListenerStateLock) {
            return isListenerReadyLocked();
        }
    }

    private boolean isListenerReadyLocked() {
        DatagramSocket socket = mSocket;
        return mReadyToScan != 0 && socket != null && !socket.isClosed();
    }

    private void processMessage(InetAddress ip, String message) {
        if (ip == null || message == null)
            return;
        Intent intent = null;
        if (mMyIP == null) {
            mMyIP = Globals.getIPAddress(getApplicationContext());
        }
        if (ip.equals(mMyIP)) {
            //Log.d(TAG, "IP matched so ignored");
            return;
        }
        // Older servers send version rejection without the normal UDP prefix.
        if (message.equals(NetMsg.NETMSG_VERSIONERROR)) {
            completeJoin(ip, NetMsg.NETMSG_VERSIONERROR);
            return;
        }
        if (message.startsWith(NetMsg.MESSAGE_PREFIX)) {
            message = message.substring(NetMsg.MESSAGE_PREFIX.length());
            if (message.startsWith(NetMsg.NETMSG_GAMEINVITE_PREFIX)) {
                String roundToken = parseGameInviteToken(message);
                // Only the listener started by an idle player screen accepts
                // invitations. A player already in a lobby or round must not
                // be pulled into an unrelated host by a broadcast packet.
                if (roundToken != null && mPassiveInviteListener) {
                    intent = new Intent(NetMsg.NETMSG_GAMEINVITE)
                            .putExtra(INTENT_SERVERIP, ip.getHostAddress())
                            .putExtra(INTENT_GAME_INVITE_TOKEN, roundToken);
                }
            } else if (isVersionedAnnouncement(message, NetMsg.NETMSG_LOBBYINVITE_PREFIX)) {
                // This is intentionally limited to the idle listener. A phone
                // that is already in a lobby must not be pulled into another
                // nearby peer host just because it hears a broadcast.
                if (mPassiveInviteListener) {
                    intent = new Intent(NetMsg.NETMSG_LOBBYINVITE)
                            .putExtra(INTENT_SERVERIP, ip.getHostAddress());
                }
            } else if (isVersionedAnnouncement(message, NetMsg.NETMSG_HOSTTAKEOVER_PREFIX)) {
                // A takeover is different from a lobby invitation: existing
                // lobby clients and the phone host itself must see it. The
                // activity verifies that the local game is still idle before
                // switching to the announcing standalone server.
                if (!mPeerGame && keepListening) {
                    intent = new Intent(NetMsg.NETMSG_HOSTTAKEOVER)
                            .putExtra(INTENT_SERVERIP, ip.getHostAddress());
                }
            } else if (message.equals(NetMsg.NETMSG_SHOTFIRED)) {
                if (getPlayerID(ip) == null)
                    return;
                // someone else fired a shot
                intent = new Intent(NetMsg.NETMSG_SHOTFIRED);
            } else if (message.equals(NetMsg.NETMSG_HIT)) {
                // you hit someone!
                intent = new Intent(NetMsg.NETMSG_HIT);
                Byte id = getPlayerID(ip);
                if (id == null) {
                    if (BuildConfig.DEBUG)
                        Log.d(TAG, "Ignoring HIT from unknown IP " + ip);
                    return;
                }
                intent.putExtra(INTENT_PLAYERID, id);
            } else if (message.equals(NetMsg.NETMSG_OUT)) {
                // You landed the final hit that put a player out.
                intent = new Intent(NetMsg.NETMSG_OUT);
                Byte id = getPlayerID(ip);
                if (id == null) {
                    if (BuildConfig.DEBUG)
                        Log.d(TAG, "Ignoring OUT from unknown IP " + ip);
                    return;
                }
                intent.putExtra(INTENT_PLAYERID, id);
            } else if (message.equals(NetMsg.NETMSG_ALREADYDEAD)) {
                // A known player is currently eliminated, so this shot cannot
                // earn another hit or point. Surface that acknowledgement to
                // the shooter without changing game state.
                intent = new Intent(NetMsg.NETMSG_ALREADYDEAD);
                Byte id = getPlayerID(ip);
                if (id == null) {
                    if (BuildConfig.DEBUG)
                        Log.d(TAG, "Ignoring ALREADYDEAD from unknown IP " + ip);
                    return;
                }
                intent.putExtra(INTENT_PLAYERID, id);
            } else if (message.startsWith(NetMsg.NETMSG_PEER_ELIMINATED)) {
                processPeerElimination(ip,
                        message.substring(NetMsg.NETMSG_PEER_ELIMINATED.length()));
            } else if (message.equals(NetMsg.NETMSG_ELIMINATED)) {
                // Protocol 19 peer games use binary state snapshots. Keep the
                // old fixed form only for non-peer compatibility paths.
                if (mPeerGame)
                    return;
                // you eliminated someone!
                intent = new Intent(NetMsg.NETMSG_ELIMINATED);
                Byte id = getPlayerID(ip);
                if (id == null) {
                    if (BuildConfig.DEBUG)
                        Log.d(TAG, "Ignoring elimination from unknown IP " + ip);
                    return;
                }
                intent.putExtra(INTENT_PLAYERID, id);
            } else if (message.startsWith(NetMsg.NETMSG_JOIN)) {
                if (!mIsListService) return;
                // This is a player join message
                message = message.substring(NetMsg.NETMSG_JOIN.length());
                if (message.length() < 3) {
                    Log.w(TAG, "Ignoring malformed join request");
                    return;
                }
                String version = message.substring(0, 2);
                if (!version.equals(NetMsg.NETWORK_VERSION)) {
                    sendUDPMessage(NetMsg.NETMSG_VERSIONERROR, ip, LISTEN_PORT);
                    return;
                }
                message = message.substring(2);
                final int playerID;
                try {
                    playerID = Integer.parseInt(message);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Ignoring join request with an invalid player ID", e);
                    return;
                }
                if (playerID <= 0 || !Globals.isValidPlayerID(playerID)) {
                    Log.w(TAG, "Ignoring join request with unsupported player ID " + playerID);
                    return;
                }
                final Map<Byte, InetAddress> playerEndpoints;
                Globals.getmTeamIPMapSemaphore();
                try {
                    playerEndpoints = new HashMap<>(Globals.getInstance().mTeamIPMap);
                } finally {
                    Globals.getInstance().mTeamIPMapSemaphore.release();
                }
                final int assignedPlayerID = reserveAvailablePlayerID(playerID, ip, playerEndpoints);
                // Setup holds the listener-state lock while replacing the roster.
                // Do not acquire that lock to send a rejection while holding the
                // team map lock, or a simultaneous duplicate join can deadlock.
                if (assignedPlayerID <= 0) {
                    Log.e(TAG, "No free player ID remains on this team");
                    sendUDPMessage(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_SAMETEAM, ip, LISTEN_PORT);
                    return;
                }
                // Discovery only proves that a peer can receive UDP. TCP registration
                // owns roster membership and endpoints. Recording a JOIN here leaves
                // ghosts when its sender never completes the TCP handshake.
                if (BuildConfig.DEBUG)
                    Log.d(TAG, "UDP discovery from player " + assignedPlayerID + " at " + ip);
                String reply = NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_SERVERREPLY;
                if (assignedPlayerID != playerID)
                    reply += ":" + assignedPlayerID;
                sendUDPMessage(reply, ip, LISTEN_PORT);
            } else if (message.equals(NetMsg.NETMSG_SERVERREPLY)) {
                completeJoin(ip, NetMsg.NETMSG_SERVERREPLY);
                return;
            } else if (message.startsWith(NetMsg.NETMSG_SERVERREPLY_ASSIGNMENT_PREFIX)) {
                Byte assignedPlayerID = parseAssignedPlayerID(message);
                if (assignedPlayerID != null)
                    completeJoin(ip, NetMsg.NETMSG_SERVERREPLY, assignedPlayerID);
                return;
            } else if (message.startsWith(NetMsg.NETMSG_PEER_LEAVE)) {
                processPeerLeave(ip,
                        message.substring(NetMsg.NETMSG_PEER_LEAVE.length()));
            } else if (message.equals(NetMsg.NETMSG_LEAVE)) {
                // A peer LEAVE must be scoped to its current round. Dedicated
                // games keep roster removal TCP-authoritative either way.
                if (mPeerGame)
                    return;
                Byte playerID = removePeerPlayer(ip);
                if (playerID == null) {
                    // A UDP datagram cannot prove that a dedicated-server player
                    // disconnected.  Keep that roster TCP-authoritative.
                    Log.d(TAG, "Ignoring UDP leave; TCP owns roster removal");
                    return;
                }
                intent = new Intent(NetMsg.NETMSG_LEAVE);
                intent.putExtra(INTENT_PLAYERID, playerID);
            } else if (message.startsWith(NetMsg.NETMSG_GRENADEPAIR)) {
                processPeerGrenadePairing(ip,
                        message.substring(NetMsg.NETMSG_GRENADEPAIR.length()));
            } else if (message.startsWith(NetMsg.NETMSG_PEER_ENDGAME)) {
                processPeerEndGame(ip,
                        message.substring(NetMsg.NETMSG_PEER_ENDGAME.length()));
            } else if (message.equals(NetMsg.NETMSG_ENDGAME)) {
                // Protocol 19 peer games bind ENDGAME to the binary round snapshot.
                // Keep the old fixed form only for TCP-authoritative games.
                // Tournament termination is host-authoritative. Dedicated hosts
                // end clients through TCP, so a player must never be able to
                // terminate that round with a forged UDP datagram.
                if (mPeerGame || Globals.getInstance().mTournamentMode)
                    return;
                if (Globals.getInstance().mGameState == Globals.GAME_STATE_NONE
                        || (getPlayerID(ip) == null && !ip.equals(Globals.getInstance().mServerIP)))
                    return;
                // game ends!
                intent = new Intent(NetMsg.NETMSG_ENDGAME);
            } else if (message.equals(NetMsg.NETMSG_ERROR)) {
                // some kind of error
                intent = new Intent(NetMsg.NETMSG_ERROR);
            } else if (message.equals(NetMsg.NETMSG_SAMETEAM) || message.equals(NetMsg.NETMSG_VERSIONERROR)) {
                completeJoin(ip, message);
                return;
            } else if (message.startsWith(NetMsg.NETMSG_PEER_TEAMELIMINATED)) {
                processPeerTeamElimination(ip,
                        message.substring(NetMsg.NETMSG_PEER_TEAMELIMINATED.length()));
            } else if (message.equals(NetMsg.NETMSG_TEAMELIMINATED)) {
                // A bare team score packet cannot distinguish a retransmit from
                // a new kill, so it is not valid during a protocol-19 peer game.
                if (mPeerGame)
                    return;
                Byte playerID = getPlayerID(ip);
                Globals globals = Globals.getInstance();
                if (playerID == null || globals.mGameMode == Globals.GAME_MODE_FFA
                        || globals.calcNetworkTeam(playerID) != globals.calcNetworkTeam(globals.mPlayerID))
                    return;
                // Someone else on your team scored a point
                intent = new Intent(NetMsg.NETMSG_TEAMELIMINATED);
            }
        }
        if (intent != null)
            sendBroadcast(intent);
    }

    /** Process one compact combat event or its target acknowledgement. */
    private void processCombatPacket(InetAddress ip, byte[] payload, int offset, int length) {
        if (ip == null)
            return;
        if (mMyIP == null)
            mMyIP = Globals.getIPAddress(getApplicationContext());
        if (ip.equals(mMyIP))
            return;
        CombatPacket.Decoded decoded = CombatPacket.decode(payload, offset, length);
        if (decoded == null || decoded.networkVersion != NetMsg.NETWORK_VERSION_NUMBER)
            return;
        Byte sourceID = getPlayerID(ip);
        if (sourceID == null || (sourceID & 0xff) != decoded.senderID)
            return;

        byte[] acknowledgement = null;
        Intent gameplayIntent = null;
        synchronized (mListenerStateLock) {
            if (mDestroyed || !mStateGossipRound || !decoded.matchesRound(mStateRoundUuid))
                return;
            int localPlayerID = Globals.getInstance().mPlayerID & 0xff;
            if (decoded.kind == CombatPacket.KIND_ACK) {
                if (decoded.targetID != localPlayerID)
                    return;
                int slot = (int) (decoded.eventSequence % mPendingCombatEvents.length);
                PendingCombatEvent pending = mPendingCombatEvents[slot];
                if (pending != null && pending.sequence == decoded.eventSequence
                        && pending.eventType == decoded.eventType
                        && pending.targetID == decoded.senderID) {
                    mPendingCombatEvents[slot] = null;
                    // Cancel or move the one shared wake-up immediately. This
                    // keeps an ACKed burst from needlessly waking an old phone.
                    scheduleCombatRetryLocked(SystemClock.elapsedRealtime());
                }
                return;
            }

            boolean targetsLocal = decoded.targetID == localPlayerID;
            if (targetsLocal) {
                // ACK every copy, even a duplicate. The event sender may have
                // retransmitted only because our previous ACK was lost.
                acknowledgement = CombatPacket.encode(NetMsg.NETWORK_VERSION_NUMBER,
                        mStateRoundUuid, CombatPacket.KIND_ACK, decoded.eventType,
                        localPlayerID, decoded.senderID, decoded.eventSequence);
            }
            synchronized (mPeerRuntimeLock) {
                boolean firstCopy = markCombatEventSeenLocked(decoded.senderID,
                        decoded.eventSequence);
                cacheObservedCombatEventLocked(decoded);
                if (firstCopy)
                    gameplayIntent = combatEventIntent(decoded, localPlayerID, mStateRoundToken);
            }
        }
        if (acknowledgement != null)
            sendCombatAcknowledgement(acknowledgement, ip);
        if (gameplayIntent != null)
            sendBroadcast(gameplayIntent);
    }

    /** Send the latency-critical ACK without waiting behind a 32-phone tick fan-out. */
    void sendCombatAcknowledgement(byte[] acknowledgement, InetAddress destination) {
        DatagramSocket socket = mSocket;
        if (socket == null || socket.isClosed() || acknowledgement == null || destination == null)
            return;
        try {
            socket.send(new DatagramPacket(acknowledgement, acknowledgement.length,
                    destination, LISTEN_PORT));
        } catch (IOException e) {
            // The sender's 20/60 ms retry schedule is the recovery path. Avoid
            // allocating an error Intent for a transient ACK failure.
            Log.d(TAG, "Combat ACK send failed; waiting for retry", e);
        }
    }

    // Caller holds mPeerRuntimeLock.
    private boolean markCombatEventSeenLocked(int senderID, long sequence) {
        if (!Globals.isValidPlayerID(senderID) || sequence <= 0)
            return false;
        long highest = mCombatHighestSequences[senderID];
        if (sequence > highest) {
            long distance = sequence - highest;
            mCombatSeenMasks[senderID] = distance >= Long.SIZE
                    ? 1L : mCombatSeenMasks[senderID] << distance | 1L;
            mCombatHighestSequences[senderID] = sequence;
            return true;
        }
        long distance = highest - sequence;
        if (distance >= Long.SIZE)
            return false;
        long bit = 1L << distance;
        if ((mCombatSeenMasks[senderID] & bit) != 0)
            return false;
        mCombatSeenMasks[senderID] |= bit;
        return true;
    }

    // Caller holds mPeerRuntimeLock. Retaining the event in the sender's cached
    // row lets the slower state mesh provide a final repair after all ACK-based
    // retries were lost.
    private void cacheObservedCombatEventLocked(CombatPacket.Decoded event) {
        PeerStatePacket.PlayerState current = mPeerStates[event.senderID];
        if (current == null || event.eventSequence <= current.eventSequence)
            return;
        mPeerStates[event.senderID] = new PeerStatePacket.PlayerState(current.playerID,
                current.flags, current.ownerSequence, event.eventSequence, event.eventType,
                event.targetID, current.gameState, current.grenadeID, current.score,
                current.deaths, current.health, current.shield, current.shotsRemaining,
                current.gpsAgeSeconds, current.latitude, current.longitude);
    }

    private Intent combatEventIntent(CombatPacket.Decoded event, int localPlayerID,
                                     String roundToken) {
        boolean targetsLocal = event.targetID == localPlayerID;
        Intent intent;
        switch (event.eventType) {
            case PeerStatePacket.EVENT_SHOT_FIRED:
                intent = new Intent(NetMsg.NETMSG_SHOTFIRED);
                break;
            case PeerStatePacket.EVENT_HIT:
                if (!targetsLocal) return null;
                intent = new Intent(NetMsg.NETMSG_HIT);
                break;
            case PeerStatePacket.EVENT_OUT:
                if (!targetsLocal) return null;
                intent = new Intent(NetMsg.NETMSG_OUT);
                break;
            case PeerStatePacket.EVENT_ALREADY_DEAD:
                if (!targetsLocal) return null;
                intent = new Intent(NetMsg.NETMSG_ALREADYDEAD);
                break;
            case PeerStatePacket.EVENT_ELIMINATED:
                if (!targetsLocal) return null;
                Globals globals = Globals.getInstance();
                if (globals.mGameMode != Globals.GAME_MODE_FFA
                        && globals.calcNetworkTeam((byte) event.senderID)
                        == globals.calcNetworkTeam((byte) localPlayerID))
                    return null;
                intent = new Intent(NetMsg.NETMSG_ELIMINATED);
                break;
            default:
                return null;
        }
        if (event.eventType != PeerStatePacket.EVENT_SHOT_FIRED)
            intent.putExtra(INTENT_PLAYERID, (byte) event.senderID);
        return intent.putExtra(NetMsg.INTENT_EVENT_SEQUENCE, event.eventSequence)
                .putExtra(NetMsg.INTENT_ROUND_TOKEN, roundToken);
    }

    /** Cache player gossip or apply one authoritative host tick. */
    private void processPeerStatePacket(InetAddress ip, byte[] payload, int offset, int length) {
        if (ip == null)
            return;
        if (mMyIP == null)
            mMyIP = Globals.getIPAddress(getApplicationContext());
        if (ip.equals(mMyIP))
            return;

        PeerStatePacket.Decoded decoded = PeerStatePacket.decode(payload, offset, length);
        if (decoded == null || decoded.networkVersion != NetMsg.NETWORK_VERSION_NUMBER
                || decoded.gameMode != Globals.getInstance().mGameMode)
            return;
        // Resolve roster ownership before taking the round lock. Registration
        // paths can hold the roster semaphore while transitioning listener
        // state, so reversing that order here would deadlock under load.
        Byte sourceID = decoded.senderID == 0 ? null : getPlayerID(ip);
        boolean[] knownPlayers = snapshotKnownPlayers();
        Globals globals = Globals.getInstance();
        int localPlayerID = globals.mPlayerID & 0xff;
        if (Globals.isValidPlayerID(localPlayerID))
            knownPlayers[localPlayerID] = true;

        String roundToken = decoded.roundToken.toString();
        StateMergeResult applied = null;
        StateMergeResult gossipEvents = null;
        synchronized (mListenerStateLock) {
            if (!isCurrentStateRoundLocked(roundToken))
                return;
            boolean authorityTick = decoded.senderID == 0;
            if (authorityTick) {
                if (mStateAuthority || globals.mServerIP == null || !globals.mServerIP.equals(ip))
                    return;
            } else {
                if (sourceID == null || (sourceID & 0xff) != decoded.senderID
                        || decoded.players[decoded.senderID] == null)
                    return;
            }
            synchronized (mPeerRuntimeLock) {
                if (decoded.snapshotSequence <= mLastPeerSnapshotSequences[decoded.senderID])
                    return;
                mLastPeerSnapshotSequences[decoded.senderID] = decoded.snapshotSequence;
                if (authorityTick) {
                    mergeGossipRowsLocked(decoded.players, knownPlayers, localPlayerID,
                            false, null);
                    applied = applyCumulativeRowsLocked(decoded.players, knownPlayers,
                            localPlayerID, true, roundToken);
                    noteAuthorityTickLocked(decoded.snapshotSequence);
                } else {
                    PeerStatePacket.PlayerState[] rows = decoded.players;
                    if (mStateAuthority) {
                        // A host trusts only the row owned by the datagram source;
                        // relayed rows are still useful to other players as fallback.
                        PeerStatePacket.PlayerState[] direct =
                                new PeerStatePacket.PlayerState[rows.length];
                        direct[decoded.senderID] = rows[decoded.senderID];
                        mergeGossipRowsLocked(direct, knownPlayers, localPlayerID, false, null);
                        // A phone-host is also a player. It never receives its
                        // own sender-zero tick, so apply the authoritative cache
                        // here or hits, GPS, and team totals would disappear only
                        // for the hosting player. A dedicated host has no player UI.
                        if (mPeerGame)
                            applied = applyCumulativeRowsLocked(mPeerStates, knownPlayers,
                                    localPlayerID, true, roundToken);
                    } else {
                        gossipEvents = new StateMergeResult();
                        mergeGossipRowsLocked(rows, knownPlayers, localPlayerID, true,
                                gossipEvents);
                    }
                }
            }
        }
        publishStateMerge(applied, roundToken);
        publishStateMerge(gossipEvents, roundToken);
    }

    // Caller holds mPeerRuntimeLock.
    private void mergeGossipRowsLocked(PeerStatePacket.PlayerState[] rows,
                                       boolean[] knownPlayers, int localPlayerID,
                                       boolean dispatchPeerEvents, StateMergeResult result) {
        if (rows == null)
            return;
        for (int playerID = 1; playerID <= PeerStatePacket.PLAYER_CAPACITY; playerID++) {
            PeerStatePacket.PlayerState incoming = rows[playerID];
            if (incoming == null || !knownPlayers[playerID] || playerID == localPlayerID)
                continue;
            PeerStatePacket.PlayerState current = mPeerStates[playerID];
            if (current != null && incoming.ownerSequence < current.ownerSequence)
                continue;
            if (current == null || incoming.ownerSequence > current.ownerSequence)
                mPeerStates[playerID] = incoming;
            if (dispatchPeerEvents && markCombatEventSeenLocked(playerID,
                    incoming.eventSequence)) {
                Intent event = peerEventIntent(getPlayerEndpoint((byte) playerID), incoming,
                        localPlayerID, mStateRoundToken);
                if (event != null && result != null)
                    result.gameplayIntents.add(event);
            }
        }
    }

    // Caller holds mPeerRuntimeLock.
    private StateMergeResult applyCumulativeRowsLocked(PeerStatePacket.PlayerState[] rows,
                                                        boolean[] knownPlayers, int localPlayerID,
                                                        boolean dispatchPeerEvents,
                                                        String roundToken) {
        StateMergeResult result = new StateMergeResult();
        for (int playerID = 1; playerID <= PeerStatePacket.PLAYER_CAPACITY; playerID++) {
            PeerStatePacket.PlayerState incoming = rows[playerID];
            if (incoming == null || !knownPlayers[playerID])
                continue;
            PeerStatePacket.PlayerState current = mAppliedPeerStates[playerID];
            if (current != null && incoming.ownerSequence < current.ownerSequence)
                continue;
            if (current == null || incoming.ownerSequence > current.ownerSequence) {
                mAppliedPeerStates[playerID] = incoming;
                updateGrenadePairing((byte) playerID, incoming.grenadeID);
                if (playerID != localPlayerID
                        && (incoming.flags & PeerStatePacket.FLAG_GPS_VALID) != 0)
                    result.gpsChanged |= updatePeerGps(incoming);
            }
            if (dispatchPeerEvents && playerID != localPlayerID
                    && markCombatEventSeenLocked(playerID, incoming.eventSequence)) {
                Intent event = peerEventIntent(getPlayerEndpoint((byte) playerID), incoming,
                        localPlayerID, roundToken);
                if (event != null)
                    result.gameplayIntents.add(event);
            }
        }
        if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA && localPlayerID > 0) {
            int teamScore = calculateLocalTeamScoreLocked(localPlayerID);
            if (teamScore != mLastPublishedTeamScore) {
                mLastPublishedTeamScore = teamScore;
                result.teamScore = teamScore;
            }
        }
        return result;
    }

    private void recoverStateFromGossip(String roundToken) {
        if (!TcpServer.isValidRoundToken(roundToken))
            return;
        boolean[] knownPlayers = snapshotKnownPlayers();
        int localPlayerID = Globals.getInstance().mPlayerID & 0xff;
        if (Globals.isValidPlayerID(localPlayerID))
            knownPlayers[localPlayerID] = true;
        final StateMergeResult result;
        synchronized (mListenerStateLock) {
            if (!isCurrentStateRoundLocked(roundToken) || mStateAuthority)
                return;
            synchronized (mPeerRuntimeLock) {
                result = applyCumulativeRowsLocked(mPeerStates, knownPlayers, localPlayerID,
                        false, roundToken);
            }
        }
        publishStateMerge(result, roundToken);
    }

    private void publishStateMerge(StateMergeResult result, String roundToken) {
        if (result == null)
            return;
        if (result.gpsChanged)
            sendBroadcast(new Intent(NetMsg.NETMSG_GPSDATAUPDATE));
        if (result.teamScore >= 0)
            sendBroadcast(new Intent(NetMsg.NETMSG_TEAMSCORESTATE)
                    .putExtra(NetMsg.INTENT_TEAMSCORE, result.teamScore)
                    .putExtra(NetMsg.INTENT_ROUND_TOKEN, roundToken));
        for (Intent gameplayIntent : result.gameplayIntents)
            sendBroadcast(gameplayIntent);
    }

    private void noteAuthorityTickLocked(long sequence) {
        if (sequence <= mLastAuthorityTickSequence)
            return;
        mLastAuthorityTickSequence = sequence;
        mLastAuthorityTickAt = SystemClock.elapsedRealtime();
        mMainHandler.removeCallbacks(mGossipRecovery);
        mMainHandler.postDelayed(mGossipRecovery,
                GAME_TICK_INTERVAL_MS + GAME_TICK_DRIFT_GRACE_MS);
    }

    private static final class StateMergeResult {
        boolean gpsChanged;
        int teamScore = -1;
        final List<Intent> gameplayIntents = new ArrayList<>();
    }

    private boolean[] snapshotKnownPlayers() {
        boolean[] known = new boolean[PeerStatePacket.PLAYER_CAPACITY + 1];
        Globals.getmTeamIPMapSemaphore();
        try {
            for (Byte playerID : Globals.getInstance().mTeamIPMap.keySet()) {
                int id = playerID == null ? 0 : playerID & 0xff;
                if (id > 0 && id < known.length)
                    known[id] = true;
            }
        } finally {
            Globals.getInstance().mTeamIPMapSemaphore.release();
        }
        return known;
    }

    private Intent peerEventIntent(InetAddress ip, PeerStatePacket.PlayerState state,
                                   int localPlayerID, String roundToken) {
        if (state.eventSequence <= 0 || ip == null)
            return null;
        boolean targetsLocal = state.eventTargetID == localPlayerID;
        Intent intent;
        switch (state.eventType) {
            case PeerStatePacket.EVENT_SHOT_FIRED:
                intent = new Intent(NetMsg.NETMSG_SHOTFIRED);
                break;
            case PeerStatePacket.EVENT_HIT:
                if (!targetsLocal) return null;
                intent = new Intent(NetMsg.NETMSG_HIT).putExtra(INTENT_PLAYERID, (byte) state.playerID);
                break;
            case PeerStatePacket.EVENT_OUT:
                if (!targetsLocal) return null;
                intent = new Intent(NetMsg.NETMSG_OUT).putExtra(INTENT_PLAYERID, (byte) state.playerID);
                break;
            case PeerStatePacket.EVENT_ALREADY_DEAD:
                if (!targetsLocal) return null;
                intent = new Intent(NetMsg.NETMSG_ALREADYDEAD)
                        .putExtra(INTENT_PLAYERID, (byte) state.playerID);
                break;
            case PeerStatePacket.EVENT_ELIMINATED:
                if (!targetsLocal) return null;
                Globals globals = Globals.getInstance();
                if (globals.mGameMode != Globals.GAME_MODE_FFA
                        && globals.calcNetworkTeam((byte) state.playerID)
                        == globals.calcNetworkTeam((byte) localPlayerID))
                    return null;
                intent = new Intent(NetMsg.NETMSG_ELIMINATED)
                        .putExtra(INTENT_PLAYERID, (byte) state.playerID)
                        .putExtra(NetMsg.INTENT_EVENT_SEQUENCE, state.eventSequence);
                break;
            case PeerStatePacket.EVENT_LEAVE:
                Byte removed = removePeerPlayer(ip);
                if (removed == null || (removed & 0xff) != state.playerID)
                    return null;
                intent = new Intent(NetMsg.NETMSG_LEAVE).putExtra(INTENT_PLAYERID, removed);
                break;
            case PeerStatePacket.EVENT_END_GAME:
                if (Globals.getInstance().mTournamentMode
                        && !ip.equals(Globals.getInstance().mServerIP))
                    return null;
                intent = new Intent(NetMsg.NETMSG_ENDGAME);
                mPendingPeerEndGame = new Intent(intent)
                        .putExtra(NetMsg.INTENT_ROUND_TOKEN, roundToken);
                break;
            default:
                return null;
        }
        return intent.putExtra(NetMsg.INTENT_ROUND_TOKEN, roundToken);
    }

    private InetAddress getPlayerEndpoint(byte playerID) {
        Globals.getmTeamIPMapSemaphore();
        try {
            return Globals.getInstance().mTeamIPMap.get(playerID);
        } finally {
            Globals.getInstance().mTeamIPMapSemaphore.release();
        }
    }

    private boolean updatePeerGps(PeerStatePacket.PlayerState state) {
        Globals globals = Globals.getInstance();
        if (!globals.mUseGPS || !Globals.isValidCoordinates(state.longitude, state.latitude))
            return false;
        Globals.getmGPSDataSemaphore();
        try {
            Globals.GPSData gps = globals.mGPSData.get((byte) state.playerID);
            if (gps == null) {
                gps = new Globals.GPSData();
                globals.mGPSData.put((byte) state.playerID, gps);
            }
            boolean changed = Double.compare(gps.longitude, state.longitude) != 0
                    || Double.compare(gps.latitude, state.latitude) != 0;
            gps.longitude = state.longitude;
            gps.latitude = state.latitude;
            gps.team = globals.calcNetworkTeam((byte) state.playerID);
            gps.hasUpdate |= changed;
            return changed;
        } finally {
            globals.mGPSDataSemaphore.release();
        }
    }

    // Caller holds mPeerRuntimeLock.
    private int calculateLocalTeamScoreLocked(int localPlayerID) {
        Globals globals = Globals.getInstance();
        int localTeam = globals.calcNetworkTeam((byte) localPlayerID);
        long total = 0;
        for (int playerID = 1; playerID <= Globals.MAX_PLAYER_ID; playerID++) {
            PeerStatePacket.PlayerState state = mAppliedPeerStates[playerID];
            // A player leaving removes the live roster entry, not points that
            // team already earned during this round.
            if (state != null && globals.calcNetworkTeam((byte) playerID) == localTeam)
                total += state.score;
        }
        return (int) Math.min(Globals.MAX_TEAM_SCOREBOARD_VALUE, total);
    }

    /** Returns a validated invite round token, or {@code null} for an untrusted packet. */
    private static String parseGameInviteToken(String message) {
        if (message == null || !message.startsWith(NetMsg.NETMSG_GAMEINVITE_PREFIX))
            return null;
        String payload = message.substring(NetMsg.NETMSG_GAMEINVITE_PREFIX.length());
        int separator = payload.indexOf(':');
        if (separator != NetMsg.NETWORK_VERSION.length()
                || separator != payload.lastIndexOf(':'))
            return null;
        if (!NetMsg.NETWORK_VERSION.equals(payload.substring(0, separator)))
            return null;
        String roundToken = payload.substring(separator + 1);
        return TcpServer.isValidRoundToken(roundToken) ? roundToken : null;
    }

    private static boolean isVersionedAnnouncement(String message, String prefix) {
        return message != null && prefix != null
                && message.equals(prefix + NetMsg.NETWORK_VERSION);
    }

    /**
     * Select an available identity for a discovery request. Team modes retain
     * the joining player's current host-side team; FFA has no shared teams, so
     * any unused valid identity is suitable. The TCP server remains the
     * authoritative roster owner after this discovery reply.
     *
     * The caller supplies a roster snapshot so this method never holds a
     * roster lock while acquiring the assignment lock.
     */
    private int reserveAvailablePlayerID(int requestedPlayerID, InetAddress joiningIP,
                                         Map<Byte, InetAddress> playerEndpoints) {
        Globals globals = Globals.getInstance();
        int requestedTeam = globals.calcNetworkTeam((byte) requestedPlayerID);
        if (requestedTeam == Globals.INVALID_PLAYER_ID)
            return Globals.INVALID_PLAYER_ID;
        long now = SystemClock.elapsedRealtime();
        synchronized (mJoinAssignmentLock) {
            pruneJoinAssignmentsLocked(playerEndpoints, now);
            PendingJoinAssignment existingAssignment = mPendingJoinAssignments.get(joiningIP);
            if (existingAssignment != null)
                return existingAssignment.playerID;
            for (int candidate = 1; candidate <= Globals.MAX_PLAYER_ID; candidate++) {
                if (globals.mGameMode != Globals.GAME_MODE_FFA && !globals.mBalancedRandom
                        && globals.calcNetworkTeam((byte) candidate) != requestedTeam)
                    continue;
                if (candidate == globals.mPlayerID)
                    continue; // The host's own player identity is never available.
                InetAddress existingPlayerIP = playerEndpoints.get((byte) candidate);
                // A retransmitted discovery request from an already registered
                // player must receive the original reply, not a new identity.
                boolean registeredRequester = candidate == requestedPlayerID
                        && joiningIP.equals(existingPlayerIP);
                if (existingPlayerIP != null && !registeredRequester)
                    continue;
                if (isReservedByAnotherPlayerLocked((byte) candidate, joiningIP))
                    continue;
                if (!registeredRequester) {
                    mPendingJoinAssignments.put(joiningIP, new PendingJoinAssignment((byte) candidate,
                            now + JOIN_ASSIGNMENT_TIMEOUT_MS));
                }
                return candidate;
            }
        }
        return Globals.INVALID_PLAYER_ID;
    }

    private boolean isReservedByAnotherPlayerLocked(byte playerID, InetAddress joiningIP) {
        for (Map.Entry<InetAddress, PendingJoinAssignment> entry : mPendingJoinAssignments.entrySet()) {
            if (!joiningIP.equals(entry.getKey()) && entry.getValue().playerID == playerID)
                return true;
        }
        return false;
    }

    private void pruneJoinAssignmentsLocked(Map<Byte, InetAddress> playerEndpoints, long now) {
        Iterator<Map.Entry<InetAddress, PendingJoinAssignment>> assignments =
                mPendingJoinAssignments.entrySet().iterator();
        while (assignments.hasNext()) {
            Map.Entry<InetAddress, PendingJoinAssignment> entry = assignments.next();
            PendingJoinAssignment assignment = entry.getValue();
            if (assignment.expiresAt <= now || playerEndpoints.containsKey(assignment.playerID))
                assignments.remove();
        }
    }

    private void clearJoinAssignments() {
        synchronized (mJoinAssignmentLock) {
            mPendingJoinAssignments.clear();
        }
    }

    private static final class PendingJoinAssignment {
        final byte playerID;
        final long expiresAt;

        PendingJoinAssignment(byte playerID, long expiresAt) {
            this.playerID = playerID;
            this.expiresAt = expiresAt;
        }
    }

    private static Byte parseAssignedPlayerID(String message) {
        String value = message.substring(NetMsg.NETMSG_SERVERREPLY_ASSIGNMENT_PREFIX.length());
        if (!isDecimal(value))
            return null;
        try {
            int playerID = Integer.parseInt(value);
            return playerID > 0 && Globals.isValidPlayerID(playerID) ? (byte) playerID : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Accept an ENDGAME only from a current peer and only for this exact round. */
    private void processPeerEndGame(InetAddress ip, String roundToken) {
        if (!TcpServer.isValidRoundToken(roundToken))
            return;
        // Tournament peer rounds still use UDP for low-latency gameplay, but
        // only the host endpoint may terminate the shared match. The round
        // nonce prevents stale packets; it is not an authority credential
        // because every player receives it in the synchronized start.
        if (Globals.getInstance().mTournamentMode
                && !ip.equals(Globals.getInstance().mServerIP))
            return;
        final Intent intent;
        final boolean deliverNow;
        synchronized (mListenerStateLock) {
            boolean activePeerRound = isCurrentPeerRoundLocked(roundToken);
            // TCP broadcasts are asynchronous, so a peer ENDGAME can arrive
            // after TcpClient has announced a synchronized start but before
            // this service's start receiver runs. Retain a candidate only
            // while no peer round is active. It is never broadcast in that
            // state and can only be consumed after startGame() installs the
            // exact same token.
            boolean candidatePeerRound = !mPeerGame && (mPendingPeerRoundToken == null
                    || roundToken.equals(mPendingPeerRoundToken));
            if ((!activePeerRound && !candidatePeerRound) || getPlayerID(ip) == null)
                return;
            // Broadcast delivery is asynchronous with a concurrent round start.
            // Preserve the nonce so the activity can reject an old event after
            // the listener has switched to a newer round.
            intent = new Intent(NetMsg.NETMSG_ENDGAME)
                    .putExtra(NetMsg.INTENT_ROUND_TOKEN, roundToken);
            mPendingPeerEndGame = new Intent(intent);
            // A pre-UI start belongs to no active round yet. Do not let a
            // dedicated activity react to it; FullscreenActivity consumes it
            // after it has made the matching peer round active.
            deliverNow = activePeerRound;
        }
        if (deliverNow)
            sendBroadcast(intent);
    }

    /**
     * Returns and clears a peer ENDGAME received while no activity receiver was
     * registered. A pending synchronized start must become active first so an
     * ENDGAME cannot be applied to an unrelated dedicated round.
     */
    Intent consumePendingPeerEndGame() {
        synchronized (mListenerStateLock) {
            if (mPendingPeerEndGame == null || !mPeerGame)
                return null;
            String pendingRoundToken =
                    mPendingPeerEndGame.getStringExtra(NetMsg.INTENT_ROUND_TOKEN);
            if (pendingRoundToken == null || !pendingRoundToken.equals(mPeerRoundToken))
                return null;
            Intent pendingEvent = new Intent(mPendingPeerEndGame);
            mPendingPeerEndGame = null;
            return pendingEvent;
        }
    }

    // Receives the token from a local synchronized TCP start broadcast. Package
    // visibility also lets focused regression tests exercise this paused-start
    // handoff without creating a second Android service process.
    void preparePeerRound(String roundToken) {
        if (!TcpServer.isValidRoundToken(roundToken))
            return;
        synchronized (mListenerStateLock) {
            if (mDestroyed || (mPeerGame && roundToken.equals(mPeerRoundToken)))
                return;
            if (!mPeerGame) {
                mPendingPeerRoundToken = roundToken;
                if (mPendingPeerEndGame != null && !roundToken.equals(
                        mPendingPeerEndGame.getStringExtra(NetMsg.INTENT_ROUND_TOKEN)))
                    mPendingPeerEndGame = null;
            }
        }
    }

    /** A peer LEAVE is valid only for the round that authenticated its roster. */
    private void processPeerLeave(InetAddress ip, String roundToken) {
        if (!TcpServer.isValidRoundToken(roundToken))
            return;
        final Intent intent;
        synchronized (mListenerStateLock) {
            if (!isCurrentPeerRoundLocked(roundToken))
                return;
            Byte playerID = removePeerPlayer(ip);
            if (playerID == null)
                return;
            intent = new Intent(NetMsg.NETMSG_LEAVE)
                    .putExtra(INTENT_PLAYERID, playerID)
                    .putExtra(NetMsg.INTENT_ROUND_TOKEN, roundToken);
        }
        sendBroadcast(intent);
    }

    // Caller holds mListenerStateLock and has already validated the token.
    private boolean isCurrentPeerRoundLocked(String roundToken) {
        return !mDestroyed && mPeerGame && roundToken.equals(mPeerRoundToken);
    }

    private boolean isCurrentStateRoundLocked(String roundToken) {
        return !mDestroyed && mStateGossipRound && roundToken != null
                && roundToken.equals(mStateRoundToken);
    }

    /**
     * Apply a pair/disarm message received from a known peer. The UDP source
     * endpoint determines the player; the payload never gets to choose an
     * owner. The synchronized round nonce and a per-player sequence make the
     * repeated datagrams idempotent and prevent an old queued update from
     * reviving a pairing after a disarm or a later round has started.
     */
    private void processPeerGrenadePairing(InetAddress ip, String payload) {
        if (payload == null)
            return;
        int tokenSeparator = payload.indexOf(':');
        int sequenceSeparator = tokenSeparator < 0 ? -1 : payload.indexOf(':', tokenSeparator + 1);
        if (tokenSeparator <= 0 || sequenceSeparator <= tokenSeparator + 1
                || sequenceSeparator == payload.length() - 1
                || payload.indexOf(':', sequenceSeparator + 1) >= 0) {
            Log.w(TAG, "Ignoring malformed peer grenade pairing");
            return;
        }
        String roundToken = payload.substring(0, tokenSeparator);
        if (!TcpServer.isValidRoundToken(roundToken))
            return;
        String sequenceText = payload.substring(tokenSeparator + 1, sequenceSeparator);
        String grenadeText = payload.substring(sequenceSeparator + 1);
        if (!isDecimal(sequenceText) || !isDecimal(grenadeText)) {
            Log.w(TAG, "Ignoring non-numeric peer grenade pairing");
            return;
        }
        final long sequence;
        final int grenadeID;
        try {
            sequence = Long.parseLong(sequenceText);
            grenadeID = Integer.parseInt(grenadeText);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Ignoring oversized peer grenade pairing", e);
            return;
        }
        synchronized (mListenerStateLock) {
            // Do the sequence update and the pairing mutation in the same
            // round-critical section as the token check. Otherwise startGame
            // can reset the sequence between validation and mutation.
            if (!isCurrentPeerRoundLocked(roundToken))
                return;
            Byte playerID = getPlayerID(ip);
            if (sequence <= 0 || playerID == null || !Globals.isValidGrenadeID(grenadeID)) {
                Log.w(TAG, "Ignoring invalid peer grenade pairing");
                return;
            }
            synchronized (mPeerGrenadeLock) {
                if (sequence <= mLastPeerGrenadeSequences[playerID])
                    return;
                mLastPeerGrenadeSequences[playerID] = sequence;
            }
            updateGrenadePairing(playerID, grenadeID);
        }
    }

    private static boolean isDecimal(String value) {
        if (value == null || value.isEmpty())
            return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9')
                return false;
        }
        return true;
    }

    private static long parsePositiveSequence(String value) {
        if (!isDecimal(value))
            return 0;
        try {
            long sequence = Long.parseLong(value);
            return sequence > 0 ? sequence : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Accept one peer elimination exactly once. The UDP source owns the
     * sequence and identifies the eliminated player; it cannot claim another
     * player in the payload. The round nonce prevents a delayed event from
     * becoming a new score after sequence state is reset for another round.
     */
    private void processPeerElimination(InetAddress ip, String payload) {
        if (payload == null)
            return;
        int separator = payload.indexOf(':');
        if (separator <= 0 || separator == payload.length() - 1
                || payload.indexOf(':', separator + 1) >= 0)
            return;
        String roundToken = payload.substring(0, separator);
        if (!TcpServer.isValidRoundToken(roundToken))
            return;
        String sequenceText = payload.substring(separator + 1);
        long sequence = parsePositiveSequence(sequenceText);
        final Intent intent;
        synchronized (mListenerStateLock) {
            if (!isCurrentPeerRoundLocked(roundToken))
                return;
            Byte eliminatedPlayerID = getPeerScoreSourceID(ip);
            Globals globals = Globals.getInstance();
            int localPlayerID = globals.mPlayerID;
            if (sequence == 0 || eliminatedPlayerID == null || localPlayerID <= 0
                    || !Globals.isValidPlayerID(localPlayerID))
                return;
            // A teammate cannot award this phone a kill in team play. The direct
            // recipient is the scorer, so this also rejects a stale/misdirected
            // team packet without relying on its destination port.
            if (globals.mGameMode != Globals.GAME_MODE_FFA
                    && globals.calcNetworkTeam(eliminatedPlayerID)
                    == globals.calcNetworkTeam((byte) localPlayerID))
                return;
            synchronized (mPeerScoreLock) {
                if (sequence <= mLastPeerEliminationSequences[eliminatedPlayerID])
                    return;
                mLastPeerEliminationSequences[eliminatedPlayerID] = sequence;
            }
            intent = new Intent(NetMsg.NETMSG_ELIMINATED)
                    .putExtra(INTENT_PLAYERID, eliminatedPlayerID)
                    .putExtra(NetMsg.INTENT_EVENT_SEQUENCE, sequence)
                    .putExtra(NetMsg.INTENT_ROUND_TOKEN, roundToken);
        }
        sendBroadcast(intent);
    }

    /**
     * Relay an already accepted elimination to a scorer's teammates. The
     * scorer and victim form the event key, while the victim's sequence makes
     * retransmits and reordering harmless.
     */
    private void processPeerTeamElimination(InetAddress ip, String payload) {
        if (payload == null)
            return;
        int tokenSeparator = payload.indexOf(':');
        int playerSeparator = tokenSeparator < 0 ? -1 : payload.indexOf(':', tokenSeparator + 1);
        if (tokenSeparator <= 0 || playerSeparator <= tokenSeparator + 1
                || playerSeparator == payload.length() - 1
                || payload.indexOf(':', playerSeparator + 1) >= 0)
            return;
        String roundToken = payload.substring(0, tokenSeparator);
        if (!TcpServer.isValidRoundToken(roundToken))
            return;
        String eliminatedText = payload.substring(tokenSeparator + 1, playerSeparator);
        long sequence = parsePositiveSequence(payload.substring(playerSeparator + 1));
        if (!isDecimal(eliminatedText) || sequence == 0)
            return;
        final int eliminatedPlayerID;
        try {
            eliminatedPlayerID = Integer.parseInt(eliminatedText);
        } catch (NumberFormatException e) {
            return;
        }
        final Intent intent;
        synchronized (mListenerStateLock) {
            if (!isCurrentPeerRoundLocked(roundToken))
                return;
            Byte scoringPlayerID = getPeerScoreSourceID(ip);
            Globals globals = Globals.getInstance();
            int localPlayerID = globals.mPlayerID;
            if (scoringPlayerID == null || eliminatedPlayerID <= 0
                    || !Globals.isValidPlayerID(eliminatedPlayerID) || localPlayerID <= 0
                    || !Globals.isValidPlayerID(localPlayerID)
                    || globals.mGameMode == Globals.GAME_MODE_FFA)
                return;
            int scoringTeam = globals.calcNetworkTeam(scoringPlayerID);
            if (scoringPlayerID == eliminatedPlayerID
                    || scoringTeam != globals.calcNetworkTeam((byte) localPlayerID)
                    || scoringTeam == globals.calcNetworkTeam((byte) eliminatedPlayerID))
                return;
            synchronized (mPeerScoreLock) {
                if (sequence <= mLastPeerTeamEliminationSequences[scoringPlayerID][eliminatedPlayerID])
                    return;
                mLastPeerTeamEliminationSequences[scoringPlayerID][eliminatedPlayerID] = sequence;
            }
            intent = new Intent(NetMsg.NETMSG_TEAMELIMINATED)
                    .putExtra(NetMsg.INTENT_ROUND_TOKEN, roundToken);
        }
        sendBroadcast(intent);
    }

    private void updateGrenadePairing(byte playerID, int grenadeID) {
        if (playerID <= 0 || !Globals.isValidPlayerID(playerID)
                || !Globals.isValidGrenadeID(grenadeID))
            return;
        Globals globals = Globals.getInstance();
        Globals.getmGrenadePairingsSemaphore();
        try {
            // A player owns at most one grenade. Zero is an explicit unpair,
            // never a valid index to assign.
            for (int index = 1; index < globals.mGrenadePairings.length; index++) {
                if (globals.mGrenadePairings[index] == playerID)
                    globals.mGrenadePairings[index] = Globals.INVALID_PLAYER_ID;
            }
            if (grenadeID != 0)
                globals.mGrenadePairings[grenadeID] = playerID;
        } finally {
            globals.mGrenadePairingsSemaphore.release();
        }
    }

    /**
     * Publish this phone's grenade state into the redundant round snapshot.
     * Dedicated games still keep their TCP-authoritative pairing snapshots.
     */
    public void publishPeerGrenadePairing() {
        Globals globals = Globals.getInstance();
        int playerID = globals.mPlayerID;
        int grenadeID = globals.mPairedGrenadeID & 0xff;
        if (playerID <= 0 || !Globals.isValidPlayerID(playerID)
                || !Globals.isValidGrenadeID(grenadeID))
            return;

        synchronized (mListenerStateLock) {
            if (mDestroyed || !mStateGossipRound
                    || !TcpServer.isValidRoundToken(mStateRoundToken))
                return;
            synchronized (mPeerRuntimeLock) {
                PeerStatePacket.PlayerState current = localPeerStateLocked(playerID);
                mPeerStates[playerID] = copyLocalState(current, nextLocalStateSequenceLocked(),
                        current.eventSequence, current.eventType, current.eventTargetID,
                        current.score, current.deaths, current.health, current.shield,
                        current.shotsRemaining, globals.mGameState, grenadeID,
                        current.flags, current.latitude, current.longitude);
            }
            updateGrenadePairing((byte) playerID, grenadeID);
        }
        broadcastPeerState(PEER_GRENADE_UPDATE_REPETITIONS);
    }

    /** Publish an eliminated player's event to the peer that earned the point. */
    public void publishPeerElimination(byte scoringPlayerID) {
        if (!publishCombatEvent(PeerStatePacket.EVENT_ELIMINATED, scoringPlayerID & 0xff))
            publishPeerEvent(PeerStatePacket.EVENT_ELIMINATED, scoringPlayerID,
                    PEER_SCORE_UPDATE_REPETITIONS);
    }

    /** Relay a deduplicable peer score event to one teammate. */
    public void publishPeerTeamElimination(byte eliminatedPlayerID, long sequence,
                                           byte teammateID) {
        // Protocol 19 derives team totals from the cumulative scores carried in
        // every state snapshot. No per-teammate fan-out is needed.
    }

    /** A peer that leaves mid-round announces it repeatedly because UDP can drop packets. */
    public void announcePeerLeave() {
        publishPeerEvent(PeerStatePacket.EVENT_LEAVE, 0, PEER_SCORE_UPDATE_REPETITIONS);
    }

    /** Update the local row repeated by the one-second heartbeat. */
    public void updatePeerRuntimeState(int score, int deaths, int health, int shield,
                                       int shotsRemaining, int gameState) {
        Globals globals = Globals.getInstance();
        int playerID = globals.mPlayerID & 0xff;
        synchronized (mListenerStateLock) {
            if (mDestroyed || !mStateGossipRound
                    || !TcpServer.isValidRoundToken(mStateRoundToken)
                    || playerID < 1 || playerID > Globals.MAX_PLAYER_ID)
                return;
            synchronized (mPeerRuntimeLock) {
                PeerStatePacket.PlayerState current = localPeerStateLocked(playerID);
                mPeerStates[playerID] = copyLocalState(current, nextLocalStateSequenceLocked(),
                        current.eventSequence, current.eventType, current.eventTargetID,
                        Math.max(0, score), Math.max(0, deaths), health, shield, shotsRemaining,
                        gameState, globals.mPairedGrenadeID & 0xff, current.flags,
                        current.latitude, current.longitude);
            }
        }
    }

    /** Return known round scores; -1 means that player's state has not arrived. */
    public int[] getRoundScoresSnapshot() {
        int[] scores = new int[Globals.MAX_PLAYER_ID + 1];
        Arrays.fill(scores, -1);
        int localPlayerID = Globals.getInstance().mPlayerID & 0xff;
        synchronized (mPeerRuntimeLock) {
            for (int playerID = 1; playerID <= Globals.MAX_PLAYER_ID; playerID++) {
                PeerStatePacket.PlayerState state = playerID == localPlayerID
                        ? mPeerStates[playerID] : mAppliedPeerStates[playerID];
                if (state != null)
                    scores[playerID] = state.score;
            }
        }
        return scores;
    }

    private void publishPeerLocation(double longitude, double latitude) {
        if (!Globals.isValidCoordinates(longitude, latitude))
            return;
        int playerID = Globals.getInstance().mPlayerID & 0xff;
        synchronized (mListenerStateLock) {
            if (mDestroyed || !mStateGossipRound
                    || playerID < 1 || playerID > Globals.MAX_PLAYER_ID)
                return;
            synchronized (mPeerRuntimeLock) {
                PeerStatePacket.PlayerState current = localPeerStateLocked(playerID);
                mLocalGpsUpdatedAt = SystemClock.elapsedRealtime();
                mPeerStates[playerID] = copyLocalState(current, nextLocalStateSequenceLocked(),
                        current.eventSequence, current.eventType, current.eventTargetID,
                        current.score, current.deaths, current.health, current.shield,
                        current.shotsRemaining, current.gameState, current.grenadeID,
                        current.flags | PeerStatePacket.FLAG_GPS_VALID, latitude, longitude);
            }
        }
    }

    /**
     * Broadcast one 32-byte combat event. Targeted events retain at most one
     * fixed pending slot and are retried at 20/60 ms until their target ACKs.
     */
    private boolean publishCombatEvent(int eventType, int targetPlayerID) {
        if (!CombatPacket.isCombatEvent(eventType)
                || (eventType != PeerStatePacket.EVENT_SHOT_FIRED
                && !Globals.isValidPlayerID(targetPlayerID)))
            return false;
        final byte[] payload;
        final long generation;
        synchronized (mListenerStateLock) {
            int playerID = Globals.getInstance().mPlayerID & 0xff;
            if (mDestroyed || !mStateGossipRound || mStateRoundUuid == null
                    || !Globals.isValidPlayerID(playerID))
                return false;
            final long eventSequence;
            synchronized (mPeerRuntimeLock) {
                PeerStatePacket.PlayerState current = localPeerStateLocked(playerID);
                eventSequence = nextLocalEventSequenceLocked();
                mPeerStates[playerID] = copyLocalState(current,
                        nextLocalStateSequenceLocked(), eventSequence, eventType,
                        eventType == PeerStatePacket.EVENT_SHOT_FIRED ? 0 : targetPlayerID,
                        current.score, current.deaths, current.health, current.shield,
                        current.shotsRemaining, current.gameState, current.grenadeID,
                        current.flags, current.latitude, current.longitude);
            }
            payload = CombatPacket.encode(NetMsg.NETWORK_VERSION_NUMBER, mStateRoundUuid,
                    CombatPacket.KIND_EVENT, eventType, playerID,
                    eventType == PeerStatePacket.EVENT_SHOT_FIRED ? 0 : targetPlayerID,
                    eventSequence);
            generation = mSendGeneration;
            if (eventType != PeerStatePacket.EVENT_SHOT_FIRED) {
                int slot = (int) (eventSequence % mPendingCombatEvents.length);
                long now = SystemClock.elapsedRealtime();
                mPendingCombatEvents[slot] = new PendingCombatEvent(eventSequence,
                        eventType, targetPlayerID,
                        getPlayerEndpoint((byte) targetPlayerID), payload, now,
                        now + COMBAT_RETRY_OFFSETS_MS[0]);
                scheduleCombatRetryLocked(now);
            }
        }
        sendCombatBroadcast(payload, generation);
        return true;
    }

    private void sendCombatBroadcast(byte[] payload, long generation) {
        final List<InetAddress> recipients;
        synchronized (mListenerStateLock) {
            if (mDestroyed || !mStateGossipRound || generation != mSendGeneration)
                return;
            if (mBroadcastAddress == null)
                mBroadcastAddress = getBroadcastAddress();
            if (mBroadcastAddress != null) {
                recipients = Collections.singletonList(mBroadcastAddress);
            } else {
                Globals.getmIPTeamMapSemaphore();
                try {
                    recipients = new ArrayList<>(Globals.getInstance().mIPTeamMap.keySet());
                } finally {
                    Globals.getInstance().mIPTeamMapSemaphore.release();
                }
            }
        }
        if (!recipients.isEmpty())
            sendCombatDatagrams(payload, recipients, generation);
    }

    /** Queue latency-sensitive events independently of bulk state traffic. */
    void sendCombatDatagrams(byte[] payload, List<InetAddress> recipients, long generation) {
        if (payload == null || payload.length != CombatPacket.PACKET_BYTES
                || recipients == null || recipients.isEmpty() || !isCurrentSend(generation))
            return;
        if (mQueuedCombatSends.incrementAndGet() > MAX_PENDING_DATAGRAM_SENDS) {
            mQueuedCombatSends.decrementAndGet();
            Log.w(TAG, "Dropping combat send because the bounded queue is full");
            return;
        }
        Runnable sendTask = () -> {
            try {
                DatagramSocket socket = mSocket;
                if (socket == null || socket.isClosed()) {
                    // A listener hand-off can briefly replace the bound socket.
                    // Preserve the event through the ordinary sender in that rare
                    // case; normal gameplay stays on the independent fast path.
                    sendDatagrams(payload, recipients, LISTEN_PORT, 1, generation);
                    return;
                }
                DatagramPacket packet = new DatagramPacket(payload, payload.length);
                for (InetAddress recipient : recipients) {
                    if (!isCurrentSend(generation) || Thread.currentThread().isInterrupted())
                        return;
                    try {
                        packet.setAddress(recipient);
                        packet.setPort(LISTEN_PORT);
                        socket.send(packet);
                    } catch (IOException e) {
                        // Targeted events have 20/60 ms retries. Logging at debug
                        // avoids a UI/error allocation in the combat path.
                        Log.d(TAG, "Combat event send failed; waiting for retry", e);
                    }
                }
            } finally {
                mQueuedCombatSends.decrementAndGet();
            }
        };
        try {
            mCombatExecutor.execute(sendTask);
        } catch (RuntimeException e) {
            mQueuedCombatSends.decrementAndGet();
            Log.w(TAG, "Unable to queue combat event", e);
        }
    }

    // Caller holds mListenerStateLock.
    private void scheduleCombatRetryLocked(long now) {
        long nextRetryAt = Long.MAX_VALUE;
        for (PendingCombatEvent pending : mPendingCombatEvents) {
            if (pending != null && pending.nextRetryAt < nextRetryAt)
                nextRetryAt = pending.nextRetryAt;
        }
        if (mCombatRetryFuture != null) {
            mCombatRetryFuture.cancel(false);
            mCombatRetryFuture = null;
        }
        if (nextRetryAt != Long.MAX_VALUE) {
            try {
                mCombatRetryFuture = mCombatExecutor.schedule(mCombatRetrySweep,
                        Math.max(0, nextRetryAt - now), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "Unable to schedule combat retry", e);
            }
        }
    }

    private static final class PendingCombatEvent {
        final long sequence;
        final int eventType;
        final int targetID;
        final InetAddress targetEndpoint;
        final byte[] payload;
        final long createdAt;
        long nextRetryAt;
        int nextRetryIndex;

        PendingCombatEvent(long sequence, int eventType, int targetID,
                           InetAddress targetEndpoint, byte[] payload, long createdAt,
                           long nextRetryAt) {
            this.sequence = sequence;
            this.eventType = eventType;
            this.targetID = targetID;
            this.targetEndpoint = targetEndpoint;
            this.payload = payload;
            this.createdAt = createdAt;
            this.nextRetryAt = nextRetryAt;
        }
    }

    private void publishPeerEvent(int eventType, int targetPlayerID, int repetitions) {
        int playerID = Globals.getInstance().mPlayerID & 0xff;
        if (targetPlayerID < 0 || targetPlayerID > Globals.MAX_PLAYER_ID)
            return;
        synchronized (mListenerStateLock) {
            if (mDestroyed || !mPeerGame || !TcpServer.isValidRoundToken(mPeerRoundToken)
                    || playerID < 1 || playerID > Globals.MAX_PLAYER_ID)
                return;
            synchronized (mPeerRuntimeLock) {
                PeerStatePacket.PlayerState current = localPeerStateLocked(playerID);
                long eventSequence = nextLocalEventSequenceLocked();
                int flags = eventType == PeerStatePacket.EVENT_LEAVE
                        ? current.flags | PeerStatePacket.FLAG_LEFT : current.flags;
                mPeerStates[playerID] = copyLocalState(current, nextLocalStateSequenceLocked(),
                        eventSequence, eventType, targetPlayerID, current.score, current.deaths,
                        current.health, current.shield, current.shotsRemaining, current.gameState,
                        current.grenadeID, flags, current.latitude, current.longitude);
            }
        }
        broadcastPeerState(repetitions);
    }

    // Caller holds mPeerRuntimeLock.
    private PeerStatePacket.PlayerState localPeerStateLocked(int playerID) {
        PeerStatePacket.PlayerState current = mPeerStates[playerID];
        if (current != null)
            return current;
        Globals globals = Globals.getInstance();
        return new PeerStatePacket.PlayerState(playerID, PeerStatePacket.FLAG_PRESENT,
                Math.max(1, mNextLocalPeerStateSequence), 0, PeerStatePacket.EVENT_NONE, 0,
                globals.mGameState, globals.mPairedGrenadeID & 0xff, 0, 0,
                globals.mFullHealth, globals.mFullShields, globals.mFullReload & 0xff,
                0, 0, 0);
    }

    private PeerStatePacket.PlayerState copyLocalState(PeerStatePacket.PlayerState current,
            long ownerSequence, long eventSequence, int eventType, int eventTargetID,
            int score, int deaths, int health, int shield, int shotsRemaining, int gameState,
            int grenadeID, int flags, double latitude, double longitude) {
        int gpsAge = mLocalGpsUpdatedAt == 0 ? 0 : (int) Math.min(0xffff,
                Math.max(0, (SystemClock.elapsedRealtime() - mLocalGpsUpdatedAt) / 1000));
        return new PeerStatePacket.PlayerState(current.playerID,
                flags | PeerStatePacket.FLAG_PRESENT, ownerSequence, eventSequence, eventType,
                eventTargetID, gameState, grenadeID, score, deaths, health, shield,
                shotsRemaining, gpsAge, latitude, longitude);
    }

    private long nextLocalStateSequenceLocked() {
        if (mNextLocalPeerStateSequence >= 0xffffffffL)
            throw new IllegalStateException("Peer state sequence exhausted");
        return ++mNextLocalPeerStateSequence;
    }

    private long nextLocalEventSequenceLocked() {
        if (mNextLocalPeerEventSequence >= 0xffffffffL)
            throw new IllegalStateException("Peer event sequence exhausted");
        return ++mNextLocalPeerEventSequence;
    }

    private void resetPeerGrenadeSequences() {
        synchronized (mPeerGrenadeLock) {
            mNextPeerGrenadeSequence = 0;
            for (int index = 0; index < mLastPeerGrenadeSequences.length; index++)
                mLastPeerGrenadeSequences[index] = 0;
        }
    }

    private void resetPeerScoreSequences() {
        synchronized (mPeerScoreLock) {
            mNextPeerEliminationSequence = 0;
            mDepartedPeerScoreSources.clear();
            for (int playerID = 0; playerID < mLastPeerEliminationSequences.length; playerID++) {
                mLastPeerEliminationSequences[playerID] = 0;
                for (int eliminatedPlayerID = 0;
                     eliminatedPlayerID < mLastPeerTeamEliminationSequences[playerID].length;
                     eliminatedPlayerID++)
                    mLastPeerTeamEliminationSequences[playerID][eliminatedPlayerID] = 0;
            }
        }
    }

    private void resetPeerGameSequences() {
        resetPeerGrenadeSequences();
        resetPeerScoreSequences();
        synchronized (mPeerRuntimeLock) {
            mNextPeerSnapshotSequence = 0;
            mNextAuthorityTickSequence = 0;
            mLastAuthorityTickSequence = 0;
            mLastAuthorityTickAt = 0;
            mNextLocalPeerStateSequence = 0;
            mNextLocalPeerEventSequence = 0;
            mLocalGpsUpdatedAt = 0;
            mLastPublishedTeamScore = -1;
            for (int playerID = 0; playerID < mPeerStates.length; playerID++) {
                mPeerStates[playerID] = null;
                mAppliedPeerStates[playerID] = null;
                mLastPeerSnapshotSequences[playerID] = 0;
                mCombatHighestSequences[playerID] = 0;
                mCombatSeenMasks[playerID] = 0;
            }
            for (int index = 0; index < mPendingCombatEvents.length; index++)
                mPendingCombatEvents[index] = null;
        }
        mMainHandler.removeCallbacks(mPeerStateHeartbeat);
        mMainHandler.removeCallbacks(mAuthorityTickHeartbeat);
        mMainHandler.removeCallbacks(mGossipRecovery);
        if (mCombatRetryFuture != null) {
            mCombatRetryFuture.cancel(false);
            mCombatRetryFuture = null;
        }
    }

    private Byte getPlayerID(InetAddress ip) {
        Globals.getmIPTeamMapSemaphore();
        try {
            Byte playerID = Globals.getInstance().mIPTeamMap.get(ip);
            return playerID != null && playerID > 0 && Globals.isValidPlayerID(playerID) ? playerID : null;
        } finally {
            Globals.getInstance().mIPTeamMapSemaphore.release();
        }
    }

    /**
     * Resolve a current peer, or one that was just removed by a validated
     * LEAVE. This is deliberately limited to sequenced peer score events;
     * departed peers cannot otherwise participate in the roster or game.
     */
    private Byte getPeerScoreSourceID(InetAddress ip) {
        Byte playerID = getPlayerID(ip);
        if (playerID != null)
            return playerID;
        synchronized (mPeerScoreLock) {
            playerID = mDepartedPeerScoreSources.get(ip);
            return playerID != null && playerID > 0 && Globals.isValidPlayerID(playerID)
                    ? playerID : null;
        }
    }

    /**
     * Peer games no longer have a TCP listener after the synchronized start is
     * announced.  Remove a departing, already-known peer from the local
     * snapshots so remaining players do not keep waiting for it forever.  This
     * is deliberately disabled for dedicated games, where UDP alone must never
     * alter the TCP-owned roster.
     */
    private Byte removePeerPlayer(InetAddress ip) {
        if (!mPeerGame)
            return null;
        final Byte playerID;
        Globals globals = Globals.getInstance();
        Globals.getmIPTeamMapSemaphore();
        try {
            playerID = globals.mIPTeamMap.get(ip);
            if (playerID == null || playerID <= 0 || !Globals.isValidPlayerID(playerID))
                return null;
            globals.mIPTeamMap.remove(ip);
        } finally {
            globals.mIPTeamMapSemaphore.release();
        }

        synchronized (mPeerScoreLock) {
            mDepartedPeerScoreSources.put(ip, playerID);
        }

        Globals.getmTeamIPMapSemaphore();
        try {
            InetAddress endpoint = globals.mTeamIPMap.get(playerID);
            if (ip.equals(endpoint))
                globals.mTeamIPMap.remove(playerID);
        } finally {
            globals.mTeamIPMapSemaphore.release();
        }
        Globals.getmTeamPlayerNameSemaphore();
        try {
            globals.mTeamPlayerNameMap.remove(playerID);
        } finally {
            globals.mTeamPlayerNameSemaphore.release();
        }
        Globals.getmGPSDataSemaphore();
        try {
            globals.mGPSData.remove(playerID);
        } finally {
            globals.mGPSDataSemaphore.release();
        }
        Globals.getmPlayerSettingsSemaphore();
        try {
            globals.mPlayerSettings.remove(playerID);
        } finally {
            globals.mPlayerSettingsSemaphore.release();
        }
        Globals.getmGrenadePairingsSemaphore();
        try {
            for (int index = 1; index < globals.mGrenadePairings.length; index++) {
                if (globals.mGrenadePairings[index] == playerID)
                    globals.mGrenadePairings[index] = Globals.INVALID_PLAYER_ID;
            }
        } finally {
            globals.mGrenadePairingsSemaphore.release();
        }
        return playerID;
    }

    private void completeJoin(InetAddress ip, String action) {
        completeJoin(ip, action, null);
    }

    private void completeJoin(InetAddress ip, String action, Byte assignedPlayerID) {
        synchronized (mListenerStateLock) {
            if (mDestroyed || !mScanRunning || (!mBroadcastScan && !ip.equals(mJoinAddress)))
                return;
            endScanningLocked();
            if (NetMsg.NETMSG_SERVERREPLY.equals(action))
                Globals.getInstance().mServerIP = ip;
            else
                stopListen();
            Intent result = new Intent(action);
            if (assignedPlayerID != null)
                result.putExtra(INTENT_PLAYERID, assignedPlayerID);
            sendBroadcast(result);
        }
    }

    private volatile Thread mUDPMessageThread;

    /**
     * Listen for a committed dedicated-game invitation while this phone is
     * otherwise idle. The socket is reused in-place when the player accepts,
     * avoiding a close/rebind race on Android 5.1-era Wi-Fi stacks.
     */
    public void startGameInviteListener() {
        synchronized (mListenerStateLock) {
            if (mDestroyed)
                return;
            mInviteListenerRequested = true;
            startGameInviteListenerLocked();
        }
    }

    // Caller holds mListenerStateLock.
    private void startGameInviteListenerLocked() {
        // A host, an actual scan, or a joined client already owns this socket.
        // Do not replace any of those sessions with the passive listener.
        if (mDestroyed || mIsListService || mScanRunning || mPeerGame
                || (keepListening && !mPassiveInviteListener)) {
            cancelInviteListenerRequestLocked();
            return;
        }
        if (mPassiveInviteListener && !doneListening) {
            cancelInviteListenerRequestLocked();
            return;
        }
        if (!doneListening) {
            scheduleInviteListenerRetryLocked();
            return;
        }
        cancelInviteListenerRequestLocked();
        mPassiveInviteListener = true;
        mMyIP = null;
        mReadyToScan = 0;
        startListenForUDPMessage();
    }

    // Caller holds mListenerStateLock.
    private void scheduleInviteListenerRetryLocked() {
        if (mInviteListenerRetry != null)
            return;
        final Runnable retry = new Runnable() {
            @Override public void run() {
                synchronized (mListenerStateLock) {
                    if (mInviteListenerRetry != this)
                        return;
                    mInviteListenerRetry = null;
                    if (mInviteListenerRequested)
                        startGameInviteListenerLocked();
                }
            }
        };
        mInviteListenerRetry = retry;
        mMainHandler.postDelayed(retry, INVITE_LISTENER_RETRY_MS);
    }

    // Caller holds mListenerStateLock.
    private void cancelInviteListenerRequestLocked() {
        mInviteListenerRequested = false;
        if (mInviteListenerRetry != null) {
            mMainHandler.removeCallbacks(mInviteListenerRetry);
            mInviteListenerRetry = null;
        }
    }

    // Caller holds mListenerStateLock. A real join/create can reuse the
    // passive listener without reporting a false "could not find lobby".
    private boolean canReusePassiveInviteListenerLocked() {
        if (!mPassiveInviteListener || doneListening || mIsListService || mScanRunning)
            return false;
        mPassiveInviteListener = false;
        cancelInviteListenerRequestLocked();
        return true;
    }

    public void startListenForUDPMessage() {
        synchronized (mListenerStateLock) {
            if (mDestroyed)
                return;
            if (mUDPMessageThread != null && mUDPMessageThread.isAlive()) {
                Log.w(TAG, "UDP listener is already running");
                return;
            }
            keepListening = true;
            // Set this before starting the thread so a fast second Join/Create request cannot
            // start another listener while the first one is still binding its socket.
            doneListening = false;
            if (mIsListService || mPassiveInviteListener) {
                if (wm == null)
                    wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm == null) {
                    Log.e(TAG, "Failed to get wifi manager");
                } else {
                    if (multicastLock == null) {
                        multicastLock = wm.createMulticastLock("SimpleCoil");
                        multicastLock.setReferenceCounted(false);
                    }
                }
                if (multicastLock != null && !multicastLock.isHeld()) multicastLock.acquire();
            }

            mUDPMessageThread = new Thread(() -> {
                try {
                    while (keepListening) {
                        try {
                            InetAddress address = Globals.getIPAddress(getApplicationContext());
                            if (mIsListService || address == null)
                                address = InetAddress.getByName("0.0.0.0");
                            listenForMessage(address, LISTEN_PORT, LISTEN_TIMEOUT_MS);
                        } catch (Exception e) {
                            Log.i(TAG, "no longer listening for UDP messages: " + e.getMessage());
                            if (keepListening)
                                sleep(100);
                        }
                    }
                } finally {
                    Log.i(TAG, "Stopped listening for UDP messages");
                    synchronized (mListenerStateLock) {
                        releaseMulticastLockLocked();
                        if (Thread.currentThread() == mUDPMessageThread) {
                            mUDPMessageThread = null;
                            doneListening = true;
                        }
                    }
                }
            }, "SimpleCoil UDP listener");
            mUDPMessageThread.start();
        }
    }

    private InetAddress getBroadcastAddress() {
        try {
            Context appContext = getApplicationContext();
            if (wm == null && appContext != null)
                wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                Log.e(TAG, "Failed to get wifi manager");
                return null;
            }
            DhcpInfo dhcp = wm.getDhcpInfo();
            if (dhcp == null) {
                Log.e(TAG, "Failed to get dhcp info");
                return null;
            }
            return broadcastAddressForDhcp(dhcp.ipAddress, dhcp.netmask);
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to read Wi-Fi DHCP information", e);
            return null;
        }
    }

    static InetAddress broadcastAddressForDhcp(int ipAddress, int netmask) {
        // Wi-Fi reports zeroes before it has a DHCP lease. Calculating a
        // broadcast from those values produces 255.255.255.255, which can make
        // a host look ready even though peers cannot discover it on the LAN.
        if (ipAddress == 0 || netmask == 0) {
            Log.w(TAG, "No usable Wi-Fi DHCP lease for UDP discovery");
            return null;
        }
        int broadcast = (ipAddress & netmask) | ~netmask;
        byte[] quads = new byte[4];
        for (int k = 0; k < 4; k++)
            quads[k] = (byte) ((broadcast >> k * 8) & 0xFF);
        try {
            return InetAddress.getByAddress(quads);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get broadcast IP address");
            return null;
        }
    }

    public void createServer() {
        synchronized (mListenerStateLock) {
            if (mDestroyed)
                return;
            // The idle invite listener owns the same UDP socket that a new
            // lobby needs.  Keep its ready state when promoting it to a host:
            // clearing mReadyToScan below while startListenForUDPMessage()
            // correctly declines to start a second thread left the socket
            // usable but permanently marked "not ready".  That made every
            // host creation from an idle player time out after five seconds.
            final boolean reusingPassiveInviteListener = !doneListening
                    && canReusePassiveInviteListenerLocked();
            if (!doneListening && !reusingPassiveInviteListener) {
                Log.e(TAG, "Listening is still in progress");
                // A new host request supersedes an unfinished discovery scan.
                // Leaving the old scan alive lets a late SERVERREPLY join the
                // abandoned server after this request reports its failure.
                stopListen();
                sendFailedJoin();
                return;
            }
            cancelInviteListenerRequestLocked();
            mPassiveInviteListener = false;
            mInviteTemporarilyAllowsJoin = false;
            mInviteWindowGeneration++;
            mBroadcastAddress = getBroadcastAddress();
            if (mBroadcastAddress == null) {
                Log.e(TAG, "Failed to get broadcast IP address");
                sendFailedJoin();
                return;
            }
            mSendGeneration++;
            endScanningLocked();
            mPeerGame = false;
            mGameRunning = false;
            mPeerRoundToken = null;
            mStateGossipRound = false;
            mStateAuthority = false;
            mStateRoundToken = null;
            mStateRoundUuid = null;
            mPendingPeerRoundToken = null;
            mPendingPeerEndGame = null;
            resetPeerGameSequences();
            releaseGameplayWifiLocksLocked();
            Globals.getmIPTeamMapSemaphore();
            Globals.getInstance().mIPTeamMap.clear();
            Globals.getInstance().mIPTeamMapSemaphore.release();
            Globals.getmTeamIPMapSemaphore();
            Globals.getInstance().mTeamIPMap.clear();
            Globals.getInstance().mTeamIPMapSemaphore.release();
            clearJoinAssignments();
            keepListening = true;
            mIsListService = true;
            mScanRunning = false;
            mMyIP = null;
            if (!reusingPassiveInviteListener)
                mReadyToScan = 0;
            startListenForUDPMessage();
            final long generation = mJoinGeneration;
            new Thread(() -> finishServerCreation(generation), "SimpleCoil UDP server startup").start();
        }
    }

    private void finishServerCreation(long generation) {
        long deadline = System.currentTimeMillis() + LISTENER_START_TIMEOUT_MS;
        while (keepListening && generation == mJoinGeneration && !isListenerReady()
                && System.currentTimeMillis() < deadline)
            sleep(50);
        synchronized (mListenerStateLock) {
            if (mDestroyed || !keepListening || generation != mJoinGeneration)
                return;
            if (!isListenerReadyLocked()) {
                Log.e(TAG, "Timed out starting UDP listener");
                stopListen();
                sendFailedJoin();
                return;
            }
            mMyIP = Globals.getIPAddress(getApplicationContext());
            if (mMyIP == null) {
                Log.e(TAG, "No local IPv4 address available for server");
                stopListen();
                sendFailedJoin();
                return;
            }
            Globals.getInstance().mServerIP = mMyIP;
            sendBroadcast(new Intent(NetMsg.NETMSG_SERVERCREATED));
        }
    }

    public void cancelServer() {
        synchronized (mListenerStateLock) {
            if (mIsListService)
                stopListen();
        }
    }

    public void joinServer() {
        synchronized (mListenerStateLock) {
            if (mDestroyed)
                return;
            // Match the replacement behavior of explicit-address joins. If a
            // prior scan is still listening, a new Join request supersedes it
            // even when Wi-Fi has already lost its DHCP lease. Otherwise a
            // late reply can attach this player to the abandoned server after
            // the new request has reported failure.
            if (!doneListening && !mPassiveInviteListener) {
                Log.e(TAG, "Listening is still in progress");
                stopListen();
                sendFailedJoin();
                return;
            }
            mBroadcastAddress = getBroadcastAddress();
            if (mBroadcastAddress == null) {
                sendFailedJoin();
                return;
            }
            joinServer(mBroadcastAddress, LISTEN_PORT);
        }
    }

    public void joinServer(String serverIP) {
        serverIP = serverIP == null ? "" : serverIP.trim();
        if (serverIP.startsWith("/"))
            serverIP = serverIP.substring(1).trim();
        final String ip = serverIP;
        final long generation;
        synchronized (mListenerStateLock) {
            if (mDestroyed)
                return;
            if (ip.isEmpty()) {
                Log.w(TAG, "Cannot join an empty server address");
                sendFailedJoin();
                return;
            }
            if (!doneListening && !mPassiveInviteListener) {
                // This request replaces the in-flight discovery. Leaving it
                // active after reporting failure lets a late reply join the
                // server the player just abandoned.
                stopListen();
                sendFailedJoin();
                return;
            }
            endScanningLocked();
            generation = mJoinGeneration;
        }
        Log.e(TAG, "attempt to join " + ip);
        try {
            mLookupExecutor.execute(() -> resolveAndJoinServer(ip, generation));
        } catch (RejectedExecutionException e) {
            synchronized (mListenerStateLock) {
                if (!mDestroyed && generation == mJoinGeneration)
                    sendFailedJoin();
            }
        }
    }

    InetAddress resolveServerAddress(String address) throws UnknownHostException {
        return InetAddress.getByName(address);
    }

    private void resolveAndJoinServer(String address, long generation) {
        final InetAddress ipAddr;
        try {
            ipAddr = resolveServerAddress(address);
        } catch (UnknownHostException | SecurityException e) {
            Log.w(TAG, "Unable to resolve server address", e);
            synchronized (mListenerStateLock) {
                if (!mDestroyed && generation == mJoinGeneration)
                    sendFailedJoin();
            }
            return;
        }
        synchronized (mListenerStateLock) {
            // A cancelled lookup must not resurrect discovery or replace the
            // target of a newer Join request after it finally resolves.
            if (!mDestroyed && generation == mJoinGeneration)
                joinServer(ipAddr);
        }
    }

    public void joinServer(InetAddress serverIP) {
        joinServer(serverIP, LISTEN_PORT);
    }

    /**
     * Join the host that sent a validated GAMEINVITE. This accepts the passive
     * listener as a reusable socket, rather than treating it as an abandoned
     * manual discovery request and displaying a false failure.
     */
    public boolean joinGameInvite(InetAddress serverIP) {
        synchronized (mListenerStateLock) {
            if (mDestroyed || serverIP == null) {
                return false;
            }
            if (!doneListening && !mPassiveInviteListener) {
                Log.w(TAG, "Ignoring a stale game invitation while another network session is active");
                return false;
            }
            joinServer(serverIP, LISTEN_PORT);
            return mScanRunning;
        }
    }

    /** Join a newly-created nearby peer lobby from the reusable idle listener. */
    public boolean joinLobbyInvite(InetAddress serverIP) {
        synchronized (mListenerStateLock) {
            if (mDestroyed || serverIP == null)
                return false;
            if (!doneListening && !mPassiveInviteListener) {
                Log.w(TAG, "Ignoring a lobby invitation while another network session is active");
                return false;
            }
            joinServer(serverIP, LISTEN_PORT);
            return mScanRunning;
        }
    }

    /**
     * Move an idle lobby to a standalone host. Unlike an ordinary invitation,
     * this is allowed to reuse an active host/client listener in-place, so the
     * handoff does not create a close/rebind race on older Wi-Fi stacks.
     */
    public boolean joinServerAfterHostTakeover(InetAddress serverIP) {
        synchronized (mListenerStateLock) {
            if (mDestroyed || serverIP == null || mPeerGame)
                return false;
            final boolean reusingActiveListener = !doneListening && isListenerReadyLocked();
            if (!doneListening && !reusingActiveListener) {
                Log.w(TAG, "Ignoring host takeover while UDP listener is not ready");
                return false;
            }
            cancelInviteListenerRequestLocked();
            mPassiveInviteListener = false;
            mInviteTemporarilyAllowsJoin = false;
            mInviteWindowGeneration++;
            mSendGeneration++;
            endScanningLocked();
            mPeerGame = false;
            mGameRunning = false;
            mPeerRoundToken = null;
            mStateGossipRound = false;
            mStateAuthority = false;
            mStateRoundToken = null;
            mStateRoundUuid = null;
            mPendingPeerRoundToken = null;
            mPendingPeerEndGame = null;
            resetPeerGameSequences();
            releaseGameplayWifiLocksLocked();
            // The replacement TCP host owns a new roster. Drop the old peer
            // snapshot immediately so its players cannot appear during the
            // short UDP/TCP handoff window.
            Globals.getmIPTeamMapSemaphore();
            Globals.getInstance().mIPTeamMap.clear();
            Globals.getInstance().mIPTeamMapSemaphore.release();
            Globals.getmTeamIPMapSemaphore();
            Globals.getInstance().mTeamIPMap.clear();
            Globals.getInstance().mTeamIPMapSemaphore.release();
            clearJoinAssignments();
            keepListening = true;
            mIsListService = false;
            mScanRunning = true;
            mJoinAddress = serverIP;
            mBroadcastScan = false;
            mMyIP = null;
            if (!reusingActiveListener)
                mReadyToScan = 0;
            startListenForUDPMessage();
            joinFailCheck(serverIP, LISTEN_PORT);
            return true;
        }
    }

    public void joinServer(InetAddress serverIP, Integer port) {
        synchronized (mListenerStateLock) {
            if (mDestroyed)
                return;
            if (serverIP == null || port == null || port < 1 || port > 65535) {
                Log.w(TAG, "Cannot join an invalid UDP endpoint");
                sendFailedJoin();
                return;
            }
            // Preserve the readiness of a socket reused from the passive
            // invitation listener. See createServer() for why resetting it
            // before startListenForUDPMessage() is a stale-state bug.
            final boolean reusingPassiveInviteListener = !doneListening
                    && canReusePassiveInviteListenerLocked();
            if (!doneListening && !reusingPassiveInviteListener) {
                Log.e(TAG, "Listening is still in progress");
                // This request replaces the in-flight discovery. Leaving it
                // active after reporting failure lets a late reply join the
                // server the player just abandoned.
                stopListen();
                sendFailedJoin();
                return;
            }
            cancelInviteListenerRequestLocked();
            mPassiveInviteListener = false;
            mInviteTemporarilyAllowsJoin = false;
            mInviteWindowGeneration++;
            mSendGeneration++;
            endScanningLocked();
            mPeerGame = false;
            mGameRunning = false;
            mPeerRoundToken = null;
            mStateGossipRound = false;
            mStateAuthority = false;
            mStateRoundToken = null;
            mStateRoundUuid = null;
            mPendingPeerRoundToken = null;
            mPendingPeerEndGame = null;
            resetPeerGameSequences();
            releaseGameplayWifiLocksLocked();
            Globals.getmIPTeamMapSemaphore();
            Globals.getInstance().mIPTeamMap.clear();
            Globals.getInstance().mIPTeamMapSemaphore.release();
            Globals.getmTeamIPMapSemaphore();
            Globals.getInstance().mTeamIPMap.clear();
            Globals.getInstance().mTeamIPMapSemaphore.release();
            clearJoinAssignments();
            keepListening = true;
            mIsListService = false;
            mScanRunning = true;
            mJoinAddress = serverIP;
            mBroadcastScan = serverIP.equals(mBroadcastAddress);
            mMyIP = null;
            if (!reusingPassiveInviteListener)
                mReadyToScan = 0;
            startListenForUDPMessage();
            joinFailCheck(serverIP, port);
        }
    }

    private void joinFailCheck(final InetAddress serverIP, final Integer port) {
        final long generation = mJoinGeneration;
        mMainHandler.post(() -> {
            synchronized (mListenerStateLock) {
                if (mDestroyed || !mScanRunning || generation != mJoinGeneration)
                    return;
                mJoinTimer = new CountDownTimer(2000, 500) {
                    @Override public void onTick(long millisUntilFinished) {
                        synchronized (mListenerStateLock) {
                            if (mJoinTimer != this || !mScanRunning || generation != mJoinGeneration)
                                return;
                            sendUDPMessage(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_JOIN
                                    + NetMsg.NETWORK_VERSION + Globals.getInstance().mPlayerID, serverIP, port);
                        }
                    }

                    @Override public void onFinish() {
                        synchronized (mListenerStateLock) {
                            if (mJoinTimer != this || !mScanRunning || generation != mJoinGeneration)
                                return;
                            Log.d(TAG, "join failed, could not find a server");
                            stopListen();
                            sendFailedJoin();
                        }
                    }
                };
                mJoinTimer.start();
            }
        });
    }

    private void sendFailedJoin() {
        Intent intent = new Intent(NetMsg.NETMSG_FAILEDTOJOIN);
        sendBroadcast(intent);
    }

    public void sendUDPMessage(String message, Byte playerID) {
        sendUDPMessageRepeat(message, playerID, 1);
    }

    /** Send a direct UDP event with bounded, ordered retries. */
    public void sendUDPMessageRepeat(String message, Byte playerID, int repeatCount) {
        int peerEvent = peerEventForMessage(message);
        if (peerEvent != PeerStatePacket.EVENT_NONE && playerID != null
                && publishCombatEvent(peerEvent, playerID & 0xff))
            return;
        final long generation = getSendGeneration();
        if (generation < 0 || message == null || playerID == null || repeatCount <= 0)
            return;
        message = NetMsg.MESSAGE_PREFIX + message;
        Globals.getmTeamIPMapSemaphore();
        final InetAddress ip;
        try {
            ip = Globals.getInstance().mTeamIPMap.get(playerID);
        } finally {
            Globals.getInstance().mTeamIPMapSemaphore.release();
        }
        if (ip == null) {
            Log.e(TAG, "cannot send message to unknown ID " + playerID);
            return;
        }
        sendDatagrams(message, Collections.singletonList(ip), LISTEN_PORT, repeatCount, generation);
    }

    public void sendUDPMessageAll(String message) {
        sendUDPMessageAllRepeat(message, 1);
    }

    public void sendUDPMessageAllRepeat(String message, final int repeatCount) {
        if (NetMsg.NETMSG_SHOTFIRED.equals(message)
                && publishCombatEvent(PeerStatePacket.EVENT_SHOT_FIRED, 0))
            return;
        final long generation = getSendGeneration();
        if (generation < 0 || repeatCount <= 0)
            return;
        final List<InetAddress> recipients;
        synchronized (mListenerStateLock) {
            if (mGameRunning && mBroadcastAddress == null)
                mBroadcastAddress = getBroadcastAddress();
            if (mGameRunning && mBroadcastAddress != null) {
                recipients = Collections.singletonList(mBroadcastAddress);
            } else {
                Globals.getmIPTeamMapSemaphore();
                try {
                    recipients = new ArrayList<>(Globals.getInstance().mIPTeamMap.keySet());
                } finally {
                    Globals.getInstance().mIPTeamMapSemaphore.release();
                }
            }
        }
        // Freeze recipients before queuing, and never hold the roster lock over
        // socket I/O. A delayed ENDGAME must not follow players into a new lobby.
        sendDatagrams(NetMsg.MESSAGE_PREFIX + message, recipients, LISTEN_PORT, repeatCount, generation);
    }

    private static int peerEventForMessage(String message) {
        if (NetMsg.NETMSG_HIT.equals(message)) return PeerStatePacket.EVENT_HIT;
        if (NetMsg.NETMSG_OUT.equals(message)) return PeerStatePacket.EVENT_OUT;
        if (NetMsg.NETMSG_ALREADYDEAD.equals(message)) return PeerStatePacket.EVENT_ALREADY_DEAD;
        return PeerStatePacket.EVENT_NONE;
    }

    /** One directed IPv4 broadcast replaces one unicast send per player. */
    private void broadcastPeerState(int repeatCount) {
        if (repeatCount <= 0)
            return;
        final byte[] payload;
        final List<InetAddress> recipients;
        final long generation;
        synchronized (mListenerStateLock) {
            if (mDestroyed || !mStateGossipRound
                    || !TcpServer.isValidRoundToken(mStateRoundToken))
                return;
            if (mBroadcastAddress == null)
                mBroadcastAddress = getBroadcastAddress();
            if (mBroadcastAddress != null) {
                recipients = Collections.singletonList(mBroadcastAddress);
            } else {
                // Static-IP networks may lack DHCP information. Preserve
                // gameplay there with a bounded compatibility fallback.
                Globals.getmIPTeamMapSemaphore();
                try {
                    recipients = new ArrayList<>(Globals.getInstance().mIPTeamMap.keySet());
                } finally {
                    Globals.getInstance().mIPTeamMapSemaphore.release();
                }
            }
            if (recipients.isEmpty())
                return;
            generation = mSendGeneration;
            UUID token;
            try {
                token = UUID.fromString(mStateRoundToken);
            } catch (IllegalArgumentException e) {
                return;
            }
            synchronized (mPeerRuntimeLock) {
                if (mNextPeerSnapshotSequence >= 0xffffffffL) {
                    Log.e(TAG, "Peer snapshot sequence exhausted");
                    return;
                }
                int localPlayerID = Globals.getInstance().mPlayerID & 0xff;
                if (localPlayerID < 1 || localPlayerID > Globals.MAX_PLAYER_ID)
                    return;
                PeerStatePacket.PlayerState local = localPeerStateLocked(localPlayerID);
                if (mPeerStates[localPlayerID] == null)
                    mPeerStates[localPlayerID] = copyLocalState(local,
                            nextLocalStateSequenceLocked(), local.eventSequence, local.eventType,
                            local.eventTargetID, local.score, local.deaths, local.health,
                            local.shield, local.shotsRemaining, local.gameState, local.grenadeID,
                            local.flags, local.latitude, local.longitude);
                PeerStatePacket.PlayerState[] snapshot = mPeerStates.clone();
                local = mPeerStates[localPlayerID];
                if ((local.flags & PeerStatePacket.FLAG_GPS_VALID) != 0)
                    snapshot[localPlayerID] = copyLocalState(local, local.ownerSequence,
                            local.eventSequence, local.eventType, local.eventTargetID, local.score,
                            local.deaths, local.health, local.shield, local.shotsRemaining,
                            local.gameState, local.grenadeID, local.flags, local.latitude,
                            local.longitude);
                payload = PeerStatePacket.encode(NetMsg.NETWORK_VERSION_NUMBER, token,
                        ++mNextPeerSnapshotSequence, localPlayerID,
                        Globals.getInstance().mGameMode, snapshot);
            }
        }
        sendDatagrams(payload, recipients, LISTEN_PORT, repeatCount, generation);
    }

    /** Broadcast the host's best complete view while every player continues gossiping. */
    private void broadcastAuthorityTick() {
        final byte[] payload;
        final List<InetAddress> recipients;
        final long generation;
        synchronized (mListenerStateLock) {
            if (mDestroyed || !mStateGossipRound || !mStateAuthority
                    || !TcpServer.isValidRoundToken(mStateRoundToken))
                return;
            if (mBroadcastAddress == null)
                mBroadcastAddress = getBroadcastAddress();
            recipients = new ArrayList<>();
            if (mBroadcastAddress != null)
                recipients.add(mBroadcastAddress);
            // Keep the common broadcast tick, then add acknowledged Wi-Fi
            // unicast copies for every registered phone. Duplicate copies use
            // the same tick sequence and are discarded by the receiver.
            Globals.getmTeamIPMapSemaphore();
            try {
                for (InetAddress endpoint : Globals.getInstance().mTeamIPMap.values()) {
                    if (endpoint != null && !endpoint.equals(mMyIP)
                            && !recipients.contains(endpoint))
                        recipients.add(endpoint);
                }
            } finally {
                Globals.getInstance().mTeamIPMapSemaphore.release();
            }
            if (recipients.isEmpty())
                return;
            generation = mSendGeneration;
            final UUID token;
            try {
                token = UUID.fromString(mStateRoundToken);
            } catch (IllegalArgumentException e) {
                return;
            }
            synchronized (mPeerRuntimeLock) {
                if (mNextAuthorityTickSequence >= 0xffffffffL) {
                    Log.e(TAG, "Authority tick sequence exhausted");
                    return;
                }
                payload = PeerStatePacket.encode(NetMsg.NETWORK_VERSION_NUMBER, token,
                        ++mNextAuthorityTickSequence, 0, Globals.getInstance().mGameMode,
                        mPeerStates.clone());
            }
        }
        sendDatagrams(payload, recipients, LISTEN_PORT, 1, generation);
    }

    void sendUDPMessage(final String message, final InetAddress ip, final Integer port) {
        sendDatagrams(message, Collections.singletonList(ip), port, 1, getSendGeneration());
    }

    private long getSendGeneration() {
        synchronized (mListenerStateLock) {
            return mDestroyed ? -1 : mSendGeneration;
        }
    }

    private boolean isCurrentSend(long generation) {
        synchronized (mListenerStateLock) {
            return !mDestroyed && generation == mSendGeneration;
        }
    }

    private void sendDatagrams(String message, List<InetAddress> recipients, int port, int repeatCount,
                               long generation) {
        if (message == null)
            return;
        sendDatagrams(message.getBytes(StandardCharsets.UTF_8), recipients, port, repeatCount,
                generation);
    }

    void sendDatagrams(byte[] payload, List<InetAddress> recipients, int port, int repeatCount,
                       long generation) {
        if (recipients.isEmpty() || !isCurrentSend(generation))
            return;
        if (payload == null || payload.length > PeerStatePacket.MAX_UDP_PAYLOAD_BYTES) {
            Log.e(TAG, "Refusing oversized UDP payload");
            return;
        }
        Runnable sendTask = () -> {
            synchronized (mSendLock) {
                DatagramPacket packet = new DatagramPacket(payload, payload.length);
                for (int repeat = 0; repeat < repeatCount; repeat++) {
                    for (InetAddress ip : recipients) {
                        if (!isCurrentSend(generation) || Thread.currentThread().isInterrupted())
                            return;
                        sendDatagram(packet, ip, port);
                    }
                    if (repeat + 1 < repeatCount) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        };
        try {
            // Favor the latest complete snapshot over stale visual feedback
            // if Wi-Fi is congested; the next heartbeat repairs a discarded
            // queued state packet.
            mSendExecutor.execute(sendTask);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to queue UDP message", e);
        }
    }

    private DatagramSocket getSendSocketLocked() throws SocketException {
        if (mSendSocket == null || mSendSocket.isClosed()) {
            mSendSocket = new DatagramSocket();
            mSendSocket.setBroadcast(true);
        }
        return mSendSocket;
    }

    private void closeSendSocketLocked() {
        if (mSendSocket != null)
            mSendSocket.close();
        mSendSocket = null;
    }

    private void closeSendSocket() {
        synchronized (mSendLock) {
            closeSendSocketLocked();
        }
    }

    /**
     * Service destruction must not wait for a sender that is waiting on
     * mSendLock. In particular, Android can call onDestroy on the main thread
     * while a game event is queued from another thread. The executor has
     * already been stopped and mDestroyed prevents queued work from opening a
     * replacement socket, so closing the current DatagramSocket directly is
     * both safe and prompt.
     */
    private void closeSendSocketDuringDestroy() {
        DatagramSocket socket = mSendSocket;
        mSendSocket = null;
        if (socket != null)
            socket.close();
    }

    // Called with mSendLock held.  A packet object and socket are shared by the serial sender;
    // only the destination changes between recipients.
    private void sendDatagram(DatagramPacket packet, InetAddress ip, int port) {
        try {
            if (BuildConfig.DEBUG)
                Log.d(TAG, "sending UDP packet to " + ip + ":" + port);
            packet.setAddress(ip);
            packet.setPort(port);
            getSendSocketLocked().send(packet);
        } catch (SocketException e) {
            closeSendSocketLocked();
            Log.e(TAG, "Socket Error:", e);
        } catch (IOException e) {
            closeSendSocketLocked();
            Intent intent = new Intent(NetMsg.NETMSG_ERROR);
            intent.putExtra(INTENT_MESSAGE, e.getLocalizedMessage());
            sendBroadcast(intent);
        }
    }

    void stopListen() {
        synchronized (mListenerStateLock) {
            cancelInviteListenerRequestLocked();
            mPassiveInviteListener = false;
            mInviteTemporarilyAllowsJoin = false;
            mInviteWindowGeneration++;
            endScanningLocked();
            mIsListService = false;
            mPeerGame = false;
            mGameRunning = false;
            mPeerRoundToken = null;
            mStateGossipRound = false;
            mStateAuthority = false;
            mStateRoundToken = null;
            mStateRoundUuid = null;
            mPendingPeerRoundToken = null;
            mPendingPeerEndGame = null;
            resetPeerGameSequences();
            releaseGameplayWifiLocksLocked();
            clearJoinAssignments();
            keepListening = false;
            mReadyToScan = 0;
            closeListeningSocket();
        }
    }

    private void closeListeningSocket() {
        DatagramSocket socket = mSocket;
        if (socket != null && !socket.isClosed())
            socket.close();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Avoid paying thread-start latency on the first hit of a round.
        mCombatExecutor.prestartCoreThread();
        IntentFilter serviceEvents = new IntentFilter(NetMsg.NETMSG_STARTGAME);
        serviceEvents.addAction(NetMsg.NETMSG_GPSLOCUPDATE);
        ContextCompat.registerReceiver(this, mPeerStartReceiver,
                serviceEvents, ContextCompat.RECEIVER_NOT_EXPORTED);
        mPeerStartReceiverRegistered = true;
    }

    @Override
    public void onDestroy() {
        synchronized (mListenerStateLock) {
            mDestroyed = true;
            stopListen();
            releaseMulticastLockLocked();
            releaseWifiLockLocked();
        }
        mSendExecutor.shutdownNow();
        mCombatExecutor.shutdownNow();
        mLookupExecutor.shutdownNow();
        closeSendSocketDuringDestroy();
        if (mPeerStartReceiverRegistered) {
            mPeerStartReceiverRegistered = false;
            try {
                unregisterReceiver(mPeerStartReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Peer-start receiver was already unregistered", e);
            }
        }
        super.onDestroy();
    }

    private void releaseMulticastLockLocked() {
        if (multicastLock != null && multicastLock.isHeld())
            multicastLock.release();
    }

    private void releaseWifiLockLocked() {
        if (wifiLock != null && wifiLock.isHeld())
            wifiLock.release();
    }

    private void releaseGameplayWifiLocksLocked() {
        releaseWifiLockLocked();
        releaseMulticastLockLocked();
    }

    // Caller holds mListenerStateLock. Both locks are non-reference-counted so
    // lobby listening can transition into gameplay without an accidental leak.
    private void acquireGameplayWifiLocksLocked() {
        try {
            // A service constructed directly by a test has no base context.
            if (getBaseContext() == null)
                return;
            Context appContext = getApplicationContext();
            if (wm == null && appContext != null)
                wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wm == null)
                return;
            if (multicastLock == null) {
                multicastLock = wm.createMulticastLock("SimpleCoil gameplay multicast");
                multicastLock.setReferenceCounted(false);
            }
            if (!multicastLock.isHeld())
                multicastLock.acquire();
            if (wifiLock == null) {
                int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "SimpleCoil gameplay Wi-Fi");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld())
                wifiLock.acquire();
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to hold gameplay Wi-Fi locks", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "UDP Service started");
        // A resurrected listener has no owning activity or known lobby session.
        return START_NOT_STICKY;
    }

    public class LocalBinder extends Binder {
        UDPListenerService getService() {
            return UDPListenerService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    public void startGame() { startGame(false, null); }

    /**
     * Legacy in-process callers that do not receive a synchronized start still
     * get a unique token. Network peers use the overload below with the shared
     * token parsed from the host's start announcement.
     */
    public void startGame(boolean peerGame) {
        startGame(peerGame, peerGame ? TcpServer.createRoundToken() : null);
    }

    public void startGame(boolean peerGame, String roundToken) {
        startGame(peerGame, roundToken, false);
    }

    public void startGame(boolean peerGame, String roundToken, boolean stateAuthority) {
        if (peerGame && !TcpServer.isValidRoundToken(roundToken)) {
            Log.w(TAG, "Refusing peer game without a valid round token");
            return;
        }
        boolean stateGossipRound = TcpServer.isValidRoundToken(roundToken);
        if (stateAuthority && !stateGossipRound) {
            Log.w(TAG, "Refusing authoritative state ticks without a valid round token");
            return;
        }
        synchronized (mListenerStateLock) {
            cancelInviteListenerRequestLocked();
            mPassiveInviteListener = false;
            mInviteTemporarilyAllowsJoin = false;
            mInviteWindowGeneration++;
            // A dedicated host may intentionally allow late joins while it
            // also publishes authority ticks. Peer-hosted rounds freeze their roster.
            if (!stateAuthority || peerGame)
                mIsListService = false;
            clearJoinAssignments();
            boolean peerRoundChanged = mPeerGame != peerGame
                    || (peerGame && !roundToken.equals(mPeerRoundToken));
            boolean stateRoundChanged = mStateGossipRound != stateGossipRound
                    || stateGossipRound && !roundToken.equals(mStateRoundToken)
                    || mStateAuthority != stateAuthority;
            if (peerRoundChanged || stateRoundChanged) {
                resetPeerGameSequences();
                if (mPendingPeerEndGame == null || !peerGame
                        || !roundToken.equals(mPendingPeerEndGame.getStringExtra(
                        NetMsg.INTENT_ROUND_TOKEN)))
                    mPendingPeerEndGame = null;
            }
            if (!peerGame) {
                // Candidate tokened ENDGAME packets are meaningful only to a
                // peer start. A dedicated start must always discard one, even
                // when the listener was already in non-peer mode.
                mPendingPeerRoundToken = null;
                mPendingPeerEndGame = null;
            }
            mPeerGame = peerGame;
            mGameRunning = true;
            mPeerRoundToken = peerGame ? roundToken : null;
            mStateGossipRound = stateGossipRound;
            mStateAuthority = stateGossipRound && stateAuthority;
            mStateRoundToken = stateGossipRound ? roundToken : null;
            mStateRoundUuid = stateGossipRound ? UUID.fromString(roundToken) : null;
            if (peerGame)
                mPendingPeerRoundToken = null;
            acquireGameplayWifiLocksLocked();
            if (peerGame && mBroadcastAddress == null)
                mBroadcastAddress = getBroadcastAddress();
            mMainHandler.removeCallbacks(mPeerStateHeartbeat);
            mMainHandler.removeCallbacks(mAuthorityTickHeartbeat);
            mMainHandler.removeCallbacks(mGossipRecovery);
            if (stateGossipRound && Globals.getInstance().mPlayerID > 0)
                mMainHandler.postDelayed(mPeerStateHeartbeat, GAME_TICK_INTERVAL_MS);
            if (mStateAuthority)
                mMainHandler.postDelayed(mAuthorityTickHeartbeat, GAME_TICK_INTERVAL_MS);
            else if (stateGossipRound) {
                mLastAuthorityTickAt = SystemClock.elapsedRealtime();
                mMainHandler.postDelayed(mGossipRecovery,
                        GAME_TICK_INTERVAL_MS + GAME_TICK_DRIFT_GRACE_MS);
            }
        }
    }

    /** Start the UDP authority on a dedicated host that is not itself a player. */
    public void startAuthoritativeStateTicks(String roundToken) {
        startGame(false, roundToken, true);
    }

    /** Stop a dedicated host's completed tick stream without closing its lobby listener. */
    public void finishAuthoritativeStateTicks() {
        synchronized (mListenerStateLock) {
            if (!mStateAuthority)
                return;
            mGameRunning = false;
            mStateGossipRound = false;
            mStateAuthority = false;
            mStateRoundToken = null;
            mStateRoundUuid = null;
            resetPeerGameSequences();
            releaseGameplayWifiLocksLocked();
        }
    }

    public void endGame() {
        final String message;
        synchronized (mListenerStateLock) {
            if (mPeerGame) {
                if (!TcpServer.isValidRoundToken(mPeerRoundToken)) {
                    Log.w(TAG, "Not sending peer ENDGAME without a round token");
                    return;
                }
                publishPeerEvent(PeerStatePacket.EVENT_END_GAME, 0, 3);
                return;
            } else {
                message = NetMsg.NETMSG_ENDGAME;
            }
        }
        sendUDPMessageAllRepeat(message, 3);
    }

    /**
     * Broadcast a current dedicated round to phones listening on the local
     * Wi-Fi network. While the prompt is counting down on those phones, keep
     * discovery open so their normal JOIN/TCP registration can complete.
     */
    public boolean inviteNearbyPlayers(String roundToken) {
        if (!TcpServer.isValidRoundToken(roundToken)) {
            Log.w(TAG, "Refusing to broadcast a game invitation without a valid round token");
            return false;
        }
        final InetAddress broadcastAddress;
        final long sendGeneration;
        synchronized (mListenerStateLock) {
            if (mDestroyed || mPeerGame || !keepListening)
                return false;
            if (mBroadcastAddress == null)
                mBroadcastAddress = getBroadcastAddress();
            if (mBroadcastAddress == null)
                return false;
            broadcastAddress = mBroadcastAddress;
            final long inviteGeneration = ++mInviteWindowGeneration;
            mInviteTemporarilyAllowsJoin = !mIsListService;
            if (mInviteTemporarilyAllowsJoin)
                mIsListService = true;
            mMainHandler.postDelayed(() -> closeGameInviteWindow(inviteGeneration),
                    GAME_INVITE_JOIN_WINDOW_MS);
            sendGeneration = mSendGeneration;
        }
        sendDatagrams(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_GAMEINVITE_PREFIX
                        + NetMsg.NETWORK_VERSION + ":" + roundToken,
                Collections.singletonList(broadcastAddress), LISTEN_PORT,
                GAME_INVITE_BROADCAST_REPETITIONS, sendGeneration);
        return true;
    }

    /** Broadcast a newly-created peer lobby to idle phones on this Wi-Fi. */
    public boolean inviteNearbyLobbyPlayers() {
        final InetAddress broadcastAddress;
        final long sendGeneration;
        synchronized (mListenerStateLock) {
            if (mDestroyed || mPeerGame || !mIsListService || !keepListening)
                return false;
            if (mBroadcastAddress == null)
                mBroadcastAddress = getBroadcastAddress();
            if (mBroadcastAddress == null)
                return false;
            broadcastAddress = mBroadcastAddress;
            sendGeneration = mSendGeneration;
        }
        sendDatagrams(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_LOBBYINVITE_PREFIX
                        + NetMsg.NETWORK_VERSION,
                Collections.singletonList(broadcastAddress), LISTEN_PORT,
                GAME_INVITE_BROADCAST_REPETITIONS, sendGeneration);
        return true;
    }

    private void closeGameInviteWindow(long inviteGeneration) {
        synchronized (mListenerStateLock) {
            if (mDestroyed || inviteGeneration != mInviteWindowGeneration
                    || !mInviteTemporarilyAllowsJoin)
                return;
            mInviteTemporarilyAllowsJoin = false;
            mIsListService = false;
            clearJoinAssignments();
        }
    }

    /** An explicit host policy change always supersedes an invitation timeout. */
    public void allowJoin(boolean allowed) {
        synchronized (mListenerStateLock) {
            mInviteWindowGeneration++;
            mInviteTemporarilyAllowsJoin = false;
            mIsListService = allowed;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void endScanning() {
        synchronized (mListenerStateLock) {
            endScanningLocked();
        }
    }

    private void endScanningLocked() {
        mJoinGeneration++;
        mScanRunning = false;
        mJoinAddress = null;
        mBroadcastScan = false;
        if (mJoinTimer != null) {
            CountDownTimer timer = mJoinTimer;
            mJoinTimer = null;
            // CountDownTimer invokes callbacks while holding its own monitor.
            // Cancel on the same looper, avoiding an inverted lock order with
            // callbacks that acquire mListenerStateLock.
            mMainHandler.post(timer::cancel);
        }
    }
}
