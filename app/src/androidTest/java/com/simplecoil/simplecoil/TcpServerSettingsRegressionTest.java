package com.simplecoil.simplecoil;

import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises real settings parsing and lock contention without opening sockets. */
@RunWith(AndroidJUnit4.class)
public class TcpServerSettingsRegressionTest {
    private RecordingServer server;
    private Object handler;
    private Object client;
    private Class<?> clientType;
    private Map<Integer, Object> clients;
    private WaitingSemaphore settingsLock;
    private Semaphore originalSettingsLock;
    private Map<Byte, Globals.PlayerSettings> originalSettings;
    private boolean originalAllowSettings;
    private boolean originalServerOnly;
    private final List<Thread> workers = new ArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();

    @Before
    public void setUp() throws Exception {
        Globals globals = Globals.getInstance();
        originalAllowSettings = globals.mAllowPlayerSettings;
        originalServerOnly = globals.mOnlyServerSettings;
        originalSettings = globals.mPlayerSettings;
        originalSettingsLock = globals.mPlayerSettingsSemaphore;
        globals.mAllowPlayerSettings = true;
        globals.mOnlyServerSettings = false;
        globals.mPlayerSettings = new HashMap<>();
        globals.mPlayerSettings.put((byte) 1, new Globals.PlayerSettings());
        settingsLock = new WaitingSemaphore();
        globals.mPlayerSettingsSemaphore = settingsLock;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> server = new RecordingServer());
        handler = innerInstance("ClientThread");
        client = innerInstance("ClientData");
        clientType = client.getClass();
        field(clientType, "mPlayerID").setByte(client, (byte) 1);
        clients = new ConcurrentHashMap<>();
        clients.put(1, client);
        field(TcpServer.class, "mClientData").set(server, clients);
    }

    @After
    public void tearDown() throws Exception {
        for (Thread worker : workers) {
            worker.interrupt();
            worker.join(2000);
        }
        if (server != null)
            InstrumentationRegistry.getInstrumentation().runOnMainSync(server::onDestroy);
        Globals globals = Globals.getInstance();
        globals.mPlayerSettings = originalSettings;
        globals.mPlayerSettingsSemaphore = originalSettingsLock;
        globals.mAllowPlayerSettings = originalAllowSettings;
        globals.mOnlyServerSettings = originalServerOnly;
        for (Thread worker : workers) assertFalse("Settings worker survived cleanup", worker.isAlive());
        assertTrue("Settings worker failed: " + failures, failures.isEmpty());
    }

    @Test
    public void disablingPlayerSettingsInAnEmptyLobbyIsRetained() {
        clients.clear();
        server.sendPlayerSettings(1, false, false);
        assertFalse(Globals.getInstance().mAllowPlayerSettings);
        assertTrue(server.messages.isEmpty());
    }

    @Test
    public void enablingPlayerSettingsInAnEmptyLobbyIsRetained() {
        clients.clear();
        Globals.getInstance().mAllowPlayerSettings = false;
        server.sendPlayerSettings(1, false, true);
        assertTrue(Globals.getInstance().mAllowPlayerSettings);
        assertTrue(server.messages.isEmpty());
    }

    @Test
    public void policyCanBeSavedBeforeTheClientMapIsInitialized() throws Exception {
        field(TcpServer.class, "mClientData").set(server, null);
        server.sendPlayerSettings(1, false, false);
        assertFalse(Globals.getInstance().mAllowPlayerSettings);
        assertTrue(server.messages.isEmpty());
    }

    @Test
    public void applyingToAllBeforePlayersJoinAlsoRetainsThePolicy() {
        clients.clear();
        Globals.getInstance().mPlayerSettings.get((byte) 1).health = 77;
        server.sendPlayerSettings(1, true, false);
        assertFalse(Globals.getInstance().mAllowPlayerSettings);
        for (byte id = 1; id <= Globals.MAX_PLAYER_ID; id++)
            assertEquals(77, Globals.getInstance().mPlayerSettings.get(id).health);
        assertEquals(1, settingsLock.availablePermits());
    }

    @Test
    public void queuedPlayerEditCannotApplyAfterPermissionIsRevoked() throws Exception {
        parseAcrossPolicyChange(settingsMessage(), () -> Globals.getInstance().mAllowPlayerSettings = false);
        assertSettingsUnchanged();
        assertFalse(lastUpdate().getBoolean(TcpServer.JSON_ALLOWPLAYERSETTINGS));
    }

    @Test
    public void queuedPlayerEditCannotApplyAfterServerOnlyModeIsEnabled() throws Exception {
        parseAcrossPolicyChange(settingsMessage(), () -> Globals.getInstance().mOnlyServerSettings = true);
        assertSettingsUnchanged();
        assertTrue(Globals.getInstance().mOnlyServerSettings);
    }

    @Test
    public void delayedSettingsReplyCannotReenablePermissionRevokedByTheHost() throws Exception {
        JSONObject invalid = settingsMessage().put(TcpServer.JSON_HEALTH, 0);
        parseAcrossPolicyChange(invalid, () -> Globals.getInstance().mAllowPlayerSettings = false);
        assertFalse(Globals.getInstance().mAllowPlayerSettings);
        assertFalse(lastUpdate().getBoolean(TcpServer.JSON_ALLOWPLAYERSETTINGS));
        assertSettingsUnchanged();
    }

    @Test
    public void delayedRejectionCannotUndoPermissionGrantedByTheHost() throws Exception {
        Globals.getInstance().mAllowPlayerSettings = false;
        parseAcrossPolicyChange(settingsMessage(), () -> Globals.getInstance().mAllowPlayerSettings = true);
        assertTrue(Globals.getInstance().mAllowPlayerSettings);
        assertTrue(lastUpdate().getBoolean(TcpServer.JSON_ALLOWPLAYERSETTINGS));
        assertSettingsUnchanged();
        // The already-rejected edit stays rejected, but a new edit can now succeed.
        parse(settingsMessage());
        assertEquals(77, Globals.getInstance().mPlayerSettings.get((byte) 1).health);
        assertEquals(1, server.events.size());
    }

    @Test
    public void allowedPlayerEditStillAppliesAndPublishesOnce() throws Exception {
        parse(settingsMessage());
        Globals.PlayerSettings settings = Globals.getInstance().mPlayerSettings.get((byte) 1);
        assertEquals(77, settings.health);
        assertEquals(200, settings.shots & 0xff);
        assertEquals(1200, settings.reloadTime);
        assertTrue(settings.reloadOnEmpty);
        assertEquals(6, settings.spawnTime);
        assertEquals(-8, settings.damage);
        assertTrue(settings.overrideLives);
        assertEquals(5, settings.lives);
        assertTrue(settings.allowShotModeSingle);
        assertFalse(settings.allowShotModeBurst3);
        assertFalse(settings.allowShotModeAuto);
        assertEquals(Globals.FIRING_MODE_OUTDOOR_WITH_CONE, settings.firingMode);
        assertEquals(1, server.messages.size());
        assertEquals(1, server.events.size());
        assertEquals(NetMsg.NETMSG_PLAYERDATAUPDATE, server.events.get(0));
        assertEquals(1, settingsLock.availablePermits());
    }

    @Test
    public void disabledPlayerSettingsRejectEditsAndReplyWithCurrentSettings() throws Exception {
        Globals.getInstance().mAllowPlayerSettings = false;
        parse(settingsMessage());
        assertSettingsUnchanged();
        assertFalse(lastUpdate().getBoolean(TcpServer.JSON_ALLOWPLAYERSETTINGS));
    }

    @Test
    public void serverOnlyModeRejectsPlayerSettingsEvenIfNormallyAllowed() throws Exception {
        Globals.getInstance().mOnlyServerSettings = true;
        parse(settingsMessage());
        assertSettingsUnchanged();
    }

    @Test
    public void playerCannotApplySettingsForAnotherPlayer() throws Exception {
        parse(settingsMessage().put(TcpServer.JSON_PLAYERID, 2));
        assertSettingsUnchanged();
        assertFalse(Globals.getInstance().mPlayerSettings.containsKey((byte) 2));
    }

    private void assertSettingsUnchanged() throws Exception {
        Globals.PlayerSettings settings = Globals.getInstance().mPlayerSettings.get((byte) 1);
        assertEquals(Globals.MAX_HEALTH, settings.health);
        assertEquals(Globals.RELOAD_COUNT, settings.shots);
        assertFalse(settings.overrideLives);
        assertEquals(1, server.messages.size());
        assertEquals(Globals.MAX_HEALTH, lastUpdate().getJSONArray(TcpServer.JSON_PLAYERSETTINGS)
                .getJSONObject(0).getInt(TcpServer.JSON_HEALTH));
        assertTrue(server.events.isEmpty());
        assertEquals(1, settingsLock.availablePermits());
    }

    private void parseAcrossPolicyChange(JSONObject message, Runnable change) throws Exception {
        settingsLock.acquireUninterruptibly();
        Thread worker = new Thread(() -> {
            try { parse(message); }
            catch (Throwable failure) { failures.add(failure); }
        }, "Settings regression parser");
        workers.add(worker);
        try {
            worker.start();
            assertTrue("Parser never reached the settings lock", settingsLock.waiting.await(3, TimeUnit.SECONDS));
            change.run();
        } finally {
            settingsLock.release();
        }
        worker.join(3000);
        assertFalse("Parser did not finish", worker.isAlive());
        assertTrue("Parser failed: " + failures, failures.isEmpty());
    }

    private static JSONObject settingsMessage() throws Exception {
        return new JSONObject().put(TcpServer.JSON_PLAYERSETTINGS, true)
                .put(TcpServer.JSON_PLAYERID, 1).put(TcpServer.JSON_HEALTH, 77)
                .put(TcpServer.JSON_RELOAD_SHOTS, 200).put(TcpServer.JSON_RELOAD_TIME, 1200)
                .put(TcpServer.JSON_RELOAD_ON_EMPTY, true).put(TcpServer.JSON_SPAWN_TIME, 6)
                .put(TcpServer.JSON_DAMAGE, -8).put(TcpServer.JSON_LIVESLIMIT, 5)
                .put(TcpServer.JSON_SHOT_MODE_SINGLE, true).put(TcpServer.JSON_SHOT_MODE_BURST3, false)
                .put(TcpServer.JSON_SHOT_MODE_AUTO, false)
                .put(TcpServer.JSON_FIRING_MODE, Globals.FIRING_MODE_OUTDOOR_WITH_CONE);
    }

    private void parse(JSONObject message) throws Exception {
        Method method = handler.getClass().getDeclaredMethod("parsePlayerInfo", String.class, clientType);
        method.setAccessible(true);
        method.invoke(handler, message.toString(), client);
    }

    private JSONObject lastUpdate() throws Exception {
        String message = server.messages.get(server.messages.size() - 1);
        return new JSONObject(message.substring(TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_JSON.length()));
    }

    private Object innerInstance(String name) throws Exception {
        Constructor<?> constructor = Class.forName(TcpServer.class.getName() + "$" + name)
                .getDeclaredConstructor(TcpServer.class);
        constructor.setAccessible(true);
        return constructor.newInstance(server);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class WaitingSemaphore extends Semaphore {
        final CountDownLatch waiting = new CountDownLatch(1);
        WaitingSemaphore() { super(1); }

        @Override public void acquire() throws InterruptedException {
            if (availablePermits() == 0) waiting.countDown();
            super.acquire();
        }
    }

    private static final class RecordingServer extends TcpServer {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        @Override public void sendTCPMessageAll(String message) { messages.add(message); }
        @Override public void sendBroadcast(Intent intent) { events.add(intent.getAction()); }
    }
}
