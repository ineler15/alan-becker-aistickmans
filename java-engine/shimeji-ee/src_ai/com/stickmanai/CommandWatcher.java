package com.stickmanai;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bridges the Java Shimeji-ee process with the Node "brain" process, one
 * instance per AI-driven character. Polls workspace/ai-command-<id>.json off
 * the 40ms mascot tick thread and caches the latest parsed command in memory;
 * persists status updates the same way, so neither side ever does disk I/O
 * inside a tick.
 */
public class CommandWatcher {

    private static final Logger log = Logger.getLogger(CommandWatcher.class.getName());
    private static final long POLL_INTERVAL_MS = 200;

    private static final Map<String, CommandWatcher> instances = new HashMap<String, CommandWatcher>();

    private final Path commandFile;
    private final Path statusFile;

    private final AtomicReference<Map<String, Object>> lastCommand = new AtomicReference<Map<String, Object>>();
    private final AtomicReference<Map<String, Object>> pendingStatus = new AtomicReference<Map<String, Object>>();

    private volatile long lastCommandModified = -1;

    private CommandWatcher(Path workspaceDir, String characterId) {
        this.commandFile = workspaceDir.resolve("ai-command-" + characterId + ".json");
        this.statusFile = workspaceDir.resolve("ai-status-" + characterId + ".json");

        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "stickmanai-io-" + characterId);
                t.setDaemon(true);
                return t;
            }
        });
        exec.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                poll();
            }
        }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        log.log(Level.INFO, "AI command watcher started for {0}, workspace={1}", new Object[] { characterId, workspaceDir });
    }

    /** One watcher (and one polling thread) per character id, created lazily and reused. */
    public static synchronized CommandWatcher forId(String characterId) {
        CommandWatcher watcher = instances.get(characterId);
        if (watcher == null) {
            watcher = new CommandWatcher(resolveWorkspaceDir(), characterId);
            instances.put(characterId, watcher);
        }
        return watcher;
    }

    private static Path resolveWorkspaceDir() {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream("./conf/settings.properties")) {
            props.load(in);
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not read settings.properties for AIWorkspaceDir, using default", e);
        }
        String dir = props.getProperty("AIWorkspaceDir", "./workspace");
        return Paths.get(dir).toAbsolutePath().normalize();
    }

    private void poll() {
        try {
            if (Files.exists(commandFile)) {
                long modified = Files.getLastModifiedTime(commandFile).toMillis();
                if (modified != lastCommandModified) {
                    lastCommandModified = modified;
                    String text = new String(Files.readAllBytes(commandFile), "UTF-8");
                    if (!text.trim().isEmpty()) {
                        lastCommand.set(Json.parseObject(text));
                    }
                }
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to read AI command file", e);
        }

        try {
            Map<String, Object> status = pendingStatus.getAndSet(null);
            if (status != null) {
                String json = Json.write(status);
                Path tmp = statusFile.resolveSibling(statusFile.getFileName().toString() + ".tmp");
                Files.write(tmp, json.getBytes("UTF-8"));
                Files.move(tmp, statusFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to write AI status file", e);
        }
    }

    /** Cheap, memory-only read - safe to call every 40ms tick. */
    public Map<String, Object> getCommand() {
        return lastCommand.get();
    }

    /** Cheap, memory-only write - the daemon thread persists it every ~200ms. */
    public void queueStatus(Map<String, Object> status) {
        pendingStatus.set(status);
    }
}
