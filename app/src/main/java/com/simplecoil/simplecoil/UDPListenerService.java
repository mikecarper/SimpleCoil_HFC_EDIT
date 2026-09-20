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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class UDPListenerService extends Service {
    private static final String TAG = "UDPSvc";

    private static final Integer LISTEN_PORT = 17500;
    private static final Integer LISTEN_TIMEOUT_MS = 1000;
    // Rosters travel over TCP; UDP carries short individual discovery/game events.
    private static final int RECEIVE_BUFFER_SIZE = 500;

    private volatile DatagramSocket mSocket;

    WifiManager wm = null;
    WifiManager.MulticastLock multicastLock = null;

    private InetAddress mMyIP = null;
    private InetAddress mBroadcastAddress = null;

    private static final long LISTENER_START_TIMEOUT_MS = 5000;
    // Combat messages can arrive much faster than a congested Wi-Fi link can
    // transmit them. Keep the sender bounded so a stalled socket cannot create
    // an unbounded number of Java threads or queued packet snapshots.
    static final int MAX_PENDING_DATAGRAM_SENDS = 64;
    private static final long SEND_THREAD_KEEP_ALIVE_MS = 100;
    private final Object mSendLock = new Object();
    private final ThreadPoolExecutor mSendExecutor = new ThreadPoolExecutor(0, 1,
            SEND_THREAD_KEEP_ALIVE_MS, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING_DATAGRAM_SENDS),
            runnable -> new Thread(runnable, "SimpleCoil UDP send"),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    // A manual join can require DNS. Keep an unresolved lookup from creating a
    // new thread for every tap, while retaining the most recent address request.
    static final int MAX_PENDING_SERVER_LOOKUPS = 1;
    private final ThreadPoolExecutor mLookupExecutor = new ThreadPoolExecutor(0, 1,
            SEND_THREAD_KEEP_ALIVE_MS, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING_SERVER_LOOKUPS),
            runnable -> new Thread(runnable, "SimpleCoil UDP lookup"),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile boolean keepListening = false;
    private volatile boolean doneListening = true;
    private volatile boolean mIsListService = false;
    // Dedicated games keep their roster authoritative over TCP.  A peer-hosted
    // round closes that TCP listener after the start announcement, so a player
    // leaving mid-round must be reflected by a validated UDP LEAVE instead.
    private volatile boolean mPeerGame;
    // A fresh synchronized round gets a nonce from the TCP start announcement.
    // UDP endpoint membership alone cannot distinguish a delayed prior-round
    // packet from a current one.
    private String mPeerRoundToken;
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
    // Queued sends may drain after stopListen(), but not into a replacement lobby.
    // Separate from discovery's generation, which changes on a successful reply.
    private long mSendGeneration;
    private volatile long mJoinGeneration;
    private InetAddress mJoinAddress;
    private boolean mBroadcastScan;
    private CountDownTimer mJoinTimer;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private boolean mPeerStartReceiverRegistered;

    private final BroadcastReceiver mPeerStartReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !NetMsg.NETMSG_STARTGAME.equals(intent.getAction())
                    || !intent.getBooleanExtra(NetMsg.INTENT_PEER_GAME, false))
                return;
            preparePeerRound(intent.getStringExtra(NetMsg.INTENT_ROUND_TOKEN));
        }
    };

    public static final String INTENT_PLAYERID = "playerid";
    public static final String INTENT_MESSAGE = "message";

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
            Log.d(TAG, "Waiting for UDP messages on " + ip.toString() + ":" + port);
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
                    String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    processMessage(packet.getAddress(), message);
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
            if (message.equals(NetMsg.NETMSG_SHOTFIRED)) {
                if (getPlayerID(ip) == null)
                    return;
                // someone else fired a shot
                intent = new Intent(NetMsg.NETMSG_SHOTFIRED);
            } else if (message.equals(NetMsg.NETMSG_HIT)) {
                // you hit someone!
                intent = new Intent(NetMsg.NETMSG_HIT);
                Byte id = getPlayerID(ip);
                if (id == null) {
                    Log.e(TAG, "Unknown IP " + ip.toString());
                    return;
                }
                intent.putExtra(INTENT_PLAYERID, id);
            } else if (message.equals(NetMsg.NETMSG_OUT)) {
                // hitting a player that's already out
                intent = new Intent(NetMsg.NETMSG_OUT);
                Byte id = getPlayerID(ip);
                if (id == null) {
                    Log.e(TAG, "Unknown IP " + ip.toString());
                    return;
                }
                intent.putExtra(INTENT_PLAYERID, id);
            } else if (message.startsWith(NetMsg.NETMSG_PEER_ELIMINATED)) {
                processPeerElimination(ip,
                        message.substring(NetMsg.NETMSG_PEER_ELIMINATED.length()));
            } else if (message.equals(NetMsg.NETMSG_ELIMINATED)) {
                // Protocol 13 peer games use round-scoped, sequenced elimination events. Keep
                // the old fixed form only for non-peer compatibility paths.
                if (mPeerGame)
                    return;
                // you eliminated someone!
                intent = new Intent(NetMsg.NETMSG_ELIMINATED);
                Byte id = getPlayerID(ip);
                if (id == null) {
                    Log.e(TAG, "Unknown IP " + ip.toString());
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
                Log.d(TAG, "UDP discovery from player " + assignedPlayerID + " at " + ip.toString());
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
                // Protocol 13 peer games bind ENDGAME to the current round nonce.
                // Keep the old fixed form only for TCP-authoritative games.
                if (mPeerGame)
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
                // a new kill, so it is not valid during a protocol 13 peer game.
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
                if (globals.mGameMode != Globals.GAME_MODE_FFA
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
     * Publish this phone's grenade state during a peer-hosted round. Dedicated
     * games keep using their TCP-authoritative pairing snapshots.
     */
    public void publishPeerGrenadePairing() {
        Globals globals = Globals.getInstance();
        int playerID = globals.mPlayerID;
        int grenadeID = globals.mPairedGrenadeID & 0xff;
        if (playerID <= 0 || !Globals.isValidPlayerID(playerID)
                || !Globals.isValidGrenadeID(grenadeID))
            return;

        final long sequence;
        final String roundToken;
        synchronized (mListenerStateLock) {
            if (mDestroyed || !mPeerGame || !TcpServer.isValidRoundToken(mPeerRoundToken))
                return;
            roundToken = mPeerRoundToken;
            synchronized (mPeerGrenadeLock) {
                // Reaching this in a real round is not practical, but refusing
                // to wrap is safer than making every old packet look current.
                if (mNextPeerGrenadeSequence == Long.MAX_VALUE) {
                    Log.w(TAG, "Peer grenade sequence exhausted");
                    return;
                }
                sequence = ++mNextPeerGrenadeSequence;
            }
            // Keep the local pairing linearized with the round token. The
            // outbound message can be sent after the lock; recipients will
            // reject it if they have already entered a different round.
            updateGrenadePairing((byte) playerID, grenadeID);
        }
        sendUDPMessageAllRepeat(NetMsg.NETMSG_GRENADEPAIR + roundToken + ":" + sequence + ":" + grenadeID,
                PEER_GRENADE_UPDATE_REPETITIONS);
    }

    /** Publish an eliminated player's event to the peer that earned the point. */
    public void publishPeerElimination(byte scoringPlayerID) {
        Globals globals = Globals.getInstance();
        int localPlayerID = globals.mPlayerID;
        if (scoringPlayerID <= 0 || !Globals.isValidPlayerID(scoringPlayerID)
                || localPlayerID <= 0 || !Globals.isValidPlayerID(localPlayerID))
            return;
        final long sequence;
        final String roundToken;
        synchronized (mListenerStateLock) {
            if (mDestroyed || !mPeerGame || !TcpServer.isValidRoundToken(mPeerRoundToken))
                return;
            roundToken = mPeerRoundToken;
            synchronized (mPeerScoreLock) {
                if (mNextPeerEliminationSequence == Long.MAX_VALUE) {
                    Log.w(TAG, "Peer elimination sequence exhausted");
                    return;
                }
                sequence = ++mNextPeerEliminationSequence;
            }
        }
        sendUDPMessageRepeat(NetMsg.NETMSG_PEER_ELIMINATED + roundToken + ":" + sequence, scoringPlayerID,
                PEER_SCORE_UPDATE_REPETITIONS);
    }

    /** Relay a deduplicable peer score event to one teammate. */
    public void publishPeerTeamElimination(byte eliminatedPlayerID, long sequence,
                                           byte teammateID) {
        if (eliminatedPlayerID <= 0 || !Globals.isValidPlayerID(eliminatedPlayerID)
                || teammateID <= 0 || !Globals.isValidPlayerID(teammateID) || sequence <= 0)
            return;
        final String roundToken;
        synchronized (mListenerStateLock) {
            if (mDestroyed || !mPeerGame || !TcpServer.isValidRoundToken(mPeerRoundToken))
                return;
            roundToken = mPeerRoundToken;
        }
        sendUDPMessageRepeat(NetMsg.NETMSG_PEER_TEAMELIMINATED + roundToken + ":" + eliminatedPlayerID + ":"
                + sequence, teammateID, PEER_SCORE_UPDATE_REPETITIONS);
    }

    /** A peer that leaves mid-round announces it repeatedly because UDP can drop packets. */
    public void announcePeerLeave() {
        final String roundToken;
        synchronized (mListenerStateLock) {
            if (mDestroyed || !mPeerGame || !TcpServer.isValidRoundToken(mPeerRoundToken))
                return;
            roundToken = mPeerRoundToken;
        }
        sendUDPMessageAllRepeat(NetMsg.NETMSG_PEER_LEAVE + roundToken, PEER_SCORE_UPDATE_REPETITIONS);
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
            if (mIsListService) {
                if (wm == null)
                    wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm == null) {
                    Log.e(TAG, "Failed to get wifi manager");
                } else {
                    if (multicastLock == null) {
                        multicastLock = wm.createMulticastLock("SimpleCoil");

                        multicastLock.setReferenceCounted(true);
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
            if (wm == null)
                wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
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
            if (!doneListening) {
                Log.e(TAG, "Listening is still in progress");
                // A new host request supersedes an unfinished discovery scan.
                // Leaving the old scan alive lets a late SERVERREPLY join the
                // abandoned server after this request reports its failure.
                stopListen();
                sendFailedJoin();
                return;
            }
            mBroadcastAddress = getBroadcastAddress();
            if (mBroadcastAddress == null) {
                Log.e(TAG, "Failed to get broadcast IP address");
                sendFailedJoin();
                return;
            }
            mSendGeneration++;
            endScanningLocked();
            mPeerGame = false;
            mPeerRoundToken = null;
            mPendingPeerRoundToken = null;
            mPendingPeerEndGame = null;
            resetPeerGameSequences();
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
            if (!doneListening) {
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
            if (!doneListening) {
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

    public void joinServer(InetAddress serverIP, Integer port) {
        synchronized (mListenerStateLock) {
            if (mDestroyed)
                return;
            if (serverIP == null || port == null || port < 1 || port > 65535) {
                Log.w(TAG, "Cannot join an invalid UDP endpoint");
                sendFailedJoin();
                return;
            }
            if (!doneListening) {
                Log.e(TAG, "Listening is still in progress");
                // This request replaces the in-flight discovery. Leaving it
                // active after reporting failure lets a late reply join the
                // server the player just abandoned.
                stopListen();
                sendFailedJoin();
                return;
            }
            mSendGeneration++;
            endScanningLocked();
            mPeerGame = false;
            mPeerRoundToken = null;
            mPendingPeerRoundToken = null;
            mPendingPeerEndGame = null;
            resetPeerGameSequences();
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
        final long generation = getSendGeneration();
        if (generation < 0 || repeatCount <= 0)
            return;
        final List<InetAddress> recipients;
        Globals.getmIPTeamMapSemaphore();
        try {
            recipients = new ArrayList<>(Globals.getInstance().mIPTeamMap.keySet());
        } finally {
            Globals.getInstance().mIPTeamMapSemaphore.release();
        }
        // Freeze recipients before queuing, and never hold the roster lock over
        // socket I/O. A delayed ENDGAME must not follow players into a new lobby.
        sendDatagrams(NetMsg.MESSAGE_PREFIX + message, recipients, LISTEN_PORT, repeatCount, generation);
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
        if (recipients.isEmpty() || !isCurrentSend(generation))
            return;
        Runnable sendTask = () -> {
            synchronized (mSendLock) {
                for (int repeat = 0; repeat < repeatCount; repeat++) {
                    for (InetAddress ip : recipients) {
                        if (!isCurrentSend(generation) || Thread.currentThread().isInterrupted())
                            return;
                        sendDatagram(message, ip, port);
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
            // Favor the latest state over stale visual feedback if Wi-Fi is
            // congested. Game state remains protected by the TCP protocol.
            mSendExecutor.execute(sendTask);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to queue UDP message", e);
        }
    }

    private void sendDatagram(String message, InetAddress ip, int port) {
        DatagramSocket udpSocket = null;
        try {
            Log.d(TAG, "sending '" + message + "' to " + ip.toString() + ":" + port);
            udpSocket = new DatagramSocket(0); // system will assign any unused port for sending
            byte[] buf = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(buf, buf.length, ip, port);
            udpSocket.send(packet);
        } catch (SocketException e) {
            Log.e(TAG, "Socket Error:", e);
        } catch (IOException e) {
            Intent intent = new Intent(NetMsg.NETMSG_ERROR);
            intent.putExtra(INTENT_MESSAGE, e.getLocalizedMessage());
            sendBroadcast(intent);
        } finally {
            if (udpSocket != null)
                udpSocket.close();
        }
    }

    void stopListen() {
        synchronized (mListenerStateLock) {
            endScanningLocked();
            mIsListService = false;
            mPeerGame = false;
            mPeerRoundToken = null;
            mPendingPeerRoundToken = null;
            mPendingPeerEndGame = null;
            resetPeerGameSequences();
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
        ContextCompat.registerReceiver(this, mPeerStartReceiver,
                new IntentFilter(NetMsg.NETMSG_STARTGAME), ContextCompat.RECEIVER_NOT_EXPORTED);
        mPeerStartReceiverRegistered = true;
    }

    @Override
    public void onDestroy() {
        synchronized (mListenerStateLock) {
            mDestroyed = true;
            stopListen();
            releaseMulticastLockLocked();
        }
        mSendExecutor.shutdownNow();
        mLookupExecutor.shutdownNow();
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
        if (peerGame && !TcpServer.isValidRoundToken(roundToken)) {
            Log.w(TAG, "Refusing peer game without a valid round token");
            return;
        }
        synchronized (mListenerStateLock) {
            mIsListService = false; // There is no list service while the game is running
            clearJoinAssignments();
            boolean peerRoundChanged = mPeerGame != peerGame
                    || (peerGame && !roundToken.equals(mPeerRoundToken));
            if (!peerGame) {
                // Candidate tokened ENDGAME packets are meaningful only to a
                // peer start. A dedicated start must always discard one, even
                // when the listener was already in non-peer mode.
                mPendingPeerRoundToken = null;
                mPendingPeerEndGame = null;
            } else if (peerRoundChanged) {
                resetPeerGameSequences();
                if (mPendingPeerEndGame == null || !peerGame
                        || !roundToken.equals(mPendingPeerEndGame.getStringExtra(NetMsg.INTENT_ROUND_TOKEN)))
                    mPendingPeerEndGame = null;
            }
            mPeerGame = peerGame;
            mPeerRoundToken = peerGame ? roundToken : null;
            if (peerGame)
                mPendingPeerRoundToken = null;
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
                message = NetMsg.NETMSG_PEER_ENDGAME + mPeerRoundToken;
            } else {
                message = NetMsg.NETMSG_ENDGAME;
            }
        }
        sendUDPMessageAllRepeat(message, 3);
    }

    public void allowJoin(boolean allowed) { mIsListService = allowed;}

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
