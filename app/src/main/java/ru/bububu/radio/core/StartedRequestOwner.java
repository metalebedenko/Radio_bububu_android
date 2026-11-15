package ru.bububu.radio.core;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/**
 * Completes delivered service starts without canceling a later registered start.
 * All operations belong to the service owner thread. Only Android knows whether
 * another start has been registered but not delivered: the supplied port must
 * call Service.stopSelfResult with the captured, actually delivered start id.
 */
public final class StartedRequestOwner {
    private final IntPredicate stopSelfResult;
    private int latestDeliveredId;
    private int dispatchDepth;

    public StartedRequestOwner(IntPredicate stopSelfResult) {
        this.stopSelfResult = Objects.requireNonNull(stopSelfResult, "stopSelfResult");
    }

    /** Release during a callback is completed only after its replacement work is known. */
    public void dispatch(int startId, Runnable command, BooleanSupplier retainStartedRequest) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(retainStartedRequest, "retainStartedRequest");
        latestDeliveredId = startId;
        dispatchDepth++;
        try {
            command.run();
        } finally {
            dispatchDepth--;
            // Use this delivery's id, even if command code reentered and delivered a newer one.
            if (!retainStartedRequest.getAsBoolean()) finish(startId);
        }
    }

    /** Capture before releasing playback resources, whose callbacks may reenter. */
    public int captureDeliveredId() { return latestDeliveredId; }

    /** Outside a delivery, close only the captured old work; inside, defer to its finally. */
    public void finishAfterPlaybackRelease(int capturedId) {
        if (dispatchDepth == 0) finish(capturedId);
    }

    private void finish(int deliveredId) {
        // A merely bound service has no delivered start to complete. Never use -1/stopSelf().
        if (deliveredId > 0) stopSelfResult.test(deliveredId);
    }
}
