# Heap Dump Analysis Overview

`hprof-redact views` + Eclipse MAT 1.17.0 — thinkstation (128 cores, 123 GB RAM)

## Summary

| Dump | MB | Objects | **ours** time | **ours** Peak RSS | MAT time | MAT Peak RSS |
|---|---:|---:|---:|---:|---:|---:|
| dump_1_mnemonics | 21 | 338,590 | 0.8s | 149 MB | 0:05.37 | 716 MB |
| dump_4_philosophers | 23 | 242,426 | 0.8s | 146 MB | 0:06.24 | 930 MB |
| dump_3_par-mnemonics | 32 | 521,698 | 1.3s | 160 MB | 0:06.75 | 1001 MB |
| dump_2_scala-doku | 51 | 956,931 | 3.1s | 157 MB | 0:08.12 | 987 MB |
| dump_7_gauss-mix | 70 | 623,284 | 1.6s | 222 MB | 0:10.31 | 1135 MB |
| dump_6_dec-tree | 117 | 932,542 | 2.2s | 258 MB | 0:11.75 | 1150 MB |
| dump_0_fj-kmeans | 135 | 3,232,888 | 3.5s | 268 MB | 0:17.35 | 1498 MB |
| dump_8_log-regression | 240 | 1,003,597 | 2.4s | 267 MB | 0:10.06 | 1173 MB |
| dump_5_naive-bayes | 1251 | 1,146,507 | 4.0s | 258 MB | 0:11.74 | 1249 MB |

### Phase RSS detail

| Dump | A.1 RSS | A.2 RSS | B RSS | CHK RSS |
|---|---:|---:|---:|---:|
| dump_1_mnemonics | 165 MB | 122 MB | 145 MB | 145 MB |
| dump_4_philosophers | 162 MB | 123 MB | 144 MB | 147 MB |
| dump_3_par-mnemonics | 172 MB | 137 MB | 149 MB | 149 MB |
| dump_2_scala-doku | 169 MB | 145 MB | 172 MB | 173 MB |
| dump_7_gauss-mix | 244 MB | 169 MB | 185 MB | 185 MB |
| dump_6_dec-tree | 273 MB | 181 MB | 186 MB | 186 MB |
| dump_0_fj-kmeans | 208 MB | 212 MB | 274 MB | 281 MB |
| dump_8_log-regression | 286 MB | 179 MB | 187 MB | 190 MB |
| dump_5_naive-bayes | 179 MB | 187 MB | 202 MB | 195 MB |

---

## dump_1_mnemonics  (21 MB)

### Timings & RSS

| Phase | Time (s) | RSS (MB) |
|---|---:|---:|
| Phase A.1  (metadata/addresses) | 0.2 | 165 |
| Phase A.2  (edge counting/fwd CSR) | 0.3 | 122 |
| RPO DFS | 0.0 | 128 |
| Phase B    (inbound CSR fill) | 0.2 | 145 |
| CHK domtree | 0.0 | 145 |
| Retained sizes | 0.0 | 145 |

**Total:** 0.8s &nbsp; **Peak RSS:** 149 MB

### Our Report — Biggest Classes by Retained Heap

| # | Class | Instances | Retained |
|---|---|---:|---:|
| 1 | `java.lang.invoke.LambdaForm$DMH+0x00007ff1d4130000` | 70 | 441.3 KB |
| 2 | `java.lang.Thread` | 25 | 228.8 KB |
| 3 | `java.lang.Class` | 1,446 | 158.2 KB |
| 4 | `java.lang.Module` | 1 | 133.6 KB |
| 5 | `java.lang.String` | 1,071 | 49.9 KB |
| 6 | `jdk.internal.module.ModuleReferenceImpl` | 62 | 21.4 KB |
| 7 | `java.lang.invoke.MethodType` | 42 | 13.6 KB |
| 8 | `java.lang.module.Configuration` | 2 | 8.7 KB |
| 9 | `java.lang.invoke.MethodTypeForm` | 11 | 5.8 KB |
| 10 | `jdk.internal.misc.InnocuousThread` | 1 | 416 B |
| 11 | `java.lang.ThreadGroup` | 2 | 232 B |
| 12 | `java.lang.ref.Finalizer$FinalizerThread` | 1 | 216 B |
| 13 | `jdk.internal.loader.ClassLoaders$AppClassLoader` | 1 | 160 B |
| 14 | `java.lang.ref.Reference$ReferenceHandler` | 1 | 144 B |
| 15 | `java.lang.NullPointerException` | 1 | 56 B |

### MAT — Class Histogram (top 15, sorted by retained)

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `java.util.HashMap$Node[]` | 417 | 389.2 KB | 6.2 MB |
| 2 | `java.util.HashMap` | 410 | 19.2 KB | 6.2 MB |
| 3 | `java.util.HashMap$Node` | 47,746 | 1.5 MB | 5.8 MB |
| 4 | `java.lang.Thread` | 27 | 2.7 KB | 5.5 MB |
| 5 | `java.util.HashSet` | 245 | 3.8 KB | 5.2 MB |
| 6 | `java.util.stream.ReduceOps$3ReducingSink` | 18 | 576 B | 5.1 MB |
| 7 | `java.lang.String` | 59,804 | 1.4 MB | 4.9 MB |
| 8 | `byte[]` | 60,520 | 4.1 MB | 4.1 MB |
| 9 | `java.lang.Class` | 2,294 | 30.0 KB | 3.5 MB |
| 10 | `java.lang.Object[]` | 2,329 | 722.5 KB | 2.9 MB |
| 11 | `scala.runtime.LazyVals$` | 1 | 16 B | 2.5 MB |
| 12 | `java.lang.Object` | 132,975 | 2.0 MB | 2.0 MB |
| 13 | `java.lang.ref.SoftReference` | 148 | 5.8 KB | 605.2 KB |
| 14 | `java.util.jar.JarFile` | 7 | 448 B | 593.3 KB |
| 15 | `java.util.jar.Manifest` | 6 | 144 B | 590.3 KB |

### MAT — Biggest Top-Level Dominator Classes

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `java.lang.Thread` | 25 | 2.5 KB | 5.5 MB |
| 2 | `java.lang.Class` | 1,622 | 24.9 KB | 3.4 MB |
| 3 | `java.util.jar.JarFile` | 7 | 448 B | 592.8 KB |
| 4 | `java.lang.String` | 8,922 | 209.1 KB | 591.6 KB |
| 5 | `java.util.zip.ZipFile$Source` | 7 | 560 B | 446.2 KB |

### Our Report — Leak Suspects

## Leak Suspects

### Suspect 1: `java.lang.invoke.LambdaForm$DMH+0x00007ff1d4130000`

- **Type**: Group of instances
- **Instances**: 68,865
- **Retained heap**: 7.9 MB (69.2% of total)
- **Shallow heap**: 6.9 MB

### Suspect 2: `java.lang.Object`

- **Type**: Group of instances
- **Instances**: 132,977
- **Retained heap**: 2.0 MB (17.7% of total)
- **Shallow heap**: 2.0 MB

### Suspect 3: `java.util.HashMap$Node`

- **Type**: Group of instances
- **Instances**: 47,753
- **Retained heap**: 1.3 MB (11.4% of total)
- **Shallow heap**: 1.1 MB

### MAT — Leak Suspects

System Overview
Problem Suspect 1
The thread
java.lang.Thread @ 0xce8ddca0  main
keeps local variables with total size
5,761,696 (48.83%)
bytes. The top consumers of its minimum retained heap are
byte[]
(43,085 instances totaling 2,962,336),
java.util.HashMap$Node
(41,322 instances totaling 1,322,304), and
java.lang.String
(43,181 instances totaling 1,036,344).
The memory is accumulated in one instance of
java.util.HashMap$Node[]
, loaded by
<system class loader>
, which occupies
4,419,056 (37.45%)
bytes.
Significant stack frames and local variables
java.util.stream.AbstractPipeline.wrapAndCopyInto(Ljava/util/stream/Sink;Ljava/util/Spliterator;)Ljava/util/stream/Sink; (AbstractPipeline.java:499)
java.util.stream.ReduceOps$3ReducingSink @ 0xc046b958 retains 4,419,152 (37.45%) bytes
The stacktrace of this Thread is available.
java.util.HashMap$Node[]
java.util.stream.AbstractPipeline.wrapAndCopyInto(Ljava/util/stream/Sink;Ljava/util/Spliterator;)Ljava/util/stream/Sink;
AbstractPipeline.java:499
Problem Suspect 2
The class
scala.runtime.LazyVals$
, loaded by
java.net.URLClassLoader @ 0xce800048
, occupies
2,622,480 (22.22%)
bytes. The top consumers of its minimum retained heap are
java.lang.Object
(131,072 instances totaling 2,097,152),
java.lang.Object[]
(3 instances totaling 524,392), and
byte[]
(8 instances totaling 312). The memory is accumulated in one instance of
java.lang.Object[]
, loaded by
<system class loader>
, which occupies
2,621,456 (22.21%)
bytes.
Thread
java.lang.Thread @ 0xce8ddca0  main
has a local variable or reference to

