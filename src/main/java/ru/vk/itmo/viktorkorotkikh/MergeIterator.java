package ru.vk.itmo.viktorkorotkikh;

import ru.vk.itmo.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

public final class MergeIterator implements Iterator<Entry<MemorySegment>> {
    private final PriorityQueue<LSMPointerIterator> lsmPointerIterators;

    public static MergeIteratorWithTombstoneFilter create(
            LSMPointerIterator memTableIterator,
            LSMPointerIterator flushingMemTableIterator,
            List<? extends LSMPointerIterator> ssTableIterators
    ) {
        return new MergeIteratorWithTombstoneFilter(
                new MergeIterator(memTableIterator, flushingMemTableIterator, ssTableIterators)
        );
    }

    public static MergeIteratorWithTombstoneFilter createThroughSSTables(
            List<? extends LSMPointerIterator> ssTableIterators
    ) {
        return new MergeIteratorWithTombstoneFilter(new MergeIterator(ssTableIterators));
    }

    private MergeIterator(
            LSMPointerIterator memTableIterator,
            LSMPointerIterator flushingMemTableIterator,
            List<? extends LSMPointerIterator> ssTableIterators
    ) {
        this.lsmPointerIterators = new PriorityQueue<>(
                ssTableIterators.size() + 2,
                LSMPointerIterator::compareByPointersWithPriority
        );
        if (memTableIterator.hasNext()) {
            lsmPointerIterators.add(memTableIterator);
        }
        if (flushingMemTableIterator.hasNext()) {
            lsmPointerIterators.add(flushingMemTableIterator);
        }
        for (LSMPointerIterator iterator : ssTableIterators) {
            if (iterator.hasNext()) {
                lsmPointerIterators.add(iterator);
            }
        }
    }

    private MergeIterator(List<? extends LSMPointerIterator> ssTableIterators) {
        this.lsmPointerIterators = new PriorityQueue<>(
                ssTableIterators.size(),
                LSMPointerIterator::compareByPointersWithPriority
        );
        for (LSMPointerIterator iterator : ssTableIterators) {
            if (iterator.hasNext()) {
                lsmPointerIterators.add(iterator);
            }
        }
    }

    private boolean isOnTombstone() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return lsmPointerIterators.peek().isPointerOnTombstone();
    }

    @Override
    public boolean hasNext() {
        return !lsmPointerIterators.isEmpty();
    }

    private LSMPointerIterator shiftIterators() {
        LSMPointerIterator lsmPointerIterator = lsmPointerIterators.remove();
        LSMPointerIterator nextIterator;
        while ((nextIterator = lsmPointerIterators.peek()) != null) {
            int keyComparison = lsmPointerIterator.compareByPointers(nextIterator);
            if (keyComparison != 0) {
                break;
            }
            lsmPointerIterators.remove();
            nextIterator.shift();
            if (nextIterator.hasNext()) {
                lsmPointerIterators.add(nextIterator);
            }
        }
        return lsmPointerIterator;
    }

    private void shift() {
        LSMPointerIterator lsmPointerIterator = shiftIterators();
        lsmPointerIterator.shift();
        if (lsmPointerIterator.hasNext()) {
            lsmPointerIterators.add(lsmPointerIterator);
        }
    }

    @Override
    public Entry<MemorySegment> next() {
        LSMPointerIterator lsmPointerIterator = shiftIterators();
        Entry<MemorySegment> next = lsmPointerIterator.next();
        if (lsmPointerIterator.hasNext()) {
            lsmPointerIterators.add(lsmPointerIterator);
        }
        return next;
    }

    public static final class MergeIteratorWithTombstoneFilter implements Iterator<Entry<MemorySegment>> {

        private final MergeIterator mergeIterator;
        private boolean haveNext;

        private MergeIteratorWithTombstoneFilter(MergeIterator mergeIterator) {
            this.mergeIterator = mergeIterator;
        }

        @Override
        public boolean hasNext() {
            if (haveNext) {
                return true;
            }

            while (mergeIterator.hasNext()) {
                if (!mergeIterator.isOnTombstone()) {
                    haveNext = true;
                    return true;
                }
                mergeIterator.shift();
            }

            return false;
        }

        @Override
        public Entry<MemorySegment> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Entry<MemorySegment> next = mergeIterator.next();
            haveNext = false;
            return next;
        }
    }
}
