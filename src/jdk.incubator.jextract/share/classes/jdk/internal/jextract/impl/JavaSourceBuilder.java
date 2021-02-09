/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package jdk.internal.jextract.impl;

import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.GroupLayout;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.jextract.Type;

import javax.tools.JavaFileObject;
import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.invoke.MethodType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Superclass for .java source generator classes.
 */
abstract class JavaSourceBuilder {

    enum Kind {
        CLASS("class"),
        INTERFACE("interface");

        final String kindName;

        Kind(String kindName) {
            this.kindName = kindName;
        }
    }

    static final String PUB_CLS_MODS = "public final ";
    static final String PUB_MODS = "public static ";
    protected final StringSourceBuilder builder;
    final Kind kind;
    protected final String className;
    protected final String pkgName;

    Set<String> nestedClassNames = new HashSet<>();
    int nestedClassNameCount = 0;

    JavaSourceBuilder(StringSourceBuilder builder, Kind kind, String className, String pkgName) {
        this.builder = builder;
        this.kind = kind;
        this.className = className;
        this.pkgName = pkgName;
    }

    String superClass() {
        return null;
    }

    protected String getClassModifiers() {
        return PUB_CLS_MODS;
    }

    void classBegin() {
        addPackagePrefix();
        addImportSection();

        builder.indent();
        builder.append(getClassModifiers());
        builder.append(kind.kindName + " " + className);
        if (superClass() != null) {
            builder.append(" extends ");
            builder.append(superClass());
        }
        builder.append(" {\n\n");
        if (kind != Kind.INTERFACE) {
            emitConstructor();
        }
    }

    void emitConstructor() {
        builder.incrAlign();
        builder.indent();
        builder.append("/* package-private */ ");
        builder.append(className);
        builder.append("() {}");
        builder.append('\n');
        builder.decrAlign();
    }

    JavaSourceBuilder classEnd() {
        builder.indent();
        builder.append("}\n\n");
        return this;
    }

    public void addVar(String javaName, String nativeName, MemoryLayout layout, Class<?> type) {
        throw new UnsupportedOperationException();
    }

    public void addFunction(String javaName, String nativeName, MethodType mtype, FunctionDescriptor desc, boolean varargs, List<String> paramNames) {
        throw new UnsupportedOperationException();
    }

    public void addConstant(String javaName, Class<?> type, Object value) {
        throw new UnsupportedOperationException();
    }

    public void addTypedef(String name, String superClass, Type type) {
        throw new UnsupportedOperationException();
    }

    public StructBuilder addStruct(String name, GroupLayout parentLayout, Type type) {
        throw new UnsupportedOperationException();
    }

    public void addFunctionalInterface(String name, MethodType mtype, FunctionDescriptor desc, Type type) {
        throw new UnsupportedOperationException();
    }

    protected void addPackagePrefix() {
        assert pkgName.indexOf('/') == -1 : "package name invalid: " + pkgName;
        builder.append("// Generated by jextract\n\n");
        if (!pkgName.isEmpty()) {
            builder.append("package ");
            builder.append(pkgName);
            builder.append(";\n\n");
        }
    }

    protected void addImportSection() {
        builder.append("import java.lang.invoke.MethodHandle;\n");
        builder.append("import java.lang.invoke.VarHandle;\n");
        builder.append("import java.util.Objects;\n");
        builder.append("import jdk.incubator.foreign.*;\n");
        builder.append("import jdk.incubator.foreign.MemoryLayout.PathElement;\n");
        builder.append("import static ");
        builder.append(OutputFactory.C_LANG_CONSTANTS_HOLDER);
        builder.append(".*;\n");
    }

    /*
     * We may have case-insensitive name collision! A C program may have
     * defined structs/unions/typedefs with the names FooS, fooS, FoOs, fOOs.
     * Because we map structs/unions/typedefs to nested classes of header classes,
     * such a case-insensitive name collision is problematic. This is because in
     * a case-insensitive file system javac will overwrite classes for
     * Header$CFooS, Header$CfooS, Header$CFoOs and so on! We solve this by
     * generating unique case-insensitive names for nested classes.
     */
    final String uniqueNestedClassName(String name) {
        name = Utils.javaSafeIdentifier(name);
        return nestedClassNames.add(name.toLowerCase()) ? name : (name + "$" + nestedClassNameCount++);
    }

    final String getCallString(DirectMethodHandleDesc desc) {
        return desc.owner().displayName() + "." + desc.methodName() + "()";
    }

    final String displayName(ClassDesc returnType) {
        return returnType.displayName(); // TODO shorten based on imports
    }



    public List<JavaFileObject> build() {
        return build(Function.identity());
    }

    public List<JavaFileObject> build(Function<String, String> mapper) {
        classEnd();
        String res = mapper.apply(builder.build());
        return List.of(Utils.fileFromString(pkgName, className, res));
    }
}