### MAT — Top Components (by classloader)

<system class loader> (76%)
java.net.URLClassLoader @ 0xce800048 (23%)

---

## dump_4_philosophers  (23 MB)

### Timings & RSS

| Phase | Time (s) | RSS (MB) |
|---|---:|---:|
| Phase A.1  (metadata/addresses) | 0.2 | 162 |
| Phase A.2  (edge counting/fwd CSR) | 0.4 | 123 |
| RPO DFS | 0.0 | 126 |
| Phase B    (inbound CSR fill) | 0.2 | 144 |
| CHK domtree | 0.0 | 147 |
| Retained sizes | 0.0 | 148 |

**Total:** 0.8s &nbsp; **Peak RSS:** 146 MB

### Our Report — Biggest Classes by Retained Heap

| # | Class | Instances | Retained |
|---|---|---:|---:|
| 1 | `java.lang.Thread` | 25 | 449.9 KB |
| 2 | `java.lang.invoke.LambdaForm$DMH+0x00007e41c023bc00` | 71 | 441.5 KB |
| 3 | `java.lang.Class` | 1,443 | 157.8 KB |
| 4 | `java.lang.Module` | 1 | 133.6 KB |
| 5 | `java.lang.String` | 1,065 | 49.7 KB |
| 6 | `jdk.internal.module.ModuleReferenceImpl` | 62 | 21.4 KB |
| 7 | `java.lang.invoke.MethodType` | 43 | 15.3 KB |
| 8 | `java.lang.module.Configuration` | 2 | 8.7 KB |
| 9 | `java.lang.invoke.MethodTypeForm` | 11 | 7.0 KB |
| 10 | `jdk.internal.misc.InnocuousThread` | 1 | 416 B |
| 11 | `java.lang.ThreadGroup` | 2 | 232 B |
| 12 | `java.lang.ref.Finalizer$FinalizerThread` | 1 | 216 B |
| 13 | `jdk.internal.loader.ClassLoaders$AppClassLoader` | 1 | 160 B |
| 14 | `java.lang.ref.Reference$ReferenceHandler` | 1 | 144 B |
| 15 | `java.lang.ClassValue$Entry` | 1 | 72 B |

### MAT — Class Histogram (top 15, sorted by retained)

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `java.lang.Object[]` | 2,237 | 1.3 MB | 3.7 MB |
| 2 | `scala.concurrent.stm.ccstm.InTxnImpl` | 129 | 18.1 KB | 3.7 MB |
| 3 | `java.lang.Class` | 2,793 | 34.3 KB | 3.6 MB |
| 4 | `byte[]` | 24,078 | 2.6 MB | 2.6 MB |
| 5 | `scala.runtime.LazyVals$` | 1 | 16 B | 2.5 MB |
| 6 | `java.lang.Object` | 134,275 | 2.0 MB | 2.0 MB |
| 7 | `java.lang.String` | 23,331 | 546.8 KB | 1.8 MB |
| 8 | `java.util.HashMap` | 361 | 16.9 KB | 1.5 MB |
| 9 | `java.util.HashMap$Node[]` | 395 | 96.7 KB | 1.5 MB |
| 10 | `java.util.HashMap$Node` | 9,876 | 308.6 KB | 1.5 MB |
| 11 | `java.util.zip.ZipFile$Source` | 19 | 1.5 KB | 1.3 MB |
| 12 | `java.lang.ref.SoftReference` | 151 | 5.9 KB | 1.1 MB |
| 13 | `long[]` | 173 | 1.1 MB | 1.1 MB |
| 14 | `java.util.jar.JarFile` | 19 | 1.2 KB | 1.1 MB |
| 15 | `java.util.jar.Manifest` | 8 | 192 B | 1.1 MB |

### MAT — Biggest Top-Level Dominator Classes

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `java.lang.Class` | 1,697 | 25.8 KB | 3.4 MB |
| 2 | `scala.concurrent.stm.ccstm.InTxnImpl` | 94 | 13.2 KB | 2.7 MB |
| 3 | `java.util.zip.ZipFile$Source` | 19 | 1.5 KB | 1.3 MB |
| 4 | `java.util.jar.JarFile` | 19 | 1.2 KB | 1.1 MB |
| 5 | `org.renaissance.scala.stm.RealityShowPhilosophers$PhilosopherThread` | 128 | 16.0 KB | 1.0 MB |
| 6 | `java.lang.String` | 9,412 | 220.6 KB | 626.9 KB |
| 7 | `java.lang.Thread` | 25 | 2.5 KB | 308.5 KB |
| 8 | `java.net.URLClassLoader` | 2 | 176 B | 243.5 KB |
| 9 | `org.renaissance.scala.stm.RealityShowPhilosophers$CameraThread` | 1 | 120 B | 155.3 KB |

### Our Report — Leak Suspects

## Leak Suspects

### Suspect 1: `java.lang.invoke.LambdaForm$DMH+0x00007e41c023bc00`

- **Type**: Group of instances
- **Instances**: 35,573
- **Retained heap**: 12.2 MB (82.4% of total)
- **Shallow heap**: 11.0 MB

### Suspect 2: `java.lang.Object`

- **Type**: Group of instances
- **Instances**: 134,277
- **Retained heap**: 2.0 MB (13.8% of total)
- **Shallow heap**: 2.0 MB

### MAT — Leak Suspects

System Overview
Problem Suspect 1
94 instances of
scala.concurrent.stm.ccstm.InTxnImpl
, loaded by
java.net.URLClassLoader @ 0x80300d20
occupy
2,791,424 (22.90%)
bytes. The top consumers of their minimum retained heap are
scala.concurrent.stm.ccstm.Handle[]
(188 instances totaling 773,056),
long[]
(94 instances totaling 771,552), and
int[]
(188 instances totaling 485,040).
scala.concurrent.stm.ccstm.InTxnImpl
java.net.URLClassLoader
Problem Suspect 2
The class
scala.runtime.LazyVals$
, loaded by
java.net.URLClassLoader @ 0x8e800048
, occupies
2,622,376 (21.52%)
bytes. The top consumers of its minimum retained heap are
java.lang.Object
(131,072 instances totaling 2,097,152),
java.lang.Object[]
(3 instances totaling 524,392), and
byte[]
(6 instances totaling 256). The memory is accumulated in one instance of
java.lang.Object[]
, loaded by
<system class loader>
, which occupies
2,621,456 (21.51%)
bytes.
Thread
java.lang.Thread @ 0x8e7ddc48  main
has a local variable or reference to
java.net.URLClassLoader @ 0x8e800048
which is on the shortest path to
java.lang.Object[131072] @ 0x80000000
. The thread
java.lang.Thread @ 0x8e7ddc48  main
keeps local variables with total size
309,608 (2.54%)
bytes. The top consumers of its minimum retained heap are
java.lang.Object
(131,072 instances totaling 2,097,152),

### MAT — Top Components (by classloader)

<system class loader> (41%)
java.net.URLClassLoader @ 0x80300d20 (36%)
java.net.URLClassLoader @ 0x8e800048 (22%)

---

## dump_3_par-mnemonics  (32 MB)

### Timings & RSS

| Phase | Time (s) | RSS (MB) |
|---|---:|---:|
| Phase A.1  (metadata/addresses) | 0.2 | 172 |
| Phase A.2  (edge counting/fwd CSR) | 0.7 | 137 |
| RPO DFS | 0.0 | 147 |
| Phase B    (inbound CSR fill) | 0.3 | 149 |
| CHK domtree | 0.0 | 149 |
| Retained sizes | 0.0 | 150 |

**Total:** 1.3s &nbsp; **Peak RSS:** 160 MB

### Our Report — Biggest Classes by Retained Heap

