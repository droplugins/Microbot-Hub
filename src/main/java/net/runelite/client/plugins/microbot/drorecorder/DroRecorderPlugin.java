package net.runelite.client.plugins.microbot.drorecorder;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.*;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ClientUI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@PluginDescriptor(
    name = PluginConstants.DRO + "Recorder",
    description = "Passive gameplay recorder for Fight Caves training and automation development",
    minClientVersion = "2.6.33",
    version = DroRecorderPlugin.version,
    authors = {"droplugins"},
    tags = {"microbot", "recorder", "fightcaves", "firecape"},
    enabledByDefault = PluginConstants.DEFAULT_ENABLED,
    isExternal = PluginConstants.IS_EXTERNAL
)
@Singleton
@Slf4j
public class DroRecorderPlugin extends Plugin
{
    public static final String version = "1.1.6";
    public static final String NAME = "DRO's Recorder";
    private static final Path OUTPUT_ROOT = RuneLite.RUNELITE_DIR.toPath().resolve("dro-recorder");

    @Inject
    private Client client;

    @Inject
    private Gson gson;

    @Inject
    private DroRecorderConfig config;

    @Inject
    private DroRecorderOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private KeyManager keyManager;

    @Inject
    private MouseManager mouseManager;

    @Inject
    private ClientThread clientThread;

    private volatile DroRecorderSession session;
    private DroRecorderSession focusSession;
    private Canvas focusCanvas;
    private Window focusWindow;
    private final FocusAdapter canvasFocusListener = new FocusAdapter()
    {
        @Override
        public void focusGained(FocusEvent event) { recordFocus("canvas", true, "change", event.isTemporary()); }
        @Override
        public void focusLost(FocusEvent event) { recordFocus("canvas", false, "change", event.isTemporary()); }
    };
    private final WindowAdapter windowFocusListener = new WindowAdapter()
    {
        @Override
        public void windowGainedFocus(WindowEvent event) { recordFocus("window", true, "change", false); }
        @Override
        public void windowLostFocus(WindowEvent event) { recordFocus("window", false, "change", false); }
    };

    private void recordFocus(String scope, boolean focused, String reason, boolean temporary)
    {
        DroRecorderSession current = session;
        if (current != null) current.recordFocus(scope, focused, reason, temporary);
    }

    // AWT owns focus. Attach on its thread, and reattach if RuneLite replaces the canvas.
    private void attachFocusListeners(Canvas canvas, DroRecorderSession expected)
    {
        SwingUtilities.invokeLater(() ->
        {
            if (session != expected || expected == null) return;
            boolean newSession = focusSession != expected;
            focusSession = expected;
            if (focusCanvas != canvas)
            {
                if (focusCanvas != null) focusCanvas.removeFocusListener(canvasFocusListener);
                focusCanvas = canvas;
                if (canvas != null)
                {
                    canvas.addFocusListener(canvasFocusListener);
                    recordFocus("canvas", canvas.isFocusOwner(), "baseline", false);
                }
            }
            else if (newSession && canvas != null)
                recordFocus("canvas", canvas.isFocusOwner(), "baseline", false);
            Window window = ClientUI.getFrame();
            if (focusWindow != window)
            {
                if (focusWindow != null) focusWindow.removeWindowFocusListener(windowFocusListener);
                focusWindow = window;
                if (window != null)
                {
                    window.addWindowFocusListener(windowFocusListener);
                    recordFocus("window", window.isFocused(), "baseline", false);
                }
            }
            else if (newSession && window != null)
                recordFocus("window", window.isFocused(), "baseline", false);
        });
    }

    private void detachFocusListeners()
    {
        SwingUtilities.invokeLater(() ->
        {
            // A rapid restart may already have reused the listeners.
            if (session != null) return;
            if (focusCanvas != null) focusCanvas.removeFocusListener(canvasFocusListener);
            if (focusWindow != null) focusWindow.removeWindowFocusListener(windowFocusListener);
            focusCanvas = null;
            focusWindow = null;
            focusSession = null;
        });
    }

