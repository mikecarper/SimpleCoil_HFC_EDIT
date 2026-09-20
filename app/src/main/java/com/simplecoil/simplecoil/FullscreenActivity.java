/*
 * Copyright (C) 2018
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

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.AnimationDrawable;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
@SuppressWarnings("NonAtomicOperationOnVolatileField")
public class FullscreenActivity extends AppCompatActivity implements PopupMenu.OnMenuItemClickListener {
    private static final String TAG = "scmain";

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_QR_SCAN = 2;
    private static final int REQUEST_CODE_RESPAWN_CAMERA_PERMISSION = 1024;

    // For testing and debugging network only -- dumps you straight to the play game layout and allows you to switch teams without connecting a blaster
    private static final boolean TEST_NETWORK = false;

    private Button mReconnectButton = null;
    private Button mConnectButton = null;
    private Button mQRConnectButton = null;
    private Button mDedicatedServerButton = null;
    private Button mTeamMinusButton = null;
    private Button mTeamPlusButton = null;
    private Button mStartGameButton = null;
    private Button mPlayerSettingsButton = null;
    private Button mEndGameButton = null;
    private Button mEndNetworkGameButton = null;
    private Button mPlayerDataButton = null;
    private ImageView mNetworkStatusIV = null;
    private TextView mNetworkPlayerCountTV = null;
    private TextView mScoreTV = null;
    private TextView mScoreLabelTV = null;
    private TextView mTeamScoreTV = null;
    private TextView mTeamScoreLabelTV = null;
    private TextView mGameModeTV = null;
    private TextView mGameModeLabelTV = null;
    private TextView minfo = null;
    private Button mUseNetworkingButton = null;
    private Button mFiringModeButton = null;
    private TextView mEliminationCountTV = null;
    private TextView mShotsRemainingLabelTV = null;
    private TextView mShotsRemainingTV = null;
    private TextView mRecoilModeTV = null;
    private TextView mHitsTakenTV = null;
    private TextView mTeamLabelTV = null;
    private TextView mTeamTV = null;
    private TextView mShotModeTV = null;
    private TextView mEliminatedTV = null;
    private TextView mSpawnInTV = null;
    private TextView mEliminatedByTV = null;
    private Button mRespawnQrScanButton = null;
    private View mRespawnQrScannerOverlay = null;
    private DecoratedBarcodeView mRespawnQrScanner = null;
    private TextView mRespawnQrScannerPrompt = null;
    private Button mRespawnQrWaitButton = null;
    private TextView mHealthLabelTV = null;
    private TextView mPlayerNameTV = null;
    private ProgressBar mHealthBar = null;
    private ProgressBar mShieldBar = null;
    private ProgressBar mReloadBar = null;
    private ImageView mHitIV = null;
    private ImageView mBatteryLevelIV = null;
    private AnimationDrawable mHitAnimation = null;
    private ImageView mHitPlayerIV = null;
    private AnimationDrawable mHitPlayerAnimation = null;
    private TextView mHitPlayerNameTV = null;
    private ImageView mShotsFiredIV = null;
    private ImageView mScoreIncreaseIV = null;
    private TextView mScoreIncreasePlayerNameTV = null;
    private final Handler mCombatFeedbackHandler = new Handler(Looper.getMainLooper());
    private Runnable mIncomingHitCleanup;
    private Runnable mScoreFeedbackCleanup;
    private TextView mServerIPTV = null;
    private Chronometer mGameTimer = null;
    private Button mGameLimitButton = null;
    private TextView mGameCountDownTV = null;
    private PopupMenu mNetworkPopup = null;

    private FragmentManager mFragmentMgr = null;
    private Fragment mMapFragment = null;

    private CountDownTimer mSpawnTimer = null;
    private CountDownTimer mReloadTimer = null;
    private CountDownTimer mShieldTimer = null;
    private CountDownTimer mGameCountdownTimer = null;
    private CountDownTimer mConnectFailTimer = null;
    private boolean mGameTimerRunning = false;
    // A team checkpoint stays associated with one eliminated player until they
    // either use it, receive a Game Master grant, or complete the wait.
    private int mRespawnQrTeam;
    private boolean mRespawnQrScannerActive;
    private boolean mRespawnQrRequestPending;
    private boolean mActivityResumed;
    private boolean mRespawnQrOpenWhenResumed;

    private BluetoothLeScanner mBluetoothLeScanner;
    private BluetoothLeService mBluetoothLeService;

    private Vibrator vibrator = null;

    private static final int MAX_EMPTY_TRIGGER_PULLS = 3; // automatically reloads if the trigger is pulled this many times while empty (for young players)
    private static final long SHIELD_REGEN_DELAY_MILLISECONDS = 4000;
    private static final long SHIELD_REGEN_TICK_MILLISECONDS = 1000;
    private static final int SHIELD_REGEN_TICK_AMOUNT = 1;

    private boolean mScanning = false;
    private volatile boolean mConnected = false;
    private boolean mCommunicating = false; // Used to make sure that we start receiving telemetry data after initial connection
    private String mDeviceAddress = ""; // MAC address of the tagger
    private static byte mLastTeam = 0;
    private int mHitsTaken = 0; // total hits taken regardless of lives
    private static int mHealth = Globals.MAX_HEALTH;
    private static int mShield = Globals.MAX_SHIELDS;
    private byte mLastShotCount = 0;
    private byte mLastTriggerCount = 0;
    private byte mLastReloadButtonCount = 0;
    private byte mLastPowerButtonCount = 0;
    private byte mLastThumbButtonCount = 0;
    private static int mEliminationCount = 0;
    private static int mEmptyTriggerCount = 0;
    private boolean mStartGameTimer = true;
    private boolean mHasSynchronizedStart;
    private long mSynchronizedStartAt;
    private long mSynchronizedEndAt;
    // Peer UDP is connectionless, so the activity repeats the listener's
    // nonce check when an asynchronously delivered gameplay event reaches the
    // UI. This closes the gap between a listener validating a datagram and a
    // newer round being started on the main thread.
    private String mActivePeerRoundToken;
    private static boolean mHasLivesLimit = false;
    private static int mLives = 0;

    private static class LastHitData {
        int playerID;
        byte shotID;
    }

    private final LastHitData mLastHitData1 = new LastHitData();
    private final LastHitData mLastHitData2 = new LastHitData();

    /* We calculate an average of the last 100 battery reports. Starting out with a single value
       simplifies some of the code that will have to be run every time the tagger sends telemetry
       data. */
    private long mBatteryCount = 1;
    private Queue<Byte> mBatteryQueue;
    private long mBatteryTotal = 16;
    // Brand new alkalines report 16, recently charged rechargables report 13ish
    private static final long BATTERY_LEVEL_PISTOL_GREEN = 14;
    private static final long BATTERY_LEVEL_PISTOL_BLUE = 12;
    private static final long BATTERY_LEVEL_PISTOL_YELLOW = 10;
    private static final long BATTERY_LEVEL_RIFLE_GREEN = 21;
    private static final long BATTERY_LEVEL_RIFLE_BLUE = 18;
    private static final long BATTERY_LEVEL_RIFLE_YELLOW = 15;

    private static final byte BLASTER_TYPE_PISTOL = (byte)2;
    private static final byte BLASTER_TYPE_RIFLE = (byte)1;
    private static byte mBlasterType = BLASTER_TYPE_PISTOL;

    private static final int REQUEST_CODE_LOCATION_PERMISSIONS = 1022;
    private static final int REQUEST_CODE_BLUETOOTH_PERMISSIONS = 1023;

    private static final byte COMMAND_ID_INCREMENT = (byte) 0x10;
    private static byte mCommandID = (byte) 0x00;

    /* Reloading is done in 2 stages. The first stage effectively tells the tagger that it can't
       shoot anymore. We also use this state when the player is eliminated so they can't shoot while
       out of the game. At the second stage, we tell the tagger how many shots it now has. With a
       normal reload sequence, we wait until the tagger has received the first stage command and
       we automatically start a timer thread to finish the reload after the reload wait timer is up.
       We don't want this timer to run/finish if the player is eliminated, hence the eliminated
       state here. */
    private static final int RELOADING_STATE_NONE = 0;
    private static final int RELOADING_STATE_STARTED = 1;
    private static final int RELOADING_STATE_FINISHING = 2;
    private static final int RELOADING_STATE_ELIMINATED = 3;
    private int mReloading = RELOADING_STATE_ELIMINATED;
    private byte[] mPendingReloadCommand;

    private int mCurrentShotMode = Globals.SHOT_MODE_SINGLE;

    private boolean mRecoilEnabled = true;

    private BluetoothGattCharacteristic mTelemetryCharacteristic = null;
    private BluetoothGattCharacteristic mCommandCharacteristic = null;
    private BluetoothGattCharacteristic mConfigCharacteristic = null;

    public final static int RECOIL_OFFSET_BUTTONS = 2;
    public final static int RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER = 3;
    public final static int RECOIL_OFFSET_THUMB_COUNTER = 4;
    public final static int RECOIL_OFFSET_POWER_COUNTER = 5;
    public final static int RECOIL_OFFSET_BATTERY_LEVEL = 7;
    public final static int RECOIL_OFFSET_HIT_BY1_SHOTID = 8;
    public final static int RECOIL_OFFSET_HIT_BY1 = 9;
    public final static int RECOIL_OFFSET_HIT_BY2_SHOTID = 11;
    public final static int RECOIL_OFFSET_HIT_BY2 = 12;
    public final static int RECOIL_OFFSET_TEAM = 1;
    public final static int RECOIL_OFFSET_SHOTS_REMAINING = 14;
    public final static int RECOIL_OFFSET_STATUS = 15;

    public final static int RECOIL_TRIGGER_BIT = 0x01;
    public final static int RECOIL_RELOAD_BIT = 0x02;
    public final static int RECOIL_THUMB_BIT = 0x04;
    public final static int RECOIL_POWER_BIT = 0x10;

    private static final byte WEAPON_PROFILE = (byte)0x00;

    private boolean mUseNetwork = false;
    private int mScore = 0;
    private int mTeamScore = 0;

    private static int boundedCounter(int value, int maximum) {
        return Math.max(0, Math.min(maximum, value));
    }

    private static int incrementCounter(int value, int maximum) {
        value = boundedCounter(value, maximum);
        return value == maximum ? maximum : value + 1;
    }

    // A dedicated host can send a scoreboard only in response to a request or
    // when a round ends. Do not let repeated network frames create unbounded UI
    // parser threads or stack dialogs on top of the game.
    private final AtomicBoolean mPlayerDataDialogActive = new AtomicBoolean();
    private volatile boolean mReady = false;
    private int mNetworkTeam = 0;
    private boolean mIsServer = false;
    private long mLastShotFired = 0; // to reduce shots fired message spam
    private long mLastHitMessage = 0; // to reduce hit/out message spam

    // Grenade stuff
    private static final byte GRENADE_DAMAGE = (byte) 0x01;
    private static final byte GRENADE_NEW_PAIR = (byte) 0x0E;
    private static final byte GRENADE_PAIR_ID = (byte) 0x0F;
    private static final byte GRENADE_DISARM = (byte) 0x0D;

    /* We run a continuous handler in the background while the tagger is connected to monitor the
       connection status. Simply put, we set connectionTestHandler to false every time the handler
       runs. Every time we receive telemetry data, we set connectionTestHandler back to true. When
       the handler runs again, if connectionTestHandler is still false, then the tagger has
       disconnected without us knowing. */
    private Handler connectionTestHandler;
    private Runnable mConnectionTestRunnable;
    private static final int CONNECTION_TEST_INTERVAL_MILLISECONDS = 5000;
    private volatile boolean mConnectionTest = false;

    /* We run a single handler to check and see if we have connected to a weapon within 30 seconds */
    private static final int CONNECTION_FAIL_TEST_INTERVAL_MILLISECONDS = 30000;

    private static final int HIT_VIBRATE_DURATION_MILLISECONDS = 250;
    // If you change these durations you will also need to change the duration in the res/anim/fadeout xml files to match
    private static final int HIT_ANIMATION_DURATION_MILLISECONDS = 400;
    private static final int ELIMINATED_ANIMATION_DURATION_MILLISECONDS = 800;

    // Code to manage Service lifecycle.
    private ServiceConnection mBLEServiceConnection = null;
    private boolean mBLEServiceBound = false;

    private SharedPreferences sharedPreferences = null;
    public static final String PREF_NAME = "SimpleCoil";
    private static final String PREF_PLAYER_NAME = "PlayerName";
    private static final String DEFAULT_PLAYER_NAME = "Player";
    private static final String PREF_PLAYER_ID = "PlayerID";
    static final String PREF_FIRING_MODE = "FiringMode";
    private static final String PREF_SHOT_MODE = "ShotMode";
    private static final String PREF_RECOIL_ENABLED = "RecoilEnabled";
    static final String PREF_VIBRATE_ON_HIT = "VibrateOnHit";
    public static final String PREF_GAME_MODE = "GameMode";
    public static final String PREF_LIMIT_TIME = "TimeLimit";
    public static final String PREF_LIMIT_LIVES = "LivesLimit";
    public static final String PREF_LIMIT_SCORE = "ScoreLimit";
    public static final String PREF_DEVICE_ADDRESS = "DeviceAddress";

    private static void discardMalformedPreference(SharedPreferences preferences, String key,
                                                   ClassCastException exception) {
        Log.w(TAG, "Ignoring malformed saved preference " + key, exception);
        preferences.edit().remove(key).apply();
    }

    static String readStringPreference(SharedPreferences preferences, String key, String fallback) {
        try {
            String value = preferences.getString(key, fallback);
            return value == null ? fallback : value;
        } catch (ClassCastException e) {
            discardMalformedPreference(preferences, key, e);
            return fallback;
        }
    }

    static int readIntPreference(SharedPreferences preferences, String key, int fallback) {
        try {
            return preferences.getInt(key, fallback);
        } catch (ClassCastException e) {
            discardMalformedPreference(preferences, key, e);
            return fallback;
        }
    }

    static boolean readBooleanPreference(SharedPreferences preferences, String key, boolean fallback) {
        try {
            return preferences.getBoolean(key, fallback);
        } catch (ClassCastException e) {
            discardMalformedPreference(preferences, key, e);
            return fallback;
        }
    }

    static String normalizePlayerName(String playerName) {
        return TcpJson.isValidPlayerName(playerName) ? playerName : DEFAULT_PLAYER_NAME;
    }

    static String normalizeBluetoothAddress(String address) {
        if (address == null)
            return "";
        String normalized = address.trim().toUpperCase(Locale.US);
        return BluetoothAdapter.checkBluetoothAddress(normalized) ? normalized : "";
    }

    private void restoreSavedProfile() {
        String savedPlayerName = readStringPreference(sharedPreferences, PREF_PLAYER_NAME, DEFAULT_PLAYER_NAME);
        String playerName = normalizePlayerName(savedPlayerName);
        String savedDeviceAddress = readStringPreference(sharedPreferences, PREF_DEVICE_ADDRESS, "");
        String deviceAddress = normalizeBluetoothAddress(savedDeviceAddress);
        Globals.getInstance().mPlayerName = playerName;
        mDeviceAddress = deviceAddress;
        if (!playerName.equals(savedPlayerName) || !deviceAddress.equals(savedDeviceAddress)) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(PREF_PLAYER_NAME, playerName);
            editor.putString(PREF_DEVICE_ADDRESS, deviceAddress);
            editor.apply();
        }
    }

    private void setupBLEServiceConnection() {
        if (mBLEServiceBound) return;
        mBLEServiceConnection = createBLEServiceConnection();
        Intent gattServiceIntent = new Intent(getBaseContext(), BluetoothLeService.class);
        mBLEServiceBound = bindService(gattServiceIntent, mBLEServiceConnection, BIND_AUTO_CREATE);
        if (!mBLEServiceBound)
            mBLEServiceConnection = null;
    }

    private ServiceConnection createBLEServiceConnection() {
        return new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                if (mBLEServiceConnection != this || !mBLEServiceBound || isFinishing() || isDestroyed())
                    return;
                mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
                if (!mBluetoothLeService.initialize()) {
                    Log.e(TAG, "Unable to initialize Bluetooth");
                    finish();
                    return;
                }
                // Automatically connects to the device upon successful start-up initialization.
                if (!mBluetoothLeService.connect(mDeviceAddress)) {
                    Log.w(TAG, "Unable to start Bluetooth connection");
                    handleDisconnect();
                    return;
                }
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(PREF_DEVICE_ADDRESS, mDeviceAddress);
                editor.apply();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                if (mBLEServiceConnection != this || !mBLEServiceBound)
                    return;
                mBluetoothLeService = null;
            }
        };
    }

    // Code to manage Service lifecycle.
    private ServiceConnection mUDPServiceConnection = null;
    private UDPListenerService mUDPListenerService = null;
    private boolean mUDPServiceBound = false;

    private void setupUDPServiceConnection() {
        if (mUDPServiceBound) return;
        mUDPServiceConnection = createUDPServiceConnection();
        Intent udpServiceIntent = new Intent(getBaseContext(), UDPListenerService.class);
        startService(udpServiceIntent);
        mUDPServiceBound = bindService(udpServiceIntent, mUDPServiceConnection, BIND_AUTO_CREATE);
        if (!mUDPServiceBound)
            mUDPServiceConnection = null;
    }

    private ServiceConnection createUDPServiceConnection() {
        return new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                if (mUDPServiceConnection != this || !mUDPServiceBound || isFinishing() || isDestroyed())
                    return;
                mUDPListenerService = ((UDPListenerService.LocalBinder) service).getService();
                startPeerUdpServerIfTcpReady();
                consumePendingServerEvent();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                if (mUDPServiceConnection != this || !mUDPServiceBound || isFinishing() || isDestroyed())
                    return;
                mReady = false;
                setReady(false);
                mIsServer = false;
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                    endGame();
                mUDPListenerService = null;
                mPeerUdpServerStarting = false;
            }
        };
    }

    private TcpClient mTcpClient = null;
    private ServiceConnection mTcpClientServiceConnection = null;
    private boolean mTcpClientServiceBound = false;
    private boolean mGattReceiverRegistered;
    private boolean mBluetoothReceiverRegistered;
    private boolean mNetworkReceiverRegistered = false;

    private TcpServer mTcpServer = null;
    private ServiceConnection mTcpServerServiceConnection = null;
    private boolean mTcpServerServiceBound = false;
    // Peer hosts must not announce a lobby until their TCP listener has bound.
    private boolean mPeerHostCreationPending;
    private boolean mPeerUdpServerStarting;

    private void startPeerUdpServerIfTcpReady() {
        if (!mPeerHostCreationPending || mPeerUdpServerStarting || mTcpServer == null
                || mUDPListenerService == null || !mTcpServer.isTcpServerReady())
            return;
        mPeerUdpServerStarting = true;
        mUDPListenerService.createServer();
    }

    private void setupTcpClientServiceConnection() {
        if (mTcpClientServiceBound) return;
        mTcpClientServiceConnection = createTcpClientServiceConnection();
        Intent serviceIntent = new Intent(getBaseContext(), TcpClient.class);
        startService(serviceIntent);
        mTcpClientServiceBound = bindService(serviceIntent, mTcpClientServiceConnection, BIND_AUTO_CREATE);
        if (!mTcpClientServiceBound)
            mTcpClientServiceConnection = null;
    }

    private ServiceConnection createTcpClientServiceConnection() {
        return new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                if (mTcpClientServiceConnection != this || !mTcpClientServiceBound
                        || isFinishing() || isDestroyed())
                    return;
                mTcpClient = ((TcpClient.LocalBinder) service).getService();
                consumePendingServerEvent();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                if (mTcpClientServiceConnection != this || !mTcpClientServiceBound
                        || isFinishing() || isDestroyed())
                    return;
                mTcpClient = null;
            }
        };
    }

    private void consumePendingServerEvent() {
        if (!mNetworkReceiverRegistered)
            return;
        if (mTcpClient != null) {
            Intent event = mTcpClient.consumePendingTerminalEvent();
            if (event != null) {
                mUDPUpdateReceiver.onReceive(this, event);
                return;
            }
            if (mUseNetwork && mReady && networkServicesReady()) {
                event = mIsServer ? mTcpServer.getScheduledGameStart()
                        : mTcpClient.consumePendingGameStart(0);
                if (event != null)
                    mUDPUpdateReceiver.onReceive(this, event);
            }
        }
        // A synchronized peer STARTGAME must be applied before its retained
        // ENDGAME. That start installs the listener/UI token which authenticates
        // the terminal event and prevents it crossing into a dedicated round.
        consumePendingPeerEndGame();
    }

    private void consumePendingPeerEndGame() {
        if (mUDPListenerService == null)
            return;
        Intent event = mUDPListenerService.consumePendingPeerEndGame();
        if (event != null)
            mUDPUpdateReceiver.onReceive(this, event);
    }

    private void setupTcpServerServiceConnection() {
        if (mTcpServerServiceBound) return;
        mTcpServerServiceConnection = createTcpServerServiceConnection();
        Intent serviceIntent = new Intent(getBaseContext(), TcpServer.class);
        startService(serviceIntent);
        mTcpServerServiceBound = bindService(serviceIntent, mTcpServerServiceConnection, BIND_AUTO_CREATE);
        if (!mTcpServerServiceBound)
            mTcpServerServiceConnection = null;
    }

    private ServiceConnection createTcpServerServiceConnection() {
        return new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                if (mTcpServerServiceConnection != this || !mTcpServerServiceBound
                        || isFinishing() || isDestroyed())
                    return;
                mTcpServer = ((TcpServer.LocalBinder) service).getService();
                mTcpServer.setDedicated(false);
                startPeerUdpServerIfTcpReady();
                consumePendingServerEvent();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                if (mTcpServerServiceConnection != this || !mTcpServerServiceBound
                        || isFinishing() || isDestroyed())
                    return;
                mTcpServer = null;
            }
        };
    }

    private void unbindUDPService() {
        if (!mUDPServiceBound) return;
        try {
            unbindService(mUDPServiceConnection);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "UDP service was already unbound", e);
        } finally {
            mUDPServiceBound = false;
            mUDPServiceConnection = null;
            mUDPListenerService = null;
        }
    }

    private void unbindTcpClientService() {
        if (!mTcpClientServiceBound) return;
        try {
            unbindService(mTcpClientServiceConnection);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "TCP client service was already unbound", e);
        } finally {
            mTcpClientServiceBound = false;
            mTcpClientServiceConnection = null;
            mTcpClient = null;
        }
    }

    private void unbindTcpServerService() {
        if (!mTcpServerServiceBound) return;
        try {
            unbindService(mTcpServerServiceConnection);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "TCP server service was already unbound", e);
        } finally {
            mTcpServerServiceBound = false;
            mTcpServerServiceConnection = null;
            mTcpServer = null;
        }
    }

    // These services are intentionally kept alive while moving between the player and
    // dedicated-host screens.  Once the player task is actually finishing, however,
    // leaving them started keeps sockets and receivers alive with no UI to own them.
    private void stopNetworkServices() {
        stopService(new Intent(getBaseContext(), UDPListenerService.class));
        stopService(new Intent(getBaseContext(), TcpClient.class));
        stopService(new Intent(getBaseContext(), TcpServer.class));
    }

    private boolean networkServicesReady() {
        return mUDPListenerService != null && mTcpClient != null && mTcpServer != null;
    }

    private boolean isDedicatedServerConnection() {
        return mTcpClient != null && mTcpClient.isDedicatedServer();
    }

    private void sendUDPMessage(String message, byte playerID) {
        if (mUDPListenerService != null)
            mUDPListenerService.sendUDPMessage(message, playerID);
    }

    private void sendUDPMessageAll(String message) {
        if (mUDPListenerService != null)
            mUDPListenerService.sendUDPMessageAll(message);
    }

    private void endUDPGame() {
        if (mUDPListenerService != null)
            mUDPListenerService.endGame();
    }

    private void endUDPScanning() {
        if (mUDPListenerService != null)
            mUDPListenerService.endScanning();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen);
        mFragmentMgr = getSupportFragmentManager();
        minfo = findViewById(R.id.textview_info);
        mEliminationCountTV = findViewById(R.id.eliminations_count_tv);
        mReconnectButton = findViewById(R.id.reconnect_weapon_button);
        mReconnectButton.setOnClickListener((v -> {
            if (sharedPreferences == null)
                sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String savedAddress = readStringPreference(sharedPreferences, PREF_DEVICE_ADDRESS, "");
            mDeviceAddress = normalizeBluetoothAddress(savedAddress);
            if (!mDeviceAddress.equals(savedAddress))
                sharedPreferences.edit().putString(PREF_DEVICE_ADDRESS, mDeviceAddress).apply();
            if (mDeviceAddress != null && !mDeviceAddress.isEmpty())
                connectWeapon();
            else {
                mReconnectButton.setVisibility(View.GONE);
            }
        }));
        mConnectButton = findViewById(R.id.connect_weapon_button);
        if (mConnectButton != null) {
            mConnectButton.setOnClickListener((v -> {
                mDeviceAddress = "";
                connectWeapon();
            }));
        }
        mQRConnectButton = findViewById(R.id.connect_qr_weapon_button);
        if (mQRConnectButton != null) {
            mQRConnectButton.setOnClickListener((v -> {
                try {
                    Intent intent = new Intent("com.google.zxing.client.android.SCAN");
                    intent.putExtra("SCAN_MODE", "QR_CODE_MODE"); // "PRODUCT_MODE for bar codes
                    startActivityForResult(intent, REQUEST_QR_SCAN);
                } catch (ActivityNotFoundException | SecurityException e) {
                    Log.w(TAG, "QR scanner is unavailable", e);
                    Uri marketUri = Uri.parse("market://details?id=com.google.zxing.client.android");
                    Intent marketIntent = new Intent(Intent.ACTION_VIEW,marketUri);
                    try {
                        startActivity(marketIntent);
                    } catch (ActivityNotFoundException | SecurityException storeError) {
                        Log.w(TAG, "No accessible app store for the QR scanner", storeError);
                        Toast.makeText(getApplicationContext(), R.string.error_qr_scanner_unavailable,
                                Toast.LENGTH_LONG).show();
                    }
                }
            }));
        }
        mDedicatedServerButton = findViewById(R.id.dedicated_server_button);
        if (mDedicatedServerButton != null) {
            mDedicatedServerButton.setOnClickListener((v -> {
                unbindUDPService();
                unbindTcpClientService();
                unbindTcpServerService();
                startActivity(new Intent(FullscreenActivity.this, DedicatedServerActivity.class));
            }));
        }
        mTeamMinusButton = findViewById(R.id.team_minus_button);
        if (mTeamMinusButton != null) {
            mTeamMinusButton.setVisibility(View.VISIBLE);
            mTeamMinusButton.setOnClickListener((v -> {
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                    return;
                if (Globals.getInstance().mPlayerID > (byte)1)
                    Globals.getInstance().mPlayerID--;
                else
                    Globals.getInstance().mPlayerID = Globals.MAX_PLAYER_ID;
                setTeam();
            }));
        }
        mTeamPlusButton = findViewById(R.id.team_plus_button);
        if (mTeamPlusButton != null) {
            mTeamPlusButton.setVisibility(View.VISIBLE);
            mTeamPlusButton.setOnClickListener((v -> {
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                    return;
                if (Globals.getInstance().mPlayerID < Globals.MAX_PLAYER_ID)
                    Globals.getInstance().mPlayerID++;
                else
                    Globals.getInstance().mPlayerID = (byte)0x01;
                setTeam();
            }));
        }
        mStartGameButton = findViewById(R.id.start_game_button);
        if (mStartGameButton != null) {
            mStartGameButton.setOnClickListener((v -> {
                if (Globals.getInstance().mPlayerID == 0) {
                    Toast.makeText(getApplicationContext(), getString(R.string.error_select_team), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (mUseNetwork) {
                    if (!networkServicesReady()) {
                        Log.w(TAG, "Ignoring start-game request before network services are ready");
                        return;
                    }
                    if (Globals.getPlayerCount() <= 1) {
                        Toast.makeText(getApplicationContext(), getString(R.string.not_enough_players_toast), Toast.LENGTH_SHORT).show();
                        mNetworkPlayerCountTV.setText(R.string.network_player_1count);
                        return;
                    }
                    if (mIsServer ? !mTcpServer.arePlayerClocksSynchronized() : !mTcpClient.isClockSynchronized()) {
                        Toast.makeText(getApplicationContext(), R.string.clock_sync_waiting, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (mIsServer)
                        mTcpServer.startGame();
                    else
                        mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_STARTGAME);
                } else
                    startGame();
            }));
        }
        mPlayerSettingsButton = findViewById(R.id.player_settings_button);
        if (mPlayerSettingsButton != null) {
            mPlayerSettingsButton.setOnClickListener((v -> {
                if (!Globals.getInstance().mAllowPlayerSettings) {
                    Toast.makeText(getApplicationContext(), getString(R.string.player_settings_not_allowed_toast), Toast.LENGTH_SHORT).show();
                    return;
                }
                PlayerSettingsAlertDialog dialog = new PlayerSettingsAlertDialog(FullscreenActivity.this);
                dialog.setLocal(mTcpClient);
                dialog.show();
            }));
        }
        mEndGameButton = findViewById(R.id.end_game_button);
        if (mEndGameButton != null) {
            mEndGameButton.setOnClickListener((v -> confirmEndGame()));
        }
        mEndNetworkGameButton = findViewById(R.id.end_network_game_button);
        if (mEndNetworkGameButton != null) {
            mEndNetworkGameButton.setOnClickListener((v -> confirmEndGame()));
        }
        mShotsRemainingLabelTV = findViewById(R.id.shots_label_tv);
        mShotsRemainingTV = findViewById(R.id.shots_remaining_tv);
        mHitsTakenTV = findViewById(R.id.hits_taken_tv);
        mTeamLabelTV = findViewById(R.id.team_label_tv);
        mTeamTV = findViewById(R.id.team_tv);
        mRecoilModeTV = findViewById(R.id.recoil_tv);
        mShotModeTV = findViewById(R.id.shot_mode_tv);
        mHealthLabelTV = findViewById(R.id.health_label_tv);
        mHealthBar = findViewById(R.id.health_pb);
        mShieldBar = findViewById(R.id.shield_pb);
        resetVitalStatBars();
        mReloadBar = findViewById(R.id.reload_pb);
        mEliminatedTV = findViewById(R.id.eliminated_tv);
        mEliminatedByTV = findViewById(R.id.eliminated_by_tv);
        mSpawnInTV = findViewById(R.id.spawn_countdown_tv);
        mRespawnQrScanButton = findViewById(R.id.respawn_qr_scan_button);
        if (mRespawnQrScanButton != null)
            mRespawnQrScanButton.setOnClickListener(v -> openRespawnQrScanner());
        mRespawnQrScannerOverlay = findViewById(R.id.respawn_qr_scanner_overlay);
        mRespawnQrScanner = findViewById(R.id.respawn_qr_scanner);
        mRespawnQrScannerPrompt = findViewById(R.id.respawn_qr_scanner_prompt);
        mRespawnQrWaitButton = findViewById(R.id.respawn_qr_wait_button);
        if (mRespawnQrScanner != null)
            mRespawnQrScanner.getBarcodeView().setDecoderFactory(
                    new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        if (mRespawnQrWaitButton != null)
            mRespawnQrWaitButton.setOnClickListener(v -> hideRespawnQrScanner());
        mHitIV = findViewById(R.id.hit_animation_iv);
        mBatteryLevelIV = findViewById(R.id.battery_iv);
        mGameTimer = findViewById(R.id.game_timer_chronometer);
        mFiringModeButton = findViewById(R.id.firing_mode_button);
        if (mFiringModeButton != null) {
            mFiringModeButton.setOnClickListener((v -> {
                PopupMenu popup = new PopupMenu(FullscreenActivity.this, v);
                MenuInflater inflater = popup.getMenuInflater();
                inflater.inflate(R.menu.firing_mode_menu, popup.getMenu());
                popup.setOnMenuItemClickListener(FullscreenActivity.this);
                popup.show();
                Toast.makeText(getApplicationContext(), getString(R.string.firing_mode_cone_toast), Toast.LENGTH_LONG).show();
            }));
        }

        showConnectLayout();
        initBatteryQueue();
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        restoreSavedProfile();
        Globals.getInstance().mCurrentFiringMode = readIntPreference(sharedPreferences, PREF_FIRING_MODE,
                Globals.FIRING_MODE_OUTDOOR_NO_CONE);
        int savedPlayerID = readIntPreference(sharedPreferences, PREF_PLAYER_ID, 0);
        Globals.getInstance().mPlayerID = Globals.isValidPlayerID(savedPlayerID) ? (byte) savedPlayerID : 0;
        getFiringMode();
        mRecoilEnabled = readBooleanPreference(sharedPreferences, PREF_RECOIL_ENABLED, true);
        Globals.getInstance().mVibrateOnHit = readBooleanPreference(sharedPreferences,
                PREF_VIBRATE_ON_HIT, false);
        mCurrentShotMode = readIntPreference(sharedPreferences, PREF_SHOT_MODE, Globals.SHOT_MODE_SINGLE);
        int savedGameMode = readIntPreference(sharedPreferences, PREF_GAME_MODE, Globals.GAME_MODE_2TEAMS);
        Globals.getInstance().mGameMode = Globals.isValidGameMode(savedGameMode) ? savedGameMode : Globals.GAME_MODE_2TEAMS;
        Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_NONE;
        int savedTimeLimit = readIntPreference(sharedPreferences, PREF_LIMIT_TIME, 0);
        Globals.getInstance().mTimeLimit = Globals.isValidGameLimit(savedTimeLimit) ? savedTimeLimit : 0;
        if (Globals.getInstance().mTimeLimit != 0)
            Globals.getInstance().mGameLimit += Globals.GAME_LIMIT_TIME;
        int savedLivesLimit = readIntPreference(sharedPreferences, PREF_LIMIT_LIVES, 0);
        Globals.getInstance().mLivesLimit = Globals.isValidGameLimit(savedLivesLimit) ? savedLivesLimit : 0;
        if (Globals.getInstance().mLivesLimit != 0)
            Globals.getInstance().mGameLimit += Globals.GAME_LIMIT_LIVES;
        int savedScoreLimit = readIntPreference(sharedPreferences, PREF_LIMIT_SCORE, 0);
        Globals.getInstance().mScoreLimit = Globals.isValidGameLimit(savedScoreLimit) ? savedScoreLimit : 0;
        if (Globals.getInstance().mScoreLimit != 0)
            Globals.getInstance().mGameLimit += Globals.GAME_LIMIT_SCORE;
        if (mDeviceAddress != null && !mDeviceAddress.isEmpty()) {
            mReconnectButton.setVisibility(View.VISIBLE);
        } else {
            mReconnectButton.setVisibility(View.GONE);
        }

        try {
            // Display app version
            PackageInfo pInfo = this.getPackageManager().getPackageInfo(getPackageName(), 0);
            TextView versionTextView = findViewById(R.id.version_tv);
            versionTextView.setText(pInfo.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        mNetworkStatusIV = findViewById(R.id.network_status_iv);
        mNetworkPlayerCountTV = findViewById(R.id.player_count_tv);
        mUseNetworkingButton = findViewById(R.id.use_network_button);
        if (mUseNetworkingButton != null) {
            mUseNetworkingButton.setOnClickListener((v -> {
                ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo mWifi = connManager == null ? null : connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (mWifi == null || !mWifi.isConnected()) {
                    Toast.makeText(getApplicationContext(), getString(R.string.error_no_wifi), Toast.LENGTH_SHORT).show();
                    mUseNetwork = false;
                    return;
                }
                if (mNetworkPopup == null) {
                    mNetworkPopup = new PopupMenu(FullscreenActivity.this, v);
                    mNetworkPopup.setOnMenuItemClickListener(FullscreenActivity.this);
                    setNetworkMenu(NETWORK_TYPE_ENABLED);
                }
                if (!mUseNetwork) {
                    mUseNetwork = true;
                    mUseNetworkingButton.setText(R.string.network_menu_button);
                    displayAllNetworkingOptions(true);
                    setNetworkMenu(NETWORK_TYPE_ENABLED);
                }
                mNetworkPopup.show();
                setTeam();
            }));
        }
        mGameModeLabelTV = findViewById(R.id.game_mode_label_tv);
        mGameModeTV = findViewById(R.id.game_mode_tv);
        mScoreLabelTV = findViewById(R.id.score_label_tv);
        mScoreTV = findViewById(R.id.score_tv);
        mTeamScoreLabelTV = findViewById(R.id.team_score_label_tv);
        mTeamScoreTV = findViewById(R.id.team_score_tv);
        mHitPlayerIV = findViewById(R.id.hit_player_iv);
        mHitPlayerNameTV = findViewById(R.id.hit_player_name_tv);
        mShotsFiredIV = findViewById(R.id.shots_fired_iv);
        mScoreIncreaseIV = findViewById(R.id.score_increase_iv);
        mScoreIncreasePlayerNameTV = findViewById(R.id.score_increase_player_name_tv);
        mServerIPTV = findViewById(R.id.server_ip_tv);
        mGameLimitButton = findViewById(R.id.game_limit_button);
        if (mGameLimitButton != null) {
            mGameLimitButton.setOnClickListener((v -> requestGameLimit()));
        }
        mGameCountDownTV = findViewById(R.id.game_countdown_tv);
        mPlayerNameTV = findViewById(R.id.player_name_label_tv);
        if (mPlayerNameTV != null)
            mPlayerNameTV.setText(Globals.getInstance().mPlayerName);
        displayAllNetworkingOptions(false);
        mUseNetworkingButton.setVisibility(View.VISIBLE);
        setGameLimit();
        mPlayerDataButton = findViewById(R.id.player_data_button);
        if (mPlayerDataButton != null) {
            mPlayerDataButton.setOnClickListener((v -> {
                if (mUseNetwork && mTcpClient != null && mTcpClient.isDedicatedServer()) {
                    mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_PLAYERDATAREQUEST);
                    // Disable the button for 1 second to prevent spamming the server
                    mPlayerDataButton.setEnabled(false);
                    new Handler().postDelayed(() -> {
                        if (!isFinishing() && !isDestroyed() && mPlayerDataButton != null)
                            mPlayerDataButton.setEnabled(true);
                    }, 1000);
                }
            }));
        }
        loadFragment();
        updatePlayerSettings();
    }

    private static final int NETWORK_TYPE_ENABLED = 1;
    private static final int NETWORK_TYPE_JOINING = 2;
    private static final int NETWORK_TYPE_JOINED = 3;
    private static final int NETWORK_TYPE_SERVING = 4;

    private void setNetworkMenu(int networkMenuType) {
        if (mNetworkPopup == null)
            return;
        mNetworkPopup.getMenu().close();
        mNetworkPopup.getMenu().clear();
        switch (networkMenuType) {
            case NETWORK_TYPE_ENABLED:
                mNetworkPopup.getMenu().add(0, R.id.join_item, 20, R.string.join_button);
                mNetworkPopup.getMenu().add(0, R.id.join_ip_item, 30, R.string.join_ip_button);
                mNetworkPopup.getMenu().add(0, R.id.create_server_item, 40, R.string.create_server_button);
                mNetworkPopup.getMenu().add(0, R.id.player_name_item, 50, getString(R.string.player_name_button, Globals.getInstance().mPlayerName));
                mNetworkPopup.getMenu().add(0, R.id.game_mode_item, 60, R.string.game_mode_toggle_button);
                mNetworkPopup.getMenu().add(0, R.id.disable_network_item, 70, R.string.no_network_button);
                break;
            case NETWORK_TYPE_JOINING:
                mNetworkPopup.getMenu().add(0, R.id.please_wait_item, 1, R.string.please_wait);
                break;
            case NETWORK_TYPE_JOINED:
                mNetworkPopup.getMenu().add(0, R.id.leave_item, 1, R.string.not_ready_button);
                mNetworkPopup.getMenu().add(0, R.id.player_name_item, 50, getString(R.string.player_name_button, Globals.getInstance().mPlayerName));
                break;
            case NETWORK_TYPE_SERVING:
                mNetworkPopup.getMenu().add(0, R.id.cancel_server_item, 1, R.string.cancel_server_button);
                break;
        }
    }
//TODDO add presets ?
    @Override
    public boolean onMenuItemClick(MenuItem item) {

            int id = item.getItemId();
            if(id == R.id.disable_network_item) {
                displayAllNetworkingOptions(false);
                mUseNetwork = false;
                mUseNetworkingButton.setText(R.string.use_network_button);
                setTeam();
                return true;
            }else if(id == R.id.join_item) {
                if (Globals.getInstance().mPlayerID == 0) {
                    Toast.makeText(getApplicationContext(), getString(R.string.error_select_team), Toast.LENGTH_SHORT).show();
                    return true;
                }
                if (!networkServicesReady()) return true;
                mReady = true;
                setReady();
                mUDPListenerService.joinServer();
                setNetworkMenu(NETWORK_TYPE_JOINING);
                return true;
            }else if (id == R.id.join_ip_item) {
                    if (Globals.getInstance().mPlayerID == 0) {
                        Toast.makeText(getApplicationContext(), getString(R.string.error_select_team), Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    if (!networkServicesReady()) return true;
                    requestServerIP();
                    return true;
            }else if (id == R.id.create_server_item) {
                    if (Globals.getInstance().mPlayerID == 0) {
                        Toast.makeText(getApplicationContext(), getString(R.string.error_select_team), Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    if (!networkServicesReady()) return true;
                    mPeerHostCreationPending = true;
                    mPeerUdpServerStarting = false;
                    mTcpServer.startTcpServer();
                    startPeerUdpServerIfTcpReady();
                    setNetworkMenu(NETWORK_TYPE_JOINING);
                    return true;
            }else if (id == R.id.player_name_item) {
                    requestPlayerName();
                    return true;
            }else if(id == R.id.game_mode_item) {
                    PopupMenu popup = new PopupMenu(FullscreenActivity.this, mUseNetworkingButton);
                    MenuInflater inflater = popup.getMenuInflater();
                    inflater.inflate(R.menu.game_mode_menu, popup.getMenu());
                    popup.setOnMenuItemClickListener(FullscreenActivity.this);
                    popup.show();
                    return true;
            }else if (id == R.id.please_wait_item) {
                    return true;
            }else if (id == R.id.cancel_server_item) {
                    if (!networkServicesReady()) return true;
                    mPeerHostCreationPending = false;
                    mPeerUdpServerStarting = false;
                    // A host does not receive its own SERVERCANCEL message.
                    // End a live local round before tearing down the listener,
                    // otherwise its weapon and timers remain active after peers
                    // have been told that the server disappeared.
                    if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
                        endGame();
                    } else {
                        mReady = false;
                        setReady();
                        mIsServer = false;
                        mUDPListenerService.cancelServer();
                        mTcpServer.cancelServer();
                    }
                    setNetworkMenu(NETWORK_TYPE_ENABLED);
                    return true;
            }else if (id == R.id.leave_item) {
                    mReady = false;
                    setReady();
                    setNetworkMenu(NETWORK_TYPE_ENABLED);
                    return true;
            }else if (id == R.id.firing_mode_outdoor_no_cone_item) {
                    Globals.getInstance().mCurrentFiringMode = Globals.FIRING_MODE_OUTDOOR_NO_CONE;
                    mFiringModeButton.setText(R.string.firing_mode_outdoor_no_cone);
                    setShotMode(mCurrentShotMode);
                {
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putInt(PREF_FIRING_MODE, Globals.getInstance().mCurrentFiringMode);
                    editor.apply();
                }
                return true;
            }else if (id == R.id.firing_mode_outdoor_with_cone_item) {
                    Globals.getInstance().mCurrentFiringMode = Globals.FIRING_MODE_OUTDOOR_WITH_CONE;
                    mFiringModeButton.setText(R.string.firing_mode_outdoor_with_cone);
                    setShotMode(mCurrentShotMode);
                {
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putInt(PREF_FIRING_MODE, Globals.getInstance().mCurrentFiringMode);
                    editor.apply();
                }
                return true;
            }else if (id == R.id.firing_mode_indoor_no_cone_item) {
                    Globals.getInstance().mCurrentFiringMode = Globals.FIRING_MODE_INDOOR_NO_CONE;
                    mFiringModeButton.setText(R.string.firing_mode_indoor_no_cone);
                    setShotMode(mCurrentShotMode);
                {
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putInt(PREF_FIRING_MODE, Globals.getInstance().mCurrentFiringMode);
                    editor.apply();
                }
                return true;
            }else if (id == R.id.game_mode_2teams_item) {
                    Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
                    mGameModeTV.setText(R.string.game_mode_2teams);
                    setTeam();
                    return true;
            }else if (id ==R.id.game_mode_4teams_item) {
                    Globals.getInstance().mGameMode = Globals.GAME_MODE_4TEAMS;
                    mGameModeTV.setText(R.string.game_mode_4teams);
                    setTeam();
                    return true;
            }else if (id == R.id.game_mode_ffa_item) {
                    Globals.getInstance().mGameMode = Globals.GAME_MODE_FFA;
                    mGameModeTV.setText(R.string.game_mode_ffa);
                    setTeam();
                    return true;
            }else {
                return false;
            }

    }

    private void getFiringMode() {
        int firingMode = Globals.getInstance().mCurrentFiringMode;
        if (!Globals.isValidFiringMode(firingMode)) {
            Log.w(TAG, "Invalid firing mode " + firingMode + "; using the default mode");
            firingMode = Globals.FIRING_MODE_OUTDOOR_NO_CONE;
            Globals.getInstance().mCurrentFiringMode = firingMode;
        }
        switch (firingMode) {
            case Globals.FIRING_MODE_OUTDOOR_NO_CONE:
                mFiringModeButton.setText(R.string.firing_mode_outdoor_no_cone);
                return;
            case Globals.FIRING_MODE_OUTDOOR_WITH_CONE:
                mFiringModeButton.setText(R.string.firing_mode_outdoor_with_cone);
                return;
            case Globals.FIRING_MODE_INDOOR_NO_CONE:
                mFiringModeButton.setText(R.string.firing_mode_indoor_no_cone);
        }
    }

    private void requestPlayerName() {
        LayoutInflater li = LayoutInflater.from(getApplicationContext());
        View view = li.inflate(R.layout.player_name_dialog, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        alertDialogBuilder.setView(view);

        final EditText playerNameET = view.findViewById(R.id.player_name_et);
        playerNameET.setText(Globals.getInstance().mPlayerName);

        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton(R.string.ok,
                        (dialog, id) -> {
                            Globals.getInstance().mPlayerName = playerNameET.getText().toString();
                            SharedPreferences.Editor editor = sharedPreferences.edit();
                            editor.putString(PREF_PLAYER_NAME, Globals.getInstance().mPlayerName);
                            editor.apply();
                            mPlayerNameTV.setText(Globals.getInstance().mPlayerName);
                            publishPlayerNameChange();
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.cancel,
                        (dialog, id) -> dialog.cancel());
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    // A peer host does not have a connected TcpClient. Publish its changed name
    // through the server's lobby snapshot so every joined player sees it.
    void publishPlayerNameChange() {
        if (!mReady) {
            displayAllNetworkingOptions(true);
            setNetworkMenu(NETWORK_TYPE_ENABLED);
            return;
        }
        if (mIsServer) {
            if (mTcpServer != null)
                mTcpServer.sendAllGameInfo(TcpServer.SEND_ALL);
            return;
        }
        if (mTcpClient != null)
            mTcpClient.sendPlayerNameChange();
    }

    private void requestServerIP() {
        LayoutInflater li = LayoutInflater.from(getApplicationContext());
        View view = li.inflate(R.layout.server_ip_dialog, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        alertDialogBuilder.setView(view);

        final EditText serverIPET = view.findViewById(R.id.server_ip_et);
        String ip;
        if (Globals.getInstance().mServerIP != null)
            ip = Globals.getInstance().mServerIP.toString();
        else
            ip = Globals.getIPAddressStr(getApplicationContext());
        if (ip.startsWith("/"))
            ip = ip.substring(1);
        serverIPET.setText(ip);

        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton(R.string.ok,
                        (dialog, id) -> {
                            if (mUDPListenerService == null) {
                                dialog.dismiss();
                                return;
                            }
                            mIsServer = true; // This tricks the setReady function into not searching for a server
                            mReady = true;
                            setReady();
                            mIsServer = false;
                            mUDPListenerService.joinServer(serverIPET.getText().toString());
                            setNetworkMenu(NETWORK_TYPE_JOINING);
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.cancel,
                        (dialog, id) -> dialog.cancel());
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }
    private void requestGameLimit() {
        LayoutInflater li = LayoutInflater.from(getApplicationContext());
        View view = li.inflate(R.layout.game_limit_dialog, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        alertDialogBuilder.setView(view);

        final EditText gameLimitET = view.findViewById(R.id.game_limit_et);
        final Switch gameUnlimitedSwitch = view.findViewById(R.id.game_unlimited_switch);
        final RadioButton gameLimitTime = view.findViewById(R.id.game_limit_time_radio);
        gameLimitTime.setChecked(true);
        final RadioButton gameLimitLives = view.findViewById(R.id.game_limit_lives_radio);
        gameLimitLives.setChecked(false);
        final RadioButton gameLimitScore = view.findViewById(R.id.game_limit_score_radio);
        gameLimitScore.setChecked(false);
        if (mUseNetwork)
            gameLimitScore.setVisibility(View.VISIBLE);
        else
            gameLimitScore.setVisibility(View.GONE);
        gameLimitTime.setOnClickListener((v -> {
            gameLimitTime.setChecked(true);
            gameLimitLives.setChecked(false);
            gameLimitScore.setChecked(false);
        }));
        gameLimitLives.setOnClickListener((v -> {
            gameLimitTime.setChecked(false);
            gameLimitLives.setChecked(true);
            gameLimitScore.setChecked(false);
        }));
        gameLimitScore.setOnClickListener((v -> {
            gameLimitTime.setChecked(false);
            gameLimitLives.setChecked(false);
            gameLimitScore.setChecked(true);
        }));
        View.OnClickListener updateLimitControls = ignored -> {
            boolean enabled = !gameUnlimitedSwitch.isChecked();
            gameLimitET.setEnabled(enabled);
            gameLimitTime.setEnabled(enabled);
            gameLimitLives.setEnabled(enabled);
            gameLimitScore.setEnabled(enabled);
        };
        gameUnlimitedSwitch.setChecked(Globals.getInstance().mGameLimit == Globals.GAME_LIMIT_NONE);
        gameUnlimitedSwitch.setOnClickListener(updateLimitControls);
        updateLimitControls.onClick(gameUnlimitedSwitch);

        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton(R.string.ok,
                        (dialog, id) -> {
                            if (gameUnlimitedSwitch.isChecked()) {
                                Globals.getInstance().clearGameLimits();
                                setGameLimit();
                                dialog.dismiss();
                                return;
                            }
                            if (gameLimitET.getText().toString().isEmpty()) {
                                dialog.dismiss();
                                return;
                            }
                            Integer limit;
                            try {
                                limit = Integer.parseInt(gameLimitET.getText().toString());
                            } catch (Exception ignored) {
                                dialog.dismiss();
                                return;
                            }
                            if (!Globals.isValidGameLimit(limit)) {
                                Toast.makeText(getApplicationContext(), getString(R.string.error_limit_too_high), Toast.LENGTH_SHORT).show();
                                dialog.dismiss();
                                return;
                            }
                            if (gameLimitTime.isChecked()) {
                                if (limit > 0)
                                    Globals.getInstance().mGameLimit |= Globals.GAME_LIMIT_TIME;
                                else
                                    Globals.getInstance().mGameLimit &= ~Globals.GAME_LIMIT_TIME;
                                Globals.getInstance().mTimeLimit = limit;
                            } else if (gameLimitLives.isChecked()) {
                                if (limit > 0)
                                    Globals.getInstance().mGameLimit |= Globals.GAME_LIMIT_LIVES;
                                else
                                    Globals.getInstance().mGameLimit &= ~Globals.GAME_LIMIT_LIVES;
                                Globals.getInstance().mLivesLimit = limit;
                            } else {
                                if (limit > 0)
                                    Globals.getInstance().mGameLimit |= Globals.GAME_LIMIT_SCORE;
                                else
                                    Globals.getInstance().mGameLimit &= ~Globals.GAME_LIMIT_SCORE;
                                Globals.getInstance().mScoreLimit = limit;
                            }
                            setGameLimit();
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.cancel,
                        (dialog, id) -> dialog.cancel());
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    private void setGameLimit() {
        boolean roundInProgress = Globals.getInstance().mGameState != Globals.GAME_STATE_NONE;
        // The HUD stores remaining lives in limited games and deaths otherwise.
        // Preserve deaths before changing that interpretation or the life budget.
        int deaths = mHasLivesLimit ? Math.max(0, mLives - mEliminationCount)
                : Math.max(0, mEliminationCount);
        if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0) {
            mGameCountDownTV.setVisibility(View.VISIBLE);
            String display = String.format(Locale.getDefault(),"%02d:00", Globals.getInstance().mTimeLimit);
            mGameCountDownTV.setText(display);
            mGameTimer.setVisibility(View.GONE);
        } else {
            mGameCountDownTV.setVisibility(View.GONE);
            mGameTimer.setVisibility(View.VISIBLE);
        }
        TextView eliminationLabel = findViewById(R.id.eliminations_count_label_tv);
        mLives = (Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_LIVES) != 0
                ? Globals.getInstance().mLivesLimit : 0;
        if (Globals.getInstance().mOverrideLives)
            mLives = Globals.getInstance().mOverrideLivesVal;
        mHasLivesLimit = mLives > 0;
        if (roundInProgress)
            mEliminationCount = mHasLivesLimit ? Math.max(0, mLives - deaths) : deaths;
        eliminationLabel.setText(mHasLivesLimit ? R.string.lives_count_label : R.string.eliminations_count_label);
        int displayedCount = !roundInProgress && mHasLivesLimit ? mLives : mEliminationCount;
        mEliminationCountTV.setText(getString(R.string.integer, displayedCount));
        if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_SCORE) != 0) {
            mScoreLabelTV.setText(getString(R.string.score_limit_label, Globals.getInstance().mScoreLimit));
            mTeamScoreLabelTV.setText(getString(R.string.team_score_limit_label, Globals.getInstance().mScoreLimit));
        } else {
            mScoreLabelTV.setText(R.string.score_label);
            mTeamScoreLabelTV.setText(R.string.team_score_label);
        }
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(PREF_LIMIT_TIME, Globals.getInstance().mTimeLimit);
        editor.putInt(PREF_LIMIT_SCORE, Globals.getInstance().mScoreLimit);
        editor.putInt(PREF_LIMIT_LIVES, Globals.getInstance().mLivesLimit);
        editor.apply();
        if (roundInProgress && mHasLivesLimit && mEliminationCount == 0)
            finishOutOfLives();
    }

    private void setReady() { setReady(true); }

    private void setReady(boolean sendPlayerLeft) {
        if (mReady) {
            if (mUseNetwork) {
                mServerIPTV.setVisibility(View.VISIBLE);
                mServerIPTV.setText(R.string.server_status_searching);
            }
            mTeamMinusButton.setVisibility(View.INVISIBLE);
            mTeamPlusButton.setVisibility(View.INVISIBLE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mGameLimitButton.setVisibility(View.GONE);
        } else {
            Globals.getInstance().mOnlyServerSettings = false;
            mFiringModeButton.setVisibility(View.VISIBLE);
            mStartGameButton.setVisibility(View.VISIBLE);
            mPlayerSettingsButton.setVisibility(View.VISIBLE);
            Globals.getInstance().mUseGPS = false;
            if (sendPlayerLeft && mTcpClient != null) {
                mTcpClient.leaveServer();
            }
            if (!mIsServer) {
                if (mUDPListenerService != null)
                    mUDPListenerService.stopListen();
                if (mTcpClient != null)
                    mTcpClient.stopTcpClient();
            }
            mNetworkStatusIV.setVisibility(View.GONE);
            mNetworkPlayerCountTV.setVisibility(View.GONE);
            mTeamMinusButton.setVisibility(View.VISIBLE);
            mTeamPlusButton.setVisibility(View.VISIBLE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mServerIPTV.setVisibility(View.GONE);
            mGameLimitButton.setVisibility(View.VISIBLE);
            setNetworkMenu(NETWORK_TYPE_ENABLED);
            mPlayerDataButton.setVisibility(View.GONE);
        }
        Intent intent = new Intent(NetMsg.NETMSG_GPSSETTING);
        sendBroadcast(intent);
    }

    private void showConnectLayout() {
        RelativeLayout playLayout = findViewById(R.id.play_layout);
        RelativeLayout connectLayout = findViewById(R.id.connect_layout);
        playLayout.setVisibility(View.GONE);
        connectLayout.setVisibility(View.VISIBLE);
        if (mDeviceAddress != null && !mDeviceAddress.isEmpty()) {
            mReconnectButton.setVisibility(View.VISIBLE);
        } else {
            mReconnectButton.setVisibility(View.GONE);
        }
    }

    private void initBatteryQueue() {
        if (mBatteryQueue == null)
            mBatteryQueue = new LinkedList<>();
        else
            mBatteryQueue.clear();
        mBatteryQueue.add((byte)16);
        mBatteryCount = 1;
        mBatteryTotal = 16;
    }

    private void resetVitalStatBars() {
        cancelShieldRegeneration();

        mHealth = Globals.getInstance().mFullHealth;
        mShield = Globals.getInstance().mFullShields;
        mHealthBar.setMax(mHealth);
        mShieldBar.setMax(mShield);
        updateVitalStatBars();
    }

    private void updateVitalStatBars() {
        mHealthBar.setProgress(mHealth);
        mShieldBar.setProgress(mShield);
    }

    private void cancelShieldRegeneration() {
        if (mShieldTimer != null) {
            mShieldTimer.cancel();
            mShieldTimer = null;
        }
    }

    private static void hideFeedbackView(View view, int visibility) {
        if (view != null) {
            view.clearAnimation();
            view.setVisibility(visibility);
        }
    }

    private void clearIncomingHitFeedback() {
        if (mIncomingHitCleanup != null) {
            mCombatFeedbackHandler.removeCallbacks(mIncomingHitCleanup);
            mIncomingHitCleanup = null;
        }
        if (mHitAnimation != null) {
            mHitAnimation.stop();
            mHitAnimation = null;
        }
        hideFeedbackView(mHitIV, View.GONE);
        // This label is reused for the killer during respawn. Retire both the
        // old fade and its cleanup before the respawn screen takes ownership.
        hideFeedbackView(mEliminatedByTV, View.INVISIBLE);
    }

    private void clearHitConfirmation() {
        if (mHitPlayerAnimation != null) {
            mHitPlayerAnimation.stop();
            mHitPlayerAnimation = null;
        }
        hideFeedbackView(mHitPlayerIV, View.GONE);
        hideFeedbackView(mHitPlayerNameTV, View.GONE);
    }

    private void clearCombatFeedback() {
        // Only transient combat UI work uses this handler. Never let an old
        // round's delayed cleanup hide feedback in the next round.
        mCombatFeedbackHandler.removeCallbacksAndMessages(null);
        mScoreFeedbackCleanup = null;
        clearIncomingHitFeedback();
        clearHitConfirmation();
        hideFeedbackView(mShotsFiredIV, View.GONE);
        hideFeedbackView(mScoreIncreaseIV, View.GONE);
        hideFeedbackView(mScoreIncreasePlayerNameTV, View.GONE);
    }

    private void startShieldRegeneration(long delayMilliseconds) {
        cancelShieldRegeneration();
        if (mShield >= Globals.getInstance().mFullShields)
            return;

        mShieldTimer = new CountDownTimer(delayMilliseconds, delayMilliseconds) {
            @Override
            public void onTick(long millisUntilFinished) {
                // Regeneration is applied when the timer completes.
            }

            @Override
            public void onFinish() {
                if (mShieldTimer != this)
                    return;
                mShieldTimer = null;
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_RUNNING)
                    return;

                mShield = Math.min(Globals.getInstance().mFullShields, mShield + SHIELD_REGEN_TICK_AMOUNT);
                updateVitalStatBars();
                if (mShield < Globals.getInstance().mFullShields)
                    startShieldRegeneration(SHIELD_REGEN_TICK_MILLISECONDS);
            }
        };
        mShieldTimer.start();
    }

    private boolean survivesDamage(int damage) {
        return mHealth + mShield + damage > 0;
    }

    private void takeDamage(int damage) {
        int shieldDamage = Math.min(mShield, Math.max(0, -damage));
        mShield -= shieldDamage;
        mHealth = Math.max(0, mHealth + damage + shieldDamage);
        updateVitalStatBars();
        startShieldRegeneration(SHIELD_REGEN_DELAY_MILLISECONDS);
    }

    /**
     * Hit packet sequence numbers belong to a physical blaster session, not a
     * game round. The small shot IDs repeat, so state retained from a completed
     * round must not suppress a real hit in the next one.
     */
    private void resetRoundTelemetryState() {
        mLastHitData1.playerID = Globals.INVALID_PLAYER_ID;
        mLastHitData1.shotID = 0;
        mLastHitData2.playerID = Globals.INVALID_PLAYER_ID;
        mLastHitData2.shotID = 0;
        mLastHitMessage = 0;
        mLastShotFired = 0;
        mEmptyTriggerCount = 0;
        mHitsTaken = 0;
        if (mHitsTakenTV != null)
            mHitsTakenTV.setText("0");
    }

    private void startGame() { startGame(null); }

    private void startGame(Intent start) {
        if (mUseNetwork && !networkServicesReady()) {
            Log.w(TAG, "Ignoring game start before network services are ready");
            return;
        }
        final boolean peerGame = mUseNetwork && !isDedicatedServerConnection();
        final String peerRoundToken = start == null ? null
                : start.getStringExtra(NetMsg.INTENT_ROUND_TOKEN);
        if (peerGame && !TcpServer.isValidRoundToken(peerRoundToken)) {
            // TCP start announcements carry the nonce used to authenticate peer
            // UDP ENDGAME packets. Never begin a peer round that could accept an
            // old, unscoped datagram.
            Log.w(TAG, "Ignoring peer game start without a valid round token");
            return;
        }
        mHasSynchronizedStart = start != null && start.hasExtra(NetMsg.INTENT_START_AT);
        mSynchronizedStartAt = mHasSynchronizedStart ? start.getLongExtra(NetMsg.INTENT_START_AT, 0) : 0;
        mSynchronizedEndAt = mHasSynchronizedStart ? start.getLongExtra(NetMsg.INTENT_END_AT, 0) : 0;
        if (mHasSynchronizedStart && mSynchronizedEndAt > 0
                && mSynchronizedEndAt <= SystemClock.elapsedRealtime()) {
            endGame();
            return;
        }
        resetRoundTelemetryState();
        clearCombatFeedback();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // hide nav bar
                        //| View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                        | View.SYSTEM_UI_FLAG_IMMERSIVE);
        mScore = 0;
        mScoreTV.setText("0");
        mTeamScore = 0;
        mTeamScoreTV.setText("0");
        if (mHasLivesLimit)
            mEliminationCount = mLives;
        else
            mEliminationCount = 0;
        mEliminationCountTV.setText(getString(R.string.integer,mEliminationCount));
        resetVitalStatBars();
        mEliminatedTV.setText(R.string.starting_game_label);
        mStartGameButton.setVisibility(View.GONE);
        mTeamMinusButton.setVisibility(View.INVISIBLE);
        mTeamPlusButton.setVisibility(View.INVISIBLE);
        mGameLimitButton.setVisibility(View.GONE);
        mPlayerSettingsButton.setVisibility(View.GONE);
        if (mUseNetwork) {
            displayInGameNetworkingOptions();
            mEndNetworkGameButton.setVisibility(View.VISIBLE);
            // Install the UI-side token immediately before changing the
            // listener's round. This keeps the two checks adjacent while
            // still allowing the first accepted current-round event through.
            mActivePeerRoundToken = peerGame ? peerRoundToken : null;
            mUDPListenerService.startGame(peerGame, peerRoundToken);
            if (!mTcpClient.isDedicatedServer())
                mTcpClient.stopTcpClient();
        } else {
            mActivePeerRoundToken = null;
            displayAllNetworkingOptions(false);
            mEndGameButton.setVisibility(View.VISIBLE);
        }
        mUseNetworkingButton.setVisibility(View.INVISIBLE);
        mFiringModeButton.setVisibility(View.INVISIBLE);
        mStartGameTimer = true;
        setShotMode(mCurrentShotMode); // make sure that the shot mode is correctly set at the start of each game
        startSpawn("");
        if (mHasSynchronizedStart && mSynchronizedEndAt > 0 && !mGameTimerRunning
                && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
            startGameCountdownMillis(Math.max(0, mSynchronizedEndAt - SystemClock.elapsedRealtime()));
    }

    private void confirmEndGame() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setTitle(R.string.end_game_dialog_title);
        builder.setMessage(R.string.end_game_dialog_message);

        builder.setPositiveButton(R.string.yes, (dialog, which) -> {
            if (mUseNetwork) {
                if (mTcpClient != null && mTcpClient.isDedicatedServer())
                    mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ENDGAME);
                else if (mUDPListenerService != null)
                    mUDPListenerService.endGame();
            }
            endGame();
        });

        builder.setNegativeButton(R.string.no, (dialog, which) -> dialog.dismiss());

        AlertDialog alert = builder.create();
        alert.show();
    }

    private void endGame() {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        clearCombatFeedback();
        resetTeamRespawnQrState();
        // A peer host owns a TCP listener only while it is serving this lobby or
        // round. Ending locally must retire that listener too; otherwise the
        // invisible listener can block a later attempt to host a new game.
        boolean ownsPeerHost = mIsServer || mPeerHostCreationPending || mPeerUdpServerStarting;
        if (mIsServer && mTcpServer != null)
            mTcpServer.clearScheduledStart();
        mHasSynchronizedStart = false;
        mActivePeerRoundToken = null;
        Globals.getInstance().mOnlyServerSettings = false;
        mFiringModeButton.setVisibility(View.VISIBLE);
        mStartGameButton.setVisibility(View.VISIBLE);
        mPlayerSettingsButton.setVisibility(View.VISIBLE);
        if (mUseNetwork) {
            if (mUDPListenerService != null)
                mUDPListenerService.stopListen();
            if (ownsPeerHost && mTcpServer != null)
                mTcpServer.cancelServer();
            if (mTcpClient != null && mTcpClient.isDedicatedServer())
                mTcpClient.stopTcpClient();
            mPeerHostCreationPending = false;
            mPeerUdpServerStarting = false;
            mReady = false;
            mIsServer = false;
            setReady(false);
        }
        hideWeaponDisconnect();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (mSpawnTimer != null) {
            mSpawnTimer.cancel();
            mSpawnTimer = null;
        }
        if (mReloadTimer != null) {
            mReloadTimer.cancel();
            mReloadTimer = null;
        }
        mGameTimerRunning = false;
        if (mGameCountdownTimer != null) {
            mGameCountdownTimer.cancel();
            mGameCountdownTimer = null;
        }
        cancelShieldRegeneration();
        mStartGameButton.setVisibility(View.VISIBLE);
        mPlayerSettingsButton.setVisibility(View.VISIBLE);
        mTeamMinusButton.setVisibility(View.VISIBLE);
        mTeamPlusButton.setVisibility(View.VISIBLE);
        mEndGameButton.setVisibility(View.GONE);
        mEndNetworkGameButton.setVisibility(View.GONE);
        mHitIV.setVisibility(View.GONE);
        mEliminatedTV.setVisibility(View.INVISIBLE);
        mEliminatedByTV.setVisibility(View.INVISIBLE);
        mSpawnInTV.setVisibility(View.GONE);
        displayAllNetworkingOptions(mUseNetwork);
        mUseNetworkingButton.setVisibility(View.VISIBLE);
        mHitPlayerIV.setVisibility(View.GONE);
        mHitPlayerNameTV.setVisibility(View.GONE);
        mShotsFiredIV.setVisibility(View.GONE);
        // The weapon still receives the disabled-state command after a round,
        // but that command is not a reload the player is waiting to complete.
        // Do not leave its indeterminate progress indicator running on the
        // finished-round screen.
        startReload(RELOADING_STATE_ELIMINATED);
        hideReloadProgress();
        mGameTimer.stop();
        mUseNetworkingButton.setVisibility(View.VISIBLE);
        mFiringModeButton.setVisibility(View.VISIBLE);
        mGameLimitButton.setVisibility(View.VISIBLE);
        setNetworkMenu(NETWORK_TYPE_ENABLED);
    }

    private void finishOutOfLives() {
        if (mUseNetwork) {
            if (isDedicatedServerConnection())
                mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_LEAVE);
            else if (mUDPListenerService != null)
                mUDPListenerService.announcePeerLeave();
        }
        Toast.makeText(getApplicationContext(), getString(R.string.dialog_out_of_lives), Toast.LENGTH_LONG).show();
        endGame();
    }

    /* Sending to Command a packet with 10 00 80 00 PLAYER# then 15 more 00's sets the player ID.
       This works for initially setting the ID, but does not work consistently afterwards. You can
       consistently change the player ID by doing a reload procedure, but doing a reload without
       having previously set a team does not seem to work. We do a reload cycle before starting the
       game each time to ensure that the player starts on the right team and with full ammo. */
    private void setTeam() {
        if (Globals.getInstance().mPlayerID < 1 || Globals.getInstance().mPlayerID > Globals.MAX_PLAYER_ID) {
            Log.e(TAG, "Invalid player ID!");
            return;
        }
        if (!TEST_NETWORK && mBluetoothLeService != null && mCommandCharacteristic != null) {
            Log.d(TAG, "setting player ID to " + Globals.getInstance().mPlayerID);
            byte[] command = new byte[20];
            command[0] = mCommandID;
            mCommandID += COMMAND_ID_INCREMENT;
            command[2] = (byte) 0x80;
            command[4] = Globals.getInstance().mPlayerID;
            mCommandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            mBluetoothLeService.writeCharacteristic(mCommandCharacteristic, command);
        } else if (!TEST_NETWORK) {
            // Keep the selected/network-assigned ID in the UI and preferences
            // until a disconnected blaster is available to receive it.
            Log.d(TAG, "Saving player ID until the blaster command is available");
        }
        if (sharedPreferences != null) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putInt(PREF_PLAYER_ID, Globals.getInstance().mPlayerID);
            editor.putInt(PREF_GAME_MODE, Globals.getInstance().mGameMode);
            editor.apply();
        }
        displayCurrentTeam();
    }

    private void applyServerAssignedPlayerID(byte assignedPlayerID) {
        if (assignedPlayerID <= 0 || !Globals.isValidPlayerID(assignedPlayerID)
                || Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
            return;
        if (Globals.getInstance().mPlayerID == assignedPlayerID)
            return;
        Globals.getInstance().mPlayerID = assignedPlayerID;
        setTeam();
        Toast.makeText(getApplicationContext(), getString(R.string.player_id_reassigned,
                assignedPlayerID), Toast.LENGTH_SHORT).show();
    }

    private void displayCurrentTeam() {
        if (Globals.getInstance().mPlayerID == 0) {
            mTeamTV.setText(R.string.no_team);
        } else if (mUseNetwork && Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
            mNetworkTeam = 1;
            int x = ((Globals.MAX_PLAYER_ID + 1) / 2);
            if (Globals.getInstance().mGameMode == Globals.GAME_MODE_2TEAMS) {
                if (Globals.getInstance().mPlayerID > x)
                    mNetworkTeam = 2;
            } else {
                x = ((Globals.MAX_PLAYER_ID + 1) / 4);
                if (Globals.getInstance().mPlayerID > 3 * x)
                    mNetworkTeam = 4;
                else if (Globals.getInstance().mPlayerID > 2 * x)
                    mNetworkTeam = 3;
                else if (Globals.getInstance().mPlayerID > x)
                    mNetworkTeam = 2;
            }
            int player = (Globals.getInstance().mPlayerID - (byte)(x * (mNetworkTeam - 1)));
            mTeamLabelTV.setText(getString(R.string.team_number_label, mNetworkTeam));
            mTeamTV.setText(getString(R.string.network_team, player));
            mTeamScoreLabelTV.setVisibility(View.VISIBLE);
            mTeamScoreTV.setVisibility(View.VISIBLE);
        } else {
            mTeamLabelTV.setText(R.string.team_label);
            String teamStr = "" + Globals.getInstance().mPlayerID;
            mTeamTV.setText(teamStr);
            mTeamScoreLabelTV.setVisibility(View.INVISIBLE);
            mTeamScoreTV.setVisibility(View.INVISIBLE);
        }
    }

    private void startReload() { startReload(RELOADING_STATE_STARTED); }

    private void hideReloadProgress() {
        if (mReloadBar != null)
            mReloadBar.setVisibility(View.GONE);
        if (mShotsRemainingTV != null)
            mShotsRemainingTV.setVisibility(View.VISIBLE);
    }

    /* Initial reload command that tells the tagger not to shoot anymore (or maybe it just sets the
       remaining shot counter to 0). It also sets the tagger in what I guess is status 0x03 instead
       of the usual 0x02. The command format is F0 00 02 00 PLAYER_ID and then 0 filled to the end. */
    private void startReload(int reloadStatus) {
        boolean disablingWeapon = reloadStatus == RELOADING_STATE_ELIMINATED;
        if (!disablingWeapon && mReloading != RELOADING_STATE_NONE)
            return;
        // Elimination must supersede even a refill whose acknowledgement is in flight.
        if (disablingWeapon) {
            if (mReloadTimer != null) {
                mReloadTimer.cancel();
                mReloadTimer = null;
            }
            mPendingReloadCommand = null;
            mReloading = RELOADING_STATE_ELIMINATED;
        }
        if (mCommandCharacteristic == null || mBluetoothLeService == null)
            return;
        mReloading = reloadStatus;
        mShotsRemainingTV.setVisibility(View.INVISIBLE);
        mReloadBar.setVisibility(View.VISIBLE);
        byte[] command = new byte[20];
        command[0] = mCommandID;
        mCommandID += COMMAND_ID_INCREMENT;
        command[2] = (byte)0x02;
        command[4] = Globals.getInstance().mPlayerID;
        //command[5] = WEAPON_PROFILE; // changing profiles during the first stage of reload doesn't really do anything since the blaster can't shoot in this state anyway
        mCommandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        mPendingReloadCommand = command.clone();
        mBluetoothLeService.writeCharacteristic(mCommandCharacteristic, command);
    }

    /* Second stage of the reload commands which tells the tagger how many shots to load and allows
       it to shoot again. Command format is 00 00 04 00 PLAYER_ID 00 SHOT_COUNT and then 0 filled. */
    private void finishReload() {
        if ((mReloading != RELOADING_STATE_STARTED && mReloading != RELOADING_STATE_ELIMINATED) || mCommandCharacteristic == null || mBluetoothLeService == null || Globals.getInstance().mGameState != Globals.GAME_STATE_RUNNING)
            return;
        mReloading = RELOADING_STATE_FINISHING;
        mEmptyTriggerCount = 0;
        Log.d(TAG, "Finishing reload");
        byte[] command = new byte[20];
        command[0] = mCommandID;
        mCommandID += COMMAND_ID_INCREMENT;
        command[2] = (byte)0x04;
        command[4] = Globals.getInstance().mPlayerID;
        command[5] = WEAPON_PROFILE;
        command[6] = Globals.getInstance().mFullReload;
        mCommandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        mPendingReloadCommand = command.clone();
        mBluetoothLeService.writeCharacteristic(mCommandCharacteristic, command);
    }

    private void setShotsRemaining(byte shotsRemaining) {
        String shotsRemainingStr = "" + (shotsRemaining & 0xff);
        mShotsRemainingTV.setText(shotsRemainingStr);
        mLastShotCount = shotsRemaining;
    }

    private boolean isTeamQrRespawnEnabled() {
        Globals globals = Globals.getInstance();
        if (!mUseNetwork || mStartGameTimer
                || (globals.mGameMode != Globals.GAME_MODE_2TEAMS
                && globals.mGameMode != Globals.GAME_MODE_4TEAMS))
            return false;
        int team = globals.calcNetworkTeam(globals.mPlayerID);
        return team >= 1 && team <= Globals.GAME_MODE_4TEAMS;
    }

    private boolean isTeamQrRespawnActive() {
        return mRespawnQrTeam >= 1 && Globals.getInstance().mGameState == Globals.GAME_STATE_ELIMINATED
                && isTeamQrRespawnEnabled();
    }

    private void updateSpawnCountdown(long millisUntilFinished, boolean synchronizedCountdown,
                                      boolean teamQrRespawn) {
        if (mSpawnInTV == null)
            return;
        long seconds = Math.max(0, (millisUntilFinished + 999) / 1000);
        if (synchronizedCountdown) {
            mSpawnInTV.setText(getString(R.string.game_start_countdown, seconds));
        } else if (teamQrRespawn) {
            mSpawnInTV.setText(getString(R.string.respawn_qr_wait_label, seconds / 60,
                    seconds % 60, mRespawnQrTeam));
        } else {
            mSpawnInTV.setText(getString(R.string.spawn_in_label, seconds));
        }
    }

    private final BarcodeCallback mRespawnQrBarcodeCallback = new BarcodeCallback() {
        @Override
        public void barcodeResult(BarcodeResult result) {
            if (!mRespawnQrScannerActive || result == null)
                return;
            handleRespawnQrCode(result.getText());
        }

        @Override
        public void possibleResultPoints(List<ResultPoint> resultPoints) {
            // The embedded view draws its own finder feedback.
        }
    };

    private void scheduleRespawnQrDecoder() {
        if (!mRespawnQrScannerActive || !mActivityResumed || mRespawnQrScanner == null)
            return;
        mRespawnQrScanner.postDelayed(() -> {
            if (mRespawnQrScannerActive && mActivityResumed && !isFinishing() && !isDestroyed()) {
                try {
                    mRespawnQrScanner.decodeSingle(mRespawnQrBarcodeCallback);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Unable to start respawn QR decoder", e);
                    hideRespawnQrScanner();
                    Toast.makeText(getApplicationContext(), R.string.error_camera_unavailable,
                            Toast.LENGTH_SHORT).show();
                }
            }
        }, 300);
    }

    private void resumeRespawnQrScanner() {
        if (!mRespawnQrScannerActive || !mActivityResumed || mRespawnQrScanner == null
                || isFinishing() || isDestroyed())
            return;
        try {
            mRespawnQrScanner.resume();
            scheduleRespawnQrDecoder();
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to resume respawn QR scanner", e);
            hideRespawnQrScanner();
            Toast.makeText(getApplicationContext(), R.string.error_camera_unavailable,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openRespawnQrScanner() {
        if (!isTeamQrRespawnActive() || mRespawnQrRequestPending || isFinishing() || isDestroyed())
            return;
        if (!mActivityResumed) {
            mRespawnQrOpenWhenResumed = true;
            return;
        }
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(getApplicationContext(), R.string.error_camera_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            mRespawnQrOpenWhenResumed = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA},
                    REQUEST_CODE_RESPAWN_CAMERA_PERMISSION);
            return;
        }
        if (mRespawnQrScannerOverlay == null || mRespawnQrScanner == null)
            return;
        if (mRespawnQrScannerPrompt != null)
            mRespawnQrScannerPrompt.setText(getString(R.string.respawn_qr_scanner_prompt,
                    mRespawnQrTeam));
        mRespawnQrOpenWhenResumed = false;
        mRespawnQrScannerOverlay.setVisibility(View.VISIBLE);
        mRespawnQrScannerActive = true;
        resumeRespawnQrScanner();
    }

    private void hideRespawnQrScanner() {
        mRespawnQrScannerActive = false;
        mRespawnQrOpenWhenResumed = false;
        if (mRespawnQrScanner != null) {
            try {
                mRespawnQrScanner.pause();
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to pause respawn QR scanner", e);
            }
        }
        if (mRespawnQrScannerOverlay != null)
            mRespawnQrScannerOverlay.setVisibility(View.GONE);
    }

    private void resetTeamRespawnQrState() {
        hideRespawnQrScanner();
        mRespawnQrTeam = 0;
        mRespawnQrRequestPending = false;
        if (mRespawnQrScanButton != null) {
            mRespawnQrScanButton.setEnabled(true);
            mRespawnQrScanButton.setVisibility(View.GONE);
        }
    }

    private void handleRespawnQrCode(String contents) {
        if (!isTeamQrRespawnActive()) {
            hideRespawnQrScanner();
            return;
        }
        int checkpointTeam = Globals.getRespawnTeamFromQrCode(contents);
        if (checkpointTeam == 0) {
            Toast.makeText(getApplicationContext(), R.string.respawn_qr_invalid, Toast.LENGTH_SHORT).show();
            scheduleRespawnQrDecoder();
            return;
        }
        if (checkpointTeam != mRespawnQrTeam) {
            Toast.makeText(getApplicationContext(), R.string.respawn_qr_wrong_team,
                    Toast.LENGTH_SHORT).show();
            scheduleRespawnQrDecoder();
            return;
        }

        hideRespawnQrScanner();
        if (isDedicatedServerConnection()) {
            mRespawnQrRequestPending = true;
            if (mRespawnQrScanButton != null)
                mRespawnQrScanButton.setEnabled(false);
            mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG
                    + NetMsg.NETMSG_RESPAWNREQUEST, true);
            Toast.makeText(getApplicationContext(), R.string.respawn_qr_request_sent,
                    Toast.LENGTH_SHORT).show();
        } else {
            finishSpawn(false);
        }
    }

    private void startSpawn(String eliminatedBy) {
        clearIncomingHitFeedback();
        if (mSpawnTimer != null) {
            mSpawnTimer.cancel();
            mSpawnTimer = null;
        }
        if (mReloadTimer != null) {
            mReloadTimer.cancel();
            mReloadTimer = null;
        }
        cancelShieldRegeneration();
        Globals.getInstance().mGameState = Globals.GAME_STATE_ELIMINATED;
        resetTeamRespawnQrState();
        startReload(RELOADING_STATE_ELIMINATED);
        mHitIV.setVisibility(View.GONE);
        mEliminatedTV.setVisibility(View.VISIBLE);
        mEliminatedByTV.setText(eliminatedBy);
        mEliminatedByTV.setVisibility(View.VISIBLE);
        final boolean synchronizedCountdown = mStartGameTimer && mHasSynchronizedStart;
        final boolean teamQrRespawn = !synchronizedCountdown && isTeamQrRespawnEnabled();
        if (teamQrRespawn)
            mRespawnQrTeam = Globals.getInstance().calcNetworkTeam(Globals.getInstance().mPlayerID);
        final long spawnDelay = synchronizedCountdown
                ? Math.max(0, mSynchronizedStartAt - SystemClock.elapsedRealtime())
                : teamQrRespawn ? Globals.TEAM_QR_RESPAWN_WAIT_SECONDS * 1000
                : Globals.getInstance().mRespawnTime * 1000;
        final boolean notifyDedicatedHost = !mStartGameTimer && isDedicatedServerConnection();
        updateSpawnCountdown(spawnDelay, synchronizedCountdown, teamQrRespawn);
        mSpawnInTV.setVisibility(View.VISIBLE);
        if (teamQrRespawn && mRespawnQrScanButton != null) {
            mRespawnQrScanButton.setText(getString(R.string.respawn_qr_scan_button, mRespawnQrTeam));
            mRespawnQrScanButton.setVisibility(View.VISIBLE);
        }
        mSpawnTimer = new CountDownTimer(spawnDelay, 999) {

            public void onTick(long millisUntilFinished) {
                if (mSpawnTimer != this || Globals.getInstance().mGameState != Globals.GAME_STATE_ELIMINATED)
                    return;
                updateSpawnCountdown(millisUntilFinished, synchronizedCountdown, teamQrRespawn);
                playSound(R.raw.beep, getApplicationContext());
            }

            public void onFinish() {
                if (mSpawnTimer != this || Globals.getInstance().mGameState != Globals.GAME_STATE_ELIMINATED)
                    return;
                mSpawnTimer = null;
                finishSpawn(notifyDedicatedHost);
            }
        };
        mSpawnTimer.start();
        if (teamQrRespawn)
            openRespawnQrScanner();
    }

    private void finishSpawn(boolean notifyDedicatedHost) {
        if (Globals.getInstance().mGameState != Globals.GAME_STATE_ELIMINATED)
            return;
        if (mSpawnTimer != null) {
            mSpawnTimer.cancel();
            mSpawnTimer = null;
        }
        final boolean startingGame = mStartGameTimer;
        Log.d(TAG, "spawned!");
        resetTeamRespawnQrState();
        mEliminatedTV.setVisibility(View.INVISIBLE);
        mEliminatedTV.setText(R.string.eliminated_label);
        mEliminatedByTV.setVisibility(View.INVISIBLE);
        mSpawnInTV.setVisibility(View.GONE);
        resetVitalStatBars();
        Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
        playSound(R.raw.spawn, getApplicationContext());
        finishReload();
        if (notifyDedicatedHost && !startingGame && isDedicatedServerConnection()) {
            mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG
                    + NetMsg.NETMSG_RESPAWNCOMPLETE, true);
        }
        if (startingGame) {
            mStartGameTimer = false;
            if (mHasSynchronizedStart ? mSynchronizedEndAt > 0
                    : (Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0) {
                if (!mGameTimerRunning) {
                    if (mHasSynchronizedStart)
                        startGameCountdownMillis(Math.max(0, mSynchronizedEndAt - SystemClock.elapsedRealtime()));
                    else
                        startGameCountdown();
                }
            } else {
                mGameTimer.setBase(mHasSynchronizedStart ? mSynchronizedStartAt : SystemClock.elapsedRealtime());
                mGameTimer.start();
            }
        }
    }

    private void startGameCountdown() {
        startGameCountdown(Globals.getInstance().mTimeLimit * 60L);
    }

    private void startGameCountdown(long timeInSeconds) {
        startGameCountdownMillis(timeInSeconds * 1000);
    }

    private void startGameCountdownMillis(long timeInMillis) {
        if (mGameCountdownTimer != null)
            mGameCountdownTimer.cancel();
        mGameTimerRunning = true;
        mGameCountdownTimer = new CountDownTimer(timeInMillis, 1000) {

            public void onTick(long millisUntilFinished) {
                if (mGameCountdownTimer != this || Globals.getInstance().mGameState == Globals.GAME_STATE_NONE)
                    return;
                String display = ""+String.format(Locale.getDefault(),"%02d:%02d",
                        TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished),
                        TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) - TimeUnit.MINUTES.toSeconds(
                                TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished)));
                mGameCountDownTV.setText(display);
            }

            public void onFinish() {
                if (mGameCountdownTimer != this || Globals.getInstance().mGameState == Globals.GAME_STATE_NONE)
                    return;
                mGameCountdownTimer = null;
                mGameTimerRunning = false;
                finishTimedGame();
            }
        };
        mGameCountdownTimer.start();
    }

    private void finishTimedGame() {
        Log.d(TAG, "Game time ended!");
        Toast.makeText(getApplicationContext(), getString(R.string.dialog_game_time_expired), Toast.LENGTH_SHORT).show();
        playSound(R.raw.eliminated, getApplicationContext());
        if (isDedicatedServerConnection())
            mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ENDGAME);
        else if (mUseNetwork)
            // A peer game's timers are local.  Notify the other phones before
            // stopping this listener, otherwise a peer host looks like it was
            // cancelled and its teammates can continue the finished round.
            endUDPGame();
        // The server reply can be delayed or lost. Stop spawning and shooting locally now.
        endGame();
    }

    private int resolveShotMode(int shotMode) {
        Globals globals = Globals.getInstance();
        switch (shotMode) {
            case Globals.SHOT_MODE_SINGLE:
                if (globals.mAllowSingleShotMode) return Globals.SHOT_MODE_SINGLE;
                if (globals.mAllowBurst3ShotMode) return Globals.SHOT_MODE_BURST;
                break;
            case Globals.SHOT_MODE_BURST:
                if (globals.mAllowBurst3ShotMode) return Globals.SHOT_MODE_BURST;
                if (globals.mAllowAutoShotMode) return Globals.SHOT_MODE_FULL_AUTO;
                break;
            case Globals.SHOT_MODE_FULL_AUTO:
                if (globals.mAllowAutoShotMode) return Globals.SHOT_MODE_FULL_AUTO;
                break;
        }
        if (globals.mAllowSingleShotMode) return Globals.SHOT_MODE_SINGLE;
        if (globals.mAllowBurst3ShotMode) return Globals.SHOT_MODE_BURST;
        return Globals.SHOT_MODE_FULL_AUTO;
    }

    // Config 00 00 09 xx yy ff c8 ff ff 80 01 34 - xx is the number of shots and if you set yy to 01 for full auto for xx shots or 00 for single shot mode, increasing yy decreases RoF
    // setting 03 03 for shots and RoF gives a good 3 shot burst, 03 01 is so fast that you feel 1 recoil for 3 shots
    private void setShotMode(int shotMode) {
        shotMode = resolveShotMode(shotMode);
        mCurrentShotMode = shotMode;
        if (shotMode == Globals.SHOT_MODE_SINGLE)
            mShotModeTV.setText(R.string.shot_mode_single);
        else if (shotMode == Globals.SHOT_MODE_BURST)
            mShotModeTV.setText(R.string.shot_mode_burst3);
        else
            mShotModeTV.setText(R.string.shot_mode_auto);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(PREF_SHOT_MODE, mCurrentShotMode);
        editor.apply();
        // Keep the permitted selection even while disconnected so reconnects cannot restore a banned mode.
        if (mConfigCharacteristic == null || mBluetoothLeService == null)
            return;
        byte[] config = new byte[20];
        config[0]  = WEAPON_PROFILE;
        config[2]  = (byte)0x09;
        config[7]  = (byte)0xFF;
        config[8]  = (byte)0xFF;
        config[9]  = (byte)0x80; // Recoil strength
        config[10] = (byte)0x02;
        config[11] = (byte)0x34;
        if (shotMode == Globals.SHOT_MODE_SINGLE) {
            config[3] = (byte)0xFE;
            config[4] = (byte)0x00;
        } else if (shotMode == Globals.SHOT_MODE_BURST) {
            config[3] = (byte)0x03;
            config[4] = (byte)0x03;
            if (mBlasterType == BLASTER_TYPE_RIFLE)
                config[9] = (byte)0x78; // Reduce recoil strength on the rifle to make 3 shot mode recoil the correct number of times
        } else if (shotMode == Globals.SHOT_MODE_FULL_AUTO) {
            config[3] = (byte)0xFE;
            config[4] = (byte)0x01;
        }
        int firingMode = Globals.getInstance().mCurrentFiringMode;
        if (!Globals.isValidFiringMode(firingMode)) {
            Log.w(TAG, "Invalid firing mode " + firingMode + "; using the default mode");
            firingMode = Globals.FIRING_MODE_OUTDOOR_NO_CONE;
            Globals.getInstance().mCurrentFiringMode = firingMode;
        }
        switch (firingMode) {
            case Globals.FIRING_MODE_OUTDOOR_NO_CONE:
                config[5]  = (byte)0xFF;
                config[6]  = (byte)0x00;
                break;
            case Globals.FIRING_MODE_OUTDOOR_WITH_CONE:
                config[5]  = (byte)0xFF;
                config[6]  = (byte)0xC8;
                break;
            case Globals.FIRING_MODE_INDOOR_NO_CONE:
                config[5]  = (byte)0x19;
                config[6]  = (byte)0x00;
        }
        mConfigCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        mBluetoothLeService.writeCharacteristic(mConfigCharacteristic, config);
    }

    // Config 10 00 02 02 ff and 15 sets of 00 disables recoil
    // Config 10 00 02 03 ff and 15 sets of 00 enables recoil
    private void setRecoil(boolean enabled) {
        if (mConfigCharacteristic == null || mBluetoothLeService == null)
            return;
        byte[] config = new byte[20];
        config[0]  = (byte)0x10;
        config[2]  = (byte)0x02;
        config[4]  = (byte)0xFF;
        if (enabled) {
            config[3] = (byte)0x03;
            mRecoilModeTV.setText(R.string.recoil_enabled);
        } else {
            config[3] = (byte)0x02;
            mRecoilModeTV.setText(R.string.recoil_disabled);
        }
        mRecoilEnabled = enabled;
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PREF_RECOIL_ENABLED, mRecoilEnabled);
        editor.apply();
        mConfigCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        mBluetoothLeService.writeCharacteristic(mConfigCharacteristic, config);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mActivityResumed = true;
        ContextCompat.registerReceiver(this, mGattUpdateReceiver, makeGattUpdateIntentFilter(), ContextCompat.RECEIVER_NOT_EXPORTED);
        mGattReceiverRegistered = true;
        if (mBluetoothLeService != null && mDeviceAddress != null && !mDeviceAddress.isEmpty()) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mBluetoothReceiver, filter);
        mBluetoothReceiverRegistered = true;
        ContextCompat.registerReceiver(this, mUDPUpdateReceiver, makeUDPUpdateIntentFilter(), ContextCompat.RECEIVER_NOT_EXPORTED);
        mNetworkReceiverRegistered = true;
        setupUDPServiceConnection();
        setupTcpClientServiceConnection();
        setupTcpServerServiceConnection();
        startPeerUdpServerIfTcpReady();
        consumePendingServerEvent();
        if (mRespawnQrOpenWhenResumed) {
            mRespawnQrOpenWhenResumed = false;
            openRespawnQrScanner();
        } else {
            resumeRespawnQrScanner();
        }
    }

    @Override
    protected void onPause() {
        mActivityResumed = false;
        if (mRespawnQrScannerActive && mRespawnQrScanner != null) {
            try {
                mRespawnQrScanner.pause();
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to pause respawn QR scanner", e);
            }
        }
        if (mGattReceiverRegistered) {
            mGattReceiverRegistered = false;
            unregisterReceiver(mGattUpdateReceiver);
        }
        if (mBluetoothReceiverRegistered) {
            mBluetoothReceiverRegistered = false;
            unregisterReceiver(mBluetoothReceiver);
        }
        if (mNetworkReceiverRegistered) {
            mNetworkReceiverRegistered = false;
            unregisterReceiver(mUDPUpdateReceiver);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        clearCombatFeedback();
        resetTeamRespawnQrState();
        hideWeaponDisconnect();
        stopBLEScan();
        if (mSpawnTimer != null) {
            mSpawnTimer.cancel();
            mSpawnTimer = null;
        }
        if (mReloadTimer != null) {
            mReloadTimer.cancel();
            mReloadTimer = null;
        }
        if (mGameCountdownTimer != null) {
            mGameCountdownTimer.cancel();
            mGameCountdownTimer = null;
        }
        if (mConnectFailTimer != null) {
            mConnectFailTimer.cancel();
            mConnectFailTimer = null;
        }
        cancelShieldRegeneration();
        resetBluetoothServices();
        unbindUDPService();
        unbindTcpClientService();
        unbindTcpServerService();
        if (isFinishing())
            stopNetworkServices();
        super.onDestroy();
    }

    private void loadFragment() {
        if (mMapFragment == null) return;
        mMapFragment = getSupportFragmentManager().findFragmentById(R.id.map_fragment);
        if (mMapFragment != null)
            return;
        FragmentTransaction mFragmentTransc = mFragmentMgr.beginTransaction();
        RelativeLayout parentLayout = findViewById(R.id.play_layout);
        mFragmentTransc.add(parentLayout.getId(), mMapFragment);
        //mFragmentTransc.addToBackStack(null);
        mFragmentTransc.commit();
    }

    @Override
    public void onBackPressed()
    {
        if (mRespawnQrScannerActive) {
            hideRespawnQrScanner();
            return;
        }
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.map_fragment);
        if (f != null && mFragmentMgr.getBackStackEntryCount() > 0) {
            mFragmentMgr.popBackStack();
            return;
        }
        super.onBackPressed();
    }

    private void removeFragment() {
        Fragment mapFragment = getSupportFragmentManager().findFragmentById(R.id.map_fragment);
        if (mapFragment == null) return;
        FragmentTransaction fragmentTransaction = mFragmentMgr.beginTransaction();
        fragmentTransaction.remove(mapFragment);
        fragmentTransaction.commit();
    }

    private ScanCallback mLeScanCallback;

    @SuppressLint("MissingPermission") // Permission is checked before scanning begins.
    private ScanCallback createLEScanCallback() {
        return new ScanCallback() {
            private boolean isCurrentScan() {
                return mLeScanCallback == this && mScanning && !isFinishing() && !isDestroyed();
            }

            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                if (isCurrentScan() && result != null)
                    checkDeviceName(result.getDevice());
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                super.onBatchScanResults(results);
                if (!isCurrentScan() || results == null) return;
                for (ScanResult result : results) {
                    if (result != null && checkDeviceName(result.getDevice()))
                        return;
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                super.onScanFailed(errorCode);
                if (!isCurrentScan()) return;
                Log.w(TAG, "Bluetooth scan failed: " + errorCode);
                stopBLEScan();
                handleDisconnect();
            }

            private boolean checkDeviceName(BluetoothDevice device) {
                if (!isCurrentScan() || device == null) return false;
                final String deviceAddress;
                final String deviceName;
                try {
                    deviceAddress = device.getAddress();
                    deviceName = device.getName();
                } catch (SecurityException e) {
                    Log.w(TAG, "Bluetooth permission was revoked while scanning", e);
                    stopBLEScan();
                    return false;
                }
                boolean hasSavedDevice = mDeviceAddress != null && !mDeviceAddress.isEmpty();
                boolean isMatchingDevice = hasSavedDevice && mDeviceAddress.equals(deviceAddress);
                boolean isAutoDetectedDevice = !hasSavedDevice && deviceName != null && deviceName.startsWith("SRG1");
                if (isAutoDetectedDevice || isMatchingDevice) {
                    Log.d(TAG, "Connecting to " + deviceName + " '" + deviceAddress + "'");
                    TextView connectStatusTV = findViewById(R.id.connect_status_tv);
                    if (connectStatusTV != null) {
                        connectStatusTV.setText(R.string.connect_status_connecting);
                    }
                    mDeviceAddress = deviceAddress;
                    stopBLEScan();
                    setupBLEServiceConnection();
                    return true;
                }
                return false;
            }
        };
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_RESPAWN_CAMERA_PERMISSION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            mRespawnQrOpenWhenResumed = granted && isTeamQrRespawnActive();
            if (mRespawnQrOpenWhenResumed && mActivityResumed) {
                mRespawnQrOpenWhenResumed = false;
                openRespawnQrScanner();
            } else if (!granted) {
                Toast.makeText(this, R.string.error_camera_permission_required, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode != REQUEST_CODE_LOCATION_PERMISSIONS && requestCode != REQUEST_CODE_BLUETOOTH_PERMISSIONS)
            return;

        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            granted &= result == PackageManager.PERMISSION_GRANTED;
        }
        if (granted) {
            connectWeapon();
        } else {
            Toast.makeText(this, getString(R.string.error_location_permission_required), Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private final BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mBluetoothReceiverRegistered || isFinishing() || isDestroyed() || intent == null)
                return;
            final String action = intent.getAction();
            if (action == null)
                return;

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON)
                    connectWeapon();
            }
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.e(TAG, "onActivityResult " + requestCode);
        if (requestCode == REQUEST_QR_SCAN) {

            if (resultCode == RESULT_OK) {
                if (data == null) {
                    Log.w(TAG, "QR scanner returned RESULT_OK without result data");
                    return;
                }
                String scannedAddress = normalizeBluetoothAddress(data.getStringExtra("SCAN_RESULT"));
                if (scannedAddress.isEmpty()) {
                    Log.w(TAG, "QR scanner did not return a valid Bluetooth address");
                    Toast.makeText(getApplicationContext(), R.string.error_invalid_qr_address,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                // Keep the previous selection until a usable address has been validated.
                mDeviceAddress = scannedAddress;
                connectWeapon();
            }
            if(resultCode == RESULT_CANCELED){
                //handle cancel
                Log.e(TAG, "QR cancel");
            }
        } else if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK) {
            connectWeapon();
        }
    }

    private void connectWeapon() {
        if (isFinishing() || isDestroyed())
            return;
        if (TEST_NETWORK) {
            RelativeLayout connectLayout = findViewById(R.id.connect_layout);
            if (connectLayout != null) connectLayout.setVisibility(View.GONE);
            RelativeLayout playLayout = findViewById(R.id.play_layout);
            if (playLayout != null) playLayout.setVisibility(View.VISIBLE);
            return;
        }

        if (mScanning)
            return;

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean hasScanPermission = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean hasConnectPermission = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            if (!hasScanPermission || !hasConnectPermission) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
                        REQUEST_CODE_BLUETOOTH_PERMISSIONS);
                return;
            }
        // Location permission is required to scan for Bluetooth LE devices on Android 6 through 11.
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int hasLocationPermission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION);
            if (hasLocationPermission != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_CODE_LOCATION_PERMISSIONS);
                return;
            }
        }

        // Request to turn on Bluetooth if it's not turned on
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        // Checks if Bluetooth is supported on the device.
        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Bluetooth is off");
            Intent intentBtEnabled = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            // The REQUEST_ENABLE_BT constant passed to startActivityForResult() is a locally defined integer (which must be greater than 0), that the system passes back to you in your onActivityResult()
            // implementation as the requestCode parameter.
            startActivityForResult(intentBtEnabled, REQUEST_ENABLE_BT);
            return;
        }

        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
        // BluetoothAdapter through BluetoothManager.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            Log.e(TAG, "Failed to get Bluetooth service");
            return;
        }

        mBluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (mBluetoothLeScanner == null) {
            Log.w(TAG, "Bluetooth scanner unavailable");
            return;
        }
        Log.d(TAG, "starting to scan");
        // Give each scan its own callback identity. Android can deliver callbacks
        // from the old scan after stopScan() or after a new scan has started.
        mLeScanCallback = createLEScanCallback();
        mScanning = true;
        try {
            mBluetoothLeScanner.startScan(mLeScanCallback);
        } catch (SecurityException | IllegalStateException e) {
            Log.w(TAG, "Unable to start Bluetooth scan", e);
            stopBLEScan();
            return;
        }
        mConnectButton.setEnabled(false);
        mReconnectButton.setEnabled(false);
        mDedicatedServerButton.setEnabled(false);
        mQRConnectButton.setEnabled(false);
        TextView connectStatusTV = findViewById(R.id.connect_status_tv);
        if (connectStatusTV != null) {
            connectStatusTV.setText(R.string.connect_status_scanning);
        }
        startConnectFailTest();
    }

    @SuppressLint("MissingPermission") // Permission is checked before scanning begins.
    private void startConnectFailTest() {
        // Auto disconnect and quit searching if we fail to find a blaster
        mConnected = false;
        if (mConnectFailTimer != null)
            mConnectFailTimer.cancel();
        mConnectFailTimer = new CountDownTimer(CONNECTION_FAIL_TEST_INTERVAL_MILLISECONDS, CONNECTION_FAIL_TEST_INTERVAL_MILLISECONDS) {

            public void onTick(long millisUntilFinished) {
                // Do nothing
            }

            public void onFinish() {
                if (mConnectFailTimer != this)
                    return;
                mConnectFailTimer = null;
                if (!mConnected) {
                    Log.d(TAG, "Failed to find a weapon before timeout");
                    stopBLEScan();
                    handleDisconnect();
                }
            }
        };
        mConnectFailTimer.start();
    }

    @SuppressLint("MissingPermission") // Permission is checked before scanning begins.
    private void stopBLEScan() {
        ScanCallback callback = mLeScanCallback;
        BluetoothLeScanner scanner = mBluetoothLeScanner;
        // Invalidate first, including callbacks dispatched during scanner cleanup.
        mLeScanCallback = null;
        mBluetoothLeScanner = null;
        mScanning = false;
        try {
            if (scanner != null && callback != null)
                scanner.stopScan(callback);
        } catch (SecurityException | IllegalStateException e) {
            Log.w(TAG, "Unable to stop Bluetooth scan", e);
        }
    }

    private void resetBluetoothServices() {
        stopConnectionTest();
        mConnected = false;
        mCommunicating = false;
        if (mReloadTimer != null) {
            mReloadTimer.cancel();
            mReloadTimer = null;
        }
        mPendingReloadCommand = null;
        mReloading = RELOADING_STATE_ELIMINATED;
        mTelemetryCharacteristic = null;
        mCommandCharacteristic = null;
        mConfigCharacteristic = null;
        if (mBluetoothLeService != null)
            mBluetoothLeService.close();
        if (mBLEServiceBound) {
            try {
                unbindService(mBLEServiceConnection);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "BLE service was already unbound", e);
            }
        }
        mBLEServiceBound = false;
        mBLEServiceConnection = null;
        mBluetoothLeService = null;
    }

    private int getPlayerDamage(byte playerID) {
        Globals.getmPlayerSettingsSemaphore();
        try {
            Globals.PlayerSettings playerSettings = Globals.getInstance().mPlayerSettings.get(playerID);
            return playerSettings == null ? Globals.DAMAGE_PER_HIT : playerSettings.damage;
        } finally {
            Globals.getInstance().mPlayerSettingsSemaphore.release();
        }
    }

    private void handleDisconnect() {
        if (mConnectFailTimer != null) {
            mConnectFailTimer.cancel();
            mConnectFailTimer = null;
        }
        stopBLEScan();
        // Release the old binding before scanning again. Otherwise finding the
        // blaster calls setupBLEServiceConnection(), which skips an existing binding.
        resetBluetoothServices();
        if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
            Log.e(TAG, "blaster disconnected mid-game, attempt to reconnect");
            connectWeapon();
            showWeaponDisconnect();
            return;
        }
        showConnectLayout();
        mConnectButton.setEnabled(true);
        mReconnectButton.setEnabled(true);
        mDedicatedServerButton.setEnabled(true);
        mQRConnectButton.setEnabled(true);
        TextView connectStatusTV = findViewById(R.id.connect_status_tv);
        if (connectStatusTV != null) connectStatusTV.setText(R.string.connect_status_not_connected);
        mLastShotCount = 0;
        endGame();
        initBatteryQueue();
        playSound(R.raw.eliminated, getApplicationContext());
    }

    private AlertDialog mBlasterDisconnectDialog = null;

    private void showWeaponDisconnect() {
        if (mBlasterDisconnectDialog != null || isFinishing() || isDestroyed())
            return;
        LayoutInflater li = LayoutInflater.from(getApplicationContext());
        View view = li.inflate(R.layout.blaster_disconnected_dialog, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        alertDialogBuilder.setView(view);

        alertDialogBuilder
                .setCancelable(false)
                .setNegativeButton(R.string.blaster_disconnected_cancel_quit,
                        (dialog, id) -> {
                            mDeviceAddress = "";
                            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
                            handleDisconnect();
                            dialog.cancel();
                            mBlasterDisconnectDialog = null;
                        });
        final AlertDialog dialog = alertDialogBuilder.create();
        dialog.setOnDismissListener(ignored -> {
            if (mBlasterDisconnectDialog == dialog)
                mBlasterDisconnectDialog = null;
        });
        mBlasterDisconnectDialog = dialog;
        dialog.show();
    }

    private void hideWeaponDisconnect() {
        if (mBlasterDisconnectDialog != null) {
            mBlasterDisconnectDialog.dismiss();
            mBlasterDisconnectDialog = null;
        }
    }

    // Handles various events fired by the BLE Service.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mGattReceiverRegistered || isFinishing() || isDestroyed() || intent == null)
                return;
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                mCommunicating = false; // When we receive the first packet, we'll switch layouts
                startConnectionTest();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                handleDisconnect();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                //displayGattServices(mBluetoothLeService.getSupportedGattServices());
                //Log.d(TAG, "services discovered!");
                TextView connectStatusTV = findViewById(R.id.connect_status_tv);
                if (connectStatusTV != null) connectStatusTV.setText(R.string.connect_status_communicating);
                BluetoothLeService bluetoothLeService = mBluetoothLeService;
                if (bluetoothLeService == null) {
                    Log.w(TAG, "Ignoring service discovery after BLE service reset");
                    return;
                }
                List<BluetoothGattService> supportedGattServices = bluetoothLeService.getSupportedGattServices();
                if (supportedGattServices == null) {
                    Log.w(TAG, "Ignoring service discovery after GATT close");
                    return;
                }
                for (BluetoothGattService gattService : supportedGattServices) {
                    Log.d(TAG, "service: " + gattService.getUuid().toString());
                    if (gattService.getUuid().toString().equals(GattAttributes.RECOIL_MAIN_SERVICE)) {
                        Log.d(TAG, "Found Recoil Main Service");
                        mTelemetryCharacteristic = gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID));
                        if (mTelemetryCharacteristic != null) {
                            bluetoothLeService.setCharacteristicNotification(mTelemetryCharacteristic, true);
                        } else {
                            Log.e(TAG, "Failed to find Telemetry characteristic");
                            return;
                        }
                        mCommandCharacteristic = gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_COMMAND_UUID));
                        mConfigCharacteristic = gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_CONFIG_UUID));
                        BluetoothGattCharacteristic idCharacteristic = gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_ID_UUID));
                        if (idCharacteristic != null) {
                            bluetoothLeService.readCharacteristic(idCharacteristic); // to get the blaster type, rifle or pistol
                        } else {
                            Log.d(TAG, "failed to find ID characteristic");
                        }
                        if (Globals.getInstance().mGameState == Globals.GAME_STATE_NONE)
                            playSound(R.raw.spawn, getApplicationContext());
                        else {
                            if (Globals.getInstance().mGameState == Globals.GAME_STATE_RUNNING) {
                                mReloading = RELOADING_STATE_NONE;
                                startReload();
                            } else {
                                startReload(RELOADING_STATE_ELIMINATED);
                            }
                            hideWeaponDisconnect();
                        }
                    }
                }
            } else if (BluetoothLeService.TELEMETRY_DATA_AVAILABLE.equals(action)) {
                processTelemetryData(intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA));
            } else if (BluetoothLeService.ID_DATA_AVAILABLE.equals(action)) {
                mBlasterType = intent.getByteExtra(BluetoothLeService.EXTRA_DATA, BLASTER_TYPE_PISTOL);
                if (mBlasterType == BLASTER_TYPE_RIFLE) {
                    Toast.makeText(getApplicationContext(), getString(R.string.rifle_detected_toast, mDeviceAddress), Toast.LENGTH_LONG).show();

                } else {
                    // We'll automatically assume that this is a pistol
                    Toast.makeText(getApplicationContext(), getString(R.string.pistol_detected_toast, mDeviceAddress), Toast.LENGTH_LONG).show();
                }
                // Telemetry may have already configured the blaster before its type was known.
                setShotMode(mCurrentShotMode);
            } else if (BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED.equals(action)) {
                byte[] completedCommand = intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA);
                if (!GattAttributes.RECOIL_COMMAND_UUID.equals(intent.getStringExtra(BluetoothLeService.EXTRA_UUID))
                        || mPendingReloadCommand == null
                        || !Arrays.equals(mPendingReloadCommand, completedCommand))
                    return;
                mPendingReloadCommand = null;
                if (intent.getIntExtra(BluetoothLeService.EXTRA_STATUS, BluetoothGatt.GATT_FAILURE)
                        != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Reload command failed; allow another reload attempt");
                    mReloading = Globals.getInstance().mGameState == Globals.GAME_STATE_RUNNING
                            ? RELOADING_STATE_NONE : RELOADING_STATE_ELIMINATED;
                    if (mReloading == RELOADING_STATE_NONE)
                        hideReloadProgress();
                    return;
                }
                if (mReloading == RELOADING_STATE_STARTED) {
                    // Using a timer here so we can cancel it if the player is eliminated while waiting to reload
                    if (mReloadTimer != null)
                        mReloadTimer.cancel();
                    mReloadTimer = new CountDownTimer(Globals.getInstance().mReloadTime, Math.max(1L, Globals.getInstance().mReloadTime)) {
                        public void onTick(long millisUntilFinished) { /* do nothing */ }
                        public void onFinish() {
                            if (mReloadTimer != this)
                                return;
                            mReloadTimer = null;
                            finishReload();
                        }
                    };
                    // A zero-duration timer calls onFinish() inside start(). It must already
                    // own this field before the callback checks its identity.
                    mReloadTimer.start();
                } else if (mReloading == RELOADING_STATE_FINISHING) {
                    mReloading = RELOADING_STATE_NONE;
                    setShotsRemaining(completedCommand[6]);
                    hideReloadProgress();
                    if (!Globals.getInstance().mReloadOnEmpty)
                        playSound(R.raw.reload, getApplicationContext());
                    Log.d(TAG, "Reload finished");
                }
            }
        }
    };

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.TELEMETRY_DATA_AVAILABLE);
        intentFilter.addAction(BluetoothLeService.ID_DATA_AVAILABLE);
        intentFilter.addAction(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED);
        return intentFilter;
    }

    private void startConnectionTest() {
        // This is a looping handler that checks to see if the tagger has disconnected
        mCommunicating = false;
        mConnectionTest = false;
        if (connectionTestHandler == null)
            connectionTestHandler = new Handler(Looper.getMainLooper());
        connectionTestHandler.removeCallbacksAndMessages(null);
        mConnectionTestRunnable = new Runnable(){
            public void run(){
                if (!mConnected)
                    return;
                if (!mConnectionTest) {
                    Log.d(TAG, "Connection test reports disconnect!");
                    handleDisconnect();
                    return;
                }
                mConnectionTest = false;
                connectionTestHandler.postDelayed(this, CONNECTION_TEST_INTERVAL_MILLISECONDS);
            }
        };
        connectionTestHandler.postDelayed(mConnectionTestRunnable, CONNECTION_TEST_INTERVAL_MILLISECONDS);
    }

    private void vibrateOnHit() {
        if (!Globals.getInstance().mVibrateOnHit || vibrator == null)
            return;
        try {
            vibrator.vibrate(HIT_VIBRATE_DURATION_MILLISECONDS);
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to vibrate for a hit", e);
        }
    }

    private void stopConnectionTest() {
        if (connectionTestHandler != null)
            connectionTestHandler.removeCallbacksAndMessages(null);
        mConnectionTestRunnable = null;
        mConnectionTest = false;
    }

    public void playSound(int resId, Context ctx) {
        MediaPlayer mp = MediaPlayer.create(ctx, resId);
        if (mp == null) {
            Log.w(TAG, "Unable to create sound player for resource " + resId);
            return;
        }
        mp.setLooping(false);
        // Install cleanup before playback begins. A very short sound can otherwise
        // finish before the listener is registered and leak its native player.
        mp.setOnCompletionListener(MediaPlayer::release);
        try {
            mp.start();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Unable to start sound player for resource " + resId, e);
            mp.release();
        }
    }

    private static int validatedHitSource(byte rawSource) {
        int source = rawSource & 0xff;
        int playerID = source >> 2;
        // Grenade packets use a special source byte, not a normal player ID.
        // Unsupported sources must not cause damage, consume lives, or earn points.
        return source == Globals.GRENADE_PLAYER_ID
                || (playerID > 0 && Globals.isValidPlayerID(playerID)) ? source : 0;
    }

    /**
     * Resolve a telemetry source to the player that should receive credit. Grenades use a
     * reserved source byte, so their owner has to come from the current pairing table instead
     * of the source's upper bits. An unpaired grenade remains a valid physical hit, but must not
     * be attributed to an out-of-range pseudo-player.
     */
    private byte resolveHitPlayerID(int hitSource, byte hitData) {
        if (hitSource != Globals.GRENADE_PLAYER_ID)
            return (byte) (hitSource >> 2);

        int grenadeID = (hitData & 0xF0) >> 4;
        if (grenadeID == 0 || !Globals.isValidGrenadeID(grenadeID))
            return 0;

        Globals.getmGrenadePairingsSemaphore();
        try {
            int playerID = Globals.getInstance().mGrenadePairings[grenadeID];
            return playerID > 0 && Globals.isValidPlayerID(playerID) ? (byte) playerID : 0;
        } finally {
            Globals.getInstance().mGrenadePairingsSemaphore.release();
        }
    }

    private boolean isFriendlyHit(byte playerID) {
        return playerID > 0 && Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA
                && Globals.getInstance().calcNetworkTeam(playerID) == mNetworkTeam;
    }

    /* Telemetry data is 20 bytes of raw data in the following format:
       00 seems to be part of a continuous counter, first byte always 0 and second byte counts 0 to F, increments with each packet sent
       01 player ID, 01, 02, 03, etc. 00 when not set
       02 first byte is the power button 0 unpressed and 1 for pressed, second is button presses, 01 for trigger, 02 for reload, 04 for back button, add them together for pressing multiple buttons, so 07 means all three buttons are being pressed
       03 counters for the number of times a button has been pressed, the first byte is reload presses, second byte is trigger presses
       04 again a button counter, first byte is unused, second byte is for back/thumb/voice button presses
       05 first byte unused, second byte is a count of power button presses
       06 seem to be part of a continuous counter or otherwise just random???
       07 battery level - 10 is brand new alkalines, 0E for fully charged rechargeables with 0D showing up pretty quick, drops significantly when shooting
       08 00 related to being hit by ID 1, usually around 3* so 3D or 3E but somewhat random
       09 hit by player ID 1, player 1 seems to be 0x04 and player 2 is 0x08, 0x0C, 0x10, 0x14, 0x18
       10 related to being hit by ID 1 but fairly random in the 6* and 7* range
       11 related to being hit by ID 2, usually around 3* so 3D or 3E but somewhat random
       12 hit by player ID 2, player 1 seems to be 0x04 and player 2 is 0x08, sometimes this is the same as ID 1 but sometimes different if being shot by 2 people at once
       13 related to being hit by ID 2 but fairly random in the 6* and 7* range
       14 how many shots you have left, starts out at 0x1e which would be 30 in decimal and decreases when you pull the trigger
       15 usually 02 but I have seen 03 during reload wait... some kind of status?
       16 starts as 02 but changes to 00 after player ID is set
       17 00 Unused?
       18 00 Unused?
       19 00 Unused?
     */
    private void processTelemetryData(final byte[] data) {
        if (mTelemetryCharacteristic == null)
            return;
        if (data != null && data.length > RECOIL_OFFSET_SHOTS_REMAINING) {
            byte player_id = data[RECOIL_OFFSET_TEAM];
            byte shotsRemaining = data[RECOIL_OFFSET_SHOTS_REMAINING];
            //byte status = data[RECOIL_OFFSET_STATUS];
            //int buttons = data[RECOIL_OFFSET_BUTTONS];
            int hit_by_player1 = validatedHitSource(data[RECOIL_OFFSET_HIT_BY1]);
            // Often, hit_by_player2 is the same player ID as player1
            int hit_by_player2 = validatedHitSource(data[RECOIL_OFFSET_HIT_BY2]);
            byte trigger_counter = (byte)(data[RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] & (byte)0x0F);
            byte reload_counter = (byte)(data[RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] & (byte)0xF0);
            byte thumb_counter = data[RECOIL_OFFSET_THUMB_COUNTER];
            byte power_counter = data[RECOIL_OFFSET_POWER_COUNTER];
            mConnectionTest = true;
            if (!mCommunicating) {
                mCommunicating = true;
                RelativeLayout connectLayout = findViewById(R.id.connect_layout);
                if (connectLayout != null) connectLayout.setVisibility(View.GONE);
                RelativeLayout playLayout = findViewById(R.id.play_layout);
                if (playLayout != null) playLayout.setVisibility(View.VISIBLE);
                // Reset last counters when we start a new connection
                mLastTriggerCount = trigger_counter;
                mLastReloadButtonCount = reload_counter;
                mLastThumbButtonCount = thumb_counter;
                mLastPowerButtonCount = power_counter;
                setShotMode(mCurrentShotMode);
                if (Globals.getInstance().mPlayerID != 0)
                    setTeam();
                if (!mRecoilEnabled)
                    setRecoil(false);
            }
            if (trigger_counter != mLastTriggerCount) {
                mLastTriggerCount = trigger_counter;
                if (shotsRemaining == 0 || Globals.getInstance().mGameState != Globals.GAME_STATE_RUNNING) {
                    playSound(R.raw.empty, getApplicationContext());
                    mEmptyTriggerCount++;
                    if (mEmptyTriggerCount >= MAX_EMPTY_TRIGGER_PULLS && Globals.getInstance().mGameState == Globals.GAME_STATE_RUNNING)
                        startReload(); // Auto reload after this many empty trigger pulls (for young players)
                }
            }
            /* Rather than monitor if a button is currently pressed, we monitor the counter. Usually
               when the player presses a button, we see many packets showing that the button is
               pressed. We don't want to toggle recoil or modes with every packet we receive so it
               makes more sense to just monitor when the counter changes so we can toggle eactly
               once each time that button is pressed. */
            if (reload_counter != mLastReloadButtonCount) {
                mLastReloadButtonCount = reload_counter;
                if (shotsRemaining != Globals.getInstance().mFullReload)
                    startReload();
            }
            if (thumb_counter != mLastThumbButtonCount) {
                mLastThumbButtonCount = thumb_counter;
                if (mCurrentShotMode == Globals.SHOT_MODE_SINGLE) {
                    if (Globals.getInstance().mAllowBurst3ShotMode)
                        setShotMode(Globals.SHOT_MODE_BURST);
                    else if (Globals.getInstance().mAllowAutoShotMode)
                        setShotMode(Globals.SHOT_MODE_FULL_AUTO);
                    else
                        setShotMode(Globals.SHOT_MODE_SINGLE);
                } else if (mCurrentShotMode == Globals.SHOT_MODE_BURST) {
                    if (Globals.getInstance().mAllowAutoShotMode)
                        setShotMode(Globals.SHOT_MODE_FULL_AUTO);
                    else if (Globals.getInstance().mAllowSingleShotMode)
                        setShotMode(Globals.SHOT_MODE_SINGLE);
                    else
                        setShotMode(Globals.SHOT_MODE_BURST);
                } else if (mCurrentShotMode == Globals.SHOT_MODE_FULL_AUTO) {
                    if (Globals.getInstance().mAllowSingleShotMode)
                        setShotMode(Globals.SHOT_MODE_SINGLE);
                    else if (Globals.getInstance().mAllowBurst3ShotMode)
                        setShotMode(Globals.SHOT_MODE_BURST);
                    else
                        setShotMode(Globals.SHOT_MODE_FULL_AUTO);
                }
            }
            if (power_counter != mLastPowerButtonCount) {
                mLastPowerButtonCount = power_counter;
                mRecoilEnabled = !mRecoilEnabled;
                setRecoil(mRecoilEnabled);
            }
            byte battery_level = data[RECOIL_OFFSET_BATTERY_LEVEL];
            mBatteryQueue.add(battery_level);
            mBatteryTotal += battery_level;
            if (mBatteryCount >= 100) {
                mBatteryTotal -= (long)mBatteryQueue.peek();
                mBatteryQueue.remove();
            } else {
                mBatteryCount++;
            }
            long averageBattery = mBatteryTotal / mBatteryCount;
            if (mBatteryLevelIV != null) {
                if (mBlasterType == BLASTER_TYPE_RIFLE) {
                    if (averageBattery >= BATTERY_LEVEL_RIFLE_GREEN)
                        mBatteryLevelIV.setImageResource(R.drawable.ic_battery_full_green_24dp);
                    else if (averageBattery >= BATTERY_LEVEL_RIFLE_BLUE)
                        mBatteryLevelIV.setImageResource(R.drawable.ic_battery_80_blue_24dp);
                    else if (averageBattery >= BATTERY_LEVEL_RIFLE_YELLOW)
                        mBatteryLevelIV.setImageResource(R.drawable.ic_battery_50_yellow_24dp);
                    else
                        mBatteryLevelIV.setImageResource(R.drawable.ic_battery_alert_red_24dp);
                } else {
                    if (averageBattery >= BATTERY_LEVEL_PISTOL_GREEN)
                        mBatteryLevelIV.setImageResource(R.drawable.ic_battery_full_green_24dp);
                    else if (averageBattery >= BATTERY_LEVEL_PISTOL_BLUE)
                        mBatteryLevelIV.setImageResource(R.drawable.ic_battery_80_blue_24dp);
                    else if (averageBattery >= BATTERY_LEVEL_PISTOL_YELLOW)
                        mBatteryLevelIV.setImageResource(R.drawable.ic_battery_50_yellow_24dp);
                    else
                        mBatteryLevelIV.setImageResource(R.drawable.ic_battery_alert_red_24dp);
                }
            }
            /*if (buttons != 0) {
                boolean trigger = false;
                boolean reload = false;
                boolean thumb = false;
                boolean power = false;
                if ((buttons & RECOIL_TRIGGER_BIT) != 0) trigger = true;
                if ((buttons & RECOIL_RELOAD_BIT) != 0) reload = true;
                if ((buttons & RECOIL_THUMB_BIT) != 0) thumb = true;
                if ((buttons & RECOIL_POWER_BIT) != 0) power = true;
                //Log.d(TAG, "trigger: " + (trigger ? "X" : " ") + " reload: " + (reload ? "X" : " ") + " thumb: " + (thumb ? "X" : " ") + " power: " + (power ? "X" : " "));
                if (power) {
                    mHitsTaken = 0;
                    mHitsTakenTV.setText("0");
                }
            }*/
            if (hit_by_player1 != 0 || hit_by_player2 != 0) {
                int healthRemoved = 0;
                byte hit_by_id = 0;
                // Only the right-most 3 bits make up a normal shot ID. Grenades
                // instead use the complete byte for a grenade ID and command, so
                // retain all of it when filtering repeated telemetry packets.
                byte shot_id1 = hit_by_player1 == Globals.GRENADE_PLAYER_ID
                        ? data[RECOIL_OFFSET_HIT_BY1_SHOTID]
                        : (byte)(data[RECOIL_OFFSET_HIT_BY1_SHOTID] & 0x07);
                byte shot_id2 = hit_by_player2 == Globals.GRENADE_PLAYER_ID
                        ? data[RECOIL_OFFSET_HIT_BY2_SHOTID]
                        : (byte)(data[RECOIL_OFFSET_HIT_BY2_SHOTID] & 0x07);
                //Log.e(TAG, "Hit by " + hit_by_player1);
                /*if (hit_by_player2 != 0) {
                    Log.e(TAG, "hitdata: " + Integer.toHexString(hit_by_player1) + " " + Integer.toHexString(data[RECOIL_OFFSET_HIT_BY1_SHOTID] & 0xFF) + " " + Integer.toHexString(hit_by_player2) + " " + Integer.toHexString(data[RECOIL_OFFSET_HIT_BY2_SHOTID] & 0xFF));
                } else {
                    Log.e(TAG, "hitdata: " + Integer.toHexString(hit_by_player1) + " " + Integer.toHexString(data[RECOIL_OFFSET_HIT_BY1_SHOTID] & 0xFF));
                }*/
                if (hit_by_player1 == Globals.GRENADE_PLAYER_ID)
                    processGrenadeCommand(data[RECOIL_OFFSET_HIT_BY1_SHOTID]);
                if (hit_by_player2 == Globals.GRENADE_PLAYER_ID)
                    processGrenadeCommand(data[RECOIL_OFFSET_HIT_BY2_SHOTID]);
                byte hitByPlayer1ID = resolveHitPlayerID(hit_by_player1,
                        data[RECOIL_OFFSET_HIT_BY1_SHOTID]);
                byte hitByPlayer2ID = resolveHitPlayerID(hit_by_player2,
                        data[RECOIL_OFFSET_HIT_BY2_SHOTID]);
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
                    if (hit_by_player1 != 0) {
                    if ((mLastHitData1.playerID == hit_by_player1 && mLastHitData1.shotID == shot_id1) || (mLastHitData2.playerID == hit_by_player1 && mLastHitData2.shotID == shot_id1)) {
                        //Log.e(TAG, "Hit by 1 is using same shot ID from a previous hit, filter!" + hit_by_player1 + " " + data[RECOIL_OFFSET_HIT_BY1_SHOTID]);
                    } else {
                        if (hit_by_player1 == Globals.GRENADE_PLAYER_ID && (data[RECOIL_OFFSET_HIT_BY1_SHOTID] & 0x0F) != GRENADE_DAMAGE) {
                            // Ignore non-damage events from grenades
                        } else {
                            mHitsTaken = incrementCounter(mHitsTaken, Globals.MAX_SCOREBOARD_VALUE);
                            String hitsTaken = "" + mHitsTaken;
                            mHitsTakenTV.setText(hitsTaken);
                            // Offline games have no per-player settings snapshot. Use the
                            // locally configured damage value instead of silently reverting
                            // every weapon to the one-hit default.
                            healthRemoved = Globals.getInstance().mDamage;
                            if (mUseNetwork) {
                                if (isFriendlyHit(hitByPlayer1ID)) {
                                    //Log.d(TAG, "friendly fire ignored");
                                    healthRemoved = 0;
                                } else {
                                    healthRemoved = getPlayerDamage(hitByPlayer1ID);
                                    hit_by_id = hitByPlayer1ID;
                                    if (hitByPlayer1ID > 0 && mLastHitMessage < System.currentTimeMillis()) {
                                        mLastHitMessage = System.currentTimeMillis() + HIT_ANIMATION_DURATION_MILLISECONDS;
                                        if (survivesDamage(healthRemoved))
                                            sendUDPMessage(NetMsg.NETMSG_HIT, hitByPlayer1ID);
                                        else
                                            sendUDPMessage(NetMsg.NETMSG_OUT, hitByPlayer1ID);
                                    }
                                }
                            }
                        }
                    }
                    }
                    boolean duplicateInPacket = hit_by_player2 == hit_by_player1 && shot_id2 == shot_id1;
                    if (duplicateInPacket || (mLastHitData1.playerID == hit_by_player2 && mLastHitData1.shotID == shot_id2) || (mLastHitData2.playerID == hit_by_player2 && mLastHitData2.shotID == shot_id2)) {
                        //Log.e(TAG, "Hit by 2 is using same shot ID from a previous hit, filter!");
                    } else if (hit_by_player2 != 0 && survivesDamage(healthRemoved)) {
                        if (hit_by_player2 == Globals.GRENADE_PLAYER_ID && (data[RECOIL_OFFSET_HIT_BY2_SHOTID] & 0x0F) != GRENADE_DAMAGE) {
                            // Ignore non-damage events from grenades
                        } else {
                            mHitsTaken = incrementCounter(mHitsTaken, Globals.MAX_SCOREBOARD_VALUE);
                            mHitsTakenTV.setText(String.valueOf(mHitsTaken));
                            int secondHitDamage = Globals.getInstance().mDamage;
                            if (mUseNetwork) {
                                if (isFriendlyHit(hitByPlayer2ID)) {
                                    //Log.d(TAG, "friendly fire ignored");
                                    secondHitDamage = 0;
                                } else {
                                    secondHitDamage = getPlayerDamage(hitByPlayer2ID);
                                    hit_by_id = hitByPlayer2ID;
                                    if (hitByPlayer2ID > 0 && mLastHitMessage < System.currentTimeMillis()) {
                                        mLastHitMessage = System.currentTimeMillis() + HIT_ANIMATION_DURATION_MILLISECONDS;
                                        if (survivesDamage(healthRemoved + secondHitDamage))
                                            sendUDPMessage(NetMsg.NETMSG_HIT, hitByPlayer2ID);
                                        else
                                            sendUDPMessage(NetMsg.NETMSG_OUT, hitByPlayer2ID);
                                    }
                                }
                            }
                            healthRemoved += secondHitDamage;
                        }
                    }
                    if (hit_by_player1 > 0) {
                        mLastHitData1.playerID = hit_by_player1;
                        mLastHitData1.shotID = shot_id1;
                    } else {
                        mLastHitData1.playerID = Globals.INVALID_PLAYER_ID;
                    }
                    if (hit_by_player2 > 0) {
                        mLastHitData2.playerID = hit_by_player2;
                        mLastHitData2.shotID = shot_id2;
                    } else {
                        mLastHitData2.playerID = Globals.INVALID_PLAYER_ID;
                    }
                    //TODO hit conformation for the other player vibration toogle
                    if (healthRemoved != 0) {
                        if (Globals.getInstance().mGameState == Globals.GAME_STATE_RUNNING) {
                            takeDamage(healthRemoved);
                            if (mHealth > 0) {
                                if (mHitIV != null && mHitAnimation == null) {
                                    // Show the "you're being hit" animation
                                    mHitIV.setVisibility(View.VISIBLE);
                                    mHitIV.setBackgroundResource(R.drawable.hit_animation);
                                    vibrateOnHit();
                                    playSound(R.raw.hit, getApplicationContext());
                                    Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
                                    mHitIV.startAnimation(animationFadeOut);
                                    mHitAnimation = (AnimationDrawable) mHitIV.getBackground();
                                    mHitAnimation.start();
                                    mEliminatedByTV.setVisibility(View.VISIBLE);
                                    if (hit_by_id == 0) {
                                        mEliminatedByTV.setText("");
                                    } else {
                                        mEliminatedByTV.setText(Globals.getInstance().getPlayerName(hit_by_id));
                                    }
                                    mEliminatedByTV.startAnimation(animationFadeOut);
                                    mIncomingHitCleanup = new Runnable() {
                                        @Override public void run() {
                                            if (mIncomingHitCleanup == this)
                                                clearIncomingHitFeedback();
                                        }
                                    };
                                    mCombatFeedbackHandler.postDelayed(mIncomingHitCleanup,
                                            HIT_ANIMATION_DURATION_MILLISECONDS);
                                }
                            } else {
                                vibrateOnHit();
                                playSound(R.raw.eliminated, getApplicationContext());
                                if (mHasLivesLimit)
                                    mEliminationCount = Math.max(0, boundedCounter(mEliminationCount,
                                            Globals.MAX_SCOREBOARD_VALUE) - 1);
                                else
                                    mEliminationCount = incrementCounter(mEliminationCount,
                                            Globals.MAX_SCOREBOARD_VALUE);
                                String elimStr = "" + mEliminationCount;
                                mEliminationCountTV.setText(elimStr);
                                String eliminatedBy = "";
                                if (mUseNetwork)
                                    eliminatedBy = Globals.getInstance().getPlayerName(hit_by_id);
                                boolean outOfLives = mHasLivesLimit && mEliminationCount <= 0;
                                if (!outOfLives)
                                    startSpawn(eliminatedBy);
                                if (mUseNetwork) {
                                    if (hit_by_id > 0 && hit_by_id != Globals.getInstance().mPlayerID && Globals.getInstance().calcNetworkTeam(hit_by_id) != Globals.getInstance().calcNetworkTeam(Globals.getInstance().mPlayerID)) {
                                        if (isDedicatedServerConnection())
                                            mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ELIMINATED + hit_by_id, true);
                                        else if (mUDPListenerService != null)
                                            mUDPListenerService.publishPeerElimination(hit_by_id);
                                    }
                                }
                                if (outOfLives)
                                    finishOutOfLives();
                            }
                        }
                    }
                }
            } else {
                mLastHitData1.playerID = Globals.INVALID_PLAYER_ID;
                mLastHitData2.playerID = Globals.INVALID_PLAYER_ID;
            }
            /* Since setting player ID is somewhat unreliable, we use this to make sure that we are
               displaying the actual ID that the tagger is currently using. */
            // Zero is an unconfigured/reset blaster, not a new player selection.
            // Invalid status bytes must not become reload or network identities.
            if (player_id > 0 && Globals.isValidPlayerID(player_id) && mLastTeam != player_id) {
                Log.d(TAG, "Player ID changed to " + player_id);
                mLastTeam = player_id;
                Globals.getInstance().mPlayerID = player_id;
                displayCurrentTeam();
            }
            /* We monitor the last shot count rather than trigger pulls here so we know when to
               update the displayed shots remaining and play shooting sounds. pew! pew! */
            if (mLastShotCount != shotsRemaining) {
                setShotsRemaining(shotsRemaining);
                if (shotsRemaining != Globals.getInstance().mFullReload && mReloading == RELOADING_STATE_NONE) {
                    playSound(R.raw.shootingshort, getApplicationContext());
                    if (mUseNetwork && mLastShotFired < System.currentTimeMillis()) {
                        mLastShotFired = System.currentTimeMillis() + HIT_ANIMATION_DURATION_MILLISECONDS;
                        sendUDPMessageAll(NetMsg.NETMSG_SHOTFIRED);
                    }
                    if (Globals.getInstance().mReloadOnEmpty && shotsRemaining == 0 && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                        startReload();
                }
            }
            // Handy utility code if you want to see the raw data
            /*if (data != null && data.length > 0) {
                final StringBuilder stringBuilder = new StringBuilder(data.length);
                for(byte byteChar : data)
                    stringBuilder.append(String.format("%02X ", byteChar));
                Log.d(TAG, stringBuilder.toString());
            }*/
        } else if (data != null) {
            Log.w(TAG, "Ignoring short telemetry packet: " + data.length + " bytes");
        }
    }

    private void processGrenadeCommand(byte data) {
        int command = data & 0x0F;
        byte grenadeID = (byte) ((data & 0xF0) >> 4);
        Globals globals = Globals.getInstance();
        if (command == GRENADE_PAIR_ID) {
            if (grenadeID == 0 || globals.mPairedGrenadeID != 0)
                return;
            globals.mPairedGrenadeID = grenadeID;
            Toast.makeText(getApplicationContext(), getString(R.string.grenade_paired_toast), Toast.LENGTH_SHORT).show();
        } else if (command == GRENADE_NEW_PAIR || command == GRENADE_DISARM) {
            if (globals.mPairedGrenadeID == 0)
                return;
            globals.mPairedGrenadeID = 0;
        } else {
            return;
        }
        // Publish removals too, so the server cannot keep crediting the old owner.
        // A peer host closes its TCP listener at the round start, so its updates
        // must use the roster-validated UDP path instead.
        if (mUseNetwork) {
            if (isDedicatedServerConnection())
                mTcpClient.sendPlayerGrenade();
            else if (mUDPListenerService != null)
                mUDPListenerService.publishPeerGrenadePairing();
        }
    }

    private final BroadcastReceiver mUDPUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mNetworkReceiverRegistered || isFinishing() || isDestroyed() || intent == null)
                return;
            if (intent.hasExtra(TcpClient.EXTRA_START_EVENT_ID)) {
                if (mTcpClient == null || !mUseNetwork || !mReady || !networkServicesReady())
                    return;
                intent = mTcpClient.consumePendingGameStart(intent.getLongExtra(TcpClient.EXTRA_START_EVENT_ID, 0));
                if (intent == null)
                    return;
            }
            if (intent.hasExtra(TcpClient.EXTRA_TERMINAL_EVENT_ID)) {
                long eventId = intent.getLongExtra(TcpClient.EXTRA_TERMINAL_EVENT_ID, 0);
                if (mTcpClient == null || eventId <= 0)
                    return;
                intent = mTcpClient.consumePendingTerminalEvent(eventId);
                if (intent == null)
                    return; // Already handled, or belongs to an earlier session/service.
            }
            final String action = intent.getAction();
            if (isPeerRoundScopedAction(action) && intent.hasExtra(NetMsg.INTENT_ROUND_TOKEN)
                    && !isCurrentPeerRoundEvent(intent))
                return;
            if (!mUseNetwork && (NetMsg.NETMSG_ENDGAME.equals(action)
                    || NetMsg.NETMSG_SERVERCANCEL.equals(action) || NetMsg.NETMSG_VERSIONERROR.equals(action)))
                return;
            boolean gameplayEvent = NetMsg.NETMSG_SHOTFIRED.equals(action)
                    || NetMsg.NETMSG_HIT.equals(action) || NetMsg.NETMSG_OUT.equals(action)
                    || NetMsg.NETMSG_ELIMINATED.equals(action) || NetMsg.NETMSG_TEAMELIMINATED.equals(action);
            if (gameplayEvent && (!mUseNetwork || Globals.getInstance().mGameState == Globals.GAME_STATE_NONE))
                return; // Messages already in flight must not change a completed round.
            if (gameplayEvent && mHasSynchronizedStart && mStartGameTimer)
                return; // Initial countdown is not a scoring or firing phase.
            if (NetMsg.NETMSG_RESPAWNGRANTED.equals(action)) {
                // A dedicated host may grant either a checkpoint request or a
                // Game Master override.  Ignore late grants from an older life
                // or a finished round.
                if (isDedicatedServerConnection() && !mStartGameTimer
                        && Globals.getInstance().mGameState == Globals.GAME_STATE_ELIMINATED)
                    finishSpawn(false);
            } else if (NetMsg.NETMSG_SHOTFIRED.equals(action)) {
                // Play a sound?
                mLastShotFired = System.currentTimeMillis() + HIT_ANIMATION_DURATION_MILLISECONDS; // Keeps us from spamming shots fired messages
                if (mShotsFiredIV != null && mShotsFiredIV.getVisibility() != View.VISIBLE) {
                    // Show the "shots fired" image
                    mShotsFiredIV.setVisibility(View.VISIBLE);
                    Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
                    mShotsFiredIV.startAnimation(animationFadeOut);
                    mCombatFeedbackHandler.postDelayed(() -> hideFeedbackView(mShotsFiredIV, View.GONE),
                            HIT_ANIMATION_DURATION_MILLISECONDS);
                }
            } else if (NetMsg.NETMSG_HIT.equals(action)) {
                // Play a sound?
                if (mHitPlayerIV != null && mHitPlayerAnimation == null) {
                    // Show the "you hit a player" animation
                    mHitPlayerIV.setVisibility(View.VISIBLE);
                    mHitPlayerIV.setBackgroundResource(R.drawable.hit_other_player_animation);
                    Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
                    mHitPlayerIV.startAnimation(animationFadeOut);
                    mHitPlayerAnimation = (AnimationDrawable) mHitPlayerIV.getBackground();
                    mHitPlayerAnimation.start();
                    mHitPlayerNameTV.setVisibility(View.VISIBLE);
                    Byte hitPlayerID = intent.getByteExtra(UDPListenerService.INTENT_PLAYERID, (byte)0);
                    if (hitPlayerID == 0) {
                        mHitPlayerNameTV.setText("");
                    } else {
                        mHitPlayerNameTV.setText(Globals.getInstance().getPlayerName(hitPlayerID));
                    }
                    mHitPlayerNameTV.startAnimation(animationFadeOut);
                    mCombatFeedbackHandler.postDelayed(FullscreenActivity.this::clearHitConfirmation,
                            HIT_ANIMATION_DURATION_MILLISECONDS);
                }
            } else if (NetMsg.NETMSG_OUT.equals(action)) {
                // Play a sound?
                if (mHitPlayerIV != null && mHitPlayerAnimation == null) {
                    // Show the "you hit a player that's already out" animation
                    mHitPlayerIV.setVisibility(View.VISIBLE);
                    mHitPlayerIV.setBackgroundResource(R.drawable.hit_other_player_out_animation);
                    Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
                    mHitPlayerIV.startAnimation(animationFadeOut);
                    mHitPlayerAnimation = (AnimationDrawable) mHitPlayerIV.getBackground();
                    mHitPlayerAnimation.start();
                    mHitPlayerNameTV.setVisibility(View.VISIBLE);
                    Byte hitPlayerID = intent.getByteExtra(UDPListenerService.INTENT_PLAYERID, (byte)0);
                    if (hitPlayerID == 0) {
                        mHitPlayerNameTV.setText("");
                    } else {
                        mHitPlayerNameTV.setText(Globals.getInstance().getPlayerName(hitPlayerID));
                    }
                    mHitPlayerNameTV.startAnimation(animationFadeOut);
                    mCombatFeedbackHandler.postDelayed(FullscreenActivity.this::clearHitConfirmation,
                            HIT_ANIMATION_DURATION_MILLISECONDS);
                }
            } else if (NetMsg.NETMSG_ELIMINATED.equals(action)) {
                // Increase score
                mScore = incrementCounter(mScore, Globals.MAX_SCOREBOARD_VALUE);
                String score = "" + mScore;
                mScoreTV.setText(score);
                mScoreIncreaseIV.setVisibility(View.VISIBLE);
                Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.eliminatedfadeout);
                mScoreIncreaseIV.startAnimation(animationFadeOut);
                mScoreIncreasePlayerNameTV.setVisibility(View.VISIBLE);
                Byte hitPlayerID = intent.getByteExtra(UDPListenerService.INTENT_PLAYERID, (byte)0);
                if (hitPlayerID == 0) {
                    mScoreIncreasePlayerNameTV.setText("");
                } else {
                    mScoreIncreasePlayerNameTV.setText(Globals.getInstance().getPlayerName(hitPlayerID));
                }
                mScoreIncreasePlayerNameTV.startAnimation(animationFadeOut);
                if (mScoreFeedbackCleanup != null)
                    mCombatFeedbackHandler.removeCallbacks(mScoreFeedbackCleanup);
                mScoreFeedbackCleanup = new Runnable() {
                    @Override public void run() {
                        if (mScoreFeedbackCleanup != this)
                            return;
                        mScoreFeedbackCleanup = null;
                        hideFeedbackView(mScoreIncreaseIV, View.GONE);
                        hideFeedbackView(mScoreIncreasePlayerNameTV, View.GONE);
                    }
                };
                mCombatFeedbackHandler.postDelayed(mScoreFeedbackCleanup,
                        ELIMINATED_ANIMATION_DURATION_MILLISECONDS);
                playSound(R.raw.score, getApplicationContext());
                if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                    mTeamScore = incrementCounter(mTeamScore, Globals.MAX_TEAM_SCOREBOARD_VALUE);
                    score = "" + mTeamScore;
                    mTeamScoreTV.setText(score);
                    if (!isDedicatedServerConnection()) {
                        // Send a message to all teammates about the score increase
                        int teamSize = ((Globals.MAX_PLAYER_ID + 1) / 2);
                        if (Globals.getInstance().mGameMode == Globals.GAME_MODE_4TEAMS) {
                            teamSize = ((Globals.MAX_PLAYER_ID + 1) / 4);
                        }
                        int startPoint = (teamSize * mNetworkTeam) - teamSize + 1;
                        long eventSequence = intent.getLongExtra(NetMsg.INTENT_EVENT_SEQUENCE, 0);
                        for (int x = startPoint; x < startPoint + teamSize; x++) {
                            if (x != Globals.getInstance().mPlayerID) { // don't send a message to ourselves
                                if (eventSequence > 0 && mUDPListenerService != null)
                                    mUDPListenerService.publishPeerTeamElimination(hitPlayerID,
                                            eventSequence, (byte) x);
                            }
                        }
                    }
                }
                checkPeerScoreLimit();
            } else if (NetMsg.NETMSG_TEAMELIMINATED.equals(action)) {
                if (Globals.getInstance().mGameMode == Globals.GAME_MODE_FFA)
                    return;
                // Increase team score in team games
                mTeamScore = incrementCounter(mTeamScore, Globals.MAX_TEAM_SCOREBOARD_VALUE);
                String score = "" + mTeamScore;
                mTeamScoreTV.setText(score);
                checkPeerScoreLimit();
            } else if (NetMsg.NETMSG_JOIN.equals(action) || NetMsg.NETMSG_LEAVE.equals(action)) {
                mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, Globals.getPlayerCount()));
                mNetworkPlayerCountTV.setVisibility(View.VISIBLE);
                if (Globals.getInstance().mGameMode == Globals.GAME_MODE_FFA && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE && NetMsg.NETMSG_LEAVE.equals(action) && Globals.getPlayerCount() <= 1)
                    endGame(); // Everyone else is out so game is over - this only works in FFA because we don't keep track of who and how many people are on each team
            } else if (NetMsg.NETMSG_LISTPLAYERS.equals(action)) {
                if (mReady) {
                    endUDPScanning();
                    mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, Globals.getPlayerCount()));
                    mNetworkPlayerCountTV.setVisibility(View.VISIBLE);
                    if (Globals.getInstance().mGameMode == Globals.GAME_MODE_2TEAMS) {
                        mGameModeTV.setText(R.string.game_mode_2teams);
                    } else if (Globals.getInstance().mGameMode == Globals.GAME_MODE_4TEAMS) {
                        mGameModeTV.setText(R.string.game_mode_4teams);
                    } else {
                        mGameModeTV.setText(R.string.game_mode_ffa);
                    }
                    setTeam();
                    if (!mIsServer && Globals.getInstance().mGameState == Globals.GAME_STATE_NONE) {
                        setGameLimit();
                        setNetworkMenu(NETWORK_TYPE_JOINED);
                        if (Globals.getInstance().mServerIP != null) {
                            String ip = Globals.getInstance().mServerIP.toString();
                            if (ip.startsWith("/")) ip = ip.substring(1);
                            mServerIPTV.setText(getString(R.string.server_status_connected_to, ip));
                            mServerIPTV.setVisibility(View.VISIBLE);
                        }
                        if (isDedicatedServerConnection()) {
                            mPlayerDataButton.setVisibility(View.VISIBLE);
                            mNetworkStatusIV.setVisibility(View.VISIBLE);
                            mNetworkStatusIV.setImageResource(R.drawable.ic_network_connected_24dp);
                        }
                    }
                    boolean dedicatedServer = isDedicatedServerConnection();
                    int serverGameState = dedicatedServer
                            ? intent.getIntExtra(NetMsg.INTENT_GAMESTATE, Globals.GAME_STATE_ELIMINATED)
                            : Globals.GAME_STATE_ELIMINATED;
                    boolean hasGameUpdate = intent.getBooleanExtra(NetMsg.INTENT_HASGAMEUPDATE, false);
                    int deaths = boundedCounter(intent.getIntExtra(NetMsg.INTENT_ELIMINATIONS, 0),
                            Globals.MAX_SCOREBOARD_VALUE);
                    long timeRemaining = hasGameUpdate ? intent.getLongExtra(NetMsg.INTENT_TIMEREMAINING, -1) : -1;
                    boolean synchronizedStart = intent.hasExtra(NetMsg.INTENT_START_AT);
                    long synchronizedEnd = intent.getLongExtra(NetMsg.INTENT_END_AT, 0);
                    boolean roundExpired = synchronizedStart
                            ? synchronizedEnd > 0 && synchronizedEnd <= SystemClock.elapsedRealtime() : timeRemaining == 0;
                    boolean outOfLives = hasGameUpdate && mHasLivesLimit && deaths >= mLives;
                    boolean roundInProgress = Globals.getInstance().mGameState != Globals.GAME_STATE_NONE
                            || (dedicatedServer && serverGameState == Globals.GAME_STATE_RUNNING);
                    boolean serverRoundEnded = dedicatedServer && serverGameState == Globals.GAME_STATE_NONE;
                    if (dedicatedServer) {
                        if (serverRoundEnded && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
                            endGame();
                        } else if (serverGameState == Globals.GAME_STATE_RUNNING
                                && Globals.getInstance().mGameState == Globals.GAME_STATE_NONE
                                && !outOfLives && !roundExpired) {
                            // Initialize the round first; startGame resets the local score counters.
                            startGame(intent);
                        }
                    }
                    if (hasGameUpdate) {
                        mScore = boundedCounter(intent.getIntExtra(NetMsg.INTENT_SCORE, 0),
                                Globals.MAX_SCOREBOARD_VALUE);
                        String score = "" + mScore;
                        mScoreTV.setText(score);
                        // The server counts deaths; the local HUD counts remaining lives when limited.
                        mEliminationCount = mHasLivesLimit ? Math.max(0, mLives - deaths) : deaths;
                        mEliminationCountTV.setText(String.valueOf(mEliminationCount));
                        if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                            mTeamScore = boundedCounter(intent.getIntExtra(NetMsg.INTENT_TEAMSCORE, 0),
                                    Globals.MAX_TEAM_SCOREBOARD_VALUE);
                            score = "" + mTeamScore;
                            mTeamScoreTV.setText(score);
                        }
                        if (outOfLives && roundInProgress && !serverRoundEnded) {
                            finishOutOfLives();
                        } else if (synchronizedStart && roundInProgress && !serverRoundEnded) {
                            if (synchronizedEnd > 0)
                                startGameCountdownMillis(Math.max(0, synchronizedEnd - SystemClock.elapsedRealtime()));
                        } else if (timeRemaining >= 0 && roundInProgress && !serverRoundEnded) {
                            if (timeRemaining == 0)
                                finishTimedGame();
                            else if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                                startGameCountdown(timeRemaining);
                        }
                    }
                    if (dedicatedServer) {
                        if (Globals.getInstance().mOnlyServerSettings
                                || Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
                            mFiringModeButton.setVisibility(View.GONE);
                            mStartGameButton.setVisibility(View.GONE);
                            mPlayerSettingsButton.setVisibility(View.GONE);
                        } else {
                            mFiringModeButton.setVisibility(View.VISIBLE);
                            mStartGameButton.setVisibility(View.VISIBLE);
                            mPlayerSettingsButton.setVisibility(View.VISIBLE);
                        }
                    }
                }
            } else if (NetMsg.NETMSG_PLAYERDATAUPDATE.equals(action)) {
                String playerData = intent.getStringExtra(NetMsg.INTENT_PLAYERDATA);
                if (playerData != null)
                    displayPlayerData(playerData);
            } else if (NetMsg.NETMSG_STARTGAME.equals(action)) {
                if (mIsServer && intent.hasExtra(NetMsg.INTENT_ROUND_ID) && mTcpServer != null) {
                    Intent scheduled = mTcpServer.getScheduledGameStart();
                    if (scheduled == null || scheduled.getLongExtra(NetMsg.INTENT_ROUND_ID, 0)
                            != intent.getLongExtra(NetMsg.INTENT_ROUND_ID, -1))
                        return;
                }
                if (mUseNetwork && mReady && Globals.getInstance().mGameState == Globals.GAME_STATE_NONE) {
                    startGame(intent);
                    // An ENDGAME received between the TCP start broadcast and
                    // this activity becoming active is deliberately silent in
                    // UDPListenerService. Apply it only after startGame has
                    // installed the matching peer-round token.
                    consumePendingPeerEndGame();
                }
            } else if (NetMsg.NETMSG_CLOCKSYNCWAITING.equals(action)) {
                Toast.makeText(getApplicationContext(), R.string.clock_sync_waiting, Toast.LENGTH_SHORT).show();
            } else if (NetMsg.NETMSG_ENDGAME.equals(action)) {
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                    endGame();
                else if (mReady) {
                    mReady = false;
                    setReady(false);
                }
            } else if (NetMsg.NETMSG_ERROR.equals(action)) {
                String errorMessage = intent.getStringExtra(UDPListenerService.INTENT_MESSAGE);
                if (errorMessage != null && !errorMessage.isEmpty()) {
                    Toast.makeText(getApplicationContext(), errorMessage, Toast.LENGTH_SHORT).show();
                } else {
                    Log.d(TAG, "No error message given");
                }
            } else if (NetMsg.NETMSG_VERSIONERROR.equals(action)) {
                Toast.makeText(getApplicationContext(), getString(R.string.error_udp_version), Toast.LENGTH_SHORT).show();
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                    endGame();
                mReady = false;
                setReady(false);
            } else if (NetMsg.NETMSG_SAMETEAM.equals(action)) {
                Toast.makeText(getApplicationContext(), getString(R.string.error_same_id), Toast.LENGTH_SHORT).show();
                mReady = false;
                setReady();
            } else if (NetMsg.NETMSG_FAILEDTOJOIN.equals(action)) {
                Toast.makeText(getApplicationContext(), getString(R.string.error_join), Toast.LENGTH_SHORT).show();
                boolean failedPeerHostCreation = mPeerHostCreationPending;
                mPeerHostCreationPending = false;
                mPeerUdpServerStarting = false;
                // Only the peer-host flow owns a provisional TCP listener. A
                // normal failed join must not tear down an unrelated listener.
                if (failedPeerHostCreation && mTcpServer != null)
                    mTcpServer.cancelServer();
                mReady = false;
                setReady(false);
            } else if (NetMsg.NETMSG_TCPSERVERREADY.equals(action)) {
                startPeerUdpServerIfTcpReady();
            } else if (NetMsg.NETMSG_TCPSERVERFAILED.equals(action)) {
                // Ignore an old failure after a replacement listener is ready.
                if (mPeerHostCreationPending && (mTcpServer == null || !mTcpServer.isTcpServerReady())) {
                    mPeerHostCreationPending = false;
                    mPeerUdpServerStarting = false;
                    if (mUDPListenerService != null)
                        mUDPListenerService.cancelServer();
                    if (mTcpServer != null)
                        mTcpServer.cancelServer();
                    mReady = false;
                    mIsServer = false;
                    setReady(false);
                    Toast.makeText(getApplicationContext(), R.string.error_host_start, Toast.LENGTH_SHORT).show();
                }
            } else if (NetMsg.NETMSG_SERVERCREATED.equals(action)) {
                if (mIsServer)
                    return;
                if (mTcpServer == null || !mTcpServer.isTcpServerReady()
                        || (!mPeerHostCreationPending && !mPeerUdpServerStarting)) {
                    // UDP can finish its asynchronous startup after its TCP
                    // listener has failed or been cancelled. Do not expose a
                    // lobby that peers cannot join.
                    if (mUDPListenerService != null)
                        mUDPListenerService.cancelServer();
                    return;
                }
                mPeerHostCreationPending = false;
                mPeerUdpServerStarting = false;
                mReady = true;
                mIsServer = true;
                setReady();
                if (Globals.getInstance().mServerIP != null) {
                    String ip = Globals.getInstance().mServerIP.toString();
                    if (ip.startsWith("/"))
                        ip = ip.substring(1);
                    mServerIPTV.setText(getString(R.string.server_status_serving_on, ip));
                    mServerIPTV.setVisibility(View.VISIBLE);
                }
                setNetworkMenu(NETWORK_TYPE_SERVING);
            } else if (NetMsg.NETMSG_SERVERCANCEL.equals(action)) {
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
                    endGame();
                } else {
                    mReady = false;
                    setReady(false);
                }
                Toast.makeText(getApplicationContext(), getString(R.string.error_server_cancel), Toast.LENGTH_SHORT).show();
            } else if (NetMsg.NETMSG_SERVERREPLY.equals(action)) {
                applyServerAssignedPlayerID(intent.getByteExtra(UDPListenerService.INTENT_PLAYERID,
                        (byte) 0));
                if (mTcpClient != null)
                    mTcpClient.startTcpClient();
            } else if (NetMsg.NETMSG_NETWORKCONNECTED.equals(action)) {
                mNetworkStatusIV.setImageResource(R.drawable.ic_network_connected_24dp);
            } else if (NetMsg.NETMSG_NETWORKDISCONNECTED.equals(action)) {
                mNetworkStatusIV.setImageResource(R.drawable.ic_network_disconnected_24dp);
            } else if (NetMsg.NETMSG_PLAYERSETTINGSUPDATE.equals(action)) {
                updatePlayerSettings();
            }
        }
    };

    private static boolean isPeerRoundScopedAction(String action) {
        return NetMsg.NETMSG_ELIMINATED.equals(action)
                || NetMsg.NETMSG_TEAMELIMINATED.equals(action)
                || NetMsg.NETMSG_LEAVE.equals(action)
                || NetMsg.NETMSG_ENDGAME.equals(action);
    }

    private boolean isCurrentPeerRoundEvent(Intent intent) {
        String token = intent.getStringExtra(NetMsg.INTENT_ROUND_TOKEN);
        return token != null && token.equals(mActivePeerRoundToken);
    }

    private void checkPeerScoreLimit() {
        Globals globals = Globals.getInstance();
        // Dedicated games use the server's authoritative totals. Sending ENDGAME
        // here can be interpreted as LEAVE when server-only settings are enabled.
        if (isDedicatedServerConnection() || (globals.mGameLimit & Globals.GAME_LIMIT_SCORE) == 0
                || globals.mScoreLimit <= 0)
            return;
        int winningScore = globals.mGameMode == Globals.GAME_MODE_FFA ? mScore : mTeamScore;
        if (winningScore >= globals.mScoreLimit) {
            endUDPGame();
            endGame();
        }
    }

    private static IntentFilter makeUDPUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(NetMsg.NETMSG_SHOTFIRED);
        intentFilter.addAction(NetMsg.NETMSG_HIT);
        intentFilter.addAction(NetMsg.NETMSG_OUT);
        intentFilter.addAction(NetMsg.NETMSG_ELIMINATED);
        intentFilter.addAction(NetMsg.NETMSG_JOIN);
        intentFilter.addAction(NetMsg.NETMSG_FAILEDTOJOIN);
        intentFilter.addAction(NetMsg.NETMSG_LEAVE);
        intentFilter.addAction(NetMsg.NETMSG_LISTPLAYERS);
        intentFilter.addAction(NetMsg.NETMSG_PLAYERDATAUPDATE);
        intentFilter.addAction(NetMsg.NETMSG_STARTGAME);
        intentFilter.addAction(NetMsg.NETMSG_CLOCKSYNCWAITING);
        intentFilter.addAction(NetMsg.NETMSG_ENDGAME);
        intentFilter.addAction(NetMsg.NETMSG_ERROR);
        intentFilter.addAction(NetMsg.NETMSG_VERSIONERROR);
        intentFilter.addAction(NetMsg.NETMSG_SAMETEAM);
        intentFilter.addAction(NetMsg.NETMSG_SERVERCREATED);
        intentFilter.addAction(NetMsg.NETMSG_SERVERCANCEL);
        intentFilter.addAction(NetMsg.NETMSG_TCPSERVERREADY);
        intentFilter.addAction(NetMsg.NETMSG_TCPSERVERFAILED);
        intentFilter.addAction(NetMsg.NETMSG_SERVERREPLY);
        intentFilter.addAction(NetMsg.NETMSG_TEAMELIMINATED);
        intentFilter.addAction(NetMsg.NETMSG_RESPAWNGRANTED);
        intentFilter.addAction(NetMsg.NETMSG_NETWORKCONNECTED);
        intentFilter.addAction(NetMsg.NETMSG_NETWORKDISCONNECTED);
        intentFilter.addAction(NetMsg.NETMSG_PLAYERSETTINGSUPDATE);
        return intentFilter;
    }
    //TODO player presets
    private void displayAllNetworkingOptions(boolean enabled) {
        int visibility = enabled ? View.VISIBLE : View.INVISIBLE;
        mGameModeLabelTV.setVisibility(visibility);
        mGameModeTV.setVisibility(visibility);
        mScoreLabelTV.setVisibility(visibility);
        mScoreTV.setVisibility(visibility);
        mPlayerNameTV.setVisibility(visibility);
        mServerIPTV.setVisibility(View.GONE);
        if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
            mTeamScoreLabelTV.setVisibility(visibility);
            mTeamScoreTV.setVisibility(visibility);
        } else {
            mTeamScoreLabelTV.setVisibility(View.INVISIBLE);
            mTeamScoreTV.setVisibility(View.INVISIBLE);
        }
    }
    //TODO player presets
    private void displayInGameNetworkingOptions() {
        displayAllNetworkingOptions(false);
        mNetworkPlayerCountTV.setVisibility(View.VISIBLE);
        mGameModeLabelTV.setVisibility(View.VISIBLE);
        mGameModeTV.setVisibility(View.VISIBLE);
        mScoreLabelTV.setVisibility(View.VISIBLE);
        mScoreTV.setVisibility(View.VISIBLE);
        mEndNetworkGameButton.setVisibility(View.VISIBLE);
        if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
            mTeamScoreLabelTV.setVisibility(View.VISIBLE);
            mTeamScoreTV.setVisibility(View.VISIBLE);
        }
    }
