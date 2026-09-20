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

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("NonAtomicOperationOnVolatileField")
public class DedicatedServerActivity extends AppCompatActivity implements PopupMenu.OnMenuItemClickListener {
    private static final String TAG = "DEDSRV";

    private static final String PREF_GPS_MODE = "GPSMode";
//TODO try catch when in no network to avoid crash
    //TODO TTT GAME MODE
    //TODO BUG allow player settings isnt applied to all
    private TextView mServerIPTV = null;
    private Chronometer mGameTimer = null;
    private TextView mGameCountDownTV = null;
    private TextView mNetworkPlayerCountTV = null;
    private Button mGameModeButton = null;
    private Button mGameLimitButton = null;
    private Button mGPSModeButton = null;
    private Button mEndGameButton = null;
    private Button mStartGameButton = null;
    private TextView mGameLimitTV = null;
    private TextView mGameStatusTV = null;
    private Switch mAllowJoinSwitch = null;
    private Switch mOnlyServerSettingsSwitch = null;
    private ListView mPlayerDisplayList = null;

    private CountDownTimer mGameCountdownTimer = null;
    private CountDownTimer mSpawnTimer = null;

    private SharedPreferences sharedPreferences = null;

    private final PlayerDisplayData[] mPlayerDisplayData = new PlayerDisplayData[Globals.MAX_PLAYER_ID + 2];
    PlayerDisplayDataListAdapter mPlayerDisplayListAdapter = null;

    // Code to manage Service lifecycle.
    private ServiceConnection mUDPServiceConnection = null;
    private UDPListenerService mUDPListenerService = null;
    private boolean mUDPServiceBound = false;

