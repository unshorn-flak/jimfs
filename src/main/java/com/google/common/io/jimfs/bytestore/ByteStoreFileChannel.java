/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.io.jimfs.bytestore;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkPositionIndexes;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.OpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * A {@link FileChannel} implementation that reads and writes to a {@link ByteStore} object. The
 * read and write methods and other methods that read or change the position of the channel are
 * synchronized because the {@link ReadableByteChannel} and {@link WritableByteChannel} interfaces
 * specify that the read and write methods block when another thread is currently doing a read or
 * write operation.
 *
 * @author Colin Decker
 */
final class ByteStoreFileChannel extends FileChannel {

  private volatile ByteStore store;

  private final boolean readable;
  private final boolean writable;
  private final boolean append;

  private int position;

  public ByteStoreFileChannel(ByteStore store, Set<? extends OpenOption> options) {
    this.store = checkNotNull(store);
    this.readable = options.contains(READ);
    this.writable = options.contains(WRITE);
    this.append = options.contains(APPEND);
  }

  void checkReadable() {
    if (!readable) {
      throw new NonReadableChannelException();
    }
  }

  void checkWritable() {
    if (!writable) {
      throw new NonWritableChannelException();
    }
  }

  void checkOpen() throws ClosedChannelException {
    if (store == null) {
      throw new ClosedChannelException();
    }
  }

  @Override
  public synchronized int read(ByteBuffer dst) throws IOException {
    checkOpen();
    checkReadable();

    int read = store.read(position, dst);
    if (read != -1) {
      position += read;
    }
    return read;
  }

  @Override
  public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
    checkPositionIndexes(offset, offset + length, dsts.length);
    return read(Arrays.asList(dsts).subList(offset, offset + length));
  }

  private synchronized int read(List<ByteBuffer> buffers) throws IOException {
    checkOpen();
    checkReadable();

    int read = store.read(position, buffers);
    if (read != -1) {
      position += read;
    }
    return read;
  }

  @Override
  public synchronized int write(ByteBuffer src) throws IOException {
    checkOpen();
    checkWritable();

    int written;
    if (append) {
      written = store.append(src);
      position = store.size();
    } else {
      written = store.write(position, src);
      position += written;
    }

    return written;
  }

  @Override
  public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
    checkPositionIndexes(offset, offset + length, srcs.length);
    return write(Arrays.asList(srcs).subList(offset, offset + length));
  }

  private synchronized int write(List<ByteBuffer> srcs) throws IOException {
    checkOpen();
    checkWritable();

    int written;
    if (append) {
      written = store.append(srcs);
      position = store.size();
    } else {
      written = store.write(position, srcs);
      position += written;
    }

    return written;
  }

  @Override
  public synchronized long position() throws IOException {
    checkOpen();
    return position;
  }

  @Override
  public synchronized FileChannel position(long newPosition) throws IOException {
    checkNotNegative(newPosition, "newPosition");
    checkOpen();
    this.position = (int) newPosition;
    return this;
  }

  @Override
  public synchronized long size() throws IOException {
    checkOpen();
    return store.size();
  }

  @Override
  public synchronized FileChannel truncate(long size) throws IOException {
    checkNotNegative(size, "size");
    checkOpen();
    checkWritable();

    store.truncate((int) size);
    if (position > size) {
      position = (int) size;
    }

    return this;
  }

  @Override
  public synchronized void force(boolean metaData) throws IOException {
    checkOpen();
    // do nothing... writes are all synchronous anyway
  }

  @Override
  public synchronized long transferTo(long position, long count,
      WritableByteChannel target) throws IOException {
    checkNotNull(target);
    checkNotNegative(position, "position");
    checkNotNegative(count, "count");
    checkOpen();
    checkReadable();

    return store.transferTo((int) position, (int) count, target);
  }

  @Override
  public synchronized long transferFrom(ReadableByteChannel src,
      long position, long count) throws IOException {
    checkNotNull(src);
    checkNotNegative(position, "position");
    checkNotNegative(count, "count");
    checkOpen();
    checkWritable();

    if (append) {
      long appended = store.appendFrom(src, (int) count);
      this.position = store.size();
      return appended;
    } else {
      return store.transferFrom(src, (int) position, (int) count);
    }
  }

  @Override
  public synchronized int read(ByteBuffer dst, long position) throws IOException {
    checkNotNull(dst);
    checkNotNegative(position, "position");
    checkOpen();
    checkReadable();

    return store.read((int) position, dst);
  }

  @Override
  public synchronized int write(ByteBuffer src, long position) throws IOException {
    checkNotNull(src);
    checkNotNegative(position, "position");
    checkOpen();
    checkWritable();

    int written;
    if (append) {
      written = store.append(src);
      this.position = store.size();
    } else {
      written = store.write((int) position, src);
    }

    return written;
  }

  @Override
  public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
    // would like this to pretend to work, but can't create an implementation of MappedByteBuffer
    throw new UnsupportedOperationException();
  }

  // TODO(cgdecker): Throw UOE from these lock methods since we aren't really supporting it?

  @Override
  public synchronized FileLock lock(long position, long size, boolean shared) throws IOException {
    checkNotNegative(position, "position");
    checkNotNegative(size, "size");
    checkOpen();
    if (shared) {
      // shared is for a read lock
      checkReadable();
    } else {
      // non-shared is for a write lock
      checkWritable();
    }
    return new FakeFileLock(this, position, size, shared);
  }

  @Override
  public FileLock tryLock(long position, long size, boolean shared) throws IOException {
    // lock doesn't wait anyway
    return lock(position, size, shared);
  }

  @Override
  protected synchronized void implCloseChannel() throws IOException {
    // if the file has been deleted, allow it to be GCed even if a reference to this channel is
    // held after closing for some reason
    store = null;
  }

  /**
   * A file lock that does nothing, since only one JVM process has access to this file system.
   */
  static final class FakeFileLock extends FileLock {

    private boolean valid = true;

    public FakeFileLock(FileChannel channel, long position, long size, boolean shared) {
      super(channel, position, size, shared);
    }

    public FakeFileLock(AsynchronousFileChannel channel, long position, long size, boolean shared) {
      super(channel, position, size, shared);
    }

    @Override
    public boolean isValid() {
      return valid;
    }

    @Override
    public void release() throws IOException {
      valid = false;
    }
  }

  static void checkNotNegative(long n, String type) {
    checkArgument(n >= 0, "%s must not be negative: %s", type, n);
  }
}
