import java.util.concurrent.atomic.AtomicInteger;

/**
 * Divergence test workload: exercises the replay divergence-detection mechanism.
 *
 * HOW DIVERGENCE IS INDUCED
 * -------------------------
 * During replay, CAS operations are *injected* (the real compareAndSet is never
 * executed), so the AtomicInteger's in-memory value is not updated by the CAS.
 *
 *   Capture timeline:
 *     T1: compareAndSet(0 → 5) succeeds  →  shared = 5
 *     T2: getAndAdd(1) returns 5          →  shared = 6
 *
 *   Replay timeline:
 *     T1: CAS result injected (true),  but shared stays at 0  (CAS not run)
 *     T2: getAndAdd(1) executes for real  →  returns 0  (shared was still 0)
 *         trace expected 5  →  RMW value mismatch  →  reportDivergence()
 *
 * Expected output on replay:
 *   [DIVERGENCE] Replay has structurally diverged: role=... RMW value mismatch ...
 *   [DIVERGENCE] Synchronization and injection will no longer be applied.
 *
 * T1 is fully joined before T2 starts so the ordering is deterministic and the
 * trace captures the exact same sequencing both times. The divergence is purely
 * due to the CAS side-effect not being applied during replay.
 */
public class WorkloadDivergence {

    static AtomicInteger shared = new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {

        // T1: CAS that writes a value the system won't actually apply in replay.
        Thread t1 = new Thread(() -> {
            boolean won = shared.compareAndSet(0, 5);
            System.out.println("[T1] CAS result: " + won + "  (shared should now be 5)");
        }, "divergence-t1");

        t1.start();
        t1.join(); // deterministic: T2 never starts until T1's CAS is done

        // T2: RMW that executes for real. Capture recorded 5; replay sees 0.
        Thread t2 = new Thread(() -> {
            int before = shared.getAndAdd(1);
            System.out.println("[T2] getAndAdd returned: " + before
                    + "  (capture saw 5, replay will see 0 → divergence)");
        }, "divergence-t2");

        t2.start();
        t2.join();

        System.out.println("[Main] final shared = " + shared.get());
    }
}
