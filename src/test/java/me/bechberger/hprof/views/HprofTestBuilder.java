/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static me.bechberger.hprof.core.HprofConstants.*;

/**
 * Builds minimal synthetic HPROF byte[] for unit testing the views pipeline.
 *
 * Usage:
 * <pre>
 *   HprofTestBuilder b = new HprofTestBuilder(4 /* idSize *\/);
 *   b.addUtf8(1L, "java/lang/Object");
 *   b.addLoadClass(1, 0x10L, 1L);   // serial=1, classId=0x10, nameId=1
 *   b.addClass(0x10L, 0L, 0L, 16);  // classId, superClassId, loaderId, instanceSize
 *   b.addInstanceObject(0x100L, 0x10L, new long[0]); // objectId, classId, no refs
 *   b.addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS);
 *   byte[] hprof = b.build();
 * </pre>
 */
public final class HprofTestBuilder {

    private static final int HPROF_GC_ROOT_JNI_GLOBAL_LOCAL = HPROF_GC_ROOT_JNI_GLOBAL;

    private final int idSize;
    private final List<byte[]> topRecords = new ArrayList<>();   // before heap dump
    private final List<byte[]> heapSubRecords = new ArrayList<>(); // inside HEAP_DUMP

    public HprofTestBuilder(int idSize) {
        this.idSize = idSize;
    }

    public HprofTestBuilder() { this(4); }

    // ---- Top-level records ----

