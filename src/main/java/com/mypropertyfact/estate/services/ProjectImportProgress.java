package com.mypropertyfact.estate.services;

import com.mypropertyfact.estate.models.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private volatile int created;
    private volatile int updated;
    private volatile int imagesSaved;
    private volatile int imagesSkipped;
    /** Every row error ("Row N: reason"), so a bulk upload can report all of them, not just the first ten. */
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());

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

    void recordResult(int created, int updated, int imagesSaved, int imagesSkipped, List<String> rowErrors) {
        this.created = created;
        this.updated = updated;
        this.imagesSaved = imagesSaved;
        this.imagesSkipped = imagesSkipped;
        errors.addAll(rowErrors);
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

    public int getCreated() {
        return created;
    }

    public int getUpdated() {
        return updated;
    }

    public int getImagesSaved() {
        return imagesSaved;
    }

    public int getImagesSkipped() {
        return imagesSkipped;
    }

    public List<String> getErrors() {
        synchronized (errors) {
            return new ArrayList<>(errors);
        }
    }
}
