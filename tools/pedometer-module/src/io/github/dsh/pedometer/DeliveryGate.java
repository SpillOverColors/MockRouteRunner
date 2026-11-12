package io.github.dsh.pedometer;

/** Proxy-local delivery generation; invalidated when its registration is removed. */
final class DeliveryGate {
    private boolean active = true;
    private long token = 1;

    synchronized long token() { return active ? token : 0; }

    synchronized void deactivate() {
        if (!active) return;
        active = false;
        token++;
    }

    synchronized boolean allows(long captured) {
        return active && captured != 0 && captured == token;
    }
}
