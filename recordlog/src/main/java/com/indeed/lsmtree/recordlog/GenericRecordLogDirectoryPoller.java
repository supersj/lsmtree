package com.indeed.lsmtree.recordlog;

import com.google.common.collect.Lists;
import com.indeed.util.core.io.Closeables2;
import com.indeed.util.varexport.Export;
import com.indeed.util.io.checkpointer.Checkpointer;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author jplaisance
 */
public class GenericRecordLogDirectoryPoller<T> implements Runnable, Closeable {

    private static final Logger log = Logger.getLogger(GenericRecordLogDirectoryPoller.class);

    private static final int SYNC_FREQUENCY = 10000;

    private long lastPosition;

    private final RecordLogDirectory<T> recordLogDirectory;

    private final Checkpointer<Long> checkpointer;

    private final boolean loop;

    private final boolean gc;
    private final boolean skipFirst;

    private final AtomicBoolean isClosed;

    private final List<Functions<T>> functionsList;

    private Thread pollerThread;

    public GenericRecordLogDirectoryPoller(
            final RecordLogDirectory<T> recordLogDirectory,
            final Checkpointer<Long> checkpointer
    ) throws IOException {
        this(recordLogDirectory, checkpointer, true);
    }

    public GenericRecordLogDirectoryPoller(
            RecordLogDirectory<T> recordLogDirectory,
            Checkpointer<Long> checkpointer,
            boolean loop
    ) throws IOException {
        this(recordLogDirectory, checkpointer, loop, false);
    }

    public GenericRecordLogDirectoryPoller(
                RecordLogDirectory<T> recordLogDirectory,
                Checkpointer<Long> checkpointer,
                boolean loop,
                boolean gc) throws IOException {
        this(recordLogDirectory, checkpointer, loop, gc, false);
    }

    public GenericRecordLogDirectoryPoller(
            RecordLogDirectory<T> recordLogDirectory,
            Checkpointer<Long> checkpointer,
            boolean loop,
            boolean gc,
            final boolean skipFirst) throws IOException {
        this.recordLogDirectory = recordLogDirectory;
        this.checkpointer = checkpointer;
        this.loop = loop;
        this.gc = gc;
        this.skipFirst = skipFirst;
        this.isClosed = new AtomicBoolean(false);
        this.functionsList = Lists.newArrayList();
        lastPosition = checkpointer.getCheckpoint();
    }

    public void registerFunctions(Functions<T> functions) {
        functionsList.add(functions);
    }

    public void start() {
        pollerThread = new Thread(this);
        pollerThread.start();
    }

    @Override
    public void run() {
        do {
            try {
                final RecordFile.Reader<T> poller;
                try {
                    poller = recordLogDirectory.reader(lastPosition);
                } catch (Exception e) {
                    log.error("error seeking to last valid position", e);
                    Thread.sleep(5000);
                    continue;
                }
                int count = 0;
                final long start = lastPosition;
                long lastKnownGoodPosition = start;
                try {
                    final boolean doLoop;
                    if (skipFirst) {
                        doLoop = poller.next();
                    } else {
                        doLoop = true;
                    }
                    if (doLoop) {
                        while (poller.next()) {
                            lastPosition = poller.getPosition();
                            final T op = poller.get();
                            for (Functions<T> functions : functionsList) {
                                functions.process(lastPosition, op);
                            }
                            count++;
                            if (count % SYNC_FREQUENCY == 0) {
                                for (Functions<T> functions : functionsList) {
                                    functions.sync();
                                }
                                checkpointPosition(lastPosition);
                            }
                            lastKnownGoodPosition = lastPosition;
                        }
                    }
                } catch (Exception e) {
                    log.error("error reading segment file", e);
                    lastPosition = lastKnownGoodPosition;
                    Closeables2.closeQuietly(poller, log);
                    Thread.sleep(5000);
                    continue;
                }
                try {
                    poller.close();
                } catch (IOException e) {
                    //should never happen
                    log.error("error closing reader", e);
                }
                if (start != lastPosition) {
                    for (Functions<T> functions : functionsList) {
                        try {
                            functions.sync();
                        } catch (Exception e) {
                            log.error("sync error", e);
                            throw new RuntimeException(e);
                        }
                    }
                    try {
                        checkpointPosition(lastPosition);
                    } catch (Exception e) {
                        log.error("error writing last position file", e);
                    }
                }
                if (loop) Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        } while (!isClosed.get() && loop);
    }

    private void checkpointPosition(final long lastPosition)
            throws IOException {
        checkpointer.setCheckpoint(lastPosition);
        if (gc) recordLogDirectory.garbageCollect(lastPosition);
    }

    @Export(name = "last-position", doc = "The last position poller has read")
    public long getLastPosition() {
        return lastPosition;
    }

    @Export(name = "current-segment-timestamp", doc = "Timestamp of the current segment being read")
    public long getCurrentSegmentTimestamp() throws IOException {
        return recordLogDirectory.getSegmentTimestamp(getCurrentSegmentNum());
    }

    @Export(name = "current-segment-timestring", doc = "Same as current-segment-timestamp in human readable form")
    public String getCurrentSegmentTimestring() throws IOException {
        return new DateTime(getCurrentSegmentTimestamp()).toString();
    }

    @Export(name = "max-segment-timestamp", doc = "Timestamp of the max segment that exists in directory")
    public long getMaxSegmentTimestamp() throws IOException {
        return recordLogDirectory.getSegmentTimestamp(getMaxSegmentNum());
    }

    @Export(name = "max-segment-timestring", doc = "Same as max-segment-timestamp in human readable form")
    public String getMaxSegmentTimestring() throws IOException {
        return new DateTime(getMaxSegmentTimestamp()).toString();
    }

    @Export(name = "current-segment-num", doc = "The current segment that the poller is reading")
    public int getCurrentSegmentNum() {
        return recordLogDirectory.getSegmentNum(getLastPosition());
    }

    @Export(name = "max-segment-num", doc = "The max segment number that exists in directory")
    public int getMaxSegmentNum() throws IOException {
        return recordLogDirectory.getMaxSegmentNum();
    }

    @Override
    public void close() throws IOException {
        isClosed.set(true);
        //make sure poller thread is dead before returning so that file cache can safely be closed
        if (pollerThread != null) {
            while (pollerThread.isAlive()) {
                Thread.yield();
            }
        }
    }

    public boolean isAlive() {
        return pollerThread.isAlive();
    }

    public static interface Functions<T> {
        public void process(long position, T op) throws IOException;

        public void sync() throws IOException;
    }
}