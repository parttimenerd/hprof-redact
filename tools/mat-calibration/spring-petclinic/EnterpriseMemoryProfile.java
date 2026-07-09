/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 *
 * EnterpriseMemoryProfile — patched into Spring Petclinic by setup.sh.
 *
 * Allocates an enterprise-scale memory profile on application startup:
 *
 *   1. A second-level cache populated with synthetic Owner/Pet/Visit aggregates
 *      (mimics what Hibernate would hold on a busy production node).
 *   2. A request-history ring-buffer of recent inbound DTOs (mimics observability /
 *      tracing buffers; lots of small String-heavy objects with cross-references).
 *   3. A bean-graph snapshot (mimics typical Spring AOP proxy + bean factory state).
 *   4. A blob region of varying-size byte[] arrays (mimics file uploads, serialised
 *      payloads — exercises MAT's primitive-array sizing path).
 *
 * Configurable via -D system properties (overridable on the command line):
 *
 *   -Dprofile.ownerCount=N        owners cached                    (default 20_000)
 *   -Dprofile.petsPerOwner=N      pets per owner                   (default 4)
 *   -Dprofile.visitsPerPet=N      visits per pet                   (default 6)
 *   -Dprofile.requestHistory=N    DTOs retained in ring buffer     (default 100_000)
 *   -Dprofile.blobCount=N         number of blob payloads          (default 5_000)
 *   -Dprofile.blobAvgBytes=N      avg blob size                    (default 16384)
 *   -Dprofile.aopProxies=N        synthetic AOP proxy objects      (default 5_000)
 *
 * Defaults target ~2 GB resident heap. Scale 'ownerCount' up to reach larger targets.
 *
 * After population the bean signals readiness via a marker file
 * ${profile.readyFile:-/tmp/petclinic-memory-profile-ready} so the capture script
 * knows when to dump.
 */
package org.springframework.samples.petclinic.memprofile;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class EnterpriseMemoryProfile {

    private static final int OWNER_COUNT       = Integer.getInteger("profile.ownerCount", 20_000);
    private static final int PETS_PER_OWNER    = Integer.getInteger("profile.petsPerOwner", 4);
    private static final int VISITS_PER_PET    = Integer.getInteger("profile.visitsPerPet", 6);
    private static final int REQUEST_HISTORY   = Integer.getInteger("profile.requestHistory", 100_000);
    private static final int BLOB_COUNT        = Integer.getInteger("profile.blobCount", 5_000);
    private static final int BLOB_AVG_BYTES    = Integer.getInteger("profile.blobAvgBytes", 16_384);
    private static final int AOP_PROXIES       = Integer.getInteger("profile.aopProxies", 5_000);
    private static final String READY_FILE     = System.getProperty("profile.readyFile",
            "/tmp/petclinic-memory-profile-ready");

    private final Map<Long, CachedOwner> ownerCache = new HashMap<>();
    private final Deque<RequestEvent> requestHistory = new ArrayDeque<>();
    private final List<byte[]> blobs = new ArrayList<>();
    private final List<ProxyHolder> proxies = new ArrayList<>();

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() throws IOException {
        long t0 = System.currentTimeMillis();
        populateOwnerCache();
        populateRequestHistory();
        populateBlobs();
        populateProxies();

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) >>> 20;
        System.out.printf("[EnterpriseMemoryProfile] populated in %.1f s, used heap = %d MB%n",
                (System.currentTimeMillis() - t0) / 1000.0, usedMb);

        // Marker file for the capture script
        Files.writeString(Path.of(READY_FILE),
                "owners=" + OWNER_COUNT + " history=" + REQUEST_HISTORY + " usedMb=" + usedMb + "\n");
    }

    private void populateOwnerCache() {
        for (long i = 0; i < OWNER_COUNT; i++) {
            List<CachedPet> pets = new ArrayList<>(PETS_PER_OWNER);
            for (int p = 0; p < PETS_PER_OWNER; p++) {
                List<CachedVisit> visits = new ArrayList<>(VISITS_PER_PET);
                for (int v = 0; v < VISITS_PER_PET; v++) {
                    visits.add(new CachedVisit(
                            i * 1000L + p * 100L + v,
                            "Visit description for owner " + i + " pet " + p + " visit " + v,
                            ThreadLocalRandom.current().nextLong()));
                }
                pets.add(new CachedPet(i * 10L + p, "Pet-" + i + "-" + p, "Species-" + (p % 5), visits));
            }
            ownerCache.put(i, new CachedOwner(
                    i,
                    "Owner-" + i,
                    "Address line 1 of owner " + i + ", city " + (i % 200),
                    "+1-555-" + String.format("%07d", i % 10_000_000L),
                    pets));
        }
    }

    private void populateRequestHistory() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < REQUEST_HISTORY; i++) {
            requestHistory.addLast(new RequestEvent(
                    System.currentTimeMillis() - rng.nextInt(86_400_000),
                    "/api/owners/" + rng.nextInt(OWNER_COUNT) + "/pets",
                    rng.nextInt(500) < 480 ? "200 OK" : "500 Internal Server Error",
                    "session-" + rng.nextLong()));
        }
    }

    private void populateBlobs() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < BLOB_COUNT; i++) {
            int size = BLOB_AVG_BYTES / 2 + rng.nextInt(BLOB_AVG_BYTES);
            byte[] data = new byte[size];
            rng.nextBytes(data);
            blobs.add(data);
        }
    }

    private void populateProxies() {
        for (int i = 0; i < AOP_PROXIES; i++) {
            proxies.add(new ProxyHolder(
                    "ProxyTarget-" + i,
                    new String[] {"transactional", "cacheable", "logged"},
                    new InterceptorChain(3)));
        }
    }

    // --- simple data classes mimicking common Spring/JPA patterns ---

    static final class CachedOwner {
        long id;
        String name;
        String address;
        String phone;
        List<CachedPet> pets;
        CachedOwner(long id, String n, String a, String p, List<CachedPet> pets) {
            this.id = id; this.name = n; this.address = a; this.phone = p; this.pets = pets;
        }
    }

    static final class CachedPet {
        long id;
        String name;
        String species;
        List<CachedVisit> visits;
        CachedPet(long id, String n, String s, List<CachedVisit> v) {
            this.id = id; this.name = n; this.species = s; this.visits = v;
        }
    }

    static final class CachedVisit {
        long id;
        String description;
        long timestamp;
        CachedVisit(long id, String d, long t) {
            this.id = id; this.description = d; this.timestamp = t;
        }
    }

    static final class RequestEvent {
        long timestamp;
        String path;
        String status;
        String sessionId;
        RequestEvent(long t, String p, String s, String sid) {
            this.timestamp = t; this.path = p; this.status = s; this.sessionId = sid;
        }
    }

    static final class ProxyHolder {
        String targetName;
        String[] advisors;
        InterceptorChain chain;
        ProxyHolder(String n, String[] a, InterceptorChain c) {
            this.targetName = n; this.advisors = a; this.chain = c;
        }
    }

    static final class InterceptorChain {
        Interceptor[] interceptors;
        InterceptorChain(int n) {
            interceptors = new Interceptor[n];
            for (int i = 0; i < n; i++) interceptors[i] = new Interceptor("interceptor-" + i);
        }
    }

    static final class Interceptor {
        String name;
        Interceptor(String n) { this.name = n; }
    }
}
