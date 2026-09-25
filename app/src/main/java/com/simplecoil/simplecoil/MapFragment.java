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

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.mousebird.maply.ComponentObject;
import com.mousebird.maply.GlobeMapFragment;
import com.mousebird.maply.MapController;
import com.mousebird.maply.MaplyBaseController;
import com.mousebird.maply.MarkerInfo;
import com.mousebird.maply.Point2d;
import com.mousebird.maply.Point3d;
import com.mousebird.maply.QuadImageTileLayer;
import com.mousebird.maply.RemoteTileInfo;
import com.mousebird.maply.RemoteTileSource;
import com.mousebird.maply.ScreenMarker;
import com.mousebird.maply.SphericalMercatorCoordSystem;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Map;

public class MapFragment extends GlobeMapFragment {
    private static final String TAG = "map";

    private Location currentBestLocation = null;
    private LocationManager mLocationManager = null;
    private static final double ZOOM_LEVEL = 0.00003;
    private static final double PI180 = Math.PI / 180;

    private static final int TWO_MINUTES = 1000 * 60 * 2;
    private static final int REQUEST_LOCATION_PERMISSION = 1;
    // Hosted games use the GPS stream for a live operator display.  Request a
    // quick fix while still discarding sub-meter receiver jitter; providers are
    // free to deliver more slowly when their hardware or power policy requires it.
    private static final long LOCATION_UPDATE_INTERVAL_MS = 250;
    private static final float LOCATION_UPDATE_MIN_DISTANCE_METERS = 0.5f;

    private LocationListener mLocationListener = null;
    private boolean mResumed;
    private boolean mGPSRequested;
    private QuadImageTileLayer mBaseLayer;
    private String mBaseTileUrl;

    private double mLongitude = 0;
    private double mLatitude = 0;
    private Location mLastBroadcastLocation;

    private void sendLocation(Location location) {
        sendLocation(location, false);
    }

    private static boolean isValidLocation(Location location) {
        return location != null && Globals.isValidCoordinates(location.getLongitude(), location.getLatitude());
    }

    /** Keep a fresh GPS UTC-to-monotonic mapping for the synchronized game start. */
    private static void recordGpsGameTime(Location location) {
        if (location == null || !LocationManager.GPS_PROVIDER.equals(location.getProvider()))
            return;
        long elapsedAtFix = location.getElapsedRealtimeNanos() / 1_000_000L;
        if (elapsedAtFix <= 0)
            return;
        Globals.getInstance().mGpsGameTime.recordGpsFix(location.getTime(), elapsedAtFix,
                SystemClock.elapsedRealtime());
    }

    private void sendLocation(Location location, boolean force) {
        Context activity = getActivity();
        if (!isValidLocation(location) || activity == null) return;
        if (!force && mLastBroadcastLocation != null) {
            if (location.getLatitude() == mLatitude && location.getLongitude() == mLongitude)
                return;
            // Providers can report slightly different fixes for a stationary phone.  Avoid
            // turning that noise into a full team-map update while retaining meaningful motion.
            if (location.distanceTo(mLastBroadcastLocation) < LOCATION_UPDATE_MIN_DISTANCE_METERS)
                return;
        }
        mLongitude = location.getLongitude();
        mLatitude = location.getLatitude();
        mLastBroadcastLocation = new Location(location);
        Intent intent = new Intent(NetMsg.NETMSG_GPSLOCUPDATE);
        intent.putExtra(NetMsg.INTENT_LATITUDE, mLatitude);
        intent.putExtra(NetMsg.INTENT_LONGITUDE, mLongitude);
        activity.sendBroadcast(intent);
    }

