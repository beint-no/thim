package no.beint.thim.benchmark;

import java.io.OutputStream;

final class RecycledOutputStream extends OutputStream {
    private byte[] buffer = new byte[64 * 1024];
    private int size;

    int size() {
        return size;
    }

    byte[] toByteArray() {
        var copy = new byte[size];
        System.arraycopy(buffer, 0, copy, 0, size);
        return copy;
    }

    void reset() {
        size = 0;
    }

    @Override
    public void write(int value) {
        ensure(1);
        buffer[size++] = (byte) value;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
        ensure(length);
        System.arraycopy(bytes, offset, buffer, size, length);
        size += length;
    }

    private void ensure(int extra) {
        if (size + extra <= buffer.length) {
            return;
        }
        var next = buffer.length;
        while (next < size + extra) {
            next *= 2;
        }
        var grown = new byte[next];
        System.arraycopy(buffer, 0, grown, 0, size);
        buffer = grown;
    }
}
