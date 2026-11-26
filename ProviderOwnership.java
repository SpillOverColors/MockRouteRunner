package io.github.dsh.mockroute;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Per-run ownership only. Never carries ownership across a process restart. */
final class ProviderOwnership {
    private final Set<String> owned = new LinkedHashSet<>();

    boolean created(String provider) {
        return provider != null && !provider.isEmpty() && owned.add(provider);
    }

    boolean owns(String provider) {
        return provider != null && owned.contains(provider);
    }

    boolean removed(String provider) {
        return provider != null && owned.remove(provider);
    }

    boolean isEmpty() {
        return owned.isEmpty();
    }

    Set<String> snapshot() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(owned));
    }
}
