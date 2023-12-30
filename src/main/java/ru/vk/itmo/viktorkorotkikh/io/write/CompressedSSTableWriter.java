package ru.vk.itmo.viktorkorotkikh.io.write;

import ru.vk.itmo.Entry;
import ru.vk.itmo.viktorkorotkikh.compressor.Compressor;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * <B>compression info</B>:
 * isCompressed|algorithm|blocksCount|uncompressedBlockSize|block1Offset|block2Offset|blockNOffset
 * <p/>
 * <B>index</B>:
 * hasNoTombstones|entriesSize|key1BlockNumber|key1SizeBlockOffset|key2BlockNumber|key2SizeBlockOffset|keyNBlockNumber|keyNSizeBlockOffset|
 * <p/>
 * keyNBlockNumber - номер блока для начала ключа номер N (key1Size|key1|value1Size|value1)
 * <br/>
 * keyNSizeBlockOffset - смещение начала размера ключа внутри блока
 * <p/>
 * <B>blocks</B>:
 * block1|block2|...|blockN
 */
public final class CompressedSSTableWriter extends AbstractSSTableWriter {

    private final Compressor compressor;
    private int blobBufferOffset = 0;
    private int blockCount = 0;

    private int blockOffset = 0;

    public CompressedSSTableWriter(Compressor compressor, int blockSize) {
        super(blockSize);
        this.compressor = compressor;
    }

    @Override
    protected void writeIndexInfo(
            MemorySegment mappedIndexFile,
            MemorySegment mappedCompressionInfoFile,
            int entriesSize,
            boolean hasNoTombstones
    ) {
        // hasNoTombstones|entriesSize
        mappedIndexFile.set(ValueLayout.JAVA_BOOLEAN, 0, hasNoTombstones);
        mappedIndexFile.set(ValueLayout.JAVA_LONG_UNALIGNED, 1, entriesSize);

        // blocksCount|uncompressedBlockSize
        mappedCompressionInfoFile.set(ValueLayout.JAVA_INT_UNALIGNED, 0, blockCount);
        mappedCompressionInfoFile.set(ValueLayout.JAVA_INT_UNALIGNED, Integer.BYTES, blockSize);
    }

    @Override
    protected void writeEntry(
            final Entry<MemorySegment> entry,
            final OutputStream os,
            final OutputStream compressionInfoStream,
            final OutputStream indexStream
    ) throws IOException {
        // write index
        writeInt(indexStream, blockCount); // keyNBlockNumber
        writeInt(indexStream, blobBufferOffset);  // keyNSizeBlockOffset

        final MemorySegment key = entry.key();
        final long keySize = key.byteSize();
        // write keyNSize
        int writtenBytes = writeLong(keySize, 0);

        while (writtenBytes < Long.BYTES) { // continue writing keySize
            flush(os, compressionInfoStream);
            writtenBytes += writeLong(keySize, writtenBytes);
        }

        // write key
        writeMemorySegment(key, os, compressionInfoStream);

        final MemorySegment value = entry.value();
        final long valueSize;
        if (value == null) {
            valueSize = -1;
        } else {
            valueSize = value.byteSize();
        }

        // write value size
        writtenBytes = writeLong(valueSize, 0);
        while (writtenBytes < Long.BYTES) { // continue writing keySize
            flush(os, compressionInfoStream);
            writtenBytes += writeLong(valueSize, writtenBytes);
        }

        if (value != null) {
            // write value
            writeMemorySegment(value, os, compressionInfoStream);
        }
        flush(os, compressionInfoStream);
    }

    private static void writeInt(OutputStream outputStream, int value) throws IOException {
        outputStream.write((byte) (value));
        outputStream.write((byte) (value >> 8));
        outputStream.write((byte) (value >> 16));
        outputStream.write((byte) (value >> 24));
    }

    /**
     * Flush blobBuffer to outputStream if blobBufferOffset >= blockSize.
     *
     * @param os outputStream for writing
     * @throws IOException if an I/O error occurs.
     */
    private void flush(OutputStream os, OutputStream compressionInfoStream) throws IOException {
        if (blobBufferOffset >= blockSize) {
            byte[] compressed = compressor.compress(blobBuffer.getArray());
            os.write(compressed);
            blobBufferOffset = 0;
            blockCount++;
            writeInt(compressionInfoStream, blockOffset); // blockNOffset
            blockOffset += compressed.length;
        }
    }

    @Override
    protected void finish(OutputStream os, OutputStream compressionInfoStream) throws IOException {
        byte[] compressed = compressor.compress(blobBuffer.getArray(), blobBufferOffset);
        os.write(compressed);
        writeInt(compressionInfoStream, blockOffset);
        writeInt(compressionInfoStream, blobBufferOffset); // size of last uncompressed data
        blobBufferOffset = 0;
        blockCount++;
        blockOffset += compressed.length;
    }

    private int writeLong(final long value, final int writtenBytes) throws IOException {
        longBuffer.segment().set(ValueLayout.JAVA_LONG_UNALIGNED, 0, value);
        byte[] longBytes = longBuffer.getArray();
        int longBytesIndex = writtenBytes;
        int i = blobBufferOffset;
        while (i < blockSize && longBytesIndex < longBytes.length) { // write part
            int index = i;
            byte keySizeByte = longBytes[longBytesIndex];
            blobBuffer.withArray(array -> array[index] = keySizeByte);
            i++;
            longBytesIndex++;
        }
        blobBufferOffset += longBytesIndex - writtenBytes;
        return longBytesIndex - writtenBytes;
    }

    /**
     * Write memorySegment to blobBuffer and flush it if necessary to os
     *
     * @param memorySegment memorySegment to write
     * @param os            outputStream to flush
     * @throws IOException if an I/O error occurs.
     */
    private void writeMemorySegment(
            final MemorySegment memorySegment,
            final OutputStream os,
            final OutputStream compressionInfoStream
    ) throws IOException {
        // write memory segment
        final long memorySegmentSize = memorySegment.byteSize();
        long writtenMemorySegmentBytes = 0L;
        while (writtenMemorySegmentBytes < memorySegmentSize) {
            long bytes;
            // calc bytes size to write
            int localBufferOffset = blobBufferOffset;
            if (blobBufferOffset + memorySegmentSize <= blockSize) {
                bytes = memorySegmentSize - writtenMemorySegmentBytes;
                blobBufferOffset += (int) bytes;
            } else {
                bytes = blockSize - blobBufferOffset;
                blobBufferOffset = blockSize;
            }

            MemorySegment.copy(
                    memorySegment,
                    writtenMemorySegmentBytes,
                    blobBuffer.segment(),
                    localBufferOffset,
                    bytes
            );

            writtenMemorySegmentBytes += bytes;

            flush(os, compressionInfoStream);
        }
    }

    @Override
    protected void writeCompressionHeader(OutputStream os) throws IOException {
        os.write(1); // isCompressed == true
        os.write(0); // algorithm: 0 - LZ4
    }
}