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

import android.app.Activity;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;

public class PlayerDisplayDataListAdapter extends ArrayAdapter<PlayerDisplayData> {
    private final Activity context;
    private final boolean isClient;


    public PlayerDisplayDataListAdapter(Activity context,
                                        PlayerDisplayData[] data, boolean isClient) {
        super(context, R.layout.player_display_data, new ArrayList<>(Arrays.asList(data)));
        this.context = context;
        this.isClient = isClient;
    }

    public void setData(PlayerDisplayData[] data) {
        // Keep ArrayAdapter's count and item lookup in sync with rendered rows,
        // and notify ListView only after the complete replacement is installed.
        setNotifyOnChange(false);
        clear();
        addAll(Arrays.asList(data));
        notifyDataSetChanged();
    }
    //TODO add player and weapon presetto display
    @Override
    public View getView(int position, View view, ViewGroup parent) {
        Resources res = context.getResources();
        LayoutInflater inflater = context.getLayoutInflater();
        View rowView;
        if (isClient)
            rowView = inflater.inflate(R.layout.player_display_data_client, parent, false);
        else
            rowView = inflater.inflate(R.layout.player_display_data, parent, false);
        TextView playerIDTV = rowView.findViewById(R.id.player_id_tv);
        TextView playerNameTV = rowView.findViewById(R.id.player_name_tv);
        TextView playerPointsTV = rowView.findViewById(R.id.player_points_tv);
        TextView playerEliminatedTV = rowView.findViewById(R.id.player_eliminated_tv);
        if (position == 0) {
            playerIDTV.setText(R.string.player_list_id_label);
            playerNameTV.setText(R.string.player_list_name_label);
            playerPointsTV.setText(R.string.player_list_points_label);
            if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_LIVES) != 0)
                playerEliminatedTV.setText(R.string.game_limit_lives);
            else
                playerEliminatedTV.setText(R.string.player_list_eliminated_label);
            return rowView;
        }
        PlayerDisplayData player = getItem(position);
        if (position > Globals.MAX_PLAYER_ID) {
            if (player != null && player.playerName != null)
                playerIDTV.setText(player.playerName);
            return rowView;
        }
        switch (Globals.getInstance().mGameMode) {
            case Globals.GAME_MODE_FFA:
                playerIDTV.setText(res.getString(R.string.player_list_position,position));
                break;
            case Globals.GAME_MODE_2TEAMS:
                if (position > Globals.MAX_PLAYER_ID / 2)
                    playerIDTV.setText("2-" + (position - (Globals.MAX_PLAYER_ID / 2)));
                else
                    playerIDTV.setText("1-" + position);
                break;
            case Globals.GAME_MODE_4TEAMS:
                int playersPerTeam = Globals.MAX_PLAYER_ID / 4;
                if (position > playersPerTeam * 3)
                    playerIDTV.setText("4-" + (position - (playersPerTeam * 3)));
                else if (position > playersPerTeam * 2)
                    playerIDTV.setText("3-" + (position - (playersPerTeam * 2)));
                else if (position > playersPerTeam)
                    playerIDTV.setText("2-" + (position - playersPerTeam));
                else
                    playerIDTV.setText("1-" + position);
                break;
        }
        if (player == null) {
            playerNameTV.setText(R.string.player_name_not_connected);
            playerPointsTV.setText("");
            playerEliminatedTV.setText("");
            if (!isClient) {
                ImageView networkStatus = rowView.findViewById(R.id.network_status_iv);
                networkStatus.setVisibility(View.GONE);
            }
            return rowView;
        }
        playerNameTV.setText(player.playerName);
        playerPointsTV.setText("" + player.points);
        if (player.overrideLives) {
            if (player.lives != 0)
                playerEliminatedTV.setText("" + Math.max(0, player.lives - player.eliminated));
            else
                playerEliminatedTV.setText("" + player.eliminated);
        } else {
            if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_LIVES) != 0)
                playerEliminatedTV.setText("" + Math.max(0, Globals.getInstance().mLivesLimit - player.eliminated));
            else
                playerEliminatedTV.setText("" + player.eliminated);
        }
        if (!isClient) {
            ImageView networkStatus = rowView.findViewById(R.id.network_status_iv);
            networkStatus.setVisibility(View.VISIBLE);
            if (player.isConnected)
                networkStatus.setImageResource(R.drawable.ic_network_connected_24dp);
            else
                networkStatus.setImageResource(R.drawable.ic_network_disconnected_24dp);
        }
        return rowView;
    }
}