    /**
     * @return the last know best location
     */
    private Location getLastBestLocation() {
        if (mLocationManager == null || !isAdded())
            return null;
        // We already have this permission because of Bluetooth, but Android Studio insists on having this code or it throws an error
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int hasLocationPermission = requireActivity().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION);
            if (hasLocationPermission != PackageManager.PERMISSION_GRANTED)
                return null;
        }
        Location locationGPS = getLastKnownLocation(LocationManager.GPS_PROVIDER);
        Location locationNet = getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        if (locationGPS == null) return locationNet;
        if (locationNet == null || locationGPS.getTime() > locationNet.getTime()) return locationGPS;
        return locationNet;
    }

    private Location getLastKnownLocation(String provider) {
        try {
            Location location = mLocationManager.getLastKnownLocation(provider);
            return isValidLocation(location) ? new Location(location) : null;
        } catch (SecurityException | IllegalArgumentException e) {
            // One missing provider must not discard the other provider's usable fix.
            Log.w(TAG, "Location provider is unavailable: " + provider, e);
            return null;
        }
    }

    /*---------- Listener class to get coordinates ------------- */
    private class MyLocationListener implements LocationListener {

        @Override
        public void onLocationChanged(Location loc) {
            if (mLocationListener != this || !isGPSActive() || !isValidLocation(loc))
                return;
            recordGpsGameTime(loc);
            if (BuildConfig.DEBUG)
                Log.v(TAG, "Location: " + loc.getLongitude() + "," + loc.getLatitude());
            // Recreating a Maply marker for every raw provider callback is expensive.  A marker
            // only changes when the new fix wins the quality check below.
            if (makeUseOfNewLocation(loc))
                insertYourMarker();
        }

        @Override
        public void onProviderDisabled(String provider) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}
    }

    /**
     * This method modify the last know good location according to the arguments.
     *
     * @param location The possible new location.
     */
    boolean makeUseOfNewLocation(Location location) {
        if ( isBetterLocation(location, currentBestLocation) ) {
            // Providers expose mutable Location objects; keep our own accepted snapshot.
            currentBestLocation = new Location(location);
            sendLocation(currentBestLocation);
            return true;
        }
        return false;
    }

    /** Determines whether one location reading is better than the current location fix
     * @param location  The new location that you want to evaluate
     * @param currentBestLocation  The current location fix, to which you want to compare the new one.
     */
    protected boolean isBetterLocation(Location location, Location currentBestLocation) {
        if (!isValidLocation(location))
            return false;
        if (!isValidLocation(currentBestLocation)) {
            // A new location is always better than no location
            return true;
        }

        // Check whether the new location fix is newer or older
        long timeDelta = location.getTime() - currentBestLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
        boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
        boolean isNewer = timeDelta > 0;

        // If it's been more than two minutes since the current location, use the new location,
        // because the user has likely moved.
        if (isSignificantlyNewer) {
            return true;
            // If the new location is more than two minutes older, it must be worse.
        } else if (isSignificantlyOlder) {
            return false;
        }

        // Check whether the new location fix is more or less accurate
        int accuracyDelta = (int) (location.getAccuracy() - currentBestLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;

        // Check if the old and new location are from the same provider
        boolean isFromSameProvider = isSameProvider(location.getProvider(),
                currentBestLocation.getProvider());

        // Determine location quality using a combination of timeliness and accuracy
        if (isMoreAccurate) {
            return true;
        } else if (isNewer && !isLessAccurate) {
            return true;
        } else return isNewer && !isSignificantlyLessAccurate && isFromSameProvider;
    }

    /** Checks whether two providers are the same */
    private boolean isSameProvider(String provider1, String provider2) {
        if (provider1 == null) {
            return provider2 == null;
        }
        return provider1.equals(provider2);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle inState) {
        super.onCreateView(inflater, container, inState);
        return baseControl.getContentView();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState)
    {
        super.onActivityCreated(savedInstanceState);
        try {
            ViewGroup.LayoutParams params = baseControl.getContentView().getLayoutParams();
            params.height = params.width;
            baseControl.getContentView().setLayoutParams(params);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mResumed = true;
        IntentFilter filter = new IntentFilter(NetMsg.NETMSG_GPSDATAUPDATE);
        filter.addAction(NetMsg.NETMSG_LISTPLAYERS);
        filter.addAction(NetMsg.NETMSG_GPSSETTING);
        ContextCompat.registerReceiver(requireActivity(), mGPSDataReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        enableGPS(Globals.getInstance().mUseGPS);
    }

    @Override
    public void onPause() {
        mResumed = false;
        enableGPS(false);
        if (isAdded())
            requireActivity().unregisterReceiver(mGPSDataReceiver);
        super.onPause();
    }

    @Override
    protected MapDisplayType chooseDisplayType() {
        return MapDisplayType.Map;
    }

    private final BroadcastReceiver mGPSDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mResumed || !isAdded())
                return;
            final String action = intent.getAction();
            if (action == null)
                return;

            if (action.equals(NetMsg.NETMSG_GPSDATAUPDATE)) {
                if (!isGPSActive())
                    return;
                if (intent.getBooleanExtra(NetMsg.INTENT_FULLUPDATE, false))
                    removeMissingPlayerMarkers();
                insertPlayerMarkers(false);
            } else if (action.equals(NetMsg.NETMSG_LISTPLAYERS) || action.equals(NetMsg.NETMSG_GPSSETTING)) {
                if (action.equals(NetMsg.NETMSG_LISTPLAYERS))
                    updateBaseTileLayer();
                enableGPS(Globals.getInstance().mUseGPS);
            }
        }
    };

    private boolean isGPSActive() {
        return mResumed && mGPSRequested && Globals.getInstance().mUseGPS && isAdded();
    }

    public void enableGPS(boolean enabled) {
        mGPSRequested = enabled;
        if (enabled) {
            if (!isGPSActive())
                return;
            // Rebuild from the cached snapshot on resume and visibility/team
            // changes. Stationary players may no longer have hasUpdate set.
            refreshPlayerMarkers();
            if (mLocationListener == null) {
                if (!isAdded() || mLocationManager == null)
                    return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    int hasLocationPermission = requireActivity().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION);
                    if (hasLocationPermission != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                REQUEST_LOCATION_PERMISSION);
                        return;
                    }
                }
                mLongitude = 0;
                mLatitude = 0;
                mLastBroadcastLocation = null;
                LocationListener listener = new MyLocationListener();
                // Set it before requesting updates. Some providers can deliver
                // a cached fix as soon as registration returns.
                mLocationListener = listener;
                // GPS is the preferred source for a field map, while the network provider can
                // supply a useful first fix before GPS has locked.  Register independently so a
                // missing network provider never disables GPS tracking (or vice versa).
                boolean gpsRegistered = registerLocationProvider(listener, LocationManager.GPS_PROVIDER);
                boolean networkRegistered = registerLocationProvider(listener, LocationManager.NETWORK_PROVIDER);
                if (!gpsRegistered && !networkRegistered) {
                    mLocationListener = null;
                    return;
                }
                currentBestLocation = getLastBestLocation();
                recordGpsGameTime(currentBestLocation);
                insertYourMarker();
            }
            // Joining/rejoining or changing GPS settings may require republishing a stationary
            // fix whose earlier broadcast was sent before the TCP connection was ready.
            sendLocation(currentBestLocation, true);
        } else {
            LocationListener listener = mLocationListener;
            mLocationListener = null;
            // Remote markers do not depend on a local location subscription.
            // Clear them even when permission or provider registration failed.
            removeAllPlayerMarkers();
            if (listener != null) {
                if (mLocationManager != null) {
                    try {
                        mLocationManager.removeUpdates(listener);
                    } catch (SecurityException | IllegalArgumentException e) {
                        Log.w(TAG, "Unable to unregister location updates", e);
                    }
                }
            }
        }
    }

    /** Register one provider without allowing an unavailable optional provider to stop tracking. */
    private boolean registerLocationProvider(LocationListener listener, String provider) {
        try {
            mLocationManager.requestLocationUpdates(provider, LOCATION_UPDATE_INTERVAL_MS,
                    LOCATION_UPDATE_MIN_DISTANCE_METERS, listener);
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "Unable to register location provider: " + provider, e);
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && isGPSActive()) {
            enableGPS(true);
        }
    }

    @Override
    protected void controlHasStarted() {
        if (!isAdded() || mapControl == null)
            return;
        updateBaseTileLayer();
        //mapControl.setAllowRotateGesture(true); // need to figure out a rose compass or something so you can tell which way you have rotated
        mapControl.gestureDelegate = this;

        mLocationManager = (LocationManager) getActivity().getSystemService(Context.LOCATION_SERVICE);
        mPlayerMarkers = new ComponentObject[Globals.MAX_PLAYER_ID + 1];
        for (int x = 0; x <= Globals.MAX_PLAYER_ID; x++)
            mPlayerMarkers[x] = null;
        enableGPS(Globals.getInstance().mUseGPS);
    }

    static String laptopTileBaseUrl(InetAddress serverAddress, int port) {
        if (!(serverAddress instanceof Inet4Address) || port <= 0 || port > 65535)
            return null;
        return "http://" + serverAddress.getHostAddress() + ":" + port + "/tiles/";
    }

    private void updateBaseTileLayer() {
        if (!isAdded() || mapControl == null)
            return;
        Globals globals = Globals.getInstance();
        String nextUrl = laptopTileBaseUrl(globals.mServerIP, globals.mMapTilePort);
        if (nextUrl == null ? mBaseTileUrl == null : nextUrl.equals(mBaseTileUrl))
            return;
        if (mBaseLayer != null) {
            mapControl.removeLayer(mBaseLayer);
            mBaseLayer = null;
        }
        mBaseTileUrl = null;
        if (nextUrl == null)
            return;
        String cacheName = "map-tiles-" + globals.mServerIP.getHostAddress().replace('.', '_')
                + "-" + globals.mMapTilePort;
        File cacheDir = new File(requireActivity().getCacheDir(), cacheName);
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            Log.w(TAG, "Unable to create map tile cache " + cacheDir);
            return;
        }
        RemoteTileInfo tileInfo = new RemoteTileInfo(nextUrl, "png", 0, 22);
        RemoteTileSource tileSource = new RemoteTileSource(mapControl, tileInfo);
        tileSource.setCacheDir(cacheDir);
        QuadImageTileLayer layer = new QuadImageTileLayer(mapControl,
                new SphericalMercatorCoordSystem(), tileSource);
        layer.setImageDepth(1);
        layer.setSingleLevelLoading(false);
        layer.setUseTargetZoomLevel(false);
        layer.setCoverPoles(true);
        layer.setHandleEdges(true);
        mapControl.addLayer(layer);
        mBaseLayer = layer;
        mBaseTileUrl = nextUrl;
    }

    @Override
    public void mapDidStopMoving(MapController mapControl,
                                 Point3d[] corners,
                                 boolean userMotion) {
        if (userMotion && isValidLocation(currentBestLocation))
            mapControl.setPositionGeo(currentBestLocation.getLongitude() * PI180, currentBestLocation.getLatitude() * PI180, ZOOM_LEVEL);
    }

    private ComponentObject[] mPlayerMarkers = null;
    // Maply retains marker images while they are displayed. Reuse these immutable
    // resource bitmaps instead of allocating new native bitmaps for every GPS update.
    private Bitmap mYouMarkerIcon = null;
    private Bitmap mTeammateMarkerIcon = null;
    private Bitmap mEnemyMarkerIcon = null;

    private Bitmap getYouMarkerIcon() {
        if (mYouMarkerIcon == null && isAdded())
            mYouMarkerIcon = BitmapFactory.decodeResource(requireActivity().getResources(), R.drawable.ic_gps_you);
        return mYouMarkerIcon;
    }

    private Bitmap getTeammateMarkerIcon() {
        if (mTeammateMarkerIcon == null && isAdded())
            mTeammateMarkerIcon = BitmapFactory.decodeResource(requireActivity().getResources(), R.drawable.ic_gps_teammate);
        return mTeammateMarkerIcon;
    }

    private Bitmap getEnemyMarkerIcon() {
        if (mEnemyMarkerIcon == null && isAdded())
            mEnemyMarkerIcon = BitmapFactory.decodeResource(requireActivity().getResources(), R.drawable.ic_gps_enemy);
        return mEnemyMarkerIcon;
    }

    private void insertYourMarker() {
        if (mapControl == null || mPlayerMarkers == null || !isGPSActive()) return;
        int playerID = Globals.getInstance().mPlayerID;
        if (!Globals.isValidPlayerID(playerID) || playerID >= mPlayerMarkers.length) {
            Log.w(TAG, "Ignoring marker for invalid local player ID " + playerID);
            return;
        }
        removeYourMarker();

        if (!isValidLocation(currentBestLocation)) return;
        MarkerInfo markerInfo = new MarkerInfo();
        Bitmap icon = getYouMarkerIcon();
        if (icon == null) return;
        Point2d markerSize = new Point2d(72, 72);

        ScreenMarker you = new ScreenMarker();
        you.loc = Point2d.FromDegrees(currentBestLocation.getLongitude(), currentBestLocation.getLatitude()); // Longitude, Latitude
        you.image = icon;
        you.size = markerSize;

        mPlayerMarkers[playerID] = mapControl.addScreenMarker(you, markerInfo, MaplyBaseController.ThreadMode.ThreadCurrent);
        mapControl.setPositionGeo(currentBestLocation.getLongitude() * PI180, currentBestLocation.getLatitude() * PI180, ZOOM_LEVEL);

        mapControl.currentMapZoom(Point2d.FromDegrees(currentBestLocation.getLongitude(), currentBestLocation.getLatitude()));
        mapControl.setZoomLimits(ZOOM_LEVEL, ZOOM_LEVEL);
    }

    private void removeYourMarker() {
        if (mapControl == null || mPlayerMarkers == null) return;
        int playerID = Globals.getInstance().mPlayerID;
        if (!Globals.isValidPlayerID(playerID) || playerID >= mPlayerMarkers.length) return;
        if (mPlayerMarkers[playerID] != null) {
            mapControl.removeObject(mPlayerMarkers[playerID], MaplyBaseController.ThreadMode.ThreadCurrent);
            mPlayerMarkers[playerID] = null;
        }
    }

    private void refreshPlayerMarkers() {
        removeAllPlayerMarkers();
        insertYourMarker();
        insertPlayerMarkers(true);
    }

    private void insertPlayerMarkers(boolean fullRefresh) {
        if (mapControl == null || mPlayerMarkers == null || !isGPSActive()) return;
        MarkerInfo markerInfo = new MarkerInfo();
        Point2d markerSize = new Point2d(72, 72);
        // Add other players to the map
        int currentTeam = -1;
        if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA)
            currentTeam = Globals.getInstance().calcNetworkTeam(Globals.getInstance().mPlayerID);
        Bitmap teammate = getTeammateMarkerIcon();
        Bitmap enemy = getEnemyMarkerIcon();
        if (teammate == null || enemy == null) return;
        Globals.getmGPSDataSemaphore();
        try {
            for (Map.Entry<Byte, Globals.GPSData> entry : Globals.getInstance().mGPSData.entrySet()) {
                int playerID = entry.getKey();
                if (!Globals.isValidPlayerID(playerID) || playerID >= mPlayerMarkers.length) {
                    Log.w(TAG, "Ignoring GPS marker for invalid player ID " + playerID);
                    continue;
                }
                if (entry.getKey() != Globals.getInstance().mPlayerID
                        && (fullRefresh || entry.getValue().hasUpdate)) {
                    entry.getValue().hasUpdate = false;
                    removePlayerMarker(entry.getKey());
                    if (!Globals.isValidCoordinates(entry.getValue().longitude, entry.getValue().latitude))
                        continue;
                    ScreenMarker player = new ScreenMarker();
                    player.loc = Point2d.FromDegrees(entry.getValue().longitude, entry.getValue().latitude);
                    player.size = markerSize;
                    if (Globals.getInstance().calcNetworkTeam(entry.getKey()) == currentTeam) {
                        player.image = teammate;
                        mPlayerMarkers[playerID] = mapControl.addScreenMarker(player, markerInfo, MaplyBaseController.ThreadMode.ThreadCurrent);
                    } else if (Globals.getInstance().mGPSMode == Globals.GPS_ALL) {
                        player.image = enemy;
                        mPlayerMarkers[playerID] = mapControl.addScreenMarker(player, markerInfo, MaplyBaseController.ThreadMode.ThreadCurrent);
                    }
                }
            }
        } finally {
            Globals.getInstance().mGPSDataSemaphore.release();
        }
    }

    private void removePlayerMarker(int playerID) {
        if (mapControl == null || mPlayerMarkers == null || playerID < 0 || playerID >= mPlayerMarkers.length) return;
        if (mPlayerMarkers[playerID] != null)
            mapControl.removeObject(mPlayerMarkers[playerID], MaplyBaseController.ThreadMode.ThreadCurrent);
        mPlayerMarkers[playerID] = null;
    }

    /** Remove markers for players omitted by a full server snapshot without rebuilding unchanged ones. */
    private void removeMissingPlayerMarkers() {
        if (mPlayerMarkers == null)
            return;
        boolean[] present = new boolean[mPlayerMarkers.length];
        Globals.getmGPSDataSemaphore();
        try {
            Map<Byte, Globals.GPSData> locations = Globals.getInstance().mGPSData;
            if (locations != null) {
                for (Byte id : locations.keySet()) {
                    if (id != null && Globals.isValidPlayerID(id) && id > 0 && id < present.length)
                        present[id] = true;
                }
            }
        } finally {
            Globals.getInstance().mGPSDataSemaphore.release();
        }
        int localPlayerID = Globals.getInstance().mPlayerID;
        for (int playerID = 1; playerID < mPlayerMarkers.length; playerID++) {
            if (playerID != localPlayerID && !present[playerID])
                removePlayerMarker(playerID);
        }
    }

    private void removeAllPlayerMarkers() {
        if (mPlayerMarkers == null) return;
        for (int x = 0; x <= Globals.MAX_PLAYER_ID; x++)
            removePlayerMarker(x);
    }
}
