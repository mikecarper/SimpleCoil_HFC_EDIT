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

// Network message globals

public class NetMsg {
    public static final String MESSAGE_PREFIX = "SimpleCoil:";
    // Protocol 19 adds all-player state gossip, sender-zero authority ticks,
    // and missed-tick reconstruction. Older clients must not interpret those
    // binary snapshots as the protocol-18 peer-only stream.
    public static final int NETWORK_VERSION_NUMBER = 19;
    public static final String NETWORK_VERSION = "19";

    // Most of these messages are straightforward and contain no extra data.
    public static final String NETMSG_SHOTFIRED = "SHOTFIRED";
    public static final String NETMSG_HIT = "HIT";
    public static final String NETMSG_OUT = "OUT";
    // A downed player sends this direct acknowledgement when another player
    // hits them before they have respawned. It is feedback only; it cannot
    // change score, health, or round state.
    public static final String NETMSG_ALREADYDEAD = "ALREADYDEAD";
    public static final String NETMSG_ELIMINATED = "ELIMINATED";
    // Dedicated hosts authorize checkpoint and Game Master respawns.  Keeping
    // the approval on the host prevents a phone from reviving itself without a
    // real elimination in the current round.
    public static final String NETMSG_RESPAWNREQUEST = "RESPAWNREQUEST";
    public static final String NETMSG_RESPAWNGRANTED = "RESPAWNGRANTED";
    public static final String NETMSG_RESPAWNCOMPLETE = "RESPAWNCOMPLETE";
    public static final String NETMSG_LEAVE = "LEAVE";
    // A player may voluntarily leave a running dedicated game without asking
    // the host to declare the remaining players' round over.
    public static final String NETMSG_QUIT = "QUIT";
    public static final String NETMSG_STARTGAME = "STARTGAME";
    public static final String NETMSG_CLOCKSYNCWAITING = "CLOCKSYNCWAITING";
    public static final String NETMSG_ENDGAME = "ENDGAME";
    public static final String NETMSG_ERROR = "ERROR";
    public static final String NETMSG_FAILEDTOJOIN = "FAILEDTOJOIN";
    public static final String NETMSG_VERSIONERROR = "VERSIONERROR";
    public static final String NETMSG_SAMETEAM = "SAMETEAM";
    public static final String NETMSG_SERVERCREATED = "SERVERCREATED";
    public static final String NETMSG_SERVERCANCEL = "SERVERCANCEL";
    public static final String NETMSG_TCPSERVERREADY = "TCPSERVERREADY";
    public static final String NETMSG_TCPSERVERFAILED = "TCPSERVERFAILED";
    public static final String NETMSG_TEAMELIMINATED = "TEAMELIMINATED";
    // Internal app broadcast carrying an absolute team total reconstructed
    // from peer state snapshots. It is not a text UDP wire message.
    public static final String NETMSG_TEAMSCORESTATE = "TEAMSCORESTATE";
    public static final String NETMSG_SERVERREPLY = "SERVERREPLY";
    // A host appends ":<player ID>" to SERVERREPLY only when it moved a
    // conflicting joining player to an available slot on the same team.
    public static final String NETMSG_SERVERREPLY_ASSIGNMENT_PREFIX = NETMSG_SERVERREPLY + ":";
    // A dedicated host broadcasts this when a round is committed.  Idle phones
    // can offer to join the running game without first opening the network
    // menu.  The payload is GAMEINVITE:<network version>:<round token>.
    public static final String NETMSG_GAMEINVITE = "GAMEINVITE";
    public static final String NETMSG_GAMEINVITE_PREFIX = NETMSG_GAMEINVITE + ":";
    // A peer host broadcasts this after its TCP and UDP listeners are both
    // ready. Idle players on the same Wi-Fi can join the newly-created lobby
    // without opening the network menu. Payload: LOBBYINVITE:<network version>.
    public static final String NETMSG_LOBBYINVITE = "LOBBYINVITE";
    public static final String NETMSG_LOBBYINVITE_PREFIX = NETMSG_LOBBYINVITE + ":";
    // A standalone host broadcasts this only when explicitly asked to replace
    // an idle phone-hosted lobby. Payload: HOSTTAKEOVER:<network version>.
    public static final String NETMSG_HOSTTAKEOVER = "HOSTTAKEOVER";
    public static final String NETMSG_HOSTTAKEOVER_PREFIX = NETMSG_HOSTTAKEOVER + ":";
    public static final String NETMSG_GPSLOCUPDATE = "GPSLOCUPDATE";
    public static final String NETMSG_GPSDATAUPDATE = "GPSDATAUPDATE";
    public static final String NETMSG_GPSSETTING = "GPSSETTING";
    public static final String NETMSG_PLAYERDATAUPDATE = "PLAYERDATAUPDATE";
    public static final String NETMSG_PLAYERDATAREQUEST = "PLAYERDATAREQUEST";
    public static final String NETMSG_NETWORKCONNECTED = "NETWORKCONNECTED";
    public static final String NETMSG_NETWORKDISCONNECTED = "NETWORKDISCONNECTED";
    public static final String NETMSG_PLAYERSETTINGSUPDATE = "PLAYERSETTINGSUPDATE";
    public static final String NETMSG_BALANCEDLOBBY = "BALANCEDLOBBY";
    // Peer-hosted games close their TCP listener after the round starts. Every
    // state-changing peer UDP payload includes the synchronized round nonce, so
    // a delayed previous-round datagram cannot affect a later round.
    // GRENADEPAIR:<round nonce>:<sequence>:<grenade ID>.
    public static final String NETMSG_GRENADEPAIR = "GRENADEPAIR:";
    // Peer score events carry a source-owned sequence so a retransmitted UDP
    // datagram cannot award duplicate points: ELIMINATED:<round nonce>:<sequence>
    // and TEAMELIMINATED:<round nonce>:<eliminated player ID>:<sequence>.
    public static final String NETMSG_PEER_ELIMINATED = NETMSG_ELIMINATED + ":";
    public static final String NETMSG_PEER_TEAMELIMINATED = NETMSG_TEAMELIMINATED + ":";
    public static final String NETMSG_PEER_LEAVE = NETMSG_LEAVE + ":";
    // ENDGAME and LEAVE use the same round nonce rule as peer score and pairing
    // events. This prevents a delayed datagram from a prior lobby from ending a
    // newly started round or removing a current player.
    public static final String NETMSG_PEER_ENDGAME = NETMSG_ENDGAME + ":";

