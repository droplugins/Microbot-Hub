package net.runelite.client.plugins.microbot.drorecorder;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.SpotanimID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class DroRecorderCaptureTest
{
    @Test
    public void npcFootprintUsesDefinitionTileSizeInsteadOfActorAccessor()
    {
        net.runelite.api.NPC npc = org.mockito.Mockito.mock(net.runelite.api.NPC.class);
        net.runelite.api.NPCComposition definition = org.mockito.Mockito.mock(net.runelite.api.NPCComposition.class);
        org.mockito.Mockito.when(npc.getTransformedComposition()).thenReturn(definition);
        org.mockito.Mockito.when(definition.getSize()).thenReturn(2);
        org.mockito.Mockito.when(npc.getFootprintSize()).thenThrow(new NullPointerException("despawned actor"));
        assertEquals(Integer.valueOf(2), DroRecorderSession.npcFootprintSize(npc));
        org.mockito.Mockito.verify(npc, org.mockito.Mockito.never()).getFootprintSize();
    }

    @Test
    public void despawnedNpcHasUnknownFootprintWithoutCrashingCapture()
    {
        net.runelite.api.NPC npc = org.mockito.Mockito.mock(net.runelite.api.NPC.class);
        org.mockito.Mockito.when(npc.getTransformedComposition()).thenReturn(null);
        assertNull(DroRecorderSession.npcFootprintSize(npc));
    }

    @TempDir
    Path temporary;

    @Test
    public void focusTransitionsKeepScopeAndClockAndRespectPause() throws Exception
    {
        net.runelite.api.Client client = org.mockito.Mockito.mock(net.runelite.api.Client.class);
        DroRecorderConfig config = org.mockito.Mockito.mock(DroRecorderConfig.class);
        org.mockito.Mockito.when(client.isClientThread()).thenReturn(true);
        org.mockito.Mockito.when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
        org.mockito.Mockito.when(client.getTickCount()).thenReturn(42);
        org.mockito.Mockito.when(client.getGameCycle()).thenReturn(300);
        Path directory = Files.createTempDirectory(temporary, "recording-");
        DroRecorderSession session = new DroRecorderSession(client, new Gson(), config, directory);
        session.start();
        session.recordFocus("window", true, "baseline", false);
        session.recordFocus("canvas", false, "change", true);
        session.togglePaused();
        session.recordFocus("window", false, "change", false);
        session.togglePaused();
        session.recordFocus("runelite", true, "change", false);
        session.stop();
        session.getCompletion().get(5, TimeUnit.SECONDS);
        List<JsonObject> focus = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(directory.resolve("events.jsonl")))
        {
            JsonObject event = new JsonParser().parse(line).getAsJsonObject();
            if ("focus_changed".equals(event.get("type").getAsString())) focus.add(event);
        }
        assertEquals(3, focus.size());
        assertEquals("window", focus.get(0).get("scope").getAsString());
        assertTrue(focus.get(0).get("focused").getAsBoolean());
        assertEquals("baseline", focus.get(0).get("reason").getAsString());
        assertFalse(focus.get(1).get("focused").getAsBoolean());
        assertTrue(focus.get(1).get("temporary").getAsBoolean());
        assertEquals("runelite", focus.get(2).get("scope").getAsString());
        for (JsonObject event : focus)
        {
            assertEquals(42, event.get("tick").getAsInt());
            assertTrue(event.has("timestampMs"));
            assertTrue(event.has("sequence"));
        }
    }

    @Test
    public void unmappedAttackNeverInheritsNpcRole()
    {
        assertEquals("UNKNOWN", DroRecorderAttack.animation("MAGER", 0).get("style"));
        assertEquals("UNKNOWN", DroRecorderAttack.projectile(-1).get("style"));
        assertEquals("MELEE", DroRecorderAttack.animation("MAGER", 2644).get("style"));
        assertEquals("MAGIC", DroRecorderAttack.animation("JAD", 2656).get("style"));
        assertEquals("RANGED", DroRecorderAttack.animation("JAD", 2652).get("style"));
        assertEquals("MAGIC", DroRecorderAttack.projectile(SpotanimID.FIRESTRIKE_TRAVEL).get("style"));
        assertEquals("MAGIC", DroRecorderAttack.projectile(SpotanimID.FIREBOLT_TRAVEL).get("style"));
        assertEquals("RANGED", DroRecorderAttack.projectile(SpotanimID.CROSSBOWBOLT_TRAVEL).get("style"));
        assertEquals("RANGED", DroRecorderAttack.projectile(SpotanimID.BRONZE_ARROW_TRAVEL).get("style"));
    }

    @Test
    public void tileKeysPreservePlaneAndCoordinates()
    {
        long key = DroRecorderSpatial.tileKey(new WorldPoint(3200, 6400, 2));
        assertEquals(2, (key >>> 28) & 3);
        assertEquals(3200, (key >>> 14) & 0x3fff);
        assertEquals(6400, key & 0x3fff);
        assertNotEquals(key, DroRecorderSpatial.tileKey(new WorldPoint(3200, 6400, 1)));
    }

    @Test
    public void shutdownDrainsRecordsAndEventsRemainOrdered() throws Exception
    {
        Path directory = Files.createTempDirectory(temporary, "recording-");
        DroRecorderWriter writer = new DroRecorderWriter(directory, new Gson());
        writer.start();
        for (int i = 0; i < 300; i++)
        {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("type", i % 3 == 0 ? "menu_click" : "tick");
            record.put("sequence", i);
            if (i % 3 == 0) { writer.writeEvent(record); } else { writer.writeRaw(record); }
        }
        writer.stop();
        writer.getCompletion().get(5, TimeUnit.SECONDS);
        List<String> raw = Files.readAllLines(directory.resolve("session.jsonl"));
        List<String> events = Files.readAllLines(directory.resolve("events.jsonl"));
        assertEquals(300, raw.size());
        assertEquals(100, events.size());
        for (int i = 0; i < raw.size(); i++)
        {
            assertEquals(i, new JsonParser().parse(raw.get(i)).getAsJsonObject().get("sequence").getAsInt());
        }
        for (int i = 0; i < events.size(); i++)
        {
            assertEquals(raw.get(i * 3), events.get(i));
        }
        assertEquals(0, writer.getDroppedLines());
    }

    @Test
    public void equalEntitiesHaveSeparateIdentitiesAndRemovalRenewsIdentity()
    {
        DroRecorderIdentityMap<String> identities = new DroRecorderIdentityMap<>();
        String first = new String("same"), second = new String("same");
        assertEquals(1, identities.computeIfAbsent(first, entity -> 1L));
        assertEquals(1, identities.computeIfAbsent(first, entity -> 99L));
        assertEquals(2, identities.computeIfAbsent(second, entity -> 2L));
        identities.remove(first);
        assertEquals(3, identities.computeIfAbsent(first, entity -> 3L));
        identities.removeIf(entity -> entity == second);
        assertEquals(4, identities.computeIfAbsent(second, entity -> 4L));
    }

    @Test
    public void prayerSwitchesWithinSameTickAreCapturedWithoutTickSnapshots() throws Exception
    {
        net.runelite.api.Client client = org.mockito.Mockito.mock(net.runelite.api.Client.class);
        DroRecorderConfig config = org.mockito.Mockito.mock(DroRecorderConfig.class);
        org.mockito.Mockito.when(client.isClientThread()).thenReturn(true);
        org.mockito.Mockito.when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
        org.mockito.Mockito.when(client.getTickCount()).thenReturn(42);
        org.mockito.Mockito.when(client.getGameCycle()).thenReturn(300);
        org.mockito.Mockito.when(config.recordPrayerTransitions()).thenReturn(true);
        Path directory = Files.createTempDirectory(temporary, "recording-");
        DroRecorderSession session = new DroRecorderSession(client, new Gson(), config, directory);
        session.start();
        net.runelite.api.events.VarbitChanged change = new net.runelite.api.events.VarbitChanged();
        change.setVarbitId(net.runelite.api.Prayer.PROTECT_FROM_MAGIC.getVarbit());
        session.recordVarbit(change);
        org.mockito.Mockito.when(client.isPrayerActive(net.runelite.api.Prayer.PROTECT_FROM_MAGIC)).thenReturn(true);
        session.recordVarbit(change);
        org.mockito.Mockito.when(client.getGameCycle()).thenReturn(301);
        org.mockito.Mockito.when(client.isPrayerActive(net.runelite.api.Prayer.PROTECT_FROM_MAGIC)).thenReturn(false);
        session.recordVarbit(change);
        session.togglePaused();
        org.mockito.Mockito.when(client.getTickCount()).thenReturn(46);
        session.togglePaused();
        session.stop();
        session.getCompletion().get(5, TimeUnit.SECONDS);
        List<com.google.gson.JsonObject> prayers = new java.util.ArrayList<>();
        com.google.gson.JsonObject resumed = null;
        for (String line : Files.readAllLines(directory.resolve("events.jsonl")))
        {
            com.google.gson.JsonObject event = new JsonParser().parse(line).getAsJsonObject();
            String type = event.get("type").getAsString();
            if (type.equals("prayer_state_changed")) { prayers.add(event); }
            if (type.equals("recording_resumed")) { resumed = event; }
        }
        assertEquals(2, prayers.size());
        assertEquals(42, prayers.get(0).get("tick").getAsInt());
        assertEquals(42, prayers.get(1).get("tick").getAsInt());
        assertEquals(300, prayers.get(0).get("gameCycle").getAsInt());
        assertEquals(301, prayers.get(1).get("gameCycle").getAsInt());
        assertEquals("PROTECT_FROM_MAGIC", prayers.get(0).getAsJsonArray("activated").get(0).getAsString());
        assertEquals("PROTECT_FROM_MAGIC", prayers.get(1).getAsJsonArray("deactivated").get(0).getAsString());
        assertNotNull(resumed);
        assertEquals(4, resumed.get("gapTicks").getAsInt());
    }

    @Test
    public void fullQueueKeepsTerminalRecordAndReportsLossesByType() throws Exception
    {
        Path directory = Files.createTempDirectory(temporary, "recording-");
        Gson gson = org.mockito.Mockito.mock(Gson.class);
        Gson actual = new Gson();
        java.util.concurrent.CountDownLatch serializing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(gson.toJson(org.mockito.ArgumentMatchers.anyMap())).thenAnswer(call ->
        {
            serializing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            Object record = call.getArgument(0);
            return actual.toJson(record);
        });
        DroRecorderWriter writer = new DroRecorderWriter(directory, gson);
        writer.start();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("type", "tick");
        writer.writeRaw(first);
        assertTrue(serializing.await(5, TimeUnit.SECONDS));
        try
        {
            for (int i = 0; i < 5000; i++)
            {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("type", "projectile_moved");
                writer.writeEvent(record);
            }
            Map<String, Object> terminal = new LinkedHashMap<>();
            terminal.put("type", "session_stop");
            writer.writeEvent(terminal);
            writer.stop();
        }
        finally { release.countDown(); }
        writer.getCompletion().get(5, TimeUnit.SECONDS);
        assertEquals(Long.valueOf(904), writer.getDroppedByType().get("projectile_moved"));
        List<String> lines = Files.readAllLines(directory.resolve("session.jsonl"));
        assertEquals(4098, lines.size());
        com.google.gson.JsonObject terminal = new JsonParser().parse(lines.get(lines.size() - 1)).getAsJsonObject();
        assertEquals("session_stop", terminal.get("type").getAsString());
        assertEquals(904, terminal.get("droppedLines").getAsInt());
    }

    @Test
    public void writerFailureIsReportedThroughCompletion() throws Exception
    {
        Path file = Files.createTempFile(temporary, "unwritable-", ".tmp");
        DroRecorderWriter writer = new DroRecorderWriter(file, new Gson());
        writer.start();
        try
        {
            writer.getCompletion().get(5, TimeUnit.SECONDS);
            fail("An unwritable output destination must report failure");
        }
        catch (java.util.concurrent.ExecutionException expected)
        {
            assertTrue(expected.getCause() instanceof java.io.IOException);
        }
    }
}
