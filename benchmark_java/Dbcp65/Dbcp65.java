import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.mockito.Matchers;
import org.apache.commons.dbcp.KeyGenerator;
import org.apache.commons.dbcp.PoolingConnection;
import org.apache.commons.pool.impl.GenericKeyedObjectPool;

/**
 * Bug: DBCP-65 (https://issues.apache.org/jira/browse/DBCP-65)
 * Deadlock between GenericKeyedObjectPool.evict() and
 * PoolingConnection.prepareStatement() when testWhileIdle=true.
 * Bug signal: "Deadlock detected" printed to stdout.
 */
public class Dbcp65 {

    private final PoolingConnection poolingConnection;
    private final GenericKeyedObjectPool genericObjectPool;

    public Dbcp65() throws Exception {
        Connection c = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(c.prepareStatement(Matchers.anyString())).thenReturn(ps);
        when(c.prepareStatement((String) Matchers.isNull())).thenReturn(ps);

        genericObjectPool = new GenericKeyedObjectPool();
        poolingConnection = new PoolingConnection(c, genericObjectPool);
        genericObjectPool.setFactory(poolingConnection);
        genericObjectPool.setTestWhileIdle(true);
        genericObjectPool.addObject(KeyGenerator.generateKey(poolingConnection));
    }

    public void run() {
        new Thread1().start();
        new Thread2().start();
    }

    private final class Thread1 extends Thread {
        private Thread1() {
            super("evict-thread");
        }

        @Override
        public void run() {
            try {
                genericObjectPool.evict();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private final class Thread2 extends Thread {
        private Thread2() {
            super("prepare-thread");
        }

        @Override
        public void run() {
            try {
                poolingConnection.prepareStatement("sql");
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    private static void startDeadlockMonitor() {
        ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
        Thread monitor = new Thread(() -> {
            while (true) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                long[] deadlocked = mxBean.findDeadlockedThreads();
                if (deadlocked != null) {
                    System.out.println("Deadlock detected");
                    System.exit(1);
                }
            }
        }, "deadlock-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    public static void main(String[] args) throws Exception {
        startDeadlockMonitor();
        new Dbcp65().run();
    }
}