    // When players join a game in progress, the server can send the player updates on appropriate values.
    // These items are intent extras.
    public static final String INTENT_HASGAMEUPDATE = "HASGAMEUPDATE";
    public static final String INTENT_SCORE = "SCORE";
    public static final String INTENT_TEAMSCORE = "TEAMSCORE";
    public static final String INTENT_ELIMINATIONS = "ELIMINATIONS";
    public static final String INTENT_TIMEREMAINING = "TIMEREMAINING";
    public static final String INTENT_GAMESTATE = "GAMESTATE";
    public static final String INTENT_START_AT = "START_AT_ELAPSED";
    public static final String INTENT_END_AT = "END_AT_ELAPSED";
    public static final String INTENT_ROUND_ID = "ROUND_ID";
    public static final String INTENT_ROUND_TOKEN = "ROUND_TOKEN";
    // A TcpClient-only refinement of an already delivered synchronized start.
    // It is never sent over UDP or accepted as a new round-start request.
    public static final String INTENT_START_TIME_ADJUSTMENT = "START_TIME_ADJUSTMENT";
    // Internal synchronized-start metadata. Peer rounds need their token in
    // the UDP service before a paused activity can process the start itself.
    public static final String INTENT_PEER_GAME = "PEER_GAME";
    public static final String INTENT_EVENT_SEQUENCE = "EVENT_SEQUENCE";

    public static final String INTENT_LONGITUDE = "longitude";
    public static final String INTENT_LATITUDE = "latitude";
    public static final String INTENT_FULLUPDATE = "fullupdate";
    public static final String INTENT_PLAYERDATA = "playerdata";

    // UDPJOIN is UDPJOIN + playerID, so UDPJOIN2 for playerID 2
    public static final String NETMSG_JOIN = "JOIN";
    public static final String NETMSG_LISTPLAYERS = "LISTPLAYERS";
}
