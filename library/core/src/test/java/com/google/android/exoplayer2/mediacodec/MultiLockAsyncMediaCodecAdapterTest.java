/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.exoplayer2.mediacodec;

import static com.google.android.exoplayer2.mediacodec.MediaCodecTestUtils.areEqual;
import static com.google.android.exoplayer2.mediacodec.MediaCodecTestUtils.waitUntilAllEventsAreExecuted;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.robolectric.Shadows.shadowOf;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowLooper;

/** Unit tests for {@link MultiLockAsyncMediaCodecAdapter}. */
@RunWith(AndroidJUnit4.class)
public class MultiLockAsyncMediaCodecAdapterTest {
  private MultiLockAsyncMediaCodecAdapter adapter;
  private MediaCodec codec;
  private MediaCodec.BufferInfo bufferInfo = null;
  private TestHandlerThread handlerThread;

  @Before
  public void setUp() throws IOException {
    codec = MediaCodec.createByCodecName("h264");
    handlerThread = new TestHandlerThread("TestHandlerThread");
    adapter = new MultiLockAsyncMediaCodecAdapter(codec, handlerThread);
    adapter.setCodecStartRunnable(() -> {});
    bufferInfo = new MediaCodec.BufferInfo();
  }

  @After
  public void tearDown() {
    adapter.shutdown();

    assertThat(TestHandlerThread.INSTANCES_STARTED.get()).isEqualTo(0);
  }

  @Test
  public void startAndShutdown_works() {
    adapter.start();
    adapter.shutdown();
  }

  @Test
  public void dequeueInputBufferIndex_withAfterFlushFailed_throwsException()
      throws InterruptedException {
    AtomicInteger codecStartCalls = new AtomicInteger(0);
    adapter.setCodecStartRunnable(
        () -> {
          if (codecStartCalls.incrementAndGet() == 2) {
            throw new IllegalStateException("codec#start() exception");
          }
        });
    adapter.start();
    adapter.flush();

    assertThat(
            waitUntilAllEventsAreExecuted(
                handlerThread.getLooper(), /* time= */ 5, TimeUnit.SECONDS))
        .isTrue();
    assertThrows(IllegalStateException.class, () -> adapter.dequeueInputBufferIndex());
  }

  @Test
  public void dequeueInputBufferIndex_withoutInputBuffer_returnsTryAgainLater() {
    adapter.start();

    assertThat(adapter.dequeueInputBufferIndex()).isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
  }

  @Test
  public void dequeueInputBufferIndex_withInputBuffer_returnsInputBuffer() {
    adapter.start();
    adapter.onInputBufferAvailable(codec, 0);

    assertThat(adapter.dequeueInputBufferIndex()).isEqualTo(0);
  }

  @Test
  public void dequeueInputBufferIndex_withPendingFlush_returnsTryAgainLater() {
    adapter.start();
    adapter.onInputBufferAvailable(codec, 0);
    adapter.flush();

    assertThat(adapter.dequeueInputBufferIndex()).isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
  }

  @Test
  public void dequeueInputBufferIndex_withFlushCompletedAndInputBuffer_returnsInputBuffer()
      throws InterruptedException {
    adapter.start();
    Looper looper = handlerThread.getLooper();
    Handler handler = new Handler(looper);
    // Enqueue 10 callbacks from codec
    for (int i = 0; i < 10; i++) {
      int bufferIndex = i;
      handler.post(() -> adapter.onInputBufferAvailable(codec, bufferIndex));
    }
    adapter.flush(); // Enqueues a flush event after the onInputBufferAvailable callbacks
    // Enqueue another onInputBufferAvailable after the flush event
    handler.post(() -> adapter.onInputBufferAvailable(codec, 10));

    // Wait until all tasks have been handled
    assertThat(waitUntilAllEventsAreExecuted(looper, /* time= */ 5, TimeUnit.SECONDS)).isTrue();
    assertThat(adapter.dequeueInputBufferIndex()).isEqualTo(10);
  }