    private final HotkeyListener importantHotkey = new HotkeyListener(() -> config.importantHotkey())
    {
        @Override
        public void hotkeyPressed()
        {
            annotate("IMPORTANT");
        }
    };

    private final HotkeyListener safespotHotkey = new HotkeyListener(() -> config.safespotHotkey())
    {
        @Override
        public void hotkeyPressed()
        {
            annotate("SAFESPOT");
        }
    };

    private final HotkeyListener mistakeHotkey = new HotkeyListener(() -> config.mistakeHotkey())
    {
        @Override
        public void hotkeyPressed()
        {
            annotate("MISTAKE");
        }
    };

    private final HotkeyListener pauseHotkey = new HotkeyListener(() -> config.pauseHotkey())
    {
        @Override
        public void hotkeyPressed()
        {
            clientThread.invokeLater(() ->
            {
                DroRecorderSession current = session;
                if (current != null)
                {
                    current.togglePaused();
                }
            });
        }
    };

    private final MouseAdapter mouseListener = new MouseAdapter()
    {
        @Override
        public MouseEvent mouseMoved(MouseEvent e)
        {
            if (session != null)
            {
                session.recordMouseMove(e, false);
            }
            return e;
        }

        @Override
        public MouseEvent mouseDragged(MouseEvent e)
        {
            if (session != null)
            {
                session.recordMouseMove(e, true);
            }
            return e;
        }

        @Override
        public MouseEvent mousePressed(MouseEvent e)
        {
            if (session != null)
            {
                session.recordMouseButton("mouse_pressed", e);
            }
            return e;
        }

        @Override
        public MouseEvent mouseReleased(MouseEvent e)
        {
            if (session != null)
            {
                session.recordMouseButton("mouse_released", e);
            }
            return e;
        }

        @Override
        public MouseEvent mouseEntered(MouseEvent e)
        {
            if (session != null)
            {
                session.recordMouseBoundary("mouse_entered_canvas", e);
            }
            return e;
        }

        @Override
        public MouseEvent mouseExited(MouseEvent e)
        {
            if (session != null)
            {
                session.recordMouseBoundary("mouse_exited_canvas", e);
            }
            return e;
        }
    };

