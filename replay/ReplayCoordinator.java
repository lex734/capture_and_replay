package replay;

import common.BinarySchema;
import common.IdentityMapper;

import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ReplayCoordinator {
    // Pre-sorted event array: each element is [seq, roleId, packedType, objSite,
    // objCount, data]
    private static long[][] sortedEvents;
    private static long totalEvents;
    private static final AtomicLong currentIdx = new AtomicLong(0);
    private static final Object controlLock = new Object();
    private static final AtomicLong releasedEpoch = new AtomicLong(0);

    // Roles seen in the trace that haven't checked in yet
    private static final Set<Integer> pendingRoles = ConcurrentHashMap.newKeySet();
    // Roles that have called awaitTurn at least once — they're alive in replay
    private static final Set<Integer> activeRoles = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> startedRoles = ConcurrentHashMap.newKeySet();

    public static void init(MappedByteBuffer traceBuffer, long count) {
        // Read all non-empty records from the buffer.
        // With batched slot allocation, records may not be contiguous —
        // zero-filled gaps exist where batch tails were unused.
        ArrayList<long[]> events = new ArrayList<>();
        long maxSlots = traceBuffer.capacity() / BinarySchema.RECORD_SIZE;

        for (long i = 0; i < maxSlots; i++) {
            int pos = (int) (i * BinarySchema.RECORD_SIZE);
            long seq = traceBuffer.getLong(pos);
            long roleId = traceBuffer.getLong(pos + 8);
            int packedType = traceBuffer.getInt(pos + 16);
            int objSite = traceBuffer.getInt(pos + 20);
            int objCount = traceBuffer.getInt(pos + 24);
            int data1 = traceBuffer.getInt(pos + 28);
            int data2 = traceBuffer.getInt(pos + 32);

            // Skip zero-filled slots (unused batch tails)
            if (seq == 0 && roleId == 0 && packedType == 0)
                continue;

            events.add(new long[] { seq, roleId, packedType, objSite, objCount, data1, data2, i });
        }

        // Sort: epoch first, then localSeq, then slot as true tie-breaker
        events.sort((a, b) -> {
            long epochA = a[0] >>> 32, epochB = b[0] >>> 32;
            if (epochA != epochB) return Long.compare(epochA, epochB);
            long seqA = a[0] & 0xFFFFFFFFL, seqB = b[0] & 0xFFFFFFFFL;
            if (seqA != seqB) return Long.compare(seqA, seqB);
            return Long.compare(a[7], b[7]); // slot = actual write order to buffer
        });
        
        sortedEvents = events.toArray(new long[0][]);
        totalEvents = sortedEvents.length;

        System.out.println("[ReplayCoordinator] Loaded and sorted " + totalEvents + " events.");

        // must not advance past their first event until they arrive.
        for (long[] event : sortedEvents) {
            pendingRoles.add((int) event[1]); // event[1] = roleId
        }
        System.out.println("[ReplayCoordinator] Expecting roles: " + pendingRoles);
    }

    // Returns the roleId of the earliest pending role in the trace
    public static int peekNextPendingRole() {
        for (long[] event : sortedEvents) {
            int roleId = (int) event[1];
            if (pendingRoles.contains(roleId))
                return roleId;
        }
        return -1;
    }

    // In ReplayCoordinator — called from IdentityMapper on first role assignment
    public static void checkIn(int roleId) {
        if (pendingRoles.contains(roleId)) {
            startedRoles.add(roleId);
            synchronized (controlLock) {
                System.out.println("[ReplayCoordinator] Role=" + roleId + " started (not yet scheduled).");
                controlLock.notifyAll();
            }
        }
    }

    private static void activateRole(int roleId) {
        if (startedRoles.remove(roleId)) {
            pendingRoles.remove(roleId);
            activeRoles.add(roleId);
            System.out.println("[ReplayCoordinator] Role=" + roleId + " active.");
        }
    }

    /**
     * Blocks the calling thread until its event matches the next expected event
     * in the captured total order.
     *
     * Parameters mirror the fields written by BinarySchema.write() during capture
     * (minus seq, which is only used for ordering):
     *
     * @param roleId     Resolved role ID for the current thread
     * @param packedType Event type + flags (same packing as BinarySchema)
     * @param objSite    Birth site of the target object (lock, field owner, array)
     * @param objCount   Birth count of the target object
     * @param data       Event-specific payload (siteId for sync, fieldId for
     *                   fields,
     *                   array index for arrays, return value / siteId for atomics)
     */
    public static void awaitTurn(int roleId, int packedType, int objSite, int objCount, int data) {
        activateRole(roleId);

        synchronized (controlLock) {
            while (true) {
                long idx = currentIdx.get();
                if (idx >= totalEvents)
                    return;

                long[] expected = sortedEvents[(int) idx];
                int expectedRole = (int) expected[1];
                int expectedType = (int) expected[2];

                // Block advancing past an event whose role hasn't checked in yet —
                // that thread exists in the trace but hasn't started in replay yet.
                // Give it time to start rather than deadlocking immediately.
                if (pendingRoles.contains(expectedRole)) {
                    // Truly missing — never started, deadlock
                    System.err.println("[Replay DEADLOCK] Role=" + expectedRole + " never started.");
                    currentIdx.incrementAndGet();
                    controlLock.notifyAll();
                    continue;
                }

                if (startedRoles.contains(expectedRole)) {
                    // Thread exists but hasn't been scheduled yet — wait patiently
                    // MUST release lock so the thread can call awaitTurn when scheduled
                    try { controlLock.wait(10); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    continue;
                }
                // Normal strict total-order match
                if (roleId == expectedRole && packedType == expectedType) {
                    System.out.println("ReplayCoordinator.awaitTurn: Processed event idx=" + idx + ", roleId=" + roleId + ", packedType=" + packedType + ", objSite=" + objSite + ", objCount=" + objCount + ", data=" + data);
                    if (isReleaseEvent(expectedType))
                        releasedEpoch.set(expected[0] >>> 32);
                    currentIdx.incrementAndGet();
                    controlLock.notifyAll();
                    return;
                }

                // Always print mismatch — remove the time condition so we see it immediately
                System.err.println("[Replay Blocked]"
                        + " idx=" + idx
                        + " wants Role=" + expectedRole + " Type=" + expectedType
                        + " (eventType=" + (expectedType & 0xFF) + " flags=" + (expectedType >> 8) + ")"
                        + " got Role=" + roleId + " Type=" + packedType
                        + " (eventType=" + (packedType & 0xFF) + " flags=" + (packedType >> 8) + ")");

                try {
                    controlLock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public static int awaitTurnInt(int roleId, int packedType, int currentSiteId) {
        synchronized (controlLock) {
            while (true) {
                long idx = currentIdx.get();
                if (idx >= totalEvents)
                    return 0;
                long[] expected = sortedEvents[(int) idx];

                if (roleId == (int) expected[1] && packedType == (int) expected[2]) {
                    int returnValue = (int) expected[5];
                    System.out.println("ReplayCoordinator.awaitTurnInt: Processed event idx=" + idx + ", roleId=" + roleId + ", packedType=" + packedType + ", currentSiteId=" + currentSiteId + ", returnValue=" + returnValue);
                    if (isReleaseEvent(packedType))
                        releasedEpoch.set(expected[0] >>> 32);
                    currentIdx.incrementAndGet();
                    controlLock.notifyAll();
                    return returnValue;
                }
                try {
                    controlLock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 0;
                }
            }
        }
    }

    public static long awaitTurnLong(int roleId, int packedType, int currentSiteId) {
        synchronized (controlLock) {
            while (true) {
                long idx = currentIdx.get();
                if (idx >= totalEvents)
                    return 0L;
                long[] expected = sortedEvents[(int) idx];

                if (roleId == (int) expected[1] && packedType == (int) expected[2]) {
                    long high = (long) expected[5] << 32;
                    long low = expected[6] & 0xFFFFFFFFL;
                    long returnValue = high | low;
                    System.out.println("ReplayCoordinator.awaitTurnLong: Processed event idx=" + idx + ", roleId=" + roleId + ", packedType=" + packedType + ", currentSiteId=" + currentSiteId + ", returnValue=" + returnValue);
                    if (isReleaseEvent(packedType))
                        releasedEpoch.set(expected[0] >>> 32);
                    currentIdx.incrementAndGet();
                    controlLock.notifyAll();
                    return returnValue;
                }
                try {
                    controlLock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 0L;
                }
            }
        }
    }

    public static Object awaitTurnObj(int roleId, int packedType, int currentSiteId) {
        synchronized (controlLock) {
            while (true) {
                long idx = currentIdx.get();
                if (idx >= totalEvents)
                    return null;
                long[] expected = sortedEvents[(int) idx];

                if (roleId == (int) expected[1] && packedType == (int) expected[2]) {
                    int valueSiteId = (int) expected[5];
                    int valueCount = (int) expected[6];
                    Object returnValue = IdentityMapper.resolveByBirthId(valueSiteId, valueCount);
                    System.out.println("ReplayCoordinator.awaitTurnObj: Processed event idx=" + idx + ", roleId=" + roleId + ", packedType=" + packedType + ", currentSiteId=" + currentSiteId + ", valueSiteId=" + valueSiteId + ", valueCount=" + valueCount + ", returnValue=" + returnValue);
                    if (isReleaseEvent(packedType))
                        releasedEpoch.set(expected[0] >>> 32);
                    currentIdx.incrementAndGet();
                    controlLock.notifyAll();
                    return returnValue;
                }
                try {
                    controlLock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    private static boolean isReleaseEvent(int packedType) {
        int eventType = packedType & 0xFF;
        return eventType == BinarySchema.Event.MONITOR_EXIT
                || eventType == BinarySchema.Event.THREAD_START
                || eventType == BinarySchema.Event.THREAD_NOTIFY
                || eventType == BinarySchema.Event.THREAD_NOTIFY_ALL
                || eventType == BinarySchema.Event.THREAD_UNPARK
                || eventType == BinarySchema.Event.THREAD_INTERRUPT
                || eventType == BinarySchema.Event.THREAD_WAKEUP
                || eventType == BinarySchema.Event.CLASS_INIT_END
                || eventType == BinarySchema.Event.ATOMIC_WRITE
                || eventType == BinarySchema.Event.ATOMIC_RMW;
    }
}