| # | Class | Instances | Retained |
|---|---|---:|---:|
| 1 | `java.lang.invoke.LambdaForm$DMH+0x00007c8494131400` | 74 | 442.5 KB |
| 2 | `java.lang.Class` | 1,486 | 162.5 KB |
| 3 | `java.lang.Module` | 1 | 133.6 KB |
| 4 | `java.util.concurrent.ForkJoinPool$WorkQueue` | 27 | 115.6 KB |
| 5 | `java.lang.String` | 1,102 | 50.7 KB |
| 6 | `jdk.internal.module.ModuleReferenceImpl` | 62 | 21.4 KB |
| 7 | `java.lang.invoke.MethodType` | 42 | 13.6 KB |
| 8 | `java.lang.module.Configuration` | 2 | 8.7 KB |
| 9 | `java.lang.Thread` | 24 | 6.6 KB |
| 10 | `java.lang.invoke.MethodTypeForm` | 11 | 5.8 KB |
| 11 | `java.util.concurrent.ConcurrentHashMap` | 1 | 4.5 KB |
| 12 | `org.renaissance.jdk.streams.MnemonicsCoderWithStream` | 1 | 1.9 KB |
| 13 | `jdk.internal.misc.InnocuousThread` | 1 | 416 B |
| 14 | `java.util.concurrent.ForkJoinPool` | 1 | 232 B |
| 15 | `java.lang.ThreadGroup` | 2 | 232 B |

### MAT — Class Histogram (top 15, sorted by retained)

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `java.util.HashMap$Node[]` | 422 | 875.0 KB | 14.1 MB |
| 2 | `java.util.HashMap` | 422 | 19.8 KB | 14.0 MB |
| 3 | `java.util.HashMap$Node` | 108,445 | 3.3 MB | 13.3 MB |
| 4 | `java.util.HashSet` | 255 | 4.0 KB | 13.1 MB |
| 5 | `java.util.stream.ReduceOps$3ReducingSink` | 25 | 800 B | 13.0 MB |
| 6 | `java.lang.String` | 120,581 | 2.8 MB | 10.5 MB |
| 7 | `java.util.concurrent.ForkJoinWorkerThread` | 26 | 2.8 KB | 8.9 MB |
| 8 | `byte[]` | 121,297 | 8.3 MB | 8.3 MB |
| 9 | `java.util.stream.ReduceOps$ReduceTask` | 9 | 576 B | 4.2 MB |
| 10 | `java.lang.Class` | 2,345 | 30.7 KB | 3.5 MB |
| 11 | `java.lang.Object[]` | 2,410 | 726.9 KB | 2.9 MB |
| 12 | `scala.runtime.LazyVals$` | 1 | 16 B | 2.5 MB |
| 13 | `java.lang.Object` | 133,001 | 2.0 MB | 2.0 MB |
| 14 | `java.lang.ref.SoftReference` | 149 | 5.8 KB | 605.3 KB |
| 15 | `java.util.jar.JarFile` | 7 | 448 B | 593.3 KB |

### MAT — Biggest Top-Level Dominator Classes

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `java.util.concurrent.ForkJoinWorkerThread` | 26 | 2.8 KB | 8.9 MB |
| 2 | `java.util.stream.ReduceOps$ReduceTask` | 5 | 320 B | 4.2 MB |
| 3 | `java.lang.Class` | 1,665 | 25.6 KB | 3.4 MB |
| 4 | `java.util.jar.JarFile` | 7 | 448 B | 592.8 KB |
| 5 | `java.lang.String` | 8,924 | 209.2 KB | 591.7 KB |
| 6 | `java.util.zip.ZipFile$Source` | 7 | 560 B | 446.2 KB |
| 7 | `java.lang.Thread` | 25 | 2.5 KB | 307.7 KB |

### Our Report — Leak Suspects

## Leak Suspects

### Suspect 1: `java.lang.invoke.LambdaForm$DMH+0x00007c8494131400`

- **Type**: Group of instances
- **Instances**: 129,842
- **Retained heap**: 13.1 MB (70.8% of total)
- **Shallow heap**: 12.0 MB

### Suspect 2: `java.util.HashMap$Node`

- **Type**: Group of instances
- **Instances**: 108,452
- **Retained heap**: 2.7 MB (14.6% of total)
- **Shallow heap**: 2.5 MB

### Suspect 3: `java.lang.Object`

- **Type**: Group of instances
- **Instances**: 133,003
- **Retained heap**: 2.0 MB (11.0% of total)
- **Shallow heap**: 2.0 MB

### MAT — Leak Suspects

System Overview
Problem Suspect 1
The thread
java.util.concurrent.ForkJoinWorkerThread @ 0xc0303be0  ForkJoinPool.commonPool-worker-16
keeps local variables with total size
9,317,416 (46.31%)
bytes. The top consumers of its minimum retained heap are
byte[]
(68,660 instances totaling 4,868,920),
java.util.HashMap$Node
(68,639 instances totaling 2,196,448), and
java.lang.String
(68,659 instances totaling 1,647,816).
The memory is accumulated in one instance of
java.util.HashMap$Node[]
, loaded by
<system class loader>
, which occupies
8,825,040 (43.86%)
bytes.
Significant stack frames and local variables
java.util.stream.AbstractPipeline.wrapAndCopyInto(Ljava/util/stream/Sink;Ljava/util/Spliterator;)Ljava/util/stream/Sink; (AbstractPipeline.java:499)
java.util.stream.ReduceOps$3ReducingSink @ 0xc031cce8 retains 8,825,136 (43.86%) bytes
The stacktrace of this Thread is available.
java.util.HashMap$Node[]
java.util.stream.AbstractPipeline.wrapAndCopyInto(Ljava/util/stream/Sink;Ljava/util/Spliterator;)Ljava/util/stream/Sink;
AbstractPipeline.java:499
Problem Suspect 2
One instance of
java.util.stream.ReduceOps$ReduceTask
loaded by
<system class loader>
occupies
4,419,280 (21.97%)
bytes. The top consumers of its minimum retained heap are
byte[]
(32,500 instances totaling 2,336,896),
java.util.HashMap$Node
(32,500 instances totaling 1,040,000), and
java.lang.String
(32,500 instances totaling 780,000). The instance is referenced by
java.lang.Thread @ 0xce76f1b0  main
, loaded by
<system class loader>
The thread
java.lang.Thread @ 0xce76f1b0  main
keeps local variables with total size
308,760 (1.53%)
bytes. The top consumers of its minimum retained heap are
byte[]

### MAT — Top Components (by classloader)

<system class loader> (86%)
java.net.URLClassLoader @ 0xce800048 (14%)

---

## dump_2_scala-doku  (51 MB)

### Timings & RSS

| Phase | Time (s) | RSS (MB) |
|---|---:|---:|
| Phase A.1  (metadata/addresses) | 0.3 | 169 |
| Phase A.2  (edge counting/fwd CSR) | 0.9 | 145 |
| RPO DFS | 0.1 | 157 |
| Phase B    (inbound CSR fill) | 0.4 | 172 |
| CHK domtree | 1.4 | 173 |
| Retained sizes | 0.0 | 175 |

**Total:** 3.1s &nbsp; **Peak RSS:** 157 MB

### Our Report — Biggest Classes by Retained Heap

| # | Class | Instances | Retained |
|---|---|---:|---:|
| 1 | `java.lang.Thread` | 25 | 19.9 MB |
| 2 | `java.lang.invoke.LambdaForm$DMH+0x00007de4a4204000` | 71 | 441.6 KB |
| 3 | `java.lang.Class` | 1,410 | 154.2 KB |
| 4 | `java.lang.Module` | 1 | 133.6 KB |
| 5 | `java.lang.String` | 1,064 | 49.7 KB |
| 6 | `jdk.internal.module.ModuleReferenceImpl` | 62 | 21.4 KB |
| 7 | `java.lang.invoke.MethodType` | 32 | 14.1 KB |
| 8 | `java.lang.module.Configuration` | 2 | 8.7 KB |
| 9 | `java.lang.invoke.MethodTypeForm` | 8 | 4.3 KB |
| 10 | `jdk.internal.misc.InnocuousThread` | 1 | 416 B |
| 11 | `java.lang.ThreadGroup` | 2 | 232 B |
| 12 | `java.lang.ref.Finalizer$FinalizerThread` | 1 | 216 B |
| 13 | `jdk.internal.loader.ClassLoaders$AppClassLoader` | 1 | 160 B |
| 14 | `java.lang.ref.Reference$ReferenceHandler` | 1 | 144 B |
| 15 | `java.lang.NullPointerException` | 1 | 56 B |