  @Test
  public void dequeueInputBufferIndex_withMediaCodecError_throwsException() {
    adapter.start();
    adapter.onMediaCodecError(new IllegalStateException("error from codec"));

    assertThrows(IllegalStateException.class, () -> adapter.dequeueInputBufferIndex());
  }


  @Test
  public void dequeueOutputBufferIndex_withInternalException_throwsException()
      throws InterruptedException {
    AtomicInteger codecStartCalls = new AtomicInteger(0);
    adapter.setCodecStartRunnable(
        () -> {
          if (codecStartCalls.incrementAndGet() == 2) {
            throw new RuntimeException("codec#start() exception");
          }
        });
    adapter.start();
    adapter.flush();

    assertThat(
            waitUntilAllEventsAreExecuted(
                handlerThread.getLooper(), /* time= */ 5, TimeUnit.SECONDS))
        .isTrue();
    assertThrows(IllegalStateException.class, () -> adapter.dequeueOutputBufferIndex(bufferInfo));
  }

  @Test
  public void dequeueOutputBufferIndex_withoutInputBuffer_returnsTryAgainLater() {
    adapter.start();

    assertThat(adapter.dequeueOutputBufferIndex(bufferInfo))
        .isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
  }

  @Test
  public void dequeueOutputBufferIndex_withOutputBuffer_returnsOutputBuffer() {
    adapter.start();
    MediaCodec.BufferInfo enqueuedBufferInfo = new MediaCodec.BufferInfo();
    adapter.onOutputBufferAvailable(codec, 0, enqueuedBufferInfo);

    assertThat(adapter.dequeueOutputBufferIndex((bufferInfo))).isEqualTo(0);
    assertThat(areEqual(bufferInfo, enqueuedBufferInfo)).isTrue();
  }

  @Test
  public void dequeueOutputBufferIndex_withPendingFlush_returnsTryAgainLater() {
    adapter.start();
    adapter.dequeueOutputBufferIndex(bufferInfo);
    adapter.flush();

    assertThat(adapter.dequeueOutputBufferIndex(bufferInfo))
        .isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
  }

  @Test
  public void dequeueOutputBufferIndex_withFlushCompletedAndOutputBuffer_returnsOutputBuffer()
      throws InterruptedException {
    adapter.start();
    Looper looper = handlerThread.getLooper();
    Handler handler = new Handler(looper);
    // Enqueue 10 callbacks from codec
    for (int i = 0; i < 10; i++) {
      int bufferIndex = i;
      MediaCodec.BufferInfo outBufferInfo = new MediaCodec.BufferInfo();
      outBufferInfo.presentationTimeUs = i;
      handler.post(() -> adapter.onOutputBufferAvailable(codec, bufferIndex, outBufferInfo));
    }
    adapter.flush(); // Enqueues a flush event after the onOutputBufferAvailable callbacks
    // Enqueue another onOutputBufferAvailable after the flush event
    MediaCodec.BufferInfo lastBufferInfo = new MediaCodec.BufferInfo();
    lastBufferInfo.presentationTimeUs = 10;
    handler.post(() -> adapter.onOutputBufferAvailable(codec, 10, lastBufferInfo));

    // Wait until all tasks have been handled
    assertThat(waitUntilAllEventsAreExecuted(looper, /* time= */ 5, TimeUnit.SECONDS)).isTrue();
    assertThat(adapter.dequeueOutputBufferIndex(bufferInfo)).isEqualTo(10);
    assertThat(areEqual(bufferInfo, lastBufferInfo)).isTrue();
  }

  @Test
  public void dequeueOutputBufferIndex_withMediaCodecError_throwsException() {
    adapter.start();
    adapter.onMediaCodecError(new IllegalStateException("error from codec"));

    assertThrows(IllegalStateException.class, () -> adapter.dequeueOutputBufferIndex(bufferInfo));
  }

  @Test
  public void getOutputFormat_withoutFormatReceived_throwsException() {
    adapter.start();

    assertThrows(IllegalStateException.class, () -> adapter.getOutputFormat());
  }

