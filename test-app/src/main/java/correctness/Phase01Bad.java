package correctness;

// Translated from: https://github.com/mc-imperial/sctbench/blob/d59ab26ddaedcd575ffb6a1f5e9711f7d6d2d9f2/benchmarks/concurrent-software-benchmarks/phase01_bad.c

import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bug: one thread leaves `x` locked, so the second copy of `thread1()` can deadlock
 * trying to take the same lock again.
 * Observe it when the benchmark prints `Deadlock detected`, throws `RuntimeException`,
 * or blocks at the BAD `x.lock()` site.
 */
public class Phase01Bad {
  
  static ReentrantLock x = new ReentrantLock();
  static ReentrantLock y = new ReentrantLock();
  static ReentrantLock z = new ReentrantLock();
  static int lockStatus = 0;

  static void thread1() {
    synchronized(z){};

    x.lock();
    if (lockStatus == 1) {
      System.out.println("Deadlock detected");
      throw new RuntimeException();
    }
    x.unlock();

    x.lock(); // BAD: deadlock
    if (lockStatus == 1) {
      System.out.println("Deadlock detected");
      throw new RuntimeException();
    }
    x.unlock();

    x.lock();
    lockStatus = 1;
    lockStatus = 0;
    x.unlock();

    y.lock();
    y.unlock();
    y.lock();
    y.unlock();
  }

  public static void main(String[] args) {
    
    Thread t1 = new Thread(() -> thread1());

    Thread t2 = new Thread(() -> thread1());

    t1.start();
    t2.start();

    try {
      t1.join();
      t2.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }
}