### MAT — Class Histogram (top 15, sorted by retained)

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `java.lang.Thread` | 27 | 2.7 KB | 22.9 MB |
| 2 | `java.lang.Object[]` | 25,579 | 1.5 MB | 11.4 MB |
| 3 | `scala.collection.immutable.$colon$colon` | 146,151 | 3.3 MB | 8.6 MB |
| 4 | `scala.collection.immutable.BitmapIndexedSetNode` | 22,791 | 890.3 KB | 8.4 MB |
| 5 | `scala.collection.immutable.HashSet` | 85 | 1.3 KB | 8.0 MB |
| 6 | `cafesat.sat.Literal` | 125,219 | 4.8 MB | 4.8 MB |
| 7 | `cafesat.sat.Solver` | 1 | 168 B | 4.6 MB |
| 8 | `scala.collection.immutable.Set$Set2` | 44,628 | 1.0 MB | 4.4 MB |
| 9 | `cafesat.sat.Vector[]` | 1 | 143.0 KB | 4.3 MB |
| 10 | `cafesat.sat.Vector` | 36,614 | 858.1 KB | 4.2 MB |
| 11 | `cafesat.asts.core.Trees$ConnectiveApplication` | 43,982 | 1.0 MB | 3.9 MB |
| 12 | `cafesat.sat.Solver$Clause` | 65,565 | 2.0 MB | 3.6 MB |
| 13 | `java.lang.Class` | 2,860 | 35.5 KB | 3.5 MB |
| 14 | `cafesat.sat.Solver$Clause[]` | 36,615 | 3.4 MB | 3.4 MB |
| 15 | `int[]` | 89,265 | 2.6 MB | 2.6 MB |

### MAT — Biggest Top-Level Dominator Classes

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `java.lang.Thread` | 25 | 2.5 KB | 22.9 MB |
| 2 | `java.lang.Class` | 1,678 | 25.4 KB | 3.5 MB |
| 3 | `java.util.jar.JarFile` | 10 | 640 B | 1.2 MB |
| 4 | `java.util.zip.ZipFile$Source` | 10 | 800 B | 827.5 KB |
| 5 | `java.lang.String` | 9,115 | 213.6 KB | 606.0 KB |

### Our Report — Leak Suspects

## Leak Suspects

### Suspect 1: `java.lang.Thread`

- **Type**: Single large object
- **Instances**: 1
- **Retained heap**: 19.9 MB (70.8% of total)
- **Shallow heap**: 144 B

**Accumulation point path** (largest retained child at each step):

| Depth | Object | Class | Retained |
|---|---|---|---:|
| 0 | 883792 | `java.lang.Thread` | 19.9 MB |
| 1 | 264493 | `cafesat.sat.Solver` | 7.4 MB |
| 2 | 418848 | `java.lang.invoke.LambdaForm$DMH+0x00007de4a4204000` | 7.0 MB |
| 3 | 264502 | `cafesat.sat.Vector` | 200 B |
| 4 | 264506 | `java.lang.invoke.LambdaForm$DMH+0x00007de4a4204000` | 176 B |

### MAT — Leak Suspects

