package com.example.rag.feature.audit.service;

import com.example.rag.feature.audit.model.*;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class IngestAuditService {

    private static final int MAX_RUNS = 100;

    private final ConcurrentLinkedDeque<IngestAuditRun> runs = new ConcurrentLinkedDeque<>();

    public RunHandle startRun() {
        return new RunHandle(UUID.randomUUID().toString(), Instant.now());
    }

    public void saveRun(RunHandle handle, int processed, int skipped) {
        if (handle == null) {
            return;
        }
        IngestAuditRun run = new IngestAuditRun(
                handle.id,
                handle.startedAt,
                Instant.now(),
                processed,
                skipped,
                List.copyOf(handle.files)
        );
        runs.addFirst(run);
        while (runs.size() > MAX_RUNS) {
            runs.pollLast();
        }
    }

    public List<IngestAuditRun> recentRuns(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_RUNS));
        List<IngestAuditRun> out = new ArrayList<>(safeLimit);
        int count = 0;
        for (IngestAuditRun run : runs) {
            out.add(run);
            count++;
            if (count >= safeLimit) {
                break;
            }
        }
        return out;
    }

    public static final class RunHandle {
        private final String id;
        private final Instant startedAt;
        private final List<IngestAuditFileEntry> files = new ArrayList<>();

        private RunHandle(String id, Instant startedAt) {
            this.id = id;
            this.startedAt = startedAt;
        }

        public synchronized void addFileEvent(String filename, String status, String reason) {
            files.add(new IngestAuditFileEntry(filename, status, reason, Instant.now()));
        }
    }
}
