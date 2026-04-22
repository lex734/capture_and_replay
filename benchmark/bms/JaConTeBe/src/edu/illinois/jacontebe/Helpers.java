package edu.illinois.jacontebe;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * Helper utilities for deadlock detection and monitoring.
 */
public class Helpers {

    /**
     * Starts a background deadlock monitor thread that detects deadlocks
     * and reports them via the JaConTeBe Reporter.
     */
    public static void startDeadlockMonitor() {
        Thread monitor = new Thread(new Runnable() {
            public void run() {
                ThreadMXBean bean = ManagementFactory.getThreadMXBean();
                while (true) {
                    long[] threadIds = bean.findDeadlockedThreads();
                    // if full synchronizer support isn't available, try monitor-only deadlocks
                    if (threadIds == null) {
                        try {
                            threadIds = bean.findMonitorDeadlockedThreads();
                        } catch (UnsupportedOperationException u) {
                            threadIds = null;
                        }
                    }
                    if (threadIds != null) {
                        System.out.println("=============Deadlock detected:==============");
                        ThreadInfo[] infos = bean.getThreadInfo(threadIds);
                        for (ThreadInfo info : infos) {
                            System.out.println(info);
                            System.out.println();
                        }
                        System.out.flush();
                        System.err.flush();
                        // report to the harness instead of abruptly exiting
                        Reporter.reportEnd(true);
                        return;
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "deadlock monitor");
        monitor.setDaemon(true);
        monitor.start();
    }
}
