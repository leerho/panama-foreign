/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package org.openjdk.bench.jdk.incubator.foreign;

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.LibraryLookup;
import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.NativeScope;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.concurrent.TimeUnit;

import static jdk.incubator.foreign.CLinker.*;

@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@State(org.openjdk.jmh.annotations.Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, jvmArgsAppend = { "--add-modules=jdk.incubator.foreign", "-Dforeign.restricted=permit" })
public class StrLenTest {

    NativeScope scope = NativeScope.unboundedScope();

    @Param({"5", "20", "100"})
    public int size;
    public String str;

    static {
        System.loadLibrary("StrLen");
    }

    static final MethodHandle STRLEN;

    static {
        LibraryLookup lookup = LibraryLookup.ofDefault();
        CLinker abi = CLinker.getInstance();
        STRLEN = abi.downcallHandle(lookup.lookup("strlen").get(),
                MethodType.methodType(int.class, MemoryAddress.class),
                FunctionDescriptor.of(C_INT, C_POINTER));
    }

    @Setup
    public void setup() {
        str = makeString(size);
    }

    @TearDown
    public void tearDown() {
        scope.close();
    }

    @Benchmark
    public int jni_strlen() throws Throwable {
        return strlen(str);
    }

    @Benchmark
    public int panama_strlen() throws Throwable {
        try (MemorySegment segment = CLinker.toCString(str)) {
            return (int)STRLEN.invokeExact(segment.address());
        }
    }

    @Benchmark
    public int panama_strlen_scope() throws Throwable {
        return (int)STRLEN.invokeExact(CLinker.toCString(str, scope).address());
    }

    @Benchmark
    public int panama_strlen_unsafe() throws Throwable {
        MemoryAddress address = makeStringUnsafe(str);
        int res = (int) STRLEN.invokeExact(address);
        CLinker.freeMemoryRestricted(address);
        return res;
    }

    static MemoryAddress makeStringUnsafe(String s) {
        byte[] bytes = s.getBytes();
        int len = bytes.length;
        MemoryAddress address = CLinker.allocateMemoryRestricted(len + 1);
        MemorySegment str = address.asSegmentRestricted(len + 1);
        str.copyFrom(MemorySegment.ofArray(bytes));
        MemoryAccess.setByteAtOffset(str, len, (byte)0);
        return address;
    }

    static native int strlen(String str);

    static String makeString(int size) {
        String lorem = """
                Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et
                 dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip
                 ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu
                 fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt
                 mollit anim id est laborum.
                """;
        return lorem.substring(0, size);
    }
}