  @Test
  public void getOutputFormat_withMultipleFormats_returnsCorrectFormat() {
    adapter.start();
    MediaFormat[] formats = new MediaFormat[10];
    for (int i = 0; i < formats.length; i++) {
      formats[i] = new MediaFormat();
      adapter.onOutputFormatChanged(codec, formats[i]);
    }

    for (int i = 0; i < 10; i++) {
      assertThat(adapter.dequeueOutputBufferIndex(bufferInfo))
          .isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
      assertThat(adapter.getOutputFormat()).isEqualTo(formats[i]);
      // A subsequent call to getOutputFormat() should return the previously fetched format
      assertThat(adapter.getOutputFormat()).isEqualTo(formats[i]);
    }
    assertThat(adapter.dequeueOutputBufferIndex(bufferInfo))
        .isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
  }

  @Test
  public void getOutputFormat_afterFlush_returnsPreviousFormat() throws InterruptedException {
    MediaFormat format = new MediaFormat();
    adapter.start();
    adapter.onOutputFormatChanged(codec, format);

    assertThat(adapter.dequeueOutputBufferIndex(bufferInfo))
        .isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    assertThat(adapter.getOutputFormat()).isEqualTo(format);

    adapter.flush();
    assertThat(
            waitUntilAllEventsAreExecuted(
                handlerThread.getLooper(), /* time= */ 5, TimeUnit.SECONDS))
        .isTrue();
    assertThat(adapter.getOutputFormat()).isEqualTo(format);
  }

  @Test
  public void flush_multipleTimes_onlyLastFlushExecutes() throws InterruptedException {
    AtomicInteger codecStartCalls = new AtomicInteger(0);
    adapter.setCodecStartRunnable(() -> codecStartCalls.incrementAndGet());
    adapter.start();
    Looper looper = handlerThread.getLooper();
    Handler handler = new Handler(looper);
    handler.post(() -> adapter.onInputBufferAvailable(codec, 0));
    adapter.flush(); // Enqueues a flush event
    handler.post(() -> adapter.onInputBufferAvailable(codec, 2));
    AtomicInteger milestoneCount = new AtomicInteger(0);
    handler.post(() -> milestoneCount.incrementAndGet());
    adapter.flush(); // Enqueues a second flush event
    handler.post(() -> adapter.onInputBufferAvailable(codec, 3));

    // Progress the looper until the milestoneCount is increased:
    // adapter.start() called codec.start() but first flush event should have been a no-op
    ShadowLooper shadowLooper = shadowOf(looper);
    while (milestoneCount.get() < 1) {
      shadowLooper.runOneTask();
    }
    assertThat(codecStartCalls.get()).isEqualTo(1);

    assertThat(waitUntilAllEventsAreExecuted(looper, /* time= */ 5, TimeUnit.SECONDS)).isTrue();
    assertThat(adapter.dequeueInputBufferIndex()).isEqualTo(3);
    assertThat(codecStartCalls.get()).isEqualTo(2);
  }

  @Test
  public void flush_andImmediatelyShutdown_flushIsNoOp() throws InterruptedException {
    AtomicInteger codecStartCalls = new AtomicInteger(0);
    adapter.setCodecStartRunnable(() -> codecStartCalls.incrementAndGet());
    adapter.start();
    // Obtain looper when adapter is started.
    Looper looper = handlerThread.getLooper();
    adapter.flush();
    adapter.shutdown();

    assertThat(waitUntilAllEventsAreExecuted(looper, 5, TimeUnit.SECONDS)).isTrue();
    // Only adapter.start() called codec#start()
    assertThat(codecStartCalls.get()).isEqualTo(1);
  }

  private static class TestHandlerThread extends HandlerThread {

    private static final AtomicLong INSTANCES_STARTED = new AtomicLong(0);

    public TestHandlerThread(String name) {
      super(name);
    }

    @Override
    public synchronized void start() {
      super.start();
      INSTANCES_STARTED.incrementAndGet();
    }

    @Override
    public boolean quit() {
      boolean quit = super.quit();
      INSTANCES_STARTED.decrementAndGet();
      return quit;
    }
  }
}