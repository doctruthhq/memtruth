package ai.doctruth;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class IdentityWeakStore<K, V> {

    private final ReferenceQueue<K> queue = new ReferenceQueue<>();
    private final Map<IdentityWeakReference<K>, V> values = new HashMap<>();

    synchronized void put(K key, V value) {
        expungeStaleEntries();
        values.put(new IdentityWeakReference<>(key, queue), value);
    }

    synchronized Optional<V> get(K key) {
        expungeStaleEntries();
        return Optional.ofNullable(values.get(new IdentityWeakReference<>(key)));
    }

    private void expungeStaleEntries() {
        IdentityWeakReference<?> ref;
        while ((ref = (IdentityWeakReference<?>) queue.poll()) != null) {
            values.remove(ref);
        }
    }

    private static final class IdentityWeakReference<T> extends WeakReference<T> {
        private final int hash;

        IdentityWeakReference(T referent, ReferenceQueue<T> queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
        }

        IdentityWeakReference(T referent) {
            super(referent);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityWeakReference<?> ref && get() == ref.get();
        }
    }
}
