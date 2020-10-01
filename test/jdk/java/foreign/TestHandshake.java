/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @modules jdk.incubator.foreign java.base/jdk.internal.vm.annotation java.base/jdk.internal.misc
 * @key randomness
 * @run testng/othervm TestHandshake
 * @run testng/othervm -Xint TestHandshake
 * @run testng/othervm -XX:TieredStopAtLevel=1 TestHandshake
 * @run testng/othervm -XX:-TieredCompilation TestHandshake
 */

import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemorySegment;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class TestHandshake {

    static final int ITERATIONS = 10;
    static final int SEGMENT_SIZE = 1_000_000;
    static final int MAX_DELAY_MILLIS = 500;
    static final int MAX_EXECUTOR_WAIT_SECONDS = 10;

    @Test(dataProvider = "accessors")
    public void testHandshake(Function<MemorySegment, Runnable> accessorFactory) throws InterruptedException {
        for (int it = 0 ; it < ITERATIONS ; it++) {
            MemorySegment segment = MemorySegment.allocateNative(SEGMENT_SIZE).share();
            System.err.println("ITERATION " + it);
            ExecutorService accessExecutor = Executors.newCachedThreadPool();
            for (int i = 0; i < Runtime.getRuntime().availableProcessors() ; i++) {
                accessExecutor.execute(accessorFactory.apply(segment));
            }
            Thread.sleep(ThreadLocalRandom.current().nextInt(MAX_DELAY_MILLIS));
            accessExecutor.execute(new Handshaker(segment));
            accessExecutor.shutdown();
            assertTrue(accessExecutor.awaitTermination(MAX_EXECUTOR_WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(!segment.isAlive());
        }
    }

    static class SegmentAccessor implements Runnable {

        final MemorySegment segment;

        SegmentAccessor(MemorySegment segment) {
            this.segment = segment;
        }

        @Override
        public void run() {
            try {
                while (true) {
                    int sum = 0;
                    for (int i = 0; i < segment.byteSize(); i++) {
                        sum += MemoryAccess.getByteAtIndex(segment, i);
                    }
                }
            } catch (IllegalStateException ex) {
                // do nothing
            }
        }
    }

    static class SegmentCopyAccessor implements Runnable {

        final MemorySegment segment;

        SegmentCopyAccessor(MemorySegment segment) {
            this.segment = segment;
        }

        @Override
        public void run() {
            try {
                long split = segment.byteSize() / 2;
                MemorySegment first = segment.asSlice(0, split);
                MemorySegment second = segment.asSlice(split);
                while (true) {
                    for (int i = 0; i < segment.byteSize(); i++) {
                        first.copyFrom(second);
                    }
                }
            } catch (IllegalStateException ex) {
                // do nothing
            }
        }
    }

    static class SegmentFillAccessor implements Runnable {

        final MemorySegment segment;

        SegmentFillAccessor(MemorySegment segment) {
            this.segment = segment;
        }

        @Override
        public void run() {
            try {
                segment.fill((byte)ThreadLocalRandom.current().nextInt(10));
            } catch (IllegalStateException ex) {
                // do nothing
            }
        }
    }

    static class SegmentMismatchAccessor implements Runnable {

        final MemorySegment segment;
        final MemorySegment copy;

        SegmentMismatchAccessor(MemorySegment segment) {
            this.segment = segment;
            this.copy = MemorySegment.allocateNative(SEGMENT_SIZE).share();
            copy.copyFrom(segment);
            MemoryAccess.setByteAtIndex(copy, ThreadLocalRandom.current().nextInt(SEGMENT_SIZE), (byte)42);
        }

        @Override
        public void run() {
            try {
                long l = 0;
                while (true) {
                    l += segment.mismatch(copy);
                }
            } catch (IllegalStateException ex) {
                // do nothing
            }
        }
    }

    static class BufferAccessor implements Runnable {

        final ByteBuffer bb;

        BufferAccessor(MemorySegment segment) {
            this.bb = segment.asByteBuffer();
        }

        @Override
        public void run() {
            try {
                while (true) {
                    int sum = 0;
                    for (int i = 0; i < bb.capacity(); i++) {
                        sum += bb.get(i);
                    }
                }
            } catch (IllegalStateException ex) {
                // do nothing
            }
        }
    }

    static class BufferHandleAccessor implements Runnable {

        static VarHandle handle = MethodHandles.byteBufferViewVarHandle(short[].class, ByteOrder.nativeOrder());

        final ByteBuffer bb;

        public BufferHandleAccessor(MemorySegment segment) {
            this.bb = segment.asByteBuffer();
        }

        @Override
        public void run() {
            try {
                while (true) {
                    int sum = 0;
                    for (int i = 0; i < bb.capacity() / 2; i++) {
                        sum += (short)handle.get(bb, i);
                    }
                }
            } catch (IllegalStateException ex) {
                // do nothing
            }
        }
    };

    static class Handshaker implements Runnable {

        final MemorySegment segment;

        Handshaker(MemorySegment segment) {
            this.segment = segment;
        }

        @Override
        public void run() {
            long prev = System.currentTimeMillis();
            segment.close();
            long delay = System.currentTimeMillis() - prev;
            System.out.println("Segment closed - delay (ms): " + delay);
        }
    }

    @DataProvider
    static Object[][] accessors() {
        return new Object[][] {
                { (Function<MemorySegment, Runnable>)SegmentAccessor::new },
                { (Function<MemorySegment, Runnable>)SegmentCopyAccessor::new },
                { (Function<MemorySegment, Runnable>)SegmentMismatchAccessor::new },
                { (Function<MemorySegment, Runnable>)SegmentFillAccessor::new },
                { (Function<MemorySegment, Runnable>)BufferAccessor::new },
                { (Function<MemorySegment, Runnable>)BufferHandleAccessor::new }
        };
    }
}
