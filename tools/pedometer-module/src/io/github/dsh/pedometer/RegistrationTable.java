package io.github.dsh.pedometer;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-JVM identity registry: (SensorManager, listener, sensor) -> value.
 * Identity, not equals(), is intentional: Android reuses neither listener nor
 * sensor objects across registrations, and two managers may expose the same
 * Sensor object.
 */
final class RegistrationTable<T> {
    static final class Entry<T> {
        final Object manager;
        final Object listener;
        final Object sensor;
        final T value;
        Entry(Object manager, Object listener, Object sensor, T value) {
            this.manager = manager;
            this.listener = listener;
            this.sensor = sensor;
            this.value = value;
        }
    }

    private final List<Entry<T>> entries = new ArrayList<>();

    synchronized T find(Object manager, Object listener, Object sensor) {
        for (Entry<T> e : entries) {
            if (e.manager == manager && e.listener == listener && e.sensor == sensor) return e.value;
        }
        return null;
    }

    synchronized void put(Object manager, Object listener, Object sensor, T value) {
        if (find(manager, listener, sensor) != null) {
            throw new IllegalStateException("duplicate registration key");
        }
        entries.add(new Entry<>(manager, listener, sensor, value));
    }

    synchronized boolean removeValue(T value) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).value == value) {
                entries.remove(i);
                return true;
            }
        }
        return false;
    }

    /** sensor == null removes every sensor for this (manager, listener). */
    synchronized List<T> remove(Object manager, Object listener, Object sensor) {
        List<T> removed = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry<T> e = entries.get(i);
            if (e.manager == manager && e.listener == listener
                    && (sensor == null || e.sensor == sensor)) {
                removed.add(e.value);
                entries.remove(i);
            }
        }
        return removed;
    }

    synchronized int size() { return entries.size(); }
}
