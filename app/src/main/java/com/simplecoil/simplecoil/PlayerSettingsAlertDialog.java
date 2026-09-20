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

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class PlayerSettingsAlertDialog extends AlertDialog implements PopupMenu.OnMenuItemClickListener {
    private TextView mTitleTV = null;
    private EditText mHealthET = null;
    private EditText mReloadShotsET = null;
    private EditText mReloadTimeET = null;
    private Switch mReloadOnEmptySwitch = null;
    private EditText mSpawnTimeET = null;
    private EditText mDamageET = null;
    private Switch mOverrideLivesSwitch = null;
    private EditText mLivesET = null;
    private Switch mShotModeSingle = null;
    private Switch mShotModeBurst3 = null;
    private Switch mShotModeAuto = null;
    private Button mFiringModeButton = null;
    private Button mResetButton = null;
    private Switch mApplyAllSwitch = null;
    private Switch mVibratePhoneSwitch = null;
    private Switch mAllowPlayerSettingsSwitch = null;
    private Button mWeaponPresetButton = null;
    private Button mPlayerPresetButton = null;

    private boolean isServer = false;
    private byte mPlayerID = 0;

    private TcpServer mTcpServer = null;
    private TcpClient mTcpClient = null;

    private final Context mContext;

    public PlayerSettingsAlertDialog(Context context) {
        super(context);
        mContext = context;
    }

    public void setServer(byte playerID, TcpServer tcpServer) {
        isServer = true;
        mPlayerID = playerID;
        mTcpServer = tcpServer;
    }

    public void getServerSettings() {
        Globals.getmPlayerSettingsSemaphore();
        try {
        Globals.PlayerSettings playerSettings = Globals.getInstance().mPlayerSettings.get(mPlayerID);
        if (playerSettings == null)
            playerSettings = new Globals.PlayerSettings();
        switch (Globals.getInstance().mGameMode) {
            case Globals.GAME_MODE_FFA:
                mTitleTV.setText(mContext.getString(R.string.player_settings_id_title, mPlayerID));
                break;
            case Globals.GAME_MODE_2TEAMS:
                if (mPlayerID > Globals.MAX_PLAYER_ID / 2)
                    mTitleTV.setText(mContext.getString(R.string.player_settings_team_title, 2, (mPlayerID - (Globals.MAX_PLAYER_ID / 2))));
                else
                    mTitleTV.setText(mContext.getString(R.string.player_settings_team_title, 1, mPlayerID));
                break;
            case Globals.GAME_MODE_4TEAMS:
                int playersPerTeam = Globals.MAX_PLAYER_ID / 4;
                if (mPlayerID > playersPerTeam * 3)
                    mTitleTV.setText(mContext.getString(R.string.player_settings_team_title, 4, (mPlayerID - (playersPerTeam * 3))));
                else if (mPlayerID > playersPerTeam * 2)
                    mTitleTV.setText(mContext.getString(R.string.player_settings_team_title, 3, (mPlayerID - (playersPerTeam * 2))));
                else if (mPlayerID > playersPerTeam)
                    mTitleTV.setText(mContext.getString(R.string.player_settings_team_title, 2, (mPlayerID - playersPerTeam)));
                else
                    mTitleTV.setText(mContext.getString(R.string.player_settings_team_title, 1, mPlayerID));
                break;
        }
        mHealthET.setText("" + playerSettings.health);
        mReloadShotsET.setText("" + (playerSettings.shots & 0xff));
        mReloadTimeET.setText("" + playerSettings.reloadTime);
        mReloadOnEmptySwitch.setChecked(playerSettings.reloadOnEmpty);
        mSpawnTimeET.setText("" + playerSettings.spawnTime);
        mDamageET.setText("" + (playerSettings.damage * -1));
        mOverrideLivesSwitch.setChecked(playerSettings.overrideLives);
        mLivesET.setText("" + playerSettings.lives);
        mShotModeSingle.setChecked(playerSettings.allowShotModeSingle);
        mShotModeBurst3.setChecked(playerSettings.allowShotModeBurst3);
        mShotModeAuto.setChecked(playerSettings.allowShotModeAuto);
        mFiringModeButton.setVisibility(View.VISIBLE);
        switch (playerSettings.firingMode) {
            case Globals.FIRING_MODE_OUTDOOR_NO_CONE:
                mFiringModeButton.setText(R.string.firing_mode_outdoor_no_cone);
                break;
            case Globals.FIRING_MODE_OUTDOOR_WITH_CONE:
                mFiringModeButton.setText(R.string.firing_mode_outdoor_with_cone);
                break;
            case Globals.FIRING_MODE_INDOOR_NO_CONE:
                mFiringModeButton.setText(R.string.firing_mode_indoor_no_cone);
                break;
        }//TODO check presets
       /* switch (playerSettings.playerPreset){
            case Globals.PLAYER_PRESET_RECON:
                    mPlayerPresetButton.setText(R.string.player_preset_recon);
                    break;
            case Globals.PLAYER_PRESET_JUGGERNAUT:
                mPlayerPresetButton.setText(R.string.player_preset_juggernaut);
                break;
            case Globals.PLAYER_PRESET_TANK:
                mPlayerPresetButton.setText(R.string.player_preset_tank);
                break;
            case Globals.PLAYER_PRESET_MEDIC:
                mPlayerPresetButton.setText(R.string.player_preset_medic);
                break;
            case Globals.PLAYER_PRESET_DEFAULT:
                mPlayerPresetButton.setText(R.string.player_preset_button);
                break;
        }

        switch(playerSettings.weaponPreset){
            case Globals.WEAPON_PRESET_SNIPER:
                mWeaponPresetButton.setText(R.string.weapon_preset_sniper);
                break;
            case Globals.WEAPON_PRESET_SHOTGUN:
                mWeaponPresetButton.setText(R.string.weapon_preset_shotgun);
                break;
            case Globals.WEAPON_PRESET_LMG:
                mWeaponPresetButton.setText(R.string.weapon_preset_lmg);
                break;
            case Globals.WEAPON_PRESET_MP:
                mWeaponPresetButton.setText(R.string.weapon_preset_mp);
                break;
            case Globals.WEAPON_PRESET_CARBINE:
                mWeaponPresetButton.setText(R.string.weapon_preset_carbine);
                break;
            case Globals.WEAPON_PRESET_PISTOL:
                mWeaponPresetButton.setText(R.string.weapon_preset_pistol);
                break;
            case Globals.WEAPON_PRESET_DEFAULT:
                mWeaponPresetButton.setText(R.string.weapon_preset_button);
                break;
        }
*/
        } finally {
            Globals.getInstance().mPlayerSettingsSemaphore.release();
        }
        mApplyAllSwitch.setChecked(false);
        mAllowPlayerSettingsSwitch.setChecked(Globals.getInstance().mAllowPlayerSettings);
        // Vibration is a local accessibility/feedback preference, never a
        // remote player's game rule.
        mVibratePhoneSwitch.setVisibility(View.GONE);
    }

    public void setLocal(TcpClient tcpClient) {
        isServer = false;
        mTcpClient = tcpClient;
    }
//TODO presets ??
    private void getLocalSettings() {
        mHealthET.setText("" + Globals.getInstance().mFullHealth);
        mReloadShotsET.setText("" + (Globals.getInstance().mFullReload & 0xff));
        mReloadTimeET.setText("" + Globals.getInstance().mReloadTime);
        mReloadOnEmptySwitch.setChecked(Globals.getInstance().mReloadOnEmpty);
        mSpawnTimeET.setText("" + Globals.getInstance().mRespawnTime);
        mDamageET.setText("" + (Globals.getInstance().mDamage * -1));
        mOverrideLivesSwitch.setChecked(Globals.getInstance().mOverrideLives);
        mLivesET.setText("" + Globals.getInstance().mOverrideLivesVal);
        mShotModeSingle.setChecked(Globals.getInstance().mAllowSingleShotMode);
        mShotModeBurst3.setChecked(Globals.getInstance().mAllowBurst3ShotMode);
        mShotModeAuto.setChecked(Globals.getInstance().mAllowAutoShotMode);
        mVibratePhoneSwitch.setVisibility(View.VISIBLE);
        mVibratePhoneSwitch.setChecked(Globals.getInstance().mVibrateOnHit);
        mAllowPlayerSettingsSwitch.setVisibility(View.GONE);
        mFiringModeButton.setVisibility(View.GONE);
        mApplyAllSwitch.setVisibility(View.GONE);
        //works but changes to the numbers assigned
        //dirty fix with switch case
       /* switch (Globals.getInstance().mCurrentPlayerPreset){
            case Globals.PLAYER_PRESET_RECON:
                mPlayerPresetButton.setText(R.string.player_preset_recon);
                break;
            case Globals.PLAYER_PRESET_JUGGERNAUT:
                mPlayerPresetButton.setText(R.string.player_preset_juggernaut);
                break;
            case Globals.PLAYER_PRESET_TANK:
                mPlayerPresetButton.setText(R.string.player_preset_tank);
                break;
            case Globals.PLAYER_PRESET_MEDIC:
                mPlayerPresetButton.setText(R.string.player_preset_medic);
                break;
            case Globals.PLAYER_PRESET_DEFAULT:
                mPlayerPresetButton.setText(R.string.player_preset_button);
                break;
        }


        switch(Globals.getInstance().mCurrentWeaponPreset){
            case Globals.WEAPON_PRESET_SNIPER:
                mWeaponPresetButton.setText(R.string.weapon_preset_sniper);
                break;
            case Globals.WEAPON_PRESET_SHOTGUN:
                mWeaponPresetButton.setText(R.string.weapon_preset_shotgun);
                break;
            case Globals.WEAPON_PRESET_LMG:
                mWeaponPresetButton.setText(R.string.weapon_preset_lmg);
                break;
            case Globals.WEAPON_PRESET_MP:
                mWeaponPresetButton.setText(R.string.weapon_preset_mp);
                break;
            case Globals.WEAPON_PRESET_CARBINE:
                mWeaponPresetButton.setText(R.string.weapon_preset_carbine);
                break;
            case Globals.WEAPON_PRESET_PISTOL:
                mWeaponPresetButton.setText(R.string.weapon_preset_pistol);
                break;
            case Globals.WEAPON_PRESET_DEFAULT:
                mWeaponPresetButton.setText(R.string.weapon_preset_button);
                break;
        }
*/    }

    private int readBoundedInt(EditText field, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(field.getText().toString().trim());
            return value >= min && value <= max ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LayoutInflater inflater = getLayoutInflater();
        View view = inflater.inflate(R.layout.player_settings_dialog, null);
        setView(view);
        mTitleTV = view.findViewById(R.id.title_tv);
       mWeaponPresetButton = view.findViewById(R.id.weapon_preset_button);
       mWeaponPresetButton.setOnClickListener(view13-> {
           PopupMenu popup = new PopupMenu(getContext(), view13);
           MenuInflater inflater1 = popup.getMenuInflater();
           inflater1.inflate(R.menu.weapon_presets, popup.getMenu());
           popup.setOnMenuItemClickListener(PlayerSettingsAlertDialog.this);
           popup.show();
       });
       mPlayerPresetButton = view.findViewById(R.id.player_preset_button);
       mPlayerPresetButton.setOnClickListener(view14-> {
            PopupMenu popup = new PopupMenu(getContext(), view14);
            MenuInflater inflater1 = popup.getMenuInflater();
            inflater1.inflate(R.menu.player_presets, popup.getMenu());
            popup.setOnMenuItemClickListener(PlayerSettingsAlertDialog.this);
            popup.show();
        });
        mHealthET = view.findViewById(R.id.health_et);
        mReloadShotsET = view.findViewById(R.id.reload_shots_et);
        mReloadTimeET = view.findViewById(R.id.reload_time_et);
        mReloadOnEmptySwitch = view.findViewById(R.id.reload_on_empty_switch);
        mSpawnTimeET = view.findViewById(R.id.spawn_time_et);
        mDamageET = view.findViewById(R.id.damage_et);
        mOverrideLivesSwitch = view.findViewById(R.id.override_lives_limit_switch);
        mLivesET = view.findViewById(R.id.lives_et);
        mShotModeSingle = view.findViewById(R.id.shot_mode_single);
        mShotModeBurst3 = view.findViewById(R.id.shot_mode_burst3);
        mShotModeAuto = view.findViewById(R.id.shot_mode_auto);
        mFiringModeButton = view.findViewById(R.id.firing_mode_button);
        mFiringModeButton.setOnClickListener(view12 -> {
            PopupMenu popup = new PopupMenu(getContext(), view12);
            MenuInflater inflater1 = popup.getMenuInflater();
            inflater1.inflate(R.menu.firing_mode_menu, popup.getMenu());
            popup.setOnMenuItemClickListener(PlayerSettingsAlertDialog.this);
            popup.show();
            Toast.makeText(getContext(), getContext().getString(R.string.firing_mode_cone_toast), Toast.LENGTH_LONG).show();
        });
       //Reset to defaults if default button pressed
        mResetButton = view.findViewById(R.id.reset_defaults_button);
        mResetButton.setOnClickListener(view1 -> {
          //  mWeaponPresetButton.setText(R.string.weapon_preset_button);
          //  mPlayerPresetButton.setText(R.string.player_preset_button);
            //dirty fix ?? TODO
           // Globals.getInstance().mCurrentPlayerPreset = Globals.PLAYER_PRESET_DEFAULT;
           // Globals.getInstance().mCurrentWeaponPreset = Globals.WEAPON_PRESET_DEFAULT;

            mHealthET.setText("" + Globals.MAX_HEALTH);
            mReloadShotsET.setText("" + Globals.RELOAD_COUNT);
            mReloadTimeET.setText("" + Globals.RELOAD_TIME_MILLISECONDS);
            mReloadOnEmptySwitch.setChecked(false);
            mSpawnTimeET.setText("" + Globals.RESPAWN_TIME_SECONDS);
            mDamageET.setText("" + (Globals.DAMAGE_PER_HIT * -1));
            mOverrideLivesSwitch.setChecked(false);
            mShotModeAuto.setChecked(true);
            mShotModeBurst3.setChecked(true);
            mShotModeSingle.setChecked(true);
            mFiringModeButton.setText(R.string.firing_mode_outdoor_no_cone);
            // Local dialogs hide the range selector, so Reset defaults is the
            // only way to restore its value. Keep the value sent to the server
            // in sync with the reset label rather than retaining a prior range.
            if (!isServer)
                Globals.getInstance().mCurrentFiringMode = Globals.FIRING_MODE_OUTDOOR_NO_CONE;
            if (!isServer)
                mVibratePhoneSwitch.setChecked(false);
            mLivesET.setText("" + 0);

        });
        //TODO presets apply to all players Firing mod

        //TODO vibrate switch
        mVibratePhoneSwitch = view.findViewById(R.id.vibrate_phone_switch);

        //Change border values
        //aplying settings to player
        mAllowPlayerSettingsSwitch = view.findViewById(R.id.allow_player_settings_switch);
        mApplyAllSwitch = view.findViewById(R.id.apply_to_all_switch);
        View.OnClickListener saveSettings =
                button -> {
                    if (!mShotModeSingle.isChecked() && !mShotModeBurst3.isChecked() && !mShotModeAuto.isChecked()) {
                        Toast.makeText(getContext(), getContext().getString(R.string.player_settings_shot_mode_error), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int health = readBoundedInt(mHealthET, Globals.MAX_HEALTH, 1, 1000);
                    int reloadShots = readBoundedInt(mReloadShotsET, Globals.RELOAD_COUNT, 1, 255);
                    int reloadTime = readBoundedInt(mReloadTimeET, (int) Globals.RELOAD_TIME_MILLISECONDS, 0, 10000);
                    int spawnTime = readBoundedInt(mSpawnTimeET, (int) Globals.RESPAWN_TIME_SECONDS, 1, 1000);
                    int damage = -readBoundedInt(mDamageET, -Globals.DAMAGE_PER_HIT, 1, 1000);
                    int lives = readBoundedInt(mLivesET, 0, 0, 1000);
                    if (!isServer) {
                        Globals.getInstance().mFullHealth = health;
                        Globals.getInstance().mFullReload = (byte) reloadShots;
                        Globals.getInstance().mReloadTime = reloadTime;
                        Globals.getInstance().mReloadOnEmpty = mReloadOnEmptySwitch.isChecked();
                        Globals.getInstance().mRespawnTime = spawnTime;
                        Globals.getInstance().mDamage = damage;
                        Globals.getInstance().mOverrideLives = mOverrideLivesSwitch.isChecked();
                        Globals.getInstance().mOverrideLivesVal = lives;
                        Globals.getInstance().mAllowSingleShotMode = mShotModeSingle.isChecked();
                        Globals.getInstance().mAllowBurst3ShotMode = mShotModeBurst3.isChecked();
                        Globals.getInstance().mAllowAutoShotMode = mShotModeAuto.isChecked();
                        Globals.getInstance().mVibrateOnHit = mVibratePhoneSwitch.isChecked();
                        mContext.getSharedPreferences(FullscreenActivity.PREF_NAME, Context.MODE_PRIVATE)
                                .edit().putBoolean(FullscreenActivity.PREF_VIBRATE_ON_HIT,
                                        Globals.getInstance().mVibrateOnHit).apply();
                      //TODO check
                       // Globals.getInstance().mAllowPlayerSettings = mAllowPlayerSettingsSwitch.isChecked();



                        if (mTcpClient != null) {
                            mTcpClient.sendPlayerSettings();
                        }
                        mContext.sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
                    } else {
                        Globals.getmPlayerSettingsSemaphore();
                        try {
                        Globals.PlayerSettings playerSettings = Globals.getInstance().mPlayerSettings.get(mPlayerID);
                        if (playerSettings == null) {
                            playerSettings = new Globals.PlayerSettings();
                            Globals.getInstance().mPlayerSettings.put(mPlayerID, playerSettings);
                        }
                        playerSettings.health = health;
                        playerSettings.shots = (byte) reloadShots;
                        playerSettings.reloadTime = reloadTime;
                        playerSettings.reloadOnEmpty = mReloadOnEmptySwitch.isChecked();
                        playerSettings.spawnTime = spawnTime;
                        playerSettings.damage = damage;
                        playerSettings.overrideLives = mOverrideLivesSwitch.isChecked();
                        playerSettings.lives = lives;
                        playerSettings.allowShotModeSingle = mShotModeSingle.isChecked();
                        playerSettings.allowShotModeBurst3 = mShotModeBurst3.isChecked();
                        playerSettings.allowShotModeAuto = mShotModeAuto.isChecked();
                        // Saving local host settings must not depend on a live service binding.
                        Globals.getInstance().mAllowPlayerSettings = mAllowPlayerSettingsSwitch.isChecked();

                        if (mFiringModeButton.getText().equals(getContext().getString(R.string.firing_mode_outdoor_no_cone))) {
                            playerSettings.firingMode = Globals.FIRING_MODE_OUTDOOR_NO_CONE;
                        }else if (mFiringModeButton.getText().equals(getContext().getString(R.string.firing_mode_outdoor_with_cone)))
                            playerSettings.firingMode = Globals.FIRING_MODE_OUTDOOR_WITH_CONE;
                        else {
                            playerSettings.firingMode = Globals.FIRING_MODE_INDOOR_NO_CONE;
                        }//TODO add presets
                       /* if(mPlayerPresetButton.getText().equals(getContext().getString(R.string.player_preset_recon)))
                            playerSettings.playerPreset = Globals.PLAYER_PRESET_RECON;
                        else if(mPlayerPresetButton.getText().equals(getContext().getString(R.string.player_preset_juggernaut)))
                            playerSettings.playerPreset = Globals.PLAYER_PRESET_JUGGERNAUT;
                        else if(mPlayerPresetButton.getText().equals(getContext().getString(R.string.player_preset_tank)))
                            playerSettings.playerPreset = Globals.PLAYER_PRESET_TANK;
                        else if(mPlayerPresetButton.getText().equals(getContext().getString(R.string.player_preset_medic)))
                            playerSettings.playerPreset = Globals.PLAYER_PRESET_MEDIC;
                        else {
                            playerSettings.playerPreset = Globals.PLAYER_PRESET_DEFAULT;
                        }
                        //WEapon presets
                        if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_sniper)))
                            playerSettings.weaponPreset = Globals.WEAPON_PRESET_SNIPER;
                        else if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_shotgun)))
                            playerSettings.weaponPreset = Globals.WEAPON_PRESET_SHOTGUN;
                        else if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_lmg)))
                            playerSettings.weaponPreset = Globals.WEAPON_PRESET_LMG;
                        else if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_mp)))
                            playerSettings.weaponPreset = Globals.WEAPON_PRESET_MP;
                        else if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_carbine)))
                            playerSettings.weaponPreset = Globals.WEAPON_PRESET_CARBINE;
                        else if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_pistol)))
                            playerSettings.weaponPreset = Globals.WEAPON_PRESET_PISTOL;
                        else {
                            playerSettings.weaponPreset = Globals.WEAPON_PRESET_DEFAULT;
                        }
                        /////
                        */
/*Code crashes
                           if (mFiringModeButton.getText().equals(getContext().getString(R.string.firing_mode_outdoor_no_cone)))
                            mFiringModeButton.setText(Globals.FIRING_MODE_OUTDOOR_NO_CONE);
                        else if (mFiringModeButton.getText().equals(getContext().getString(R.string.firing_mode_outdoor_with_cone)))
                            mFiringModeButton.setText(Globals.FIRING_MODE_OUTDOOR_WITH_CONE);
                        else {
                            mFiringModeButton.setText(Globals.FIRING_MODE_INDOOR_NO_CONE);
                        }//TODO add presets
                        if(mPlayerPresetButton.getText().equals(getContext().getString(R.string.player_preset_recon)))
                            mPlayerPresetButton.setText(Globals.PLAYER_PRESET_RECON);
                        else if(mPlayerPresetButton.getText().equals(getContext().getString(R.string.player_preset_juggernaut)))
                            mPlayerPresetButton.setText(Globals.PLAYER_PRESET_JUGGERNAUT);
                        else if(mPlayerPresetButton.getText().equals(getContext().getString(R.string.player_preset_tank)))
                            mPlayerPresetButton.setText(Globals.PLAYER_PRESET_TANK);
                        else if(mPlayerPresetButton.getText().equals(getContext().getString(R.string.player_preset_medic)))
                            mPlayerPresetButton.setText(Globals.PLAYER_PRESET_MEDIC);
                        else {
                            mPlayerPresetButton.setText(Globals.PLAYER_PRESET_DEFAULT);
                        }
                        //WEapon presets
                        if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_sniper)))
                            mWeaponPresetButton.setText(Globals.WEAPON_PRESET_SNIPER);
                        else if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_shotgun)))
                            mWeaponPresetButton.setText(Globals.WEAPON_PRESET_SHOTGUN);
                        else if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_lmg)))
                            mWeaponPresetButton.setText(Globals.WEAPON_PRESET_LMG);
                        else if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_mp)))
                            mWeaponPresetButton.setText(Globals.WEAPON_PRESET_MP);
                        else if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_carbine)))
                            mWeaponPresetButton.setText(Globals.WEAPON_PRESET_CARBINE);
                        else if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_pistol)))
                            mWeaponPresetButton.setText(Globals.WEAPON_PRESET_PISTOL);
                        else {
                            mWeaponPresetButton.setText(Globals.WEAPON_PRESET_DEFAULT);
                   }*/
                        /* code does nothing
                        if (mFiringModeButton.getText().equals(getContext().getString(R.string.firing_mode_outdoor_no_cone)))
                            playerSettings.firingMode = Globals.FIRING_MODE_OUTDOOR_NO_CONE;
                        else if (mFiringModeButton.getText().equals(getContext().getString(R.string.firing_mode_outdoor_with_cone)))
                            playerSettings.firingMode = Globals.FIRING_MODE_OUTDOOR_WITH_CONE;
                        else {
                            playerSettings.firingMode = Globals.FIRING_MODE_INDOOR_NO_CONE;
                        }//TODO add presets
                        if(mPlayerPresetButton.getText().equals(getContext().getString(R.string.player_preset_recon)))
                            playerSettings.playerPreset = Globals.PLAYER_PRESET_RECON;
                        else if(mPlayerPresetButton.getText().equals(getContext().getString(R.string.player_preset_juggernaut)))
                            playerSettings.playerPreset = Globals.PLAYER_PRESET_JUGGERNAUT;
                        else if(mPlayerPresetButton.getText().equals(getContext().getString(R.string.player_preset_tank)))
                            playerSettings.playerPreset = Globals.PLAYER_PRESET_TANK;
                        else if(mPlayerPresetButton.getText().equals(getContext().getString(R.string.player_preset_medic)))
                            playerSettings.playerPreset = Globals.PLAYER_PRESET_MEDIC;
                        else {
                            playerSettings.playerPreset = Globals.PLAYER_PRESET_DEFAULT;
                        }
                        //WEapon presets
                        if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_sniper)))
                            playerSettings.weaponPreset = Globals.WEAPON_PRESET_SNIPER;
                        else if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_shotgun)))
                            playerSettings.weaponPreset = Globals.WEAPON_PRESET_SHOTGUN;
                        else if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_lmg)))
                            playerSettings.weaponPreset = Globals.WEAPON_PRESET_LMG;
                        else if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_mp)))
                            playerSettings.weaponPreset = Globals.WEAPON_PRESET_MP;
                        else if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_carbine)))
                            playerSettings.weaponPreset = Globals.WEAPON_PRESET_CARBINE;
                        else if(mWeaponPresetButton.getText().equals(getContext().getString(R.string.weapon_preset_pistol)))
                            playerSettings.weaponPreset = Globals.WEAPON_PRESET_PISTOL;
                        else {
                            playerSettings.weaponPreset = Globals.WEAPON_PRESET_DEFAULT;
                        }
*/
                        } finally {
                            Globals.getInstance().mPlayerSettingsSemaphore.release();
                        }
                        if (mTcpServer != null) {
                            mTcpServer.sendPlayerSettings(mPlayerID, mApplyAllSwitch.isChecked(), mAllowPlayerSettingsSwitch.isChecked());
                        }
                        mContext.sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                    }
                    dismiss();
                };

        setButton(DialogInterface.BUTTON_POSITIVE, mContext.getString(R.string.ok),
                (DialogInterface.OnClickListener) null);
        setButton(DialogInterface.BUTTON_NEGATIVE, mContext.getString(R.string.cancel),
                (dialog, which) -> dismiss());
        if (isServer)
            getServerSettings();
        else
            getLocalSettings();
        super.onCreate(savedInstanceState);
        // The default dialog listener dismisses even when validation returns
        // early. Dismiss explicitly only after settings have been saved.
        getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(saveSettings);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.firing_mode_outdoor_no_cone_item:
                if (!isServer)
                    Globals.getInstance().mCurrentFiringMode = Globals.FIRING_MODE_OUTDOOR_NO_CONE;
                mFiringModeButton.setText(R.string.firing_mode_outdoor_no_cone);
                return true;
            case R.id.firing_mode_outdoor_with_cone_item:
                if (!isServer)
                    Globals.getInstance().mCurrentFiringMode = Globals.FIRING_MODE_OUTDOOR_WITH_CONE;
                mFiringModeButton.setText(R.string.firing_mode_outdoor_with_cone);
                return true;
            case R.id.firing_mode_indoor_no_cone_item:
                if (!isServer)
                    Globals.getInstance().mCurrentFiringMode = Globals.FIRING_MODE_INDOOR_NO_CONE;
                mFiringModeButton.setText(R.string.firing_mode_indoor_no_cone);
                return true;
                //TODO player preset menu handling
            //TODO adding strings for each weapon for reload time ,reload shots,and damage
            //TODO preset handling over functions for better and faster changes
            //respawn timer, overwrite lives, lives
            case R.id.player_preset_recon_item:
              /*  if (!isServer) {
                    Globals.getInstance().mCurrentPlayerPreset = Globals.PLAYER_PRESET_RECON;
                    Globals.getInstance().mCurrentWeaponPreset = Globals.WEAPON_PRESET_SNIPER;
                }*/
                mPlayerPresetButton.setText(R.string.player_preset_recon);
                mHealthET.setText("15");
                //weapon
                mWeaponPresetButton.setText(R.string.weapon_preset_sniper);
                mShotModeAuto.setChecked(false);
                mShotModeBurst3.setChecked(false);
                mShotModeSingle.setChecked(true);
                mReloadTimeET.setText("5000");
                mReloadShotsET.setText("1");
                mDamageET.setText("20");
                mReloadOnEmptySwitch.setChecked(false);
                return true;
            case R.id.player_preset_juggernaut_item:
              /* if (!isServer) {
                    Globals.getInstance().mCurrentPlayerPreset = Globals.PLAYER_PRESET_JUGGERNAUT;
                }     Globals.getInstance().mCurrentWeaponPreset = Globals.WEAPON_PRESET_LMG;
*/
                mPlayerPresetButton.setText(R.string.player_preset_juggernaut);
                mHealthET.setText("40");
                //weapon
                mWeaponPresetButton.setText(R.string.weapon_preset_lmg);
                mWeaponPresetButton.setText(R.string.weapon_preset_lmg);
                mShotModeAuto.setChecked(false);
                mShotModeBurst3.setChecked(false);
                mShotModeSingle.setChecked(true);
                mReloadTimeET.setText("5000");
                //max value due to shots being byte
                mReloadShotsET.setText("127");
                mDamageET.setText("1");
                mReloadOnEmptySwitch.setChecked(false);
                return true;
            case R.id.player_preset_tank_item:
              /*  if (!isServer) {
                    Globals.getInstance().mCurrentPlayerPreset = Globals.PLAYER_PRESET_TANK;
                    Globals.getInstance().mCurrentWeaponPreset = Globals.WEAPON_PRESET_SHOTGUN;
                }*/
                mPlayerPresetButton.setText(R.string.player_preset_tank);
                mHealthET.setText("30");
                //weapon
                mWeaponPresetButton.setText(R.string.weapon_preset_shotgun);
                mShotModeAuto.setChecked(false);
                mShotModeBurst3.setChecked(false);
                mShotModeSingle.setChecked(true);
                mReloadTimeET.setText("4000");
                mReloadShotsET.setText("8");
                mDamageET.setText("4");
                mReloadOnEmptySwitch.setChecked(false);
                return true;
            case R.id.player_preset_medic_item:
               /*   if (!isServer) {
                    Globals.getInstance().mCurrentPlayerPreset = Globals.PLAYER_PRESET_MEDIC;
                    Globals.getInstance().mCurrentWeaponPreset = Globals.WEAPON_PRESET_MP;
                } */
                mPlayerPresetButton.setText(R.string.player_preset_medic);
                mHealthET.setText("20");
                //weapon
                mWeaponPresetButton.setText(R.string.weapon_preset_mp);
                mWeaponPresetButton.setText(R.string.weapon_preset_mp);
                mShotModeAuto.setChecked(true);
                mShotModeBurst3.setChecked(true);
                mShotModeSingle.setChecked(true);
                mReloadTimeET.setText("2000");
                mReloadShotsET.setText("20");
                mDamageET.setText("1");
                mReloadOnEmptySwitch.setChecked(true);
                return true;
                //weapon preset menu handling
            case R.id.weapon_preset_sniper_item:
              /*    if (!isServer)
                    Globals.getInstance().mCurrentWeaponPreset = Globals.WEAPON_PRESET_SNIPER;
               */
                mWeaponPresetButton.setText(R.string.weapon_preset_sniper);
                mShotModeAuto.setChecked(false);
                mShotModeBurst3.setChecked(false);
                mShotModeSingle.setChecked(true);
                mReloadTimeET.setText("5000");
                mReloadShotsET.setText("1");
                mDamageET.setText("20");
                mReloadOnEmptySwitch.setChecked(false);
                return true;


            case R.id.weapon_preset_shotgun_item:
             /*     if (!isServer)
                    Globals.getInstance().mCurrentWeaponPreset = Globals.WEAPON_PRESET_SHOTGUN;
              */
                mWeaponPresetButton.setText(R.string.weapon_preset_shotgun);
                mShotModeAuto.setChecked(false);
                mShotModeBurst3.setChecked(false);
                mShotModeSingle.setChecked(true);
                mReloadTimeET.setText("4000");
                mReloadShotsET.setText("8");
                mDamageET.setText("4");
                mReloadOnEmptySwitch.setChecked(false);
                return true;
            case R.id.weapon_preset_lmg_item:
             /*     if (!isServer)
                    Globals.getInstance().mCurrentWeaponPreset = Globals.WEAPON_PRESET_LMG;
               */
                mWeaponPresetButton.setText(R.string.weapon_preset_lmg);
                mShotModeAuto.setChecked(false);
                mShotModeBurst3.setChecked(false);
                mShotModeSingle.setChecked(true);
                mReloadTimeET.setText("5000");
                mReloadShotsET.setText("127");
                mDamageET.setText("1");
                mReloadOnEmptySwitch.setChecked(false);
                return true;
            case R.id.weapon_preset_mp_item:
               /*   if (!isServer)
                    Globals.getInstance().mCurrentWeaponPreset = Globals.WEAPON_PRESET_MP;
               */
                mWeaponPresetButton.setText(R.string.weapon_preset_mp);
                mShotModeAuto.setChecked(true);
                mShotModeBurst3.setChecked(true);
                mShotModeSingle.setChecked(true);
                mReloadTimeET.setText("2000");
                mReloadShotsET.setText("20");
                mDamageET.setText("1");
                mReloadOnEmptySwitch.setChecked(true);
                return true;
            case R.id.weapon_preset_carbine_item:
              /*    if (!isServer)
                    Globals.getInstance().mCurrentWeaponPreset = Globals.WEAPON_PRESET_CARBINE;
               */
                mWeaponPresetButton.setText(R.string.weapon_preset_carbine);
                mShotModeAuto.setChecked(true);
                mShotModeBurst3.setChecked(true);
                mShotModeSingle.setChecked(true);
                mReloadTimeET.setText("2500");
                mReloadShotsET.setText("30");
                mDamageET.setText("1");
                mReloadOnEmptySwitch.setChecked(false);
                return true;
            case R.id.weapon_preset_pistol_item:
               /*   if (!isServer)
                    Globals.getInstance().mCurrentWeaponPreset = Globals.WEAPON_PRESET_PISTOL;
               */
                mWeaponPresetButton.setText(R.string.weapon_preset_pistol);
                mShotModeAuto.setChecked(false);
                mShotModeBurst3.setChecked(false);
                mShotModeSingle.setChecked(true);
                mReloadTimeET.setText("1500");
                mReloadShotsET.setText("16");
                mDamageET.setText("3");
                mReloadOnEmptySwitch.setChecked(true);
                return true;
            default:
                return false;
        }
    }
}
