package edu.ipcmax.core.cache;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Generic query-local single-flight cache with explicit wait accounting. */
public final class SingleFlightCache<K, V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> values =
            new ConcurrentHashMap<>();
    private final AtomicLong lookups = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong waits = new AtomicLong();

    public V getOrCompute(K key, Supplier<V> supplier) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(supplier, "supplier");
        lookups.incrementAndGet();
        CompletableFuture<V> promise = new CompletableFuture<>();
        CompletableFuture<V> existing = values.putIfAbsent(key, promise);
        if (existing != null) {
            hits.incrementAndGet();
            if (!existing.isDone()) {
                waits.incrementAndGet();
            }
            return existing.join();
        }
        misses.incrementAndGet();
        try {
            V value = Objects.requireNonNull(
                    supplier.get(), "single-flight value");
            promise.complete(value);
            return value;
        } catch (RuntimeException | Error failure) {
            promise.completeExceptionally(failure);
            values.remove(key, promise);
            throw failure;
        }
    }

    public long lookups() {
        return lookups.get();
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public long waits() {
        return waits.get();
    }

    public int size() {
        return values.size();
    }

    public void clear() {
        values.clear();
        lookups.set(0);
        hits.set(0);
        misses.set(0);
        waits.set(0);
    }
}
