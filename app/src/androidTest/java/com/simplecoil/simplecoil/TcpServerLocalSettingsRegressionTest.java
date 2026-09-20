package com.simplecoil.simplecoil;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Verifies that a peer host publishes its own settings without self-registering. */
@RunWith(AndroidJUnit4.class)
public class TcpServerLocalSettingsRegressionTest {
    private TcpServer server;
    private Globals globals;
    private Map<Byte, Globals.PlayerSettings> originalSettings;
    private Globals.PlayerSettings originalLocalSettings;
    private byte originalPlayerID;

    @Before
    public void setUp() throws Exception {
        globals = Globals.getInstance();
        originalSettings = globals.mPlayerSettings;
        originalLocalSettings = TcpServer.localPlayerSettingsSnapshot(globals);
        originalPlayerID = globals.mPlayerID;
        globals.mPlayerSettings = new HashMap<>();
        globals.mPlayerID = 4;
        globals.mFullHealth = 77;
        globals.mFullReload = (byte) 200;
        globals.mReloadTime = 1200;
        globals.mReloadOnEmpty = true;
        globals.mRespawnTime = 6;
        globals.mDamage = -8;
        globals.mOverrideLives = true;
        globals.mOverrideLivesVal = 5;
        globals.mAllowSingleShotMode = true;
        globals.mAllowBurst3ShotMode = false;
        globals.mAllowAutoShotMode = false;
        globals.mCurrentFiringMode = Globals.FIRING_MODE_OUTDOOR_WITH_CONE;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> server = new TcpServer());
    }

    @After
    public void tearDown() {
        if (server != null)
            InstrumentationRegistry.getInstrumentation().runOnMainSync(server::onDestroy);
        globals.mPlayerSettings = originalSettings;
        globals.mPlayerID = originalPlayerID;
        restoreLocalSettings(originalLocalSettings);
    }

    @Test
    public void peerHostSettingsAreSeededForTheFirstLobbySnapshot() throws Exception {
        clearSettingsForNewListener();

        assertEquals(1, globals.mPlayerSettings.size());
        Globals.PlayerSettings settings = globals.mPlayerSettings.get((byte) 4);
        assertEquals(77, settings.health);
        assertEquals(200, settings.shots & 0xff);
        assertEquals(1200, settings.reloadTime);
        assertTrue(settings.reloadOnEmpty);
        assertEquals(6, settings.spawnTime);
        assertEquals(-8, settings.damage);
        assertTrue(settings.overrideLives);
        assertEquals(5, settings.lives);
        assertTrue(settings.allowShotModeSingle);
        assertTrue(!settings.allowShotModeBurst3);
        assertTrue(!settings.allowShotModeAuto);
        assertEquals(Globals.FIRING_MODE_OUTDOOR_WITH_CONE, settings.firingMode);
    }

    @Test
    public void dedicatedHostDoesNotPublishAnOperatorAsAPlayer() throws Exception {
        server.setDedicated(true);
        clearSettingsForNewListener();

        assertTrue(globals.mPlayerSettings.isEmpty());
    }

    private void clearSettingsForNewListener() throws Exception {
        Method method = TcpServer.class.getDeclaredMethod("clearPlayerSettingsForNewListener");
        method.setAccessible(true);
        method.invoke(server);
    }

    private static void restoreLocalSettings(Globals.PlayerSettings settings) {
        Globals globals = Globals.getInstance();
        globals.mFullHealth = settings.health;
        globals.mFullReload = settings.shots;
        globals.mReloadTime = settings.reloadTime;
        globals.mReloadOnEmpty = settings.reloadOnEmpty;
        globals.mRespawnTime = settings.spawnTime;
        globals.mDamage = settings.damage;
        globals.mOverrideLives = settings.overrideLives;
        globals.mOverrideLivesVal = settings.lives;
        globals.mAllowSingleShotMode = settings.allowShotModeSingle;
        globals.mAllowBurst3ShotMode = settings.allowShotModeBurst3;
        globals.mAllowAutoShotMode = settings.allowShotModeAuto;
        globals.mCurrentFiringMode = settings.firingMode;
    }
}
