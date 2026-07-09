/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClassNamesTest {

    @Test
    void internalNameSlashToDot() {
        assertEquals("java.lang.Object", ClassNames.pretty("java/lang/Object"));
        assertEquals("com.example.Foo", ClassNames.pretty("com/example/Foo"));
    }

    @Test
    void alreadyDottedPassesThrough() {
        assertEquals("java.lang.Object", ClassNames.pretty("java.lang.Object"));
    }

    @Test
    void objectArrayDescriptor() {
        assertEquals("java.lang.Object[]",  ClassNames.pretty("[Ljava/lang/Object;"));
        assertEquals("java.lang.String[]",  ClassNames.pretty("[Ljava/lang/String;"));
    }

    @Test
    void multiDimensionalObjectArray() {
        assertEquals("java.lang.Object[][]", ClassNames.pretty("[[Ljava/lang/Object;"));
        assertEquals("java.lang.Object[][][]", ClassNames.pretty("[[[Ljava/lang/Object;"));
    }

    @Test
    void primitiveArrayDescriptors() {
        assertEquals("boolean[]", ClassNames.pretty("[Z"));
        assertEquals("byte[]",    ClassNames.pretty("[B"));
        assertEquals("char[]",    ClassNames.pretty("[C"));
        assertEquals("short[]",   ClassNames.pretty("[S"));
        assertEquals("int[]",     ClassNames.pretty("[I"));
        assertEquals("long[]",    ClassNames.pretty("[J"));
        assertEquals("float[]",   ClassNames.pretty("[F"));
        assertEquals("double[]",  ClassNames.pretty("[D"));
    }

    @Test
    void multiDimPrimitiveArray() {
        assertEquals("int[][]",  ClassNames.pretty("[[I"));
        assertEquals("byte[][][]", ClassNames.pretty("[[[B"));
    }

    @Test
    void alreadyPrettyPrimitiveArray() {
        assertEquals("int[]",  ClassNames.pretty("int[]"));
        assertEquals("byte[]", ClassNames.pretty("byte[]"));
    }

    @Test
    void nullOrEmpty() {
        assertNull(ClassNames.pretty(null));
        assertEquals("", ClassNames.pretty(""));
    }

    @Test
    void innerClass() {
        assertEquals("scala.collection.immutable.BitmapIndexedSetNode",
            ClassNames.pretty("scala/collection/immutable/BitmapIndexedSetNode"));
        assertEquals("cafesat.sat.Solver$Clause",
            ClassNames.pretty("cafesat/sat/Solver$Clause"));
    }

    @Test
    void innerClassArray() {
        assertEquals("cafesat.sat.Solver$Clause[]",
            ClassNames.pretty("[Lcafesat/sat/Solver$Clause;"));
    }
}
