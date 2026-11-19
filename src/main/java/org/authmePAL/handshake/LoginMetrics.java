package org.authmePAL.handshake;

import java.util.concurrent.atomic.LongAdder;

public final class LoginMetrics {

    private final LongAdder verified = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder throttled = new LongAdder();

    public void verified() {
        verified.increment();
    }

    public void failed() {
        failed.increment();
    }

    public void throttled() {
        throttled.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(verified.sum(), failed.sum(), throttled.sum());
    }

    public record Snapshot(long verified, long failed, long throttled) {
    }
}
