package com.mypropertyfact.estate.services;

import com.mypropertyfact.estate.models.Response;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live state of one bulk project import, read by the admin panel while the import runs.
 * Counters are updated from several image threads, so they are atomic; Jackson serialises
 * the getters as the job's JSON.
 */
public class ProjectImportProgress {

    private final String jobId = UUID.randomUUID().toString();
    private volatile String phase = "queued";
    private volatile int rowsTotal;
    private final AtomicInteger rowsDone = new AtomicInteger();
    private volatile int imagesTotal;
    private final AtomicInteger imagesDone = new AtomicInteger();
    private volatile boolean done;
    private volatile int isSuccess;
    private volatile String message;
    private volatile long finishedAt;

    void startRows(int rows, int images) {
        rowsTotal = rows;
        imagesTotal = images;
        phase = "rows";
    }

    void rowDone() {
        rowsDone.incrementAndGet();
    }

    void startImages() {
        phase = "images";
    }

    void imageDone() {
        imagesDone.incrementAndGet();
    }

    void finish(Response result) {
        isSuccess = result.getIsSuccess();
        message = result.getMessage();
        phase = "done";
        finishedAt = System.currentTimeMillis();
        done = true;
    }

    public String getJobId() {
        return jobId;
    }

    public String getPhase() {
        return phase;
    }

    public int getRowsTotal() {
        return rowsTotal;
    }

    public int getRowsDone() {
        return rowsDone.get();
    }

    public int getImagesTotal() {
        return imagesTotal;
    }

    public int getImagesDone() {
        return Math.min(imagesDone.get(), imagesTotal);
    }

    public boolean isDone() {
        return done;
    }

    public int getIsSuccess() {
        return isSuccess;
    }

    public String getMessage() {
        return message;
    }

    public long getFinishedAt() {
        return finishedAt;
    }
}