System Overview
Problem Suspect 1
The thread
java.lang.Thread @ 0xce7ddc48  main
keeps local variables with total size
23,961,600 (76.67%)
bytes. The top consumers of its minimum retained heap are
cafesat.sat.Literal
(125,219 instances totaling 5,008,760),
cafesat.sat.Solver$Clause[]
(36,616 instances totaling 3,588,192), and
scala.collection.immutable.$colon$colon
(146,151 instances totaling 3,507,624).
The memory is accumulated in one instance of
java.lang.Thread
, loaded by
<system class loader>
, which occupies
23,961,600 (76.67%)
bytes.
Significant stack frames and local variables
scala.collection.immutable.List.foreach(Lscala/Function1;)V (List.scala:333)
scala.collection.immutable.$colon$colon @ 0xc04790d8 retains 3,399,616 (10.88%) bytes
cafesat.sat.Solver.initClauses(Lscala/collection/immutable/List;)V (Solver.scala:115)
cafesat.sat.Solver @ 0xc08c25c0 retains 4,851,728 (15.52%) bytes
cafesat.sat.Solver.solve([Lcafesat/sat/Literal;)Lcafesat/sat/Solver$Results$Result; (Solver.scala:147)
cafesat.sat.Solver @ 0xc08c25c0 retains 4,851,728 (15.52%) bytes
cafesat.api.Solver$.solveForSatisfiability(Lcafesat/api/Formulas$Formula;)Lscala/Option; (Solver.scala:84)
cafesat.sat.Solver @ 0xc08c25c0 retains 4,851,728 (15.52%) bytes
scala.collection.immutable.HashSet @ 0xc08bc238 retains 8,418,080 (26.94%) bytes
The stacktrace of this Thread is available.
java.lang.Thread
scala.collection.immutable.List.foreach(Lscala/Function1;)V
List.scala:333
cafesat.sat.Solver.initClauses(Lscala/collection/immutable/List;)V
Solver.scala:115
cafesat.sat.Solver.solve([Lcafesat/sat/Literal;)Lcafesat/sat/Solver$Results$Result;
Solver.scala:147
cafesat.api.Solver$.solveForSatisfiability(Lcafesat/api/Formulas$Formula;)Lscala/Option;
Solver.scala:84
Problem Suspect 2
1,678 instances of
java.lang.Class
, loaded by
<system class loader>
occupy
3,643,240 (11.66%)
bytes. The top consumers of their minimum retained heap are
java.lang.Object
(131,101 instances totaling 2,097,608),

### MAT — Top Components (by classloader)

<system class loader> (90%)
java.net.URLClassLoader @ 0xce800048 (9%)

---

## dump_7_gauss-mix  (70 MB)

### Timings & RSS

| Phase | Time (s) | RSS (MB) |
|---|---:|---:|
| Phase A.1  (metadata/addresses) | 0.4 | 244 |
| Phase A.2  (edge counting/fwd CSR) | 0.7 | 169 |
| RPO DFS | 0.0 | 175 |
| Phase B    (inbound CSR fill) | 0.3 | 185 |
| CHK domtree | 0.1 | 185 |
| Retained sizes | 0.0 | 186 |

**Total:** 1.6s &nbsp; **Peak RSS:** 222 MB

### Our Report — Biggest Classes by Retained Heap

| # | Class | Instances | Retained |
|---|---|---:|---:|
| 1 | `java.lang.Thread` | 42 | 570.6 KB |
| 2 | `java.lang.invoke.LambdaForm$MH+0x00007b0ac0a0e000` | 90 | 428.7 KB |
| 3 | `java.lang.Class` | 2,749 | 301.1 KB |
| 4 | `org.apache.spark.status.ElementTrackingStore` | 1 | 238.7 KB |
| 5 | `com.codahale.metrics.Timer` | 7 | 159.0 KB |
| 6 | `java.util.concurrent.ThreadPoolExecutor$Worker` | 222 | 147.8 KB |
| 7 | `java.lang.Module` | 1 | 133.6 KB |
| 8 | `java.lang.String` | 2,570 | 132.2 KB |
| 9 | `java.net.URLClassLoader` | 2 | 123.9 KB |
| 10 | `org.apache.spark.SparkContext` | 1 | 85.2 KB |
| 11 | `org.apache.spark.metrics.source.JVMCPUSource$$anon$1` | 1 | 81.8 KB |
| 12 | `org.sparkproject.jetty.util.thread.QueuedThreadPool` | 1 | 64.8 KB |
| 13 | `org.apache.spark.storage.BlockManager` | 1 | 57.8 KB |
| 14 | `java.lang.invoke.MethodType` | 127 | 42.8 KB |
| 15 | `org.apache.spark.SecurityManager` | 1 | 40.0 KB |

### MAT — Class Histogram (top 15, sorted by retained)

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `byte[]` | 58,366 | 14.7 MB | 14.7 MB |
| 2 | `java.util.zip.ZipFile$Source` | 181 | 14.1 KB | 12.3 MB |
| 3 | `java.lang.Class` | 12,493 | 140.4 KB | 7.0 MB |
| 4 | `java.lang.Object[]` | 11,059 | 1.6 MB | 6.3 MB |
| 5 | `java.lang.String` | 56,540 | 1.3 MB | 4.4 MB |
| 6 | `java.util.concurrent.ConcurrentHashMap` | 1,128 | 70.5 KB | 3.2 MB |
| 7 | `java.util.concurrent.ConcurrentHashMap$Node[]` | 444 | 412.4 KB | 3.1 MB |
| 8 | `java.util.LinkedHashMap` | 6,104 | 381.5 KB | 3.1 MB |
| 9 | `java.net.URLClassLoader` | 2 | 176 B | 2.7 MB |
| 10 | `java.util.concurrent.ConcurrentHashMap$Node` | 42,785 | 1.3 MB | 2.6 MB |
| 11 | `scala.runtime.LazyVals$` | 1 | 16 B | 2.5 MB |
| 12 | `java.lang.Object` | 154,653 | 2.4 MB | 2.4 MB |
| 13 | `org.apache.spark.storage.memory.MemoryStore` | 1 | 56 B | 2.3 MB |
| 14 | `java.util.HashMap` | 1,089 | 51.0 KB | 2.2 MB |
| 15 | `java.util.HashMap$Node[]` | 1,053 | 180.8 KB | 2.2 MB |

### MAT — Biggest Top-Level Dominator Classes

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `java.util.zip.ZipFile$Source` | 181 | 14.1 KB | 12.3 MB |
| 2 | `java.lang.Class` | 5,016 | 63.9 KB | 5.4 MB |
| 3 | `java.net.URLClassLoader` | 2 | 176 B | 2.7 MB |
| 4 | `org.apache.spark.storage.memory.MemoryStore` | 1 | 56 B | 2.3 MB |
| 5 | `java.lang.String` | 20,520 | 480.9 KB | 1.5 MB |
| 6 | `java.util.jar.JarFile` | 181 | 11.3 KB | 1.4 MB |
| 7 | `java.lang.Thread` | 268 | 27.2 KB | 912.6 KB |
| 8 | `io.netty.buffer.PoolArena$DirectArena` | 101 | 15.0 KB | 695.2 KB |
| 9 | `io.netty.buffer.PoolArena$HeapArena` | 101 | 15.0 KB | 695.2 KB |
| 10 | `java.lang.invoke.MethodType` | 3,989 | 155.8 KB | 582.6 KB |

### Our Report — Leak Suspects

## Leak Suspects

### Suspect 1: `java.lang.invoke.LambdaForm$MH+0x00007b0ac0a0e000`

- **Type**: Group of instances
- **Instances**: 135,433
- **Retained heap**: 29.2 MB (74.9% of total)
- **Shallow heap**: 27.1 MB

### Suspect 2: `scala.collection.immutable.$colon$colon`

- **Type**: Group of instances
- **Instances**: 1,870
- **Retained heap**: 14.1 MB (36.1% of total)
- **Shallow heap**: 29.2 KB

### MAT — Leak Suspects

System Overview
Problem Suspect 1
181 instances of
java.util.zip.ZipFile$Source
, loaded by
<system class loader>
occupy
12,927,464 (37.29%)
bytes. The top consumers of their minimum retained heap are
byte[]
(181 instances totaling 11,420,568),
int[]
(382 instances totaling 1,492,416), and
java.util.zip.ZipFile$Source
(181 instances totaling 14,480).
Most of these instances are referenced from one instance of
java.util.HashMap$Node[]
, loaded by
<system class loader>
, which occupies
6,832 (0.02%)
bytes. The instance is referenced by
org.apache.spark.scheduler.TaskSchedulerImpl @ 0x81952470
, loaded by
java.net.URLClassLoader @ 0x80f000a0
java.util.zip.ZipFile$Source
java.util.HashMap$Node[]
org.apache.spark.scheduler.TaskSchedulerImpl
java.net.URLClassLoader
Problem Suspect 2
5,016 instances of
java.lang.Class
, loaded by
<system class loader>
occupy
5,681,168 (16.39%)
bytes. The top consumers of their minimum retained heap are
java.lang.Object
(131,118 instances totaling 2,097,880),
java.lang.Object[]
(4,050 instances totaling 821,168), and
byte[]
(9,766 instances totaling 729,936).
java.lang.Class

### MAT — Top Components (by classloader)

<system class loader> (60%)
java.net.URLClassLoader @ 0x80f000a0 (29%)
java.net.URLClassLoader @ 0x80a17e90 (8%)
jdk.internal.loader.ClassLoaders$AppClassLoader @ 0xffeecf48 (1%)
jdk.internal.loader.ClassLoaders$PlatformClassLoader @ 0xffeec828 (1%)

---

## dump_6_dec-tree  (117 MB)

### Timings & RSS

| Phase | Time (s) | RSS (MB) |
|---|---:|---:|
| Phase A.1  (metadata/addresses) | 0.6 | 273 |
| Phase A.2  (edge counting/fwd CSR) | 0.9 | 181 |
| RPO DFS | 0.0 | 189 |
| Phase B    (inbound CSR fill) | 0.4 | 186 |
| CHK domtree | 0.1 | 186 |
| Retained sizes | 0.0 | 186 |

**Total:** 2.2s &nbsp; **Peak RSS:** 258 MB

### Our Report — Biggest Classes by Retained Heap

| # | Class | Instances | Retained |
|---|---|---:|---:|
| 1 | `org.apache.spark.sql.SparkSession` | 1 | 875.7 KB |
| 2 | `java.lang.Thread` | 42 | 716.2 KB |
| 3 | `java.lang.invoke.LambdaForm$MH+0x00007db1c11d9000` | 455 | 445.5 KB |
| 4 | `java.lang.Class` | 3,040 | 333.0 KB |
| 5 | `org.apache.spark.status.ElementTrackingStore` | 1 | 212.0 KB |
| 6 | `java.net.URLClassLoader` | 2 | 203.8 KB |
| 7 | `java.lang.String` | 2,752 | 142.8 KB |
| 8 | `java.lang.Module` | 1 | 133.7 KB |
| 9 | `java.util.concurrent.ThreadPoolExecutor$Worker` | 155 | 99.8 KB |
| 10 | `com.codahale.metrics.Timer` | 8 | 96.2 KB |
| 11 | `org.apache.spark.SparkContext` | 1 | 85.0 KB |
| 12 | `org.apache.spark.ml.feature.VectorIndexerModel` | 1 | 83.8 KB |
| 13 | `org.apache.spark.metrics.source.JVMCPUSource$$anon$1` | 1 | 81.8 KB |
| 14 | `org.sparkproject.jetty.util.thread.QueuedThreadPool` | 1 | 64.8 KB |
| 15 | `org.apache.spark.storage.BlockManager` | 1 | 58.3 KB |

### MAT — Class Histogram (top 15, sorted by retained)

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `byte[]` | 83,187 | 33.7 MB | 33.7 MB |
| 2 | `java.util.LinkedHashMap` | 6,942 | 433.9 KB | 17.6 MB |
| 3 | `java.util.LinkedHashMap$Entry` | 3,706 | 144.8 KB | 17.1 MB |
| 4 | `byte[][]` | 34 | 2.2 KB | 16.6 MB |
| 5 | `org.apache.spark.storage.memory.MemoryStore` | 1 | 56 B | 16.6 MB |
| 6 | `org.apache.spark.storage.memory.DeserializedMemoryEntry` | 7 | 224 B | 16.5 MB |
| 7 | `org.apache.spark.sql.columnar.CachedBatch[]` | 3 | 72 B | 16.5 MB |
| 8 | `org.apache.spark.sql.execution.columnar.DefaultCachedBatch` | 5 | 120 B | 16.5 MB |
| 9 | `java.util.zip.ZipFile$Source` | 181 | 14.1 KB | 12.3 MB |
| 10 | `java.lang.Class` | 19,256 | 193.6 KB | 10.3 MB |
| 11 | `java.lang.Object[]` | 27,196 | 2.4 MB | 9.1 MB |
| 12 | `java.lang.String` | 80,938 | 1.9 MB | 7.2 MB |
| 13 | `java.util.concurrent.ConcurrentHashMap` | 989 | 61.8 KB | 4.9 MB |
| 14 | `java.util.concurrent.ConcurrentHashMap$Node[]` | 584 | 668.1 KB | 4.9 MB |
| 15 | `java.util.concurrent.ConcurrentHashMap$Node` | 74,352 | 2.3 MB | 4.1 MB |

### MAT — Biggest Top-Level Dominator Classes

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `org.apache.spark.storage.memory.MemoryStore` | 1 | 56 B | 16.6 MB |
| 2 | `java.util.zip.ZipFile$Source` | 181 | 14.1 KB | 12.3 MB |
| 3 | `java.lang.Class` | 7,255 | 82.3 KB | 8.0 MB |
| 4 | `java.net.URLClassLoader` | 2 | 176 B | 3.6 MB |
| 5 | `java.lang.String` | 27,469 | 643.8 KB | 2.1 MB |
| 6 | `java.util.jar.JarFile` | 181 | 11.3 KB | 1.8 MB |
| 7 | `java.lang.invoke.MethodType` | 7,507 | 293.2 KB | 1.2 MB |
| 8 | `java.lang.Thread` | 200 | 20.3 KB | 1.1 MB |
| 9 | `org.apache.hadoop.conf.Configuration` | 14 | 672 B | 844.3 KB |
| 10 | `scala.reflect.internal.pickling.UnPickler$Scan` | 47 | 2.9 KB | 741.3 KB |

### Our Report — Leak Suspects

## Leak Suspects

### Suspect 1: `java.lang.invoke.LambdaForm$MH+0x00007db1c11d9000`

- **Type**: Group of instances
- **Instances**: 204,138
- **Retained heap**: 54.6 MB (75.4% of total)
- **Shallow heap**: 51.4 MB

### Suspect 2: `scala.collection.immutable.$colon$colon`

- **Type**: Group of instances
- **Instances**: 7,008
- **Retained heap**: 14.9 MB (20.5% of total)
- **Shallow heap**: 109.5 KB

### MAT — Leak Suspects

System Overview
Problem Suspect 1
One instance of
org.apache.spark.storage.memory.MemoryStore
loaded by
java.net.URLClassLoader @ 0x810000a0
occupies
17,405,136 (28.42%)
bytes. The top consumers of its minimum retained heap are
byte[]
(14 instances totaling 17,402,032),
java.util.LinkedHashMap$Entry
(11 instances totaling 440), and
java.lang.Object[]
(5 instances totaling 280). The memory is accumulated in one instance of
java.util.LinkedHashMap
, loaded by
<system class loader>
, which occupies
17,404,856 (28.42%)
bytes.
Thread
org.apache.spark.storage.BlockManager$RemoteBlockDownloadFileManager$$anon$2 @ 0x8fc8c060  RemoteBlock-temp-file-clean-thread
has a local variable or reference to
org.apache.spark.storage.BlockManager$RemoteBlockDownloadFileManager @ 0x8fc8c1a0
which is on the shortest path to
java.util.LinkedHashMap @ 0x8f352a90
. The thread
org.apache.spark.storage.BlockManager$RemoteBlockDownloadFileManager$$anon$2 @ 0x8fc8c060  RemoteBlock-temp-file-clean-thread
keeps local variables with total size
392 (0.00%)
bytes. The top consumers of its minimum retained heap are
byte[]
(14 instances totaling 17,402,032),
java.util.LinkedHashMap$Entry
(11 instances totaling 440), and
java.lang.Object[]
(5 instances totaling 280).
Significant stack frames and local variables
org.apache.spark.storage.BlockManager$RemoteBlockDownloadFileManager.org$apache$spark$storage$BlockManager$RemoteBlockDownloadFileManager$$keepCleaning()V (BlockManager.scala:2228)
org.apache.spark.storage.BlockManager$RemoteBlockDownloadFileManager @ 0x8fc8c1a0 retains 160 (0.00%) bytes
The stacktrace of this Thread is available.
org.apache.spark.storage.memory.MemoryStore
java.net.URLClassLoader
java.util.LinkedHashMap
org.apache.spark.storage.BlockManager$RemoteBlockDownloadFileManager.org$apache$spark$storage$BlockManager$RemoteBlockDownloadFileManager$$keepCleaning()V
BlockManager.scala:2228
Problem Suspect 2
181 instances of
java.util.zip.ZipFile$Source

### MAT — Top Components (by classloader)

java.net.URLClassLoader @ 0x810000a0 (54%)
<system class loader> (39%)
java.net.URLClassLoader @ 0x802905b8 (4%)
jdk.internal.loader.ClassLoaders$AppClassLoader @ 0xffeecf48 (1%)
jdk.internal.loader.ClassLoaders$PlatformClassLoader @ 0xffeec828 (1%)

---

## dump_0_fj-kmeans  (135 MB)

### Timings & RSS

| Phase | Time (s) | RSS (MB) |
|---|---:|---:|
| Phase A.1  (metadata/addresses) | 0.5 | 208 |
| Phase A.2  (edge counting/fwd CSR) | 1.7 | 212 |
| RPO DFS | 0.1 | 268 |
| Phase B    (inbound CSR fill) | 0.8 | 274 |
| CHK domtree | 0.2 | 281 |
| Retained sizes | 0.0 | 281 |

**Total:** 3.5s &nbsp; **Peak RSS:** 268 MB

### Our Report — Biggest Classes by Retained Heap

| # | Class | Instances | Retained |
|---|---|---:|---:|
| 1 | `java.lang.invoke.LambdaForm$DMH+0x0000793574185800` | 500,075 | 46.2 MB |
| 2 | `java.util.concurrent.ForkJoinPool$WorkQueue` | 129 | 5.7 MB |
| 3 | `java.util.ArrayList` | 2 | 4.1 MB |
| 4 | `java.lang.Class` | 1,457 | 159.4 KB |
| 5 | `java.lang.Module` | 1 | 133.6 KB |
| 6 | `java.lang.String` | 1,065 | 49.8 KB |
| 7 | `jdk.internal.module.ModuleReferenceImpl` | 62 | 21.4 KB |
| 8 | `java.lang.invoke.MethodType` | 35 | 12.7 KB |
| 9 | `java.lang.module.Configuration` | 2 | 8.7 KB |
| 10 | `java.lang.Thread` | 24 | 6.6 KB |
| 11 | `java.lang.invoke.MethodTypeForm` | 9 | 4.6 KB |
| 12 | `java.util.concurrent.ForkJoinPool` | 1 | 2.3 KB |
| 13 | `jdk.internal.misc.InnocuousThread` | 1 | 416 B |
| 14 | `java.lang.ThreadGroup` | 2 | 232 B |
| 15 | `java.lang.ref.Finalizer$FinalizerThread` | 1 | 216 B |

### MAT — Class Histogram (top 15, sorted by retained)

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `java.lang.Double[]` | 500,015 | 19.1 MB | 76.3 MB |
| 2 | `java.lang.Double` | 2,500,076 | 57.2 MB | 57.2 MB |
| 3 | `java.lang.Object[]` | 1,926 | 5.6 MB | 7.8 MB |
| 4 | `java.util.ArrayList` | 128 | 3.0 KB | 4.6 MB |
| 5 | `java.util.HashMap$Node` | 10,187 | 318.3 KB | 3.8 MB |
| 6 | `java.lang.Class` | 2,547 | 33.3 KB | 3.5 MB |
| 7 | `java.util.HashMap$Node[]` | 398 | 96.6 KB | 3.5 MB |
| 8 | `java.util.concurrent.ForkJoinWorkerThread` | 128 | 14.0 KB | 2.8 MB |
| 9 | `java.util.HashMap` | 364 | 17.1 KB | 2.8 MB |
| 10 | `scala.runtime.LazyVals$` | 1 | 16 B | 2.5 MB |
| 11 | `java.lang.Object` | 133,406 | 2.0 MB | 2.0 MB |
| 12 | `byte[]` | 23,300 | 2.0 MB | 2.0 MB |
| 13 | `java.lang.String` | 22,578 | 529.2 KB | 1.6 MB |
| 14 | `java.lang.ref.SoftReference` | 136 | 5.3 KB | 1.2 MB |
| 15 | `java.util.jar.JarFile` | 10 | 640 B | 1.2 MB |

### MAT — Biggest Top-Level Dominator Classes

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `java.lang.Double[]` | 500,005 | 19.1 MB | 76.3 MB |
| 2 | `java.lang.Class` | 1,654 | 25.7 KB | 3.4 MB |
| 3 | `java.util.concurrent.ForkJoinWorkerThread` | 128 | 14.0 KB | 2.8 MB |
| 4 | `java.util.ArrayList` | 2 | 48 B | 2.1 MB |
| 5 | `java.util.jar.JarFile` | 10 | 640 B | 1.1 MB |

### Our Report — Leak Suspects

## Leak Suspects

### Suspect 1: `java.lang.invoke.LambdaForm$DMH+0x0000793574185800`

- **Type**: Group of instances
- **Instances**: 531,732
- **Retained heap**: 64.7 MB (101.6% of total)
- **Shallow heap**: 40.9 MB

### Suspect 2: `java.lang.Double`

- **Type**: Group of instances
- **Instances**: 2,500,076
- **Retained heap**: 19.1 MB (29.9% of total)
- **Shallow heap**: 19.1 MB

### Suspect 3: `java.util.ArrayList`

- **Type**: Group of instances
- **Instances**: 128
- **Retained heap**: 9.0 MB (14.1% of total)
- **Shallow heap**: 2.0 KB

### MAT — Leak Suspects

System Overview
Problem Suspect 1
500,005 instances of
java.lang.Double[]
, loaded by
<system class loader>
occupy
80,000,800 (86.43%)
bytes. The top consumers of their minimum retained heap are
java.lang.Double
(2,500,025 instances totaling 60,000,600), and
java.lang.Double[]
(500,005 instances totaling 20,000,200).
java.lang.Double[]

### MAT — Top Components (by classloader)

<system class loader> (97%)
java.net.URLClassLoader @ 0xe02790a8 (3%)

---

## dump_8_log-regression  (240 MB)

### Timings & RSS

| Phase | Time (s) | RSS (MB) |
|---|---:|---:|
| Phase A.1  (metadata/addresses) | 0.7 | 286 |
| Phase A.2  (edge counting/fwd CSR) | 0.9 | 179 |
| RPO DFS | 0.0 | 181 |
| Phase B    (inbound CSR fill) | 0.4 | 187 |
| CHK domtree | 0.2 | 190 |
| Retained sizes | 0.0 | 190 |

**Total:** 2.4s &nbsp; **Peak RSS:** 267 MB

### Our Report — Biggest Classes by Retained Heap

| # | Class | Instances | Retained |
|---|---|---:|---:|
| 1 | `java.util.concurrent.ThreadPoolExecutor$Worker` | 248 | 4.4 MB |
| 2 | `org.apache.spark.sql.SparkSession` | 1 | 875.7 KB |
| 3 | `org.apache.spark.status.ElementTrackingStore` | 1 | 756.3 KB |
| 4 | `com.codahale.metrics.Timer` | 8 | 487.6 KB |
| 5 | `java.lang.invoke.LambdaForm$MH+0x00007106dd22d000` | 107 | 438.8 KB |
| 6 | `java.lang.Class` | 3,042 | 333.2 KB |
| 7 | `java.lang.Thread` | 42 | 320.5 KB |
| 8 | `java.net.URLClassLoader` | 2 | 203.8 KB |
| 9 | `java.lang.String` | 2,731 | 140.9 KB |
| 10 | `java.lang.Module` | 1 | 133.7 KB |
| 11 | `org.apache.spark.SparkContext` | 1 | 85.1 KB |
| 12 | `org.apache.spark.metrics.source.JVMCPUSource$$anon$1` | 1 | 81.8 KB |
| 13 | `org.sparkproject.jetty.util.thread.QueuedThreadPool` | 1 | 64.8 KB |
| 14 | `org.apache.spark.storage.BlockManager` | 1 | 56.6 KB |
| 15 | `java.lang.invoke.MethodType` | 161 | 52.7 KB |

### MAT — Class Histogram (top 15, sorted by retained)

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `java.util.LinkedHashMap` | 6,951 | 434.4 KB | 110.9 MB |
| 2 | `java.util.LinkedHashMap$Entry` | 3,690 | 144.1 KB | 110.4 MB |
| 3 | `org.apache.spark.storage.memory.MemoryStore` | 1 | 56 B | 109.9 MB |
| 4 | `org.apache.spark.storage.memory.DeserializedMemoryEntry` | 27 | 864 B | 109.8 MB |
| 5 | `byte[]` | 85,592 | 83.9 MB | 83.9 MB |
| 6 | `byte[][]` | 49 | 2.6 KB | 66.2 MB |
| 7 | `org.apache.spark.sql.columnar.CachedBatch[]` | 10 | 240 B | 66.1 MB |
| 8 | `org.apache.spark.sql.execution.columnar.DefaultCachedBatch` | 20 | 480 B | 66.1 MB |
| 9 | `org.apache.spark.ml.feature.InstanceBlock` | 70 | 2.7 KB | 61.3 MB |
| 10 | `org.apache.spark.ml.linalg.SparseMatrix` | 71 | 2.8 KB | 61.0 MB |
| 11 | `org.apache.spark.ml.feature.InstanceBlock[]` | 10 | 480 B | 59.3 MB |
| 12 | `double[]` | 194 | 41.7 MB | 41.7 MB |
| 13 | `int[]` | 9,096 | 23.1 MB | 23.1 MB |
| 14 | `java.lang.Object[]` | 26,848 | 2.5 MB | 12.6 MB |
| 15 | `java.util.zip.ZipFile$Source` | 181 | 14.1 KB | 12.3 MB |

### MAT — Biggest Top-Level Dominator Classes

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `org.apache.spark.storage.memory.MemoryStore` | 1 | 56 B | 109.9 MB |
| 2 | `org.apache.spark.ml.feature.InstanceBlock[]` | 3 | 144 B | 15.6 MB |
| 3 | `java.util.zip.ZipFile$Source` | 181 | 14.1 KB | 12.3 MB |
| 4 | `java.lang.Class` | 8,122 | 91.8 KB | 7.8 MB |
| 5 | `java.net.URLClassLoader` | 2 | 176 B | 7.4 MB |
| 6 | `java.lang.String` | 28,627 | 670.9 KB | 2.2 MB |
| 7 | `org.apache.spark.ml.feature.InstanceBlock` | 2 | 80 B | 2.0 MB |

### Our Report — Leak Suspects

## Leak Suspects

### Suspect 1: `java.lang.invoke.LambdaForm$MH+0x00007106dd22d000`

- **Type**: Group of instances
- **Instances**: 210,548
- **Retained heap**: 169.7 MB (90.2% of total)
- **Shallow heap**: 165.5 MB

### MAT — Leak Suspects

System Overview
Problem Suspect 1
One instance of
org.apache.spark.storage.memory.MemoryStore
loaded by
java.net.URLClassLoader @ 0x701000028
occupies
115,233,568 (62.95%)
bytes. The top consumers of its minimum retained heap are
byte[]
(47 instances totaling 69,432,888),
double[]
(98 instances totaling 30,523,896), and
int[]
(98 instances totaling 15,263,112). The memory is accumulated in one instance of
java.util.LinkedHashMap
, loaded by
<system class loader>
, which occupies
115,233,288 (62.95%)
bytes.
Thread
org.apache.spark.util.UninterruptibleThread @ 0x704e00020  Executor task launch worker for task 3.0 in stage 97.0 (TID 589)
has a local variable or reference to
org.apache.spark.shuffle.IndexShuffleBlockResolver @ 0x7028015b8
which is on the shortest path to
java.util.LinkedHashMap @ 0x702c0dd58
. The thread
org.apache.spark.util.UninterruptibleThread @ 0x704e00020  Executor task launch worker for task 3.0 in stage 97.0 (TID 589)
keeps local variables with total size
122,056 (0.07%)
bytes. The top consumers of its minimum retained heap are
byte[]
(47 instances totaling 69,432,888),
double[]
(98 instances totaling 30,523,896), and
int[]
(98 instances totaling 15,263,112).
Significant stack frames and local variables
org.apache.spark.shuffle.IndexShuffleBlockResolver.writeMetadataFileAndCommit(IJ[J[JLjava/io/File;)V (IndexShuffleBlockResolver.scala:364)
org.apache.spark.shuffle.IndexShuffleBlockResolver @ 0x7028015b8 retains 2,288 (0.00%) bytes
The stacktrace of this Thread is available.
org.apache.spark.storage.memory.MemoryStore
java.net.URLClassLoader
java.util.LinkedHashMap
org.apache.spark.shuffle.IndexShuffleBlockResolver.writeMetadataFileAndCommit(IJ[J[JLjava/io/File;)V
IndexShuffleBlockResolver.scala:364

### MAT — Top Components (by classloader)

java.net.URLClassLoader @ 0x701000028 (84%)
<system class loader> (13%)
java.net.URLClassLoader @ 0x7000f11b0 (1%)

---

## dump_5_naive-bayes  (1251 MB)

### Timings & RSS

| Phase | Time (s) | RSS (MB) |
|---|---:|---:|
| Phase A.1  (metadata/addresses) | 0.7 | 179 |
| Phase A.2  (edge counting/fwd CSR) | 1.2 | 187 |
| RPO DFS | 0.0 | 192 |
| Phase B    (inbound CSR fill) | 0.5 | 202 |
| CHK domtree | 1.3 | 195 |
| Retained sizes | 0.0 | 195 |

**Total:** 4.0s &nbsp; **Peak RSS:** 258 MB

### Our Report — Biggest Classes by Retained Heap

| # | Class | Instances | Retained |
|---|---|---:|---:|
| 1 | `java.util.concurrent.ThreadPoolExecutor$Worker` | 315 | 174.8 MB |
| 2 | `org.apache.spark.sql.SparkSession` | 1 | 875.6 KB |
| 3 | `com.codahale.metrics.Timer` | 8 | 477.7 KB |
| 4 | `java.lang.invoke.LambdaForm$MH+0x0000716c9580ac00` | 130 | 447.3 KB |
| 5 | `java.lang.Thread` | 42 | 379.7 KB |
| 6 | `org.apache.spark.status.ElementTrackingStore` | 1 | 367.8 KB |
| 7 | `java.lang.Class` | 3,052 | 334.3 KB |
| 8 | `java.net.URLClassLoader` | 2 | 203.9 KB |
| 9 | `java.lang.String` | 2,891 | 155.5 KB |
| 10 | `java.lang.Module` | 1 | 133.7 KB |
| 11 | `org.apache.spark.storage.BlockInfoManager` | 1 | 88.5 KB |
| 12 | `org.apache.spark.SparkContext` | 1 | 85.0 KB |
| 13 | `org.apache.spark.scheduler.TaskSetManager` | 1 | 82.0 KB |
| 14 | `org.apache.spark.metrics.source.JVMCPUSource$$anon$1` | 1 | 81.8 KB |
| 15 | `java.util.concurrent.ForkJoinPool` | 1 | 68.1 KB |

### MAT — Class Histogram (top 15, sorted by retained)

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `byte[]` | 92,847 | 1.1 GB | 1.1 GB |
| 2 | `byte[][]` | 411 | 11.0 KB | 879.9 MB |
| 3 | `org.apache.spark.sql.execution.columnar.DefaultCachedBatch` | 301 | 7.1 KB | 879.8 MB |
| 4 | `org.apache.spark.sql.columnar.CachedBatch[]` | 107 | 4.7 KB | 879.8 MB |
| 5 | `java.util.LinkedHashMap` | 7,007 | 437.9 KB | 487.6 MB |
| 6 | `java.util.LinkedHashMap$Entry` | 3,823 | 149.3 KB | 487.1 MB |
| 7 | `org.apache.spark.storage.memory.MemoryStore` | 1 | 56 B | 486.4 MB |
| 8 | `org.apache.spark.storage.memory.DeserializedMemoryEntry` | 103 | 3.2 KB | 486.3 MB |
| 9 | `org.apache.spark.util.UninterruptibleThread` | 128 | 14.0 KB | 89.9 MB |
| 10 | `java.nio.HeapByteBuffer` | 726 | 39.7 KB | 81.4 MB |
| 11 | `org.apache.spark.sql.execution.columnar.ColumnBuilder[]` | 27 | 648 B | 79.9 MB |
| 12 | `org.apache.spark.sql.execution.columnar.StructColumnBuilder` | 27 | 1.1 KB | 77.9 MB |
| 13 | `java.lang.Object[]` | 30,286 | 2.5 MB | 12.4 MB |
| 14 | `java.util.zip.ZipFile$Source` | 181 | 14.1 KB | 12.3 MB |
| 15 | `java.lang.Class` | 18,498 | 188.2 KB | 9.8 MB |

### MAT — Biggest Top-Level Dominator Classes

| # | Class | Objects | Shallow | Retained |
|---|---|---:|---:|---:|
| 1 | `org.apache.spark.storage.memory.MemoryStore` | 1 | 56 B | 486.3 MB |
| 2 | `org.apache.spark.sql.columnar.CachedBatch[]` | 51 | 1.6 KB | 393.5 MB |
| 3 | `byte[]` | 339 | 162.3 MB | 162.3 MB |
| 4 | `org.apache.spark.util.UninterruptibleThread` | 128 | 14.0 KB | 89.9 MB |
| 5 | `java.util.zip.ZipFile$Source` | 181 | 14.1 KB | 12.3 MB |

### Our Report — Leak Suspects

## Leak Suspects

### Suspect 1: `java.lang.invoke.LambdaForm$MH+0x0000716c9580ac00`

- **Type**: Group of instances
- **Instances**: 257,864
- **Retained heap**: 1.16 GB (98.4% of total)
- **Shallow heap**: 1.15 GB

### Suspect 2: `java.util.concurrent.ThreadPoolExecutor$Worker`

- **Type**: Group of instances
- **Instances**: 322
- **Retained heap**: 174.8 MB (14.5% of total)
- **Shallow heap**: 17.6 KB

### Suspect 3: `org.apache.spark.util.UninterruptibleThread`

- **Type**: Group of instances
- **Instances**: 128
- **Retained heap**: 174.7 MB (14.5% of total)
- **Shallow heap**: 19.0 KB

### Suspect 4: `org.apache.spark.sql.catalyst.expressions.GeneratedClass$GeneratedIteratorForCodegenStage1`

- **Type**: Group of instances
- **Instances**: 83
- **Retained heap**: 162.3 MB (13.5% of total)
- **Shallow heap**: 7.1 KB

### Suspect 5: `org.apache.spark.sql.catalyst.expressions.GeneratedClass$SpecificColumnarIterator`

- **Type**: Group of instances
- **Instances**: 83
- **Retained heap**: 162.1 MB (13.5% of total)
- **Shallow heap**: 7.1 KB

### Suspect 6: `org.apache.spark.sql.execution.WholeStageCodegenEvaluatorFactory$WholeStageCodegenPartitionEvaluator$$anon$1`

- **Type**: Group of instances
- **Instances**: 83
- **Retained heap**: 120.9 MB (10.1% of total)
- **Shallow heap**: 1.3 KB

### MAT — Leak Suspects

System Overview
Problem Suspect 1
One instance of
org.apache.spark.storage.memory.MemoryStore
loaded by
java.net.URLClassLoader @ 0x80f000a0
occupies
509,972,304 (41.08%)
bytes. The top consumers of its minimum retained heap are
byte[]
(276 instances totaling 509,926,072),
java.lang.Object[]
(137 instances totaling 7,672), and
java.lang.Long
(286 instances totaling 6,864). The memory is accumulated in one instance of
java.util.LinkedHashMap
, loaded by
<system class loader>
, which occupies
509,970,584 (41.08%)
bytes.
Thread
org.apache.spark.util.UninterruptibleThread @ 0x821be608  Executor task launch worker for task 28.0 in stage 7.0 (TID 490)
has a local variable or reference to
org.apache.spark.storage.memory.MemoryStore @ 0x8e056348
which is on the shortest path to
java.util.LinkedHashMap @ 0x8e0696c8
. The thread
org.apache.spark.util.UninterruptibleThread @ 0x821be608  Executor task launch worker for task 28.0 in stage 7.0 (TID 490)
keeps local variables with total size
3,379,000 (0.27%)
bytes. The top consumers of its minimum retained heap are
byte[]
(276 instances totaling 509,926,072),
java.lang.Object[]
(137 instances totaling 7,672), and
java.lang.Long
(286 instances totaling 6,864).
Significant stack frames and local variables
org.apache.spark.storage.memory.MemoryStore.putIterator(Lorg/apache/spark/storage/BlockId;Lscala/collection/Iterator;Lscala/reflect/ClassTag;Lorg/apache/spark/memory/MemoryMode;Lorg/apache/spark/storage/memory/ValuesHolder;)Lscala/util/Either; (MemoryStore.scala:224)
org.apache.spark.storage.memory.MemoryStore @ 0x8e056348 retains 509,972,304 (41.08%) bytes
org.apache.spark.storage.memory.MemoryStore.putIteratorAsValues(Lorg/apache/spark/storage/BlockId;Lscala/collection/Iterator;Lorg/apache/spark/memory/MemoryMode;Lscala/reflect/ClassTag;)Lscala/util/Either; (MemoryStore.scala:302)
org.apache.spark.storage.memory.MemoryStore @ 0x8e056348 retains 509,972,304 (41.08%) bytes
The stacktrace of this Thread is available.
org.apache.spark.storage.memory.MemoryStore
java.net.URLClassLoader
java.util.LinkedHashMap
org.apache.spark.storage.memory.MemoryStore.putIterator(Lorg/apache/spark/storage/BlockId;Lscala/collection/Iterator;Lscala/reflect/ClassTag;Lorg/apache/spark/memory/MemoryMode;Lorg/apache/spark/storage/memory/ValuesHolder;)Lscala/util/Either;
MemoryStore.scala:224
org.apache.spark.storage.memory.MemoryStore.putIteratorAsValues(Lorg/apache/spark/storage/BlockId;Lscala/collection/Iterator;Lorg/apache/spark/memory/MemoryMode;Lscala/reflect/ClassTag;)Lscala/util/Either;

### MAT — Top Components (by classloader)

java.net.URLClassLoader @ 0x80f000a0 (84%)
<system class loader> (16%)

