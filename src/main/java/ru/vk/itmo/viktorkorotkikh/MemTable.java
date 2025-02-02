package ru.vk.itmo.viktorkorotkikh;

import ru.vk.itmo.Entry;
import ru.vk.itmo.viktorkorotkikh.exceptions.LSMDaoOutOfMemoryException;

import java.lang.foreign.MemorySegment;
import java.util.Collection;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

public class MemTable {
    private final NavigableMap<MemorySegment, Entry<MemorySegment>> storage;

    private final long flushThresholdBytes;

    private final AtomicLong memTableByteSize = new AtomicLong();

    public MemTable(long flushThresholdBytes) {
        this.flushThresholdBytes = flushThresholdBytes;
        this.storage = createNewMemTable();
    }

    private static NavigableMap<MemorySegment, Entry<MemorySegment>> createNewMemTable() {
        return new ConcurrentSkipListMap<>(MemorySegmentComparator.INSTANCE);
    }

    private Iterator<Entry<MemorySegment>> storageIterator(MemorySegment from, MemorySegment to) {
        if (from == null && to == null) {
            return storage.sequencedValues().iterator();
        }

        if (from == null) {
            return storage.headMap(to).sequencedValues().iterator();
        }

        if (to == null) {
            return storage.tailMap(from).sequencedValues().iterator();
        }

        return storage.subMap(from, to).sequencedValues().iterator();
    }

    public MemTableIterator iterator(MemorySegment from, MemorySegment to, int priorityReduction) {
        return new MemTableIterator(storageIterator(from, to), priorityReduction);
    }

    public Entry<MemorySegment> get(MemorySegment key) {
        return storage.get(key);
    }

    public Collection<Entry<MemorySegment>> values() {
        return storage.values();
    }

    public boolean upsert(Entry<MemorySegment> entry) {
        long newEntrySize = Utils.getEntrySize(entry);
        if (memTableByteSize.addAndGet(newEntrySize) - newEntrySize >= flushThresholdBytes) {
            memTableByteSize.addAndGet(-newEntrySize);
            throw new LSMDaoOutOfMemoryException();
        }
        Entry<MemorySegment> previous = storage.put(entry.key(), entry);
        if (previous != null) {
            // entry already was in memTable, so we need to subtract size of previous entry
            memTableByteSize.addAndGet(-Utils.getEntrySize(previous));
        }
        return memTableByteSize.get() >= flushThresholdBytes;
    }

    public boolean isEmpty() {
        return memTableByteSize.get() == 0L;
    }

    public long getByteSize() {
        return memTableByteSize.get();
    }

    public static final class MemTableIterator extends LSMPointerIterator {
        private final Iterator<Entry<MemorySegment>> iterator;
        private Entry<MemorySegment> current;

        private final int priority;

        private MemTableIterator(Iterator<Entry<MemorySegment>> storageIterator, int priorityReduction) {
            this.iterator = storageIterator;
            if (iterator.hasNext()) {
                current = iterator.next();
            }
            this.priority = Integer.MAX_VALUE - priorityReduction;
        }

        @Override
        public int getPriority() {
            return priority;
        }

        @Override
        protected MemorySegment getPointerKeySrc() {
            return current.key();
        }

        @Override
        protected long getPointerKeySrcOffset() {
            return 0;
        }

        @Override
        public boolean isPointerOnTombstone() {
            return current.value() == null;
        }

        @Override
        public void shift() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            current = iterator.hasNext() ? iterator.next() : null;
        }

        @Override
        public long getPointerSize() {
            return Utils.getEntrySize(current);
        }

        @Override
        protected long getPointerKeySrcSize() {
            return current.key().byteSize();
        }

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public Entry<MemorySegment> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Entry<MemorySegment> entry = current;
            current = iterator.hasNext() ? iterator.next() : null;
            return entry;
        }
    }
}
