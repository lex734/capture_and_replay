package org.apache.commons.dbcp;

public class KeyGenerator {
    public static PoolingConnection.PStmtKey generateKey(PoolingConnection connection) {
        return connection.new PStmtKey("sql");
    }
}