    private void setupUDPServiceConnection() {
        if (mUDPServiceBound || isFinishing() || isDestroyed()) return;
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
                mUDPListenerService.createServer();
                mUDPListenerService.allowJoin(Globals.getInstance().mGameState == Globals.GAME_STATE_NONE
                        || mAllowJoinSwitch.isChecked());
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                if (mUDPServiceConnection != this || !mUDPServiceBound || isFinishing() || isDestroyed())
                    return;
                mUDPListenerService = null;
            }
        };
    }

    private TcpServer mTcpServer = null;
    private ServiceConnection mTcpServerServiceConnection = null;
    private boolean mTcpServerServiceBound = false;

    private void setupTcpServerServiceConnection() {
        if (mTcpServerServiceBound || isFinishing() || isDestroyed()) return;
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
                if (mTcpServerServiceConnection != this || !mTcpServerServiceBound || isFinishing() || isDestroyed())
                    return;
                mTcpServer = ((TcpServer.LocalBinder) service).getService();
                mTcpServer.setDedicated(true);
                mTcpServer.startTcpServer();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                if (mTcpServerServiceConnection != this || !mTcpServerServiceBound || isFinishing() || isDestroyed())
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

    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dedicated_server);
        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        Globals.getInstance().mPlayerID = 0;
        mServerIPTV = findViewById(R.id.server_ip_tv);
        mGameTimer = findViewById(R.id.game_timer_chronometer);
        mGameCountDownTV = findViewById(R.id.game_countdown_tv);
        mNetworkPlayerCountTV = findViewById(R.id.player_count_tv);
        mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, 0));
        mGameModeButton = findViewById(R.id.game_mode_toggle_button);
        if (mGameModeButton != null) {
            mGameModeButton.setOnClickListener((v -> {
                PopupMenu popup = new PopupMenu(DedicatedServerActivity.this, v);
                MenuInflater inflater = popup.getMenuInflater();
                inflater.inflate(R.menu.game_mode_menu, popup.getMenu());
                popup.setOnMenuItemClickListener(DedicatedServerActivity.this);
                popup.show();
            }));
        }
        mGameLimitButton = findViewById(R.id.game_limit_button);
        if (mGameLimitButton != null) {
            mGameLimitButton.setOnClickListener((v -> requestGameLimit()));
        }
        mGameLimitTV = findViewById(R.id.game_limit_tv);
        mGameStatusTV = findViewById(R.id.game_status_tv);
        mGPSModeButton = findViewById(R.id.gps_mode_button);
        if (mGPSModeButton != null) {
            mGPSModeButton.setOnClickListener((v -> {
                PopupMenu popup = new PopupMenu(DedicatedServerActivity.this, v);
                MenuInflater inflater = popup.getMenuInflater();
                inflater.inflate(R.menu.gps_mode_menu, popup.getMenu());
                popup.setOnMenuItemClickListener(DedicatedServerActivity.this);
                popup.show();
            }));
        }
        mEndGameButton = findViewById(R.id.end_game_button);
        if (mEndGameButton != null) {
            mEndGameButton.setOnClickListener((v -> {
                if (mTcpServer != null)
                    mTcpServer.endGame();
            }));
        }
        mStartGameButton = findViewById(R.id.start_game_button);
        if (mStartGameButton != null) {
            mStartGameButton.setOnClickListener((v -> {
                if (Globals.getPlayerCount() <= 1) {
                    Toast.makeText(getApplicationContext(), getString(R.string.not_enough_players_toast), Toast.LENGTH_SHORT).show();
                    mNetworkPlayerCountTV.setText(R.string.network_player_1count);
                    return;
                }
                startGame();
            }));
        }
        sharedPreferences = getSharedPreferences(FullscreenActivity.PREF_NAME, Context.MODE_PRIVATE);
        int savedGameMode = sharedPreferences.getInt(FullscreenActivity.PREF_GAME_MODE, Globals.GAME_MODE_2TEAMS);
        Globals.getInstance().mGameMode = Globals.isValidGameMode(savedGameMode) ? savedGameMode : Globals.GAME_MODE_2TEAMS;
        if (Globals.getInstance().mGameMode == Globals.GAME_MODE_2TEAMS)
            mGameModeButton.setText(R.string.game_mode_2teams);
        else if (Globals.getInstance().mGameMode == Globals.GAME_MODE_4TEAMS)
            mGameModeButton.setText(R.string.game_mode_4teams);
        else
            mGameModeButton.setText(R.string.game_mode_ffa);
        Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_NONE;
        int savedTimeLimit = sharedPreferences.getInt(FullscreenActivity.PREF_LIMIT_TIME, 0);
        Globals.getInstance().mTimeLimit = Globals.isValidGameLimit(savedTimeLimit) ? savedTimeLimit : 0;
        if (Globals.getInstance().mTimeLimit != 0)
            Globals.getInstance().mGameLimit += Globals.GAME_LIMIT_TIME;
        int savedLivesLimit = sharedPreferences.getInt(FullscreenActivity.PREF_LIMIT_LIVES, 0);
        Globals.getInstance().mLivesLimit = Globals.isValidGameLimit(savedLivesLimit) ? savedLivesLimit : 0;
        if (Globals.getInstance().mLivesLimit != 0)
            Globals.getInstance().mGameLimit += Globals.GAME_LIMIT_LIVES;
        int savedScoreLimit = sharedPreferences.getInt(FullscreenActivity.PREF_LIMIT_SCORE, 0);
        Globals.getInstance().mScoreLimit = Globals.isValidGameLimit(savedScoreLimit) ? savedScoreLimit : 0;
        if (Globals.getInstance().mScoreLimit != 0)
            Globals.getInstance().mGameLimit += Globals.GAME_LIMIT_SCORE;
        setGameLimit();
        int savedGPSMode = sharedPreferences.getInt(PREF_GPS_MODE, Globals.GPS_ALL);
        Globals.getInstance().mGPSMode = Globals.isValidGPSMode(savedGPSMode) ? savedGPSMode : Globals.GPS_ALL;
        setGPSMode(Globals.getInstance().mGPSMode);
        mAllowJoinSwitch = findViewById(R.id.allow_join_switch);
        mAllowJoinSwitch.setOnClickListener((v -> {
            if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE && mUDPListenerService != null)
                mUDPListenerService.allowJoin(mAllowJoinSwitch.isChecked());
        }));
        mOnlyServerSettingsSwitch = findViewById(R.id.only_server_settings_switch);
        mOnlyServerSettingsSwitch.setOnClickListener((v -> {
            Globals.getInstance().mOnlyServerSettings = mOnlyServerSettingsSwitch.isChecked();
            if (mTcpServer != null)
                mTcpServer.sendAllGameInfo(TcpServer.SEND_ALL);
        }));
        try {
            // Display app version
            PackageInfo pInfo = this.getPackageManager().getPackageInfo(getPackageName(), 0);
            TextView versionTextView = findViewById(R.id.version_tv);
            versionTextView.setText(pInfo.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        getPlayerDisplayData();
        mPlayerDisplayListAdapter = new PlayerDisplayDataListAdapter(DedicatedServerActivity.this, mPlayerDisplayData, false);
        mPlayerDisplayList = findViewById(R.id.player_list);
        mPlayerDisplayList.setAdapter(mPlayerDisplayListAdapter);
        mPlayerDisplayList.setOnItemClickListener((parent, view, position, id) -> {
            if (position == 0 || position > Globals.MAX_PLAYER_ID)
                return;
            PlayerSettingsAlertDialog dialog = new PlayerSettingsAlertDialog(DedicatedServerActivity.this);
            dialog.setServer((byte)position, mTcpServer);
            dialog.show();
        });
        // The server and round timers remain active while this screen is hidden.
        // Keep consuming their events until the server itself is torn down.
        ContextCompat.registerReceiver(this, mServerUpdateReceiver, makeServerUpdateIntentFilter(), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupUDPServiceConnection();
        setupTcpServerServiceConnection();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mServerUpdateReceiver);
        if (mSpawnTimer != null) {
            mSpawnTimer.cancel();
            mSpawnTimer = null;
        }
        if (mGameCountdownTimer != null) {
            mGameCountdownTimer.cancel();
            mGameCountdownTimer = null;
        }
        if (mTcpServer != null) {
            mTcpServer.sendTCPMessageAll(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_SERVERCANCEL);
            mTcpServer.stopTcpServer();
        }
        if (mUDPListenerService != null)
            mUDPListenerService.stopListen();
        unbindUDPService();
        unbindTcpServerService();
        super.onDestroy();
    }

    private void savePreference(String prefName, int prefValue) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(prefName, prefValue);
        editor.apply();
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.game_mode_2teams_item) {
            Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
            mGameModeButton.setText(R.string.game_mode_2teams);
            savePreference(FullscreenActivity.PREF_GAME_MODE, Globals.getInstance().mGameMode);
            setGPSMode(Globals.getInstance().mGPSMode);
            getPlayerDisplayData();
            return true;
        } else if (id == R.id.game_mode_4teams_item) {
            Globals.getInstance().mGameMode = Globals.GAME_MODE_4TEAMS;
            mGameModeButton.setText(R.string.game_mode_4teams);
            savePreference(FullscreenActivity.PREF_GAME_MODE, Globals.getInstance().mGameMode);
            setGPSMode(Globals.getInstance().mGPSMode);
            getPlayerDisplayData();
            return true;
        } else if (id == R.id.game_mode_ffa_item) {
            Globals.getInstance().mGameMode = Globals.GAME_MODE_FFA;
            mGameModeButton.setText(R.string.game_mode_ffa);
            savePreference(FullscreenActivity.PREF_GAME_MODE, Globals.getInstance().mGameMode);
            setGPSMode(Globals.getInstance().mGPSMode);
            getPlayerDisplayData();
            return true;
        } else if (id == R.id.gps_mode_disabled) {
            setGPSMode(Globals.GPS_DISABLED);
            return true;
        } else if (id == R.id.gps_mode_teammate) {
            setGPSMode(Globals.GPS_TEAMMATE);
            return true;
        } else if (id == R.id.gps_mode_all) {
            setGPSMode(Globals.GPS_ALL);
            return true;
        } else {
            return false;
        }

    }
    private void setGPSMode(int mode) {
        if (!Globals.isValidGPSMode(mode))
            mode = Globals.GPS_ALL;
        Globals.getInstance().mGPSMode = mode;
        if (mode == Globals.GPS_DISABLED || (Globals.getInstance().mGameMode == Globals.GAME_MODE_FFA && mode == Globals.GPS_TEAMMATE)) {
            Globals.getInstance().mUseGPS = false;
            mGPSModeButton.setText(R.string.gps_mode_disabled);
        } else {
            Globals.getInstance().mUseGPS = true;
            if (mTcpServer != null)
                mTcpServer.sendGPSData();
            if (mode == Globals.GPS_TEAMMATE)
                mGPSModeButton.setText(R.string.gps_mode_teammate);
            else
                mGPSModeButton.setText(R.string.gps_mode_all);
        }
        savePreference(PREF_GPS_MODE, mode);
        if (mTcpServer != null)
            mTcpServer.sendAllGameInfo(TcpServer.SEND_ALL);
    }

    private void requestGameLimit() {
        LayoutInflater li = LayoutInflater.from(getApplicationContext());
        View view = li.inflate(R.layout.game_limit_dialog, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        alertDialogBuilder.setView(view);

        final EditText gameLimitET = view.findViewById(R.id.game_limit_et);
        final RadioButton gameLimitTime = view.findViewById(R.id.game_limit_time_radio);
        gameLimitTime.setChecked(true);
        final RadioButton gameLimitLives = view.findViewById(R.id.game_limit_lives_radio);
        gameLimitLives.setChecked(false);
        final RadioButton gameLimitScore = view.findViewById(R.id.game_limit_score_radio);
        gameLimitScore.setChecked(false);
        gameLimitScore.setVisibility(View.VISIBLE);
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

        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton(R.string.ok,
                        (dialog, id) -> {
                            if (gameLimitET.getText().toString().isEmpty()) {
                                dialog.dismiss();
                                return;
                            }
                            int limit;
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
        String gameLimits = "";
        if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0) {
            gameLimits += getString(R.string.dedicated_time_limit, Globals.getInstance().mTimeLimit);
            mGameCountDownTV.setVisibility(View.VISIBLE);
            String display = String.format(Locale.getDefault(),"%02d:00", Globals.getInstance().mTimeLimit);
            mGameCountDownTV.setText(display);
            mGameTimer.setVisibility(View.GONE);
        } else {
            mGameCountDownTV.setVisibility(View.GONE);
            mGameTimer.setVisibility(View.VISIBLE);
        }
        if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_LIVES) != 0) {
            if (!gameLimits.isEmpty())
                gameLimits += getString(R.string.dedicated_limit_separator);
            gameLimits += getString(R.string.dedicated_lives_limit, Globals.getInstance().mLivesLimit);
        }
        if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_SCORE) != 0) {
            if (!gameLimits.isEmpty())
                gameLimits += getString(R.string.dedicated_limit_separator);
            gameLimits += getString(R.string.dedicated_score_limit, Globals.getInstance().mScoreLimit);
        }
        if (gameLimits.isEmpty())
            gameLimits = getString(R.string.dedicated_unlimited);
        mGameLimitTV.setText(gameLimits);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(FullscreenActivity.PREF_LIMIT_TIME, Globals.getInstance().mTimeLimit);
        editor.putInt(FullscreenActivity.PREF_LIMIT_SCORE, Globals.getInstance().mScoreLimit);
        editor.putInt(FullscreenActivity.PREF_LIMIT_LIVES, Globals.getInstance().mLivesLimit);
        editor.apply();
        if (mTcpServer != null)
            mTcpServer.sendAllGameInfo(TcpServer.SEND_ALL);
    }

    private void startGame() {
        if (isFinishing() || isDestroyed())
            return;
        if (mTcpServer == null || mUDPListenerService == null) {
            Log.w(TAG, "Ignoring game start before dedicated server services are ready");
            return;
        }
        if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
            Log.d(TAG, "Ignoring duplicate dedicated game-start event");
            return;
        }
        if (!mTcpServer.startGame()) {
            Log.w(TAG, "Ignoring game start before a TCP client is connected");
            Toast.makeText(getApplicationContext(), getString(R.string.not_enough_players_toast), Toast.LENGTH_SHORT).show();
            return;
        }
        onGameStarted();
    }

    private void onGameStarted() {
        // A STARTGAME notification confirms a start the server already sent.
        // Applying it locally must not issue another start request to clients.
        if (isFinishing() || isDestroyed() || Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
            return;
        Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
        mStartGameButton.setEnabled(false);
        mGameModeButton.setEnabled(false);
        mGameLimitButton.setEnabled(false);
        mGPSModeButton.setEnabled(false);
        mGameStatusTV.setText(R.string.dedicated_game_running);
        if (mUDPListenerService != null)
            mUDPListenerService.allowJoin(mAllowJoinSwitch.isChecked());
        mEndGameButton.setEnabled(true);
        if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0) {
            startGameCountdown();
        } else {
            if (mSpawnTimer != null)
                mSpawnTimer.cancel();
            mSpawnTimer = new CountDownTimer(Globals.getInstance().mRespawnTime * 1000, 999) {

                public void onTick(long millisUntilFinished) {
                    // Do nothing
                }

                public void onFinish() {
                    if (mSpawnTimer != this || Globals.getInstance().mGameState != Globals.GAME_STATE_RUNNING)
                        return;
                    mSpawnTimer = null;
                    mGameTimer.setBase(SystemClock.elapsedRealtime());
                    mGameTimer.start();
                }
            };
            mSpawnTimer.start();
        }
    }

    private void endGame() {
        mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, Globals.getPlayerCount()));
        mStartGameButton.setEnabled(true);
        mGameModeButton.setEnabled(true);
        mGameLimitButton.setEnabled(true);
        mGPSModeButton.setEnabled(true);
        mGameStatusTV.setText(R.string.dedicated_game_waiting);
        mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, 0));
        if (mUDPListenerService != null)
            mUDPListenerService.allowJoin(true);
        mEndGameButton.setEnabled(false);
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Globals.getInstance().mServerGameTimeRemaining = 0;
        mGameTimer.stop();
        if (mSpawnTimer != null) {
            mSpawnTimer.cancel();
            mSpawnTimer = null;
        }
        if (mGameCountdownTimer != null) {
            mGameCountdownTimer.cancel();
            mGameCountdownTimer = null;
        }
    }

    private final BroadcastReceiver mServerUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isFinishing() || isDestroyed())
                return;
            final String action = intent.getAction();
            if (NetMsg.NETMSG_JOIN.equals(action) || NetMsg.NETMSG_LEAVE.equals(action)) {
                mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, Globals.getPlayerCount() - 1));
                if (Globals.getInstance().mGameMode == Globals.GAME_MODE_FFA && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE && NetMsg.NETMSG_LEAVE.equals(action) && Globals.getPlayerCount() <= 1)
                    endGame(); // Everyone else is out so game is over - this only works in FFA because we don't keep track of who and how many people are on each team
                getPlayerDisplayData();
            } else if (NetMsg.NETMSG_STARTGAME.equals(action)) {
                onGameStarted();
            } else if (NetMsg.NETMSG_ENDGAME.equals(action)) {
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                    endGame();
            } else if (NetMsg.NETMSG_ERROR.equals(action)) {
                String errorMessage = intent.getStringExtra(UDPListenerService.INTENT_MESSAGE);
                if (errorMessage != null && !errorMessage.isEmpty()) {
                    Toast.makeText(getApplicationContext(), errorMessage, Toast.LENGTH_SHORT).show();
                } else {
                    Log.d(TAG, "No error message given");
                }
            } else if (NetMsg.NETMSG_SERVERCREATED.equals(action)) {
                // UDP service is listening
                if (Globals.getInstance().mServerIP != null) {
                    String ip = Globals.getInstance().mServerIP.toString();
                    if (ip.startsWith("/"))
                        ip = ip.substring(1);
                    mServerIPTV.setText(getString(R.string.server_status_serving_on, ip));
                }
            } else if (NetMsg.NETMSG_SERVERCANCEL.equals(action)) {
                mServerIPTV.setText("");
                Toast.makeText(getApplicationContext(), getString(R.string.error_server_cancel), Toast.LENGTH_SHORT).show();
            } else if (NetMsg.NETMSG_PLAYERDATAUPDATE.equals(action)) {
                getPlayerDisplayData();
            }
        }
    };

    private static IntentFilter makeServerUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(NetMsg.NETMSG_JOIN);
        intentFilter.addAction(NetMsg.NETMSG_LEAVE);
        intentFilter.addAction(NetMsg.NETMSG_STARTGAME);
        intentFilter.addAction(NetMsg.NETMSG_ENDGAME);
        intentFilter.addAction(NetMsg.NETMSG_ERROR);
        intentFilter.addAction(NetMsg.NETMSG_SERVERCREATED);
        intentFilter.addAction(NetMsg.NETMSG_SERVERCANCEL);
        intentFilter.addAction(NetMsg.NETMSG_PLAYERDATAUPDATE);
        return intentFilter;
    }
    private void getPlayerDisplayData() {
        if (mTcpServer == null) return;
        Map<Byte, String> playerNames = new HashMap<>();
        Globals.getmTeamPlayerNameSemaphore();
        try {
            playerNames.putAll(Globals.getInstance().mTeamPlayerNameMap);
        } finally {
            Globals.getInstance().mTeamPlayerNameSemaphore.release();
        }

        Map<Byte, Globals.PlayerSettings> playerSettings = new HashMap<>();
        Globals.getmPlayerSettingsSemaphore();
        try {
            for (Map.Entry<Byte, Globals.PlayerSettings> entry : Globals.getInstance().mPlayerSettings.entrySet()) {
                Globals.PlayerSettings settings = new Globals.PlayerSettings();
                settings.overrideLives = entry.getValue().overrideLives;
                settings.lives = entry.getValue().lives;
                playerSettings.put(entry.getKey(), settings);
            }
        } finally {
            Globals.getInstance().mPlayerSettingsSemaphore.release();
        }

        int[] teamPoints = new int[4];
        for (int i = 0; i < 4; i++)
            teamPoints[i] = 0;
        for (byte x = 0; x <= Globals.MAX_PLAYER_ID + 1; x++) {
            if (x == Globals.MAX_PLAYER_ID + 1) {
                // Team totals
                if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                    String total;
                    if (Globals.getInstance().mGameMode == Globals.GAME_MODE_2TEAMS) {
                        total = getString(R.string.player_list_team2_total, teamPoints[0], teamPoints[1]);
                    } else {
                        total = getString(R.string.player_list_team4_total, teamPoints[0], teamPoints[1], teamPoints[2], teamPoints[3]);
                    }
                    if (mPlayerDisplayData[x] == null)
                        mPlayerDisplayData[x] = new PlayerDisplayData();
                    mPlayerDisplayData[x].playerName = total;
                } else {
                    if (mPlayerDisplayData[x] == null)
                        mPlayerDisplayData[x] = new PlayerDisplayData();
                    mPlayerDisplayData[x].playerName = "";
                }
                break;
            }
            TcpServer.ScoreData scoreData = mTcpServer.getScore(x);
            if (scoreData == null) {
                mPlayerDisplayData[x] = null;
            } else {
                mPlayerDisplayData[x] = new PlayerDisplayData();
                mPlayerDisplayData[x].playerID = x;
                mPlayerDisplayData[x].playerName = playerNames.get(x);
                mPlayerDisplayData[x].points = scoreData.points;
                if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                    int team = Globals.getInstance().calcNetworkTeam(x);
                    if (team >= 1 && team <= teamPoints.length)
                        teamPoints[team - 1] += scoreData.points;
                }
                mPlayerDisplayData[x].eliminated = scoreData.eliminated;
                mPlayerDisplayData[x].isConnected = scoreData.isConnected;
                Globals.PlayerSettings settings = playerSettings.get(x);
                if (settings == null) {
                    mPlayerDisplayData[x].overrideLives = false;
                    mPlayerDisplayData[x].lives = 0;
                } else {
                    mPlayerDisplayData[x].overrideLives = settings.overrideLives;
                    mPlayerDisplayData[x].lives = settings.lives;
                }
            }
        }
        if (mPlayerDisplayListAdapter != null)
            mPlayerDisplayListAdapter.setData(mPlayerDisplayData);
    }

    private void startGameCountdown() {
        if (mGameCountdownTimer != null)
            mGameCountdownTimer.cancel();
        mGameCountdownTimer = new CountDownTimer(((long) Globals.getInstance().mTimeLimit * 60 * 1000) + (Globals.getInstance().mRespawnTime * 1000), 1000) {

            public void onTick(long millisUntilFinished) {
                if (mGameCountdownTimer != this || Globals.getInstance().mGameState != Globals.GAME_STATE_RUNNING)
                    return;
                String display = ""+String.format(Locale.getDefault(),"%02d:%02d",
                        TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished),
                        TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) - TimeUnit.MINUTES.toSeconds(
                                TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished)));
                mGameCountDownTV.setText(display);
                Globals.getInstance().mServerGameTimeRemaining = millisUntilFinished / 1000;
            }

            public void onFinish() {
                if (mGameCountdownTimer != this || Globals.getInstance().mGameState != Globals.GAME_STATE_RUNNING)
                    return;
                mGameCountdownTimer = null;
                Log.d(TAG, "Game time ended!");
                Toast.makeText(getApplicationContext(), getString(R.string.dialog_game_time_expired), Toast.LENGTH_SHORT).show();
                if (mTcpServer != null)
                    mTcpServer.endGame();
                Globals.getInstance().mServerGameTimeRemaining = 0;
            }
        };
        mGameCountdownTimer.start();
    }

}
