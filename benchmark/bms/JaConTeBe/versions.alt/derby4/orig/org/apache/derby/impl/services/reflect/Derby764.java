package org.apache.derby.impl.services.reflect;

import org.apache.derby.iapi.error.StandardException;
import org.apache.derby.iapi.services.locks.Lockable;
import org.apache.derby.iapi.services.locks.ShExQual;
import org.apache.derby.impl.services.locks.LockOperator;
import org.apache.derby.impl.services.locks.SinglePool;

import edu.illinois.jacontebe.Helpers;
import edu.illinois.jacontebe.framework.Reporter;

/**
 * Bug URL: https://issues.apache.org/jira/browse/DERBY-764
 * This is a deadlock bug.
 * Reproduce environment: junit 4, derby 10.5.1.1, JDK 1.6.0_33
 * 
 * @author Ziyi Lin
 * 
 */

public class Derby764 {

    private static class Thread1 extends Thread {
        public void run() {
            try {
                updateLoader.modifyJar(false);
            } catch (StandardException e) {
                e.printStackTrace();
            }
        }
    }

    private static class Thread2 extends Thread {
        public void run() {

            try {
                operator.unlock();
            } catch (StandardException e) {
                e.printStackTrace();
            }
        }
    }

    private static UpdateLoader updateLoader;
    private static SinglePool factory;
    private static LockOperator operator;

    public static void main(String[] args) throws Exception {
        factory = new SinglePool();

        String classpath = "org/class";

        DatabaseClasses parent = new ReflectClassesJava2();
        updateLoader = newUpdateLoaderForHarness(classpath, parent, factory);
        Object qualifier = ShExQual.EX;
        Lockable classloaderLock = new ClassLoaderLock(updateLoader);
        operator = new LockOperator(factory, classloaderLock, qualifier);
        operator.lock();
        Reporter.reportStart("derby764", 0, "deadlock");
        startDeadlockMonitor();

        // If test comes to this line, it means no deadlock happens.So we need
        // to report the failure of bug reproduction.
        try {
            run();
        } catch (Exception e) {

        }
        Reporter.reportEnd(false);
    }

    private static UpdateLoader newUpdateLoaderForHarness(String classpath,
            DatabaseClasses parent, SinglePool factory) throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        UpdateLoader loader = (UpdateLoader) unsafe.allocateInstance(UpdateLoader.class);
        setField(loader, "normalizeToUpper", Boolean.FALSE);
        setField(loader, "parent", parent);
        setField(loader, "lf", factory);
        setField(loader, "compat", factory.createCompatibilitySpace(loader));
        setField(loader, "myLoader", Derby764.class.getClassLoader());
        setField(loader, "classLoaderLock", new ClassLoaderLock(loader));
        setField(loader, "jarList", new JarLoader[0]);
        setField(loader, "thisClasspath", classpath);
        setField(loader, "initDone", Boolean.FALSE);
        setField(loader, "needReload", Boolean.FALSE);
        return loader;
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void startDeadlockMonitor() {
        Helpers.startDeadlockMonitor();
    }

    private static void run() throws InterruptedException {
        Thread th1 = new Thread1();
        Thread th2 = new Thread2();

        th1.start();
        th2.start();
        th1.join();
        th2.join();
    }
}