    @Provides
    DroRecorderConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(DroRecorderConfig.class);
    }

    @Override
    protected void startUp()
    {
        session = new DroRecorderSession(client, gson, config);
        session.start();
        clientThread.invoke(() -> attachFocusListeners(client.getCanvas(), session));

        overlayManager.add(overlay);
        mouseManager.registerMouseListener(mouseListener);
        keyManager.registerKeyListener(importantHotkey);
        keyManager.registerKeyListener(safespotHotkey);
        keyManager.registerKeyListener(mistakeHotkey);
        keyManager.registerKeyListener(pauseHotkey);

        log.info("DroRecorder started: {}", session.getOutputDirectory());
    }

    @Override
    protected void shutDown()
    {
        keyManager.unregisterKeyListener(importantHotkey);
        keyManager.unregisterKeyListener(safespotHotkey);
        keyManager.unregisterKeyListener(mistakeHotkey);
        keyManager.unregisterKeyListener(pauseHotkey);
        mouseManager.unregisterMouseListener(mouseListener);
        overlayManager.remove(overlay);

        DroRecorderSession stopping = session;
        session = null;
        if (stopping != null)
        {
            log.info("DroRecorder stopping: {}", stopping.getOutputDirectory());
            stopping.stop();
        }
        detachFocusListeners();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!DroRecorderConfig.GROUP.equals(event.getGroup()))
        {
            return;
        }

        if ("openOutputFolder".equals(event.getKey()))
        {
            openOutputFolder();
        }
    }

    public static void openOutputFolder()
    {
        // Config button callbacks originate on the Swing/AWT thread. Keep Finder/Explorer
        // launching off that thread, and log each attempt so failures are diagnosable.
        Thread opener = new Thread(() ->
        {
            try
            {
                Files.createDirectories(OUTPUT_ROOT);
                String absolutePath = OUTPUT_ROOT.toAbsolutePath().toString();
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                log.info("[Dro] Recorder opening output folder: {}", absolutePath);

                if (os.contains("mac"))
                {
                    // `open -R` asks Finder to reveal the folder even when its parent
                    // (~/.runelite) is hidden. If LaunchServices rejects reveal, fall back
                    // to a normal open, then finally to AppleScript Finder reveal.
                    if (runOpenCommand(new ProcessBuilder("/usr/bin/open", "-R", absolutePath)))
                    {
                        return;
                    }
                    if (runOpenCommand(new ProcessBuilder("/usr/bin/open", absolutePath)))
                    {
                        return;
                    }

                    String escaped = absolutePath.replace("\\", "\\\\").replace("\"", "\\\"");
                    runOpenCommand(new ProcessBuilder(
                        "/usr/bin/osascript",
                        "-e", "tell application \"Finder\" to reveal POSIX file \"" + escaped + "\"",
                        "-e", "tell application \"Finder\" to activate"));
                    return;
                }

                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
                {
                    Desktop.getDesktop().open(OUTPUT_ROOT.toFile());
                    return;
                }

                if (os.contains("win"))
                {
                    runOpenCommand(new ProcessBuilder("explorer.exe", absolutePath));
                }
                else
                {
                    runOpenCommand(new ProcessBuilder("xdg-open", absolutePath));
                }
            }
            catch (IOException | SecurityException e)
            {
                log.warn("Failed to open [Dro] Recorder output folder {}. Manual path: ~/.runelite/dro-recorder/", OUTPUT_ROOT, e);
            }
        }, "DroRecorder-OpenFolder");
        opener.setDaemon(true);
        opener.start();
    }

    private static boolean runOpenCommand(ProcessBuilder processBuilder)
    {
        try
        {
            Process process = processBuilder.redirectErrorStream(true).start();
            if (!process.waitFor(3, TimeUnit.SECONDS))
            {
                process.destroy();
                log.warn("[Dro] Recorder output-folder command timed out: {}", processBuilder.command());
                return false;
            }

            if (process.exitValue() == 0)
            {
                log.info("[Dro] Recorder output-folder command succeeded: {}", processBuilder.command());
                return true;
            }

            log.warn("[Dro] Recorder output-folder command failed with exit {}: {}",
                process.exitValue(), processBuilder.command());
            return false;
        }
        catch (IOException e)
        {
            log.warn("[Dro] Recorder could not start output-folder command: {}", processBuilder.command(), e);
            return false;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (session != null)
        {
            attachFocusListeners(client.getCanvas(), session);
            session.recordGameTick();
        }
    }

    @Subscribe
    public void onFocusChanged(FocusChanged event)
    {
        recordFocus("runelite", event.isFocused(), "change", false);
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (session != null)
        {
            session.recordMenuClick(event);
        }
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event)
    {
        if (session != null)
        {
            session.recordNpcLifecycle("npc_spawned", event.getNpc());
        }
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event)
    {
        if (session != null)
        {
            session.recordNpcLifecycle("npc_despawned", event.getNpc());
        }
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event)
    {
        if (session != null)
        {
            session.recordTileObjectLifecycle("object_spawned", "game", event.getGameObject());
        }
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event)
    {
        if (session != null)
        {
            session.recordTileObjectLifecycle("object_despawned", "game", event.getGameObject());
        }
    }

    @Subscribe
    public void onWallObjectSpawned(WallObjectSpawned event)
    {
        if (session != null)
        {
            session.recordTileObjectLifecycle("object_spawned", "wall", event.getWallObject());
        }
    }

    @Subscribe
    public void onWallObjectDespawned(WallObjectDespawned event)
    {
        if (session != null)
        {
            session.recordTileObjectLifecycle("object_despawned", "wall", event.getWallObject());
        }
    }

    @Subscribe
    public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
    {
        if (session != null)
        {
            session.recordTileObjectLifecycle("object_spawned", "decorative", event.getDecorativeObject());
        }
    }

    @Subscribe
    public void onDecorativeObjectDespawned(DecorativeObjectDespawned event)
    {
        if (session != null)
        {
            session.recordTileObjectLifecycle("object_despawned", "decorative", event.getDecorativeObject());
        }
    }

    @Subscribe
    public void onGroundObjectSpawned(GroundObjectSpawned event)
    {
        if (session != null)
        {
            session.recordTileObjectLifecycle("object_spawned", "ground", event.getGroundObject());
        }
    }

    @Subscribe
    public void onGroundObjectDespawned(GroundObjectDespawned event)
    {
        if (session != null)
        {
            session.recordTileObjectLifecycle("object_despawned", "ground", event.getGroundObject());
        }
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event)
    {
        if (session != null)
        {
            session.recordAnimation(event.getActor());
        }
    }

    @Subscribe
    public void onInteractingChanged(InteractingChanged event)
    {
        if (session != null)
        {
            session.recordInteraction(event.getSource(), event.getTarget());
        }
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event)
    {
        if (session != null)
        {
            session.recordHitsplat(event.getActor(), event.getHitsplat());
        }
    }

    @Subscribe
    public void onProjectileMoved(ProjectileMoved event)
    {
        if (session != null)
        {
            session.recordProjectile(event.getProjectile(), event.getPosition(), event.getZ());
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (session != null)
        {
            session.recordItemContainerChanged(event);
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (session != null)
        {
            session.recordGameState(event.getGameState());
        }
    }

    @Subscribe
    public void onNpcChanged(NpcChanged event)
    {
        if (session != null) { session.recordNpcChanged(event); }
    }

    @Subscribe
    public void onActorDeath(ActorDeath event)
    {
        if (session != null) { session.recordActorEvent("actor_death", event.getActor()); }
    }

    @Subscribe
    public void onGraphicChanged(GraphicChanged event)
    {
        if (session != null) { session.recordActorEvent("actor_graphic_changed", event.getActor()); }
    }

    @Subscribe
    public void onGraphicsObjectCreated(GraphicsObjectCreated event)
    {
        if (session != null) { session.recordGraphic(event.getGraphicsObject()); }
    }

    @Subscribe
    public void onItemSpawned(ItemSpawned event)
    {
        if (session != null) { session.recordGroundItem("ground_item_spawned", event.getTile(), event.getItem(), 0, event.getItem().getQuantity()); }
    }

    @Subscribe
    public void onItemDespawned(ItemDespawned event)
    {
        if (session != null) { session.recordGroundItem("ground_item_despawned", event.getTile(), event.getItem(), event.getItem().getQuantity(), 0); }
    }

    @Subscribe
    public void onItemQuantityChanged(ItemQuantityChanged event)
    {
        if (session != null) { session.recordGroundItem("ground_item_quantity_changed", event.getTile(), event.getItem(), event.getOldQuantity(), event.getNewQuantity()); }
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        if (session != null) { session.recordStat(event); }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        if (session != null) { session.recordVarbit(event); }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (session != null) { session.recordGameMessage(event); }
    }

    private void annotate(String label)
    {
        // Hotkey listeners run on the AWT event thread. Annotation snapshots read
        // actor world locations and scene state, which RuneLite requires on the
        // client thread. Queue the complete annotation there instead of touching
        // client-thread-only APIs from AWT.
        clientThread.invokeLater(() ->
        {
            DroRecorderSession current = session;
            if (current != null)
            {
                current.annotate(label);
            }
        });
    }

    boolean hasSession()
    {
        return session != null;
    }

    boolean isPaused()
    {
        return session == null || session.isPaused();
    }

    long getEventCount()
    {
        return session == null ? 0 : session.getEventCount();
    }

    long getTickCount()
    {
        return session == null ? 0 : session.getTickCount();
    }

    long getDroppedLines()
    {
        return session == null ? 0 : session.getDroppedLines();
    }

    String getRuntimeText()
    {
        if (session == null)
        {
            return "00:00:00";
        }

        Duration d = Duration.ofMillis(System.currentTimeMillis() - session.getStartedAtMs());
        long hours = d.toHours();
        long minutes = d.minusHours(hours).toMinutes();
        long seconds = d.minusHours(hours).minusMinutes(minutes).getSeconds();
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