    public HprofTestBuilder addUtf8(long nameId, String text) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(buf);
            byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeRecordHeader(d, HPROF_UTF8, idSize + bytes.length);
            writeId(d, nameId);
            d.write(bytes);
            topRecords.add(buf.toByteArray());
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return this;
    }

    public HprofTestBuilder addLoadClass(int serialNumber, long classId, long nameId) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(buf);
            writeRecordHeader(d, HPROF_LOAD_CLASS, 4 + idSize + 4 + idSize);
            d.writeInt(serialNumber);
            writeId(d, classId);
            d.writeInt(0); // stack trace serial
            writeId(d, nameId);
            topRecords.add(buf.toByteArray());
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return this;
    }

    // ---- Heap sub-records ----

    /** Add a CLASS_DUMP with no constant pool, no static fields, and given instance fields. */
    public HprofTestBuilder addClass(long classId, long superClassId, long classLoaderId,
                                     int instanceSize, FieldDef... fields) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(buf);
            d.write(HPROF_GC_CLASS_DUMP);
            writeId(d, classId);
            d.writeInt(0); // stack trace serial
            writeId(d, superClassId);
            writeId(d, classLoaderId);
            writeId(d, 0); // signers
            writeId(d, 0); // domain
            writeId(d, 0); // reserved1
            writeId(d, 0); // reserved2
            d.writeInt(instanceSize);
            d.writeShort(0); // constant pool count
            d.writeShort(0); // static field count
            d.writeShort(fields.length); // instance field count
            for (FieldDef f : fields) {
                writeId(d, f.nameId);
                d.write(f.typeCode);
            }
            heapSubRecords.add(buf.toByteArray());
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return this;
    }

    /** Add INSTANCE_DUMP. refValues are the OBJECT-type field values (objectIds) in order. */
    public HprofTestBuilder addInstanceObject(long objectId, long classId, long... refValues) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(buf);
            int dataLen = refValues.length * idSize;
            d.write(HPROF_GC_INSTANCE_DUMP);
            writeId(d, objectId);
            d.writeInt(0); // stack trace serial
            writeId(d, classId);
            d.writeInt(dataLen);
            for (long v : refValues) writeId(d, v);
            heapSubRecords.add(buf.toByteArray());
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return this;
    }

    /** Add OBJ_ARRAY_DUMP. elements are objectIds. */
    public HprofTestBuilder addObjectArray(long objectId, long elementClassId, long... elements) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(buf);
            d.write(HPROF_GC_OBJ_ARRAY_DUMP);
            writeId(d, objectId);
            d.writeInt(0); // stack trace serial
            d.writeInt(elements.length);
            writeId(d, elementClassId);
            for (long e : elements) writeId(d, e);
            heapSubRecords.add(buf.toByteArray());
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return this;
    }

    /** Add PRIM_ARRAY_DUMP of int type. */
    public HprofTestBuilder addIntArray(long objectId, int... values) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(buf);
            d.write(HPROF_GC_PRIM_ARRAY_DUMP);
            writeId(d, objectId);
            d.writeInt(0); // stack trace serial
            d.writeInt(values.length);
            d.write(HPROF_TYPE_INT);
            for (int v : values) d.writeInt(v);
            heapSubRecords.add(buf.toByteArray());
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return this;
    }

    /** Add PRIM_ARRAY_DUMP of char type (for String backing arrays). */
    public HprofTestBuilder addCharArray(long objectId, char... values) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(buf);
            d.write(HPROF_GC_PRIM_ARRAY_DUMP);
            writeId(d, objectId);
            d.writeInt(0);
            d.writeInt(values.length);
            d.write(HPROF_TYPE_CHAR);
            for (char c : values) d.writeChar(c);
            heapSubRecords.add(buf.toByteArray());
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return this;
    }

    public HprofTestBuilder addGCRoot(long objectId, int rootType) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(buf);
            switch (rootType) {
                case HPROF_GC_ROOT_UNKNOWN, HPROF_GC_ROOT_STICKY_CLASS, HPROF_GC_ROOT_MONITOR_USED -> {
                    d.write(rootType);
                    writeId(d, objectId);
                }
                case HPROF_GC_ROOT_JNI_GLOBAL -> {
                    d.write(rootType);
                    writeId(d, objectId);
                    writeId(d, 0); // JNI global ref id
                }
                case HPROF_GC_ROOT_JNI_LOCAL, HPROF_GC_ROOT_JAVA_FRAME, HPROF_GC_ROOT_THREAD_OBJ -> {
                    d.write(rootType);
                    writeId(d, objectId);
                    d.writeInt(0); // thread serial
                    d.writeInt(0); // frame number / 0xFFFFFFFF
                }
                case HPROF_GC_ROOT_NATIVE_STACK, HPROF_GC_ROOT_THREAD_BLOCK -> {
                    d.write(rootType);
                    writeId(d, objectId);
                    d.writeInt(0); // thread serial
                }
                default -> {
                    d.write(rootType);
                    writeId(d, objectId);
                }
            }
            heapSubRecords.add(buf.toByteArray());
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return this;
    }

    /** Build the HPROF byte array. */
    public byte[] build() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(out);

            // Header
            byte[] magic = "JAVA PROFILE 1.0.2\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            d.write(magic);
            d.writeInt(idSize);
            d.writeLong(0L); // timestamp

            // Top-level records
            for (byte[] rec : topRecords) d.write(rec);

            // Heap dump segment
            ByteArrayOutputStream heapBuf = new ByteArrayOutputStream();
            for (byte[] sub : heapSubRecords) heapBuf.write(sub);
            byte[] heapBytes = heapBuf.toByteArray();

            writeRecordHeader(d, HPROF_HEAP_DUMP, heapBytes.length);
            d.write(heapBytes);

            // Heap dump end
            writeRecordHeader(d, HPROF_HEAP_DUMP_END, 0);

            d.flush();
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** Write to a temp file and return the path. */
    public Path buildToPath() {
        try {
            Path tmp = Files.createTempFile("hprof-test-", ".hprof");
            tmp.toFile().deleteOnExit();
            Files.write(tmp, build());
            return tmp;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    // ---- Low-level write helpers ----

    private void writeRecordHeader(DataOutputStream d, int tag, int length) throws IOException {
        d.write(tag);
        d.writeInt(0); // time
        d.writeInt(length);
    }

    private void writeId(DataOutputStream d, long id) throws IOException {
        if (idSize == 4) d.writeInt((int) id);
        else d.writeLong(id);
    }

    /** Field definition for CLASS_DUMP instance fields. */
    public record FieldDef(long nameId, int typeCode) {
        public static FieldDef object(long nameId) { return new FieldDef(nameId, HPROF_TYPE_OBJECT); }
        public static FieldDef intField(long nameId) { return new FieldDef(nameId, HPROF_TYPE_INT); }
    }
}
