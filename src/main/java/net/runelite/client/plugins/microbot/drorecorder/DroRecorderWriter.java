package net.runelite.client.plugins.microbot.drorecorder;

import com.google.gson.Gson;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
final class DroRecorderWriter
{
    private static final int MAX_QUEUE = 4096;
    private static final WriteJob POISON = new WriteJob(null, false);

    private final BlockingQueue<WriteJob> queue = new LinkedBlockingQueue<>(MAX_QUEUE + 2);
    private final Path sessionDirectory;
    private final Path rawFile;
    private final Path eventsFile;
    private final Gson gson;
    private final Map<String, AtomicLong> droppedByType = new ConcurrentHashMap<>();
    private final AtomicLong droppedLines = new AtomicLong();

    @Getter
    private volatile boolean running;

    private Thread writerThread;
    @Getter
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    DroRecorderWriter(Path sessionDirectory, Gson gson)
    {
        this.gson = gson;
        this.sessionDirectory = sessionDirectory;
        this.rawFile = sessionDirectory.resolve("session.jsonl");
        this.eventsFile = sessionDirectory.resolve("events.jsonl");
    }

    Path getSessionDirectory()
    {
        return sessionDirectory;
    }

    long getDroppedLines()
    {
        return droppedLines.get();
    }

    void start()
    {
        if (running)
        {
            return;
        }

        running = true;
        writerThread = new Thread(this::writerLoop, "DroRecorder-Writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    void writeRaw(Map<String, Object> record)
    {
        enqueue(new WriteJob(record, false));
    }

    void writeEvent(Map<String, Object> record)
    {
        enqueue(new WriteJob(record, true));
    }

    private synchronized void enqueue(WriteJob job)
    {
        if (!running || job == null || job.record == null)
        {
            return;
        }

        boolean terminal = "session_stop".equals(job.record.get("type"));
        if ((!terminal && queue.size() >= MAX_QUEUE) || !queue.offer(job))
        {
            recordDrop(job);
        }
    }

    synchronized void stop()
    {
        if (!running)
        {
            return;
        }

        running = false;
        // Two reserved slots guarantee room for the terminal record and shutdown marker.
        queue.offer(POISON);
    }

    private void writerLoop()
    {
        try
        {
            Files.createDirectories(sessionDirectory);

            try (BufferedWriter raw = Files.newBufferedWriter(
                    rawFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
                 BufferedWriter events = Files.newBufferedWriter(
                    eventsFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE))
            {
                long lastFlush = System.currentTimeMillis();
                int sinceFlush = 0;

                while (true)
                {
                    WriteJob job = queue.poll(1, TimeUnit.SECONDS);
                    if (job == null)
                    {
                        raw.flush();
                        events.flush();
                        continue;
                    }
                    if (job == POISON)
                    {
                        // Drain everything that arrived before the poison marker.
                        WriteJob remaining;
                        while ((remaining = queue.poll()) != null)
                        {
                            if (remaining == POISON)
                            {
                                continue;
                            }
                            writeJob(raw, events, remaining);
                        }
                        raw.flush();
                        events.flush();
                        break;
                    }

                    writeJob(raw, events, job);
                    sinceFlush++;

                    long now = System.currentTimeMillis();
                    if (sinceFlush >= 100 || now - lastFlush >= 1000)
                    {
                        raw.flush();
                        events.flush();
                        lastFlush = now;
                        sinceFlush = 0;
                    }
                }
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            completion.completeExceptionally(e);
        }
        catch (IOException | RuntimeException e)
        {
            completion.completeExceptionally(e);
            log.error("DroRecorder writer failed", e);
        }
        finally
        {
            running = false;
            completion.complete(null);
        }
    }

    private void writeJob(BufferedWriter raw, BufferedWriter events, WriteJob job) throws IOException
    {
        if ("session_stop".equals(job.record.get("type")))
        {
            job.record.put("droppedLines", droppedLines.get());
            job.record.put("droppedByType", getDroppedByType());
        }
        String json = gson.toJson(job.record);
        raw.write(json);
        raw.newLine();

        if (job.discreteEvent)
        {
            events.write(json);
            events.newLine();
        }
    }

    private void recordDrop(WriteJob job)
    {
        droppedLines.incrementAndGet();
        if (job.record != null)
        {
            droppedByType.computeIfAbsent(String.valueOf(job.record.get("type")), key -> new AtomicLong()).incrementAndGet();
        }
    }

    Map<String, Long> getDroppedByType()
    {
        Map<String, Long> result = new LinkedHashMap<>();
        droppedByType.forEach((type, count) -> result.put(type, count.get()));
        return result;
    }

    private static final class WriteJob
    {
        private final Map<String, Object> record;
        private final boolean discreteEvent;

        private WriteJob(Map<String, Object> record, boolean discreteEvent)
        {
            this.record = record;
            this.discreteEvent = discreteEvent;
        }
    }
}
