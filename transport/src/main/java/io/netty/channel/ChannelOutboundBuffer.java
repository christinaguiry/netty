/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
/*
 * Written by Josh Bloch of Google Inc. and released to the public domain,
 * as explained at http://creativecommons.org/publicdomain/zero/1.0/.
 */
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * (Transport implementors only) an internal data structure used by {@link AbstractChannel} to store its pending
 * outbound write requests.
 */
public class ChannelOutboundBuffer {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelOutboundBuffer.class);

    protected static final int INITIAL_CAPACITY = 32;

    private static final Recycler<ChannelOutboundBuffer> RECYCLER = new Recycler<ChannelOutboundBuffer>() {
        @Override
        protected ChannelOutboundBuffer newObject(Handle<ChannelOutboundBuffer> handle) {
            return new ChannelOutboundBuffer(handle);
        }
    };

    static ChannelOutboundBuffer newInstance(AbstractChannel channel) {
        ChannelOutboundBuffer buffer = RECYCLER.get();
        buffer.channel = channel;
        return buffer;
    }

    private final Handle<? extends ChannelOutboundBuffer> handle;

    protected AbstractChannel channel;

    // A circular buffer used to store messages.  The buffer is arranged such that:  flushed <= unflushed <= tail.  The
    // flushed messages are stored in the range [flushed, unflushed).  Unflushed messages are stored in the range
    // [unflushed, tail).
    private Entry[] buffer;
    private int flushed;
    private int unflushed;
    private int tail;

    private boolean inFail;

    private static final AtomicLongFieldUpdater<ChannelOutboundBuffer> TOTAL_PENDING_SIZE_UPDATER;

    private volatile long totalPendingSize;

    private static final AtomicIntegerFieldUpdater<ChannelOutboundBuffer> WRITABLE_UPDATER;

    static {
        AtomicIntegerFieldUpdater<ChannelOutboundBuffer> writableUpdater =
                PlatformDependent.newAtomicIntegerFieldUpdater(ChannelOutboundBuffer.class, "writable");
        if (writableUpdater == null) {
            writableUpdater = AtomicIntegerFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "writable");
        }
        WRITABLE_UPDATER = writableUpdater;

        AtomicLongFieldUpdater<ChannelOutboundBuffer> pendingSizeUpdater =
                PlatformDependent.newAtomicLongFieldUpdater(ChannelOutboundBuffer.class, "totalPendingSize");
        if (pendingSizeUpdater == null) {
            pendingSizeUpdater = AtomicLongFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "totalPendingSize");
        }
        TOTAL_PENDING_SIZE_UPDATER = pendingSizeUpdater;
    }

    private volatile int writable = 1;

    protected ChannelOutboundBuffer(Handle<? extends ChannelOutboundBuffer> handle) {
        this.handle = handle;

        buffer = new Entry[INITIAL_CAPACITY];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = newEntry();
        }
    }

    protected final Entry[] entries() {
        return buffer;
    }

    public final void addMessage(Object msg, ChannelPromise promise) {
        msg = message(msg);
        int size = channel.estimatorHandle().size(msg);
        if (size < 0) {
            size = 0;
        }

        Entry e = buffer[tail++];
        e.msg = msg;
        e.pendingSize = size;
        e.promise = promise;
        e.total = total(msg);

        tail &= buffer.length - 1;

        if (tail == flushed) {
            addCapacity();
        }

        // increment pending bytes after adding message to the unflushed arrays.
        // See https://github.com/netty/netty/issues/1619
        incrementPendingOutboundBytes(size);
    }

    protected Object message(Object msg) {
        return msg;
    }

    private void addCapacity() {
        int p = flushed;
        int n = buffer.length;
        int r = n - p; // number of elements to the right of p
        int s = size();

        int newCapacity = n << 1;
        if (newCapacity < 0) {
            throw new IllegalStateException();
        }

        Entry[] e = new Entry[newCapacity];
        System.arraycopy(buffer, p, e, 0, r);
        System.arraycopy(buffer, 0, e, r, p);
        for (int i = n; i < e.length; i++) {
            e[i] = newEntry();
        }

        buffer = e;
        flushed = 0;
        unflushed = s;
        tail = n;
    }

   public final void addFlush() {
        unflushed = tail;

        final int mask = buffer.length - 1;
        int i = flushed;
        while (i != unflushed && buffer[i].msg != null) {
            Entry entry = buffer[i];
            if (!entry.promise.setUncancellable()) {
                // Was cancelled so make sure we free up memory and notify about the freed bytes
                int pending = entry.cancel();
                decrementPendingOutboundBytes(pending);
            }
            i = i + 1 & mask;
        }
    }

    /**
     * Increment the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    final void incrementPendingOutboundBytes(int size) {
        // Cache the channel and check for null to make sure we not produce a NPE in case of the Channel gets
        // recycled while process this method.
        Channel channel = this.channel;
        if (size == 0 || channel == null) {
            return;
        }

        long oldValue = totalPendingSize;
        long newWriteBufferSize = oldValue + size;
        while (!TOTAL_PENDING_SIZE_UPDATER.compareAndSet(this, oldValue, newWriteBufferSize)) {
            oldValue = totalPendingSize;
            newWriteBufferSize = oldValue + size;
        }

        int highWaterMark = channel.config().getWriteBufferHighWaterMark();

        if (newWriteBufferSize > highWaterMark) {
            if (WRITABLE_UPDATER.compareAndSet(this, 1, 0)) {
                channel.pipeline().fireChannelWritabilityChanged();
            }
        }
    }

    /**
     * Decrement the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
   final void decrementPendingOutboundBytes(int size) {
        // Cache the channel and check for null to make sure we not produce a NPE in case of the Channel gets
        // recycled while process this method.
        Channel channel = this.channel;
        if (size == 0 || channel == null) {
            return;
        }

        long oldValue = totalPendingSize;
        long newWriteBufferSize = oldValue - size;
        while (!TOTAL_PENDING_SIZE_UPDATER.compareAndSet(this, oldValue, newWriteBufferSize)) {
            oldValue = totalPendingSize;
            newWriteBufferSize = oldValue - size;
        }

        int lowWaterMark = channel.config().getWriteBufferLowWaterMark();

        if (newWriteBufferSize == 0 || newWriteBufferSize < lowWaterMark) {
            if (WRITABLE_UPDATER.compareAndSet(this, 0, 1)) {
                channel.pipeline().fireChannelWritabilityChanged();
            }
        }
    }

    private static long total(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        return -1;
    }

   public final Object current() {
        if (isEmpty()) {
            return null;
        } else {
            // TODO: Think of a smart way to handle ByteBufHolder messages
            Entry entry = buffer[flushed];
            return entry.msg;
        }
    }

    public final void progress(long amount) {
        Entry e = buffer[flushed];
        ChannelPromise p = e.promise;
        if (p instanceof ChannelProgressivePromise) {
            long progress = e.progress + amount;
            e.progress = progress;
            ((ChannelProgressivePromise) p).tryProgress(progress, e.total);
        }
    }

    public final boolean remove() {
        if (isEmpty()) {
            return false;
        }

        Entry e = buffer[flushed];
        Object msg = e.msg;
        if (msg == null) {
            return false;
        }

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        e.clear();

        flushed = flushed + 1 & buffer.length - 1;

        if (!e.cancelled) {
            // only release message, notify and decrement if it was not canceled before.
            safeRelease(msg);
            safeSuccess(promise);
            decrementPendingOutboundBytes(size);
        }

        return true;
    }

    public final boolean remove(Throwable cause) {
        if (isEmpty()) {
            return false;
        }

        Entry e = buffer[flushed];
        Object msg = e.msg;
        if (msg == null) {
            return false;
        }

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        e.clear();

        flushed = flushed + 1 & buffer.length - 1;

        if (!e.cancelled) {
            // only release message, fail and decrement if it was not canceled before.
            safeRelease(msg);

            safeFail(promise, cause);
            decrementPendingOutboundBytes(size);
        }

        return true;
    }

    final boolean getWritable() {
        return writable != 0;
    }

    public final int size() {
        return unflushed - flushed & buffer.length - 1;
    }

    public final boolean isEmpty() {
        return unflushed == flushed;
    }

    final void failFlushed(Throwable cause) {
        // Make sure that this method does not reenter.  A listener added to the current promise can be notified by the
        // current thread in the tryFailure() call of the loop below, and the listener can trigger another fail() call
        // indirectly (usually by closing the channel.)
        //
        // See https://github.com/netty/netty/issues/1501
        if (inFail) {
            return;
        }

        try {
            inFail = true;
            for (;;) {
                if (!remove(cause)) {
                    break;
                }
            }
        } finally {
            inFail = false;
        }
    }

   final void close(final ClosedChannelException cause) {
        if (inFail) {
            channel.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    close(cause);
                }
            });
            return;
        }

        inFail = true;

        if (channel.isOpen()) {
            throw new IllegalStateException("close() must be invoked after the channel is closed.");
        }

        if (!isEmpty()) {
            throw new IllegalStateException("close() must be invoked after all flushed writes are handled.");
        }

        // Release all unflushed messages.
        final int unflushedCount = tail - unflushed & buffer.length - 1;
        try {
            for (int i = 0; i < unflushedCount; i++) {
                Entry e = buffer[unflushed + i & buffer.length - 1];

                // Just decrease; do not trigger any events via decrementPendingOutboundBytes()
                int size = e.pendingSize;
                long oldValue = totalPendingSize;
                long newWriteBufferSize = oldValue - size;
                while (!TOTAL_PENDING_SIZE_UPDATER.compareAndSet(this, oldValue, newWriteBufferSize)) {
                    oldValue = totalPendingSize;
                    newWriteBufferSize = oldValue - size;
                }

                e.pendingSize = 0;
                if (!e.cancelled) {
                    safeRelease(e.msg);
                    safeFail(e.promise, cause);
                }
                e.msg = null;
                e.promise = null;
            }
        } finally {
            tail = unflushed;
            inFail = false;
        }

        recycle();
    }

    protected static void safeRelease(Object message) {
        try {
            ReferenceCountUtil.release(message);
        } catch (Throwable t) {
            logger.warn("Failed to release a message.", t);
        }
    }

    private static void safeSuccess(ChannelPromise promise) {
        if (!(promise instanceof VoidChannelPromise) && !promise.trySuccess()) {
            logger.warn("Failed to mark a promise as success because it is done already: {}", promise);
        }
    }

    private static void safeFail(ChannelPromise promise, Throwable cause) {
        if (!(promise instanceof VoidChannelPromise) && !promise.tryFailure(cause)) {
            logger.warn("Failed to mark a promise as failure because it's done already: {}", promise, cause);
        }
    }

    public void recycle() {
        if (buffer.length > INITIAL_CAPACITY) {
            Entry[] e = new Entry[INITIAL_CAPACITY];
            System.arraycopy(buffer, 0, e, 0, INITIAL_CAPACITY);
            buffer = e;
        }

        // reset flushed, unflushed and tail
        // See https://github.com/netty/netty/issues/1772
        flushed = 0;
        unflushed = 0;
        tail = 0;

        // Set the channel to null so it can be GC'ed ASAP
        channel = null;

        totalPendingSize = 0;
        writable = 1;

        RECYCLER.recycle(this, (Handle<ChannelOutboundBuffer>) handle);
    }

    public final long totalPendingWriteBytes() {
        return totalPendingSize;
    }

    protected Entry newEntry() {
        return new Entry();
    }

    protected final int flushed() {
        return flushed;
    }

    protected final int unflushed() {
        return unflushed;
    }

    protected static class Entry {
        Object msg;
        ChannelPromise promise;
        long progress;
        long total;
        int pendingSize;
        int count = -1;
        boolean cancelled;

        public Object msg() {
            return msg;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public int cancel() {
            if (!cancelled) {
                cancelled = true;
                int pSize = pendingSize;

                // release message and replace with an empty buffer
                safeRelease(msg);
                msg = Unpooled.EMPTY_BUFFER;

                pendingSize = 0;
                total = 0;
                progress = 0;
                return pSize;
            }
            return 0;
        }

        public void clear() {
            msg = null;
            promise = null;
            progress = 0;
            total = 0;
            pendingSize = 0;
            count = -1;
            cancelled = false;
        }
    }
}
