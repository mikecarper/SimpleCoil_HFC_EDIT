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
import android.content.Context;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

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
import java.util.List;
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
    private volatile int mReadyToScan = 0;

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
                mReadyToScan++;
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
            if (mSocket == socket)
                mSocket = null;
        }
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
            } else if (message.equals(NetMsg.NETMSG_ELIMINATED)) {
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
                Byte team = (byte) playerID;
                Globals.getmTeamIPMapSemaphore();
                try {
                    InetAddress existingPlayerIP = Globals.getInstance().mTeamIPMap.get(team);
                    if (team == Globals.getInstance().mPlayerID
                            || (existingPlayerIP != null && !existingPlayerIP.equals(ip))) {
                        Log.e(TAG, "2 Players using same ID!");
                        sendUDPMessage(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_SAMETEAM, ip, LISTEN_PORT);
                        return;
                    }
                } finally {
                    Globals.getInstance().mTeamIPMapSemaphore.release();
                }
                // Discovery only proves that a peer can receive UDP. TCP registration
                // owns roster membership and endpoints. Recording a JOIN here leaves
                // ghosts when its sender never completes the TCP handshake.
                Log.d(TAG, "UDP discovery from player " + team + " at " + ip.toString());
                sendUDPMessage(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_SERVERREPLY, ip, LISTEN_PORT);
            } else if (message.equals(NetMsg.NETMSG_SERVERREPLY)) {
                completeJoin(ip, NetMsg.NETMSG_SERVERREPLY);
                return;
            } else if (message.equals(NetMsg.NETMSG_LEAVE)) {
                // A UDP datagram cannot prove that a TCP player disconnected.
                // The TCP server removes roster entries after its authenticated
                // connection closes, so a stale or spoofed LEAVE cannot evict a
                // live player from game traffic.
                Log.d(TAG, "Ignoring UDP leave; TCP owns roster removal");
            } else if (message.equals(NetMsg.NETMSG_ENDGAME)) {
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
            } else if (message.equals(NetMsg.NETMSG_TEAMELIMINATED)) {
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

    private Byte getPlayerID(InetAddress ip) {
        Globals.getmIPTeamMapSemaphore();
        try {
            Byte playerID = Globals.getInstance().mIPTeamMap.get(ip);
            return playerID != null && playerID > 0 && Globals.isValidPlayerID(playerID) ? playerID : null;
        } finally {
            Globals.getInstance().mIPTeamMapSemaphore.release();
        }
    }

    private void completeJoin(InetAddress ip, String action) {
        synchronized (mListenerStateLock) {
            if (mDestroyed || !mScanRunning || (!mBroadcastScan && !ip.equals(mJoinAddress)))
                return;
            endScanningLocked();
            if (NetMsg.NETMSG_SERVERREPLY.equals(action))
                Globals.getInstance().mServerIP = ip;
            else
                stopListen();
            sendBroadcast(new Intent(action));
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
            Globals.getmIPTeamMapSemaphore();
            Globals.getInstance().mIPTeamMap.clear();
            Globals.getInstance().mIPTeamMapSemaphore.release();
            Globals.getmTeamIPMapSemaphore();
            Globals.getInstance().mTeamIPMap.clear();
            Globals.getInstance().mTeamIPMapSemaphore.release();
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
        while (keepListening && generation == mJoinGeneration && mReadyToScan == 0
                && System.currentTimeMillis() < deadline)
            sleep(50);
        synchronized (mListenerStateLock) {
            if (mDestroyed || !keepListening || generation != mJoinGeneration)
                return;
            if (mReadyToScan == 0) {
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
            Globals.getmIPTeamMapSemaphore();
            Globals.getInstance().mIPTeamMap.clear();
            Globals.getInstance().mIPTeamMapSemaphore.release();
            Globals.getmTeamIPMapSemaphore();
            Globals.getInstance().mTeamIPMap.clear();
            Globals.getInstance().mTeamIPMapSemaphore.release();
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
        final long generation = getSendGeneration();
        if (generation < 0)
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
        sendDatagrams(message, Collections.singletonList(ip), LISTEN_PORT, 1, generation);
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

    private void sendUDPMessage(final String message, final InetAddress ip, final Integer port) {
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
            keepListening = false;
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

    public void startGame() {
        mIsListService = false; // There is no list service while the game is running
    }

    public void endGame() {
        sendUDPMessageAllRepeat(NetMsg.NETMSG_ENDGAME, 3);
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
