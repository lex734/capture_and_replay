package common;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class IdentityMapper {
    // --- Thread Roles ---
    private static final ConcurrentHashMap<Long, Integer> tidToRoleId = new ConcurrentHashMap<>();
    private static final AtomicInteger roleCounter = new AtomicInteger(1);

    public static int getRoleIdBySite(long tid, int siteId) {
        // A thread is defined by the first Site ID it hits
        return tidToRoleId.computeIfAbsent(tid, k -> roleCounter.getAndIncrement());
    }

    // --- Object Identities ---
    // We map the SITE that discovered the object to a Logical ID.
    // This assumes that the same code location consistently interacts 
    // with the same logical object across runs.
    private static final ConcurrentHashMap<Integer, Integer> siteToObjectId = new ConcurrentHashMap<>();
    private static final AtomicInteger objectCounter = new AtomicInteger(1);

    /**
     * Returns the Logical ID for the object encountered at this site.
     */
    public static synchronized int getLogicalObjectId(int siteId) {
        // If this bytecode location has already "claimed" an object identity, return it.
        // Otherwise, this site is the "birthplace" of a new logical object identity.
        return siteToObjectId.computeIfAbsent(siteId, k -> objectCounter.getAndIncrement());
    }
    public static void reset() {
        tidToRoleId.clear();
        roleCounter.set(1);
        siteToObjectId.clear();
        objectCounter.set(1);
        System.out.println("[IdentityMapper] Maps reset for new run.");
    }
}