//TODO player presets
    private void displayPlayerData(final String message) {
        if (message == null || isFinishing() || isDestroyed()) return;
        if (!mPlayerDataDialogActive.compareAndSet(false, true)) {
            Log.w(TAG, "Ignoring duplicate player-data response while a scoreboard is active");
            return;
        }
        new Thread(() -> {
            boolean dialogPosted = false;
            PlayerDisplayData[] playerDisplayData = new PlayerDisplayData[Globals.MAX_PLAYER_ID + 2];
            for (int x = 1; x <= Globals.MAX_PLAYER_ID; x++)
                playerDisplayData[x] = null;

            boolean hasSemaphore = false;
            long[] teamPoints = new long[4];
            boolean[] seenPlayers = new boolean[Globals.MAX_PLAYER_ID + 1];
            for (int i = 0; i < 4; i++)
                teamPoints[i] = 0;
            try {
                JSONObject json = TcpJson.parseObject(message);
                JSONArray players = json.getJSONArray(TcpServer.JSON_PLAYERDATA);
                if (players.length() > Globals.MAX_PLAYER_ID)
                    throw new JSONException("Too many players in player-data response");
                Globals.getInstance().getmPlayerSettingsSemaphore();
                hasSemaphore = true;
                for (int x = 0; x < players.length(); x++) {
                    JSONObject player = players.getJSONObject(x);
                    PlayerDisplayData playerData = new PlayerDisplayData();
                    int rawPlayerID = TcpJson.getInt(player, TcpServer.JSON_PLAYERID);
                    if (!Globals.isValidPlayerID(rawPlayerID) || rawPlayerID <= 0
                            || rawPlayerID >= playerDisplayData.length || seenPlayers[rawPlayerID]) {
                        Log.w(TAG, "Ignoring player data for invalid player ID " + rawPlayerID);
                        throw new JSONException("Invalid player ID in player-data response");
                    }
                    seenPlayers[rawPlayerID] = true;
                    playerData.playerID = (byte) rawPlayerID;
                    playerData.playerName = TcpJson.getPlayerName(player, TcpServer.JSON_PLAYERNAME);
                    playerData.points = TcpJson.getInt(player, TcpServer.JSON_PLAYERPOINTS);
                    if (playerData.points < 0 || playerData.points > TcpClient.MAX_SCOREBOARD_VALUE)
                        throw new JSONException("Invalid player points in player-data response");
                    if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                        int team = Globals.getInstance().calcNetworkTeam(playerData.playerID);
                        if (team >= 1 && team <= teamPoints.length)
                            teamPoints[team - 1] += playerData.points;
                    }
                    playerData.eliminated = TcpJson.getInt(player, TcpServer.JSON_PLAYERELIMINATED);
                    if (playerData.eliminated < 0 || playerData.eliminated > TcpClient.MAX_SCOREBOARD_VALUE)
                        throw new JSONException("Invalid eliminations in player-data response");
                    Globals.PlayerSettings playerSettings = Globals.getInstance().mPlayerSettings.get(playerData.playerID);
                    if (playerSettings == null) {
                        playerData.overrideLives = false;
                        playerData.lives = 0;
                    } else {
                        playerData.overrideLives = playerSettings.overrideLives;
                        playerData.lives = playerSettings.lives;
                    }
                    playerDisplayData[playerData.playerID] = playerData;
                }
                Globals.getInstance().mPlayerSettingsSemaphore.release();
                hasSemaphore = false;
            } catch (JSONException | RuntimeException e) {
                Log.w(TAG, "Ignoring invalid player-data response", e);
                if (hasSemaphore)
                    Globals.getInstance().mPlayerSettingsSemaphore.release();
                mPlayerDataDialogActive.set(false);
                return;
            }
            try {
                if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                    String total;
                    if (Globals.getInstance().mGameMode == Globals.GAME_MODE_2TEAMS) {
                        total = getString(R.string.player_list_team2_total, teamPoints[0], teamPoints[1]);
                    } else {
                        total = getString(R.string.player_list_team4_total, teamPoints[0], teamPoints[1], teamPoints[2], teamPoints[3]);
                    }
                    playerDisplayData[Globals.MAX_PLAYER_ID + 1] = new PlayerDisplayData();
                    playerDisplayData[Globals.MAX_PLAYER_ID + 1].playerName = total;
                }
                final PlayerDisplayDataListAdapter playerDisplayListAdapter = new PlayerDisplayDataListAdapter(FullscreenActivity.this, playerDisplayData, true);
                runOnUiThread(() -> {
                    try {
                        if (isFinishing() || isDestroyed()) {
                            mPlayerDataDialogActive.set(false);
                            return;
                        }
                        AlertDialog.Builder alertDialog = new AlertDialog.Builder(FullscreenActivity.this);
                        alertDialog.setNegativeButton(R.string.ok,
                                (dialog, id) -> dialog.cancel());
                        LayoutInflater inflater = getLayoutInflater();
                        View view = inflater.inflate(R.layout.player_data_dialog, null);
                        alertDialog.setView(view);
                        ListView listView = view.findViewById(R.id.player_list);
                        listView.setAdapter(playerDisplayListAdapter);
                        AlertDialog dialog = alertDialog.create();
                        dialog.setOnDismissListener(ignored -> mPlayerDataDialogActive.set(false));
                        dialog.show();
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Unable to display player-data response", e);
                        mPlayerDataDialogActive.set(false);
                    }
                });
                dialogPosted = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to prepare player-data response", e);
            } finally {
                if (!dialogPosted)
                    mPlayerDataDialogActive.set(false);
            }
        }).start();
    }
//TODO presets ??
    private void updatePlayerSettings() {
        mHealthLabelTV.setText(getString(R.string.health_label, Globals.getInstance().mFullHealth));
        mShotsRemainingLabelTV.setText(getString(R.string.shots_remaining_label,
                Globals.getInstance().mFullReload & 0xff, (Globals.getInstance().mDamage * -1)));
        setGameLimit();
        // Firing range can change even when the current shot mode is still permitted.
        setShotMode(mCurrentShotMode);
        getFiringMode();
    }
}
