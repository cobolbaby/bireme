/**
 * Copyright HashData. All Rights Reserved.
 */

package cn.hashdata.bireme;

import cn.hashdata.bireme.pipeline.PipeLine;
import com.codahale.metrics.Timer;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

/**
 * {@code ChangeLoader} poll tasks and load the tasks to database. Each {@code ChangeLoader}
 * corresponds to a specific table in a {@code PipeLine}. All {@code ChangeLoaders} share
 * connections to the database.
 *
 * @author yuze
 */
public class ChangeLoader implements Callable<Long> {
    protected static final Long DELETE_TIMEOUT_NS = 10000000000L;
    protected static final Long NANOSECONDS_TO_SECONDS = 1000000000L;
    private final Timer copyForDeleteTimer;
    private final Timer deleteTimer;
    private final Timer copyForInsertTimer;
    public Logger logger;
    public String mappedTable;
    protected boolean optimisticMode = true;
    protected Context cxt;
    protected Config conf;
    protected Connection conn;
    protected LinkedBlockingQueue<Future<LoadTask>> taskIn;
    protected Table table;
    protected LoadTask currentTask;
    protected ExecutorService copyThread;
    private Timer.Context timerCTX;

    /**
     * Create a new {@code ChangeLoader}.
     *
     * @param cxt         the Bireme Context
     * @param pipeLine    the {@code PipeLine} belongs to
     * @param mappedTable the target table
     * @param taskIn      a queue to get {@code LoadTask}
     */
    public ChangeLoader(Context cxt, PipeLine pipeLine, String mappedTable,
                        LinkedBlockingQueue<Future<LoadTask>> taskIn) {
        this.cxt = cxt;
        this.conf = cxt.conf;
        this.conn = null;
        this.mappedTable = mappedTable;
        this.table = cxt.tablesInfo.get(mappedTable);
        this.taskIn = taskIn;
        this.copyThread = Executors.newFixedThreadPool(1, new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = Executors.defaultThreadFactory().newThread(r);
                t.setDaemon(true);
                return t;
            }
        });

        // add statistics
        Timer[] timers = pipeLine.stat.addTimerForLoader(mappedTable);
        copyForDeleteTimer = timers[0];
        deleteTimer = timers[1];
        copyForInsertTimer = timers[2];

        logger = pipeLine.logger;
    }

    /**
     * Get the task and copy it to target database
     *
     * @return if normally end, return 0
     * @throws BiremeException      load exception
     * @throws InterruptedException interrupted when load the task
     */
    @Override
    public Long call() throws BiremeException, InterruptedException {
        // 如果有很多数据堆积的话，则该线程继续工作，一次性把所有待做的工作全做完
        // 在不能执行导入的时候，则Loader线程主动退出，退位让贤。。。
        // 等待下一次该线程被调度的之后，继续往下执行
        while (!cxt.stop) {
            // get task
            if (currentTask == null) {
                currentTask = pollTask();
            }

            // 如果currentTask为空，那下一轮线程调度的时候再执行
            if (currentTask == null) {
                break;
            }

            // get connection
            conn = getConnection();
            if (conn == null) {
                logger.error("Unable to get Connection.");
                break;
            }

            // Execute task and release connection. If failed, close the connection and abandon it.
            try {
                executeTask();
                releaseConnection();
            } catch (BiremeException e) {
                logger.error("Fail to execute task. Message: {}", e.getMessage());

                try {
                    conn.rollback();
                    conn.close();
                } catch (Exception ee) {
                    logger.error("Fail to roll back after load exception. Message: {}", ee.getMessage());
                }
                throw e;

            } finally {
                currentTask.destory();
                currentTask = null;
                conn = null;
            }
        }
        return 0L;
    }

    /**
     * Check whether {@code Rows} have been merged to a task. If done, poll the task and return.
     *
     * @return a task need be loaded to database
     * @throws BiremeException      merge task failed
     * @throws InterruptedException if the current thread was interrupted while waiting
     */
    protected LoadTask pollTask() throws BiremeException, InterruptedException {
        LoadTask task = null;
        Future<LoadTask> head = taskIn.peek();

        if (head != null && head.isDone()) {
            taskIn.remove();

            try {
                task = head.get();
            } catch (ExecutionException e) {
                throw new BiremeException("Merge task failed.", e.getCause());
            }
        }

        return task;
    }

    /**
     * Get connection to the destination database from connection pool.
     *
     * @return the connection
     * @throws BiremeException when unable to create temporary table
     */
    protected Connection getConnection() throws BiremeException {
        Connection connection = cxt.loaderConnections.poll();
        if (connection == null) {
            String message = "Unable to get Connection.";
            logger.fatal(message);
            throw new BiremeException(message);
        }

        HashSet<String> temporaryTables = cxt.temporaryTables.get(connection);

        if (!temporaryTables.contains(mappedTable)) {
            createTemporaryTable(connection);
            temporaryTables.add(mappedTable);
        }
        return connection;
    }

    /**
     * Return the connection to connection pool.
     */
    protected void releaseConnection() {
        cxt.loaderConnections.offer(conn);
        conn = null;
    }

    /**
     * Load the task to destination database. First load the delete set and then load the insert set.
     *
     * @throws BiremeException      Wrap the exception when load the task
     * @throws InterruptedException if interrupted while waiting
     */
    protected void executeTask() throws BiremeException, InterruptedException {
        if (!currentTask.delete.isEmpty() || (!optimisticMode && !currentTask.insert.isEmpty())) {
            int size = currentTask.delete.size();

            if (!optimisticMode) {
                currentTask.delete.addAll(currentTask.insert.keySet());
            }

            if (executeDelete(currentTask.delete) <= size && optimisticMode == false) {
                optimisticMode = true;

                logger.info("Chang to optimistic mode.");
            }
        }

        if (!currentTask.insert.isEmpty()) {
            HashSet<String> insertSet = new HashSet<String>();
            insertSet.addAll(currentTask.insert.values());
            executeInsert(insertSet);
        }

        try {
            conn.commit();
        } catch (SQLException e) {
            String message = "commit failed.";
            throw new BiremeException(message, e);
        }

        for (CommitCallback callback : currentTask.callbacks) {
            callback.done();
        }
    }

    private Long executeDelete(Set<String> delete) throws BiremeException, InterruptedException {
        long deleteCounts;
        ArrayList<String> keyNames = table.keyNames;
        String temporaryTableName = getTemporaryTableName();

        timerCTX = copyForDeleteTimer.time();
        copyWorker(temporaryTableName, keyNames, delete);
        timerCTX.stop();

        timerCTX = deleteTimer.time();
        deleteCounts = deleteWorker(mappedTable, temporaryTableName, keyNames);

        long deleteTime = timerCTX.stop();
        if (deleteTime > DELETE_TIMEOUT_NS) {
            String plan = deletePlan(mappedTable, temporaryTableName, keyNames);

            logger.warn("Delete operation takes {} seconds, delete plan:\n {}",
                    deleteTime / NANOSECONDS_TO_SECONDS, plan);
        }

        return deleteCounts;
    }

    private void executeInsert(Set<String> insertSet) throws BiremeException, InterruptedException {
        ArrayList<String> columnList = table.columnName;

        timerCTX = copyForInsertTimer.time();
        try {
            copyWorker(mappedTable, columnList, insertSet);
        } catch (BiremeException e) {
            // 如果了乐观处理没成功，则只能先删后新增了
            if (e.getCause().getMessage().contains("duplicate key value") && optimisticMode) {
                try {
                    conn.rollback();
                } catch (SQLException ignore) {
                }

                optimisticMode = false;
                
                logger.info("Chang to passimistic mode.");

                executeDelete(currentTask.insert.keySet());
                executeInsert(insertSet);
            } else {
                throw e;
            }
        }
        timerCTX.stop();
    }

    private Long copyWorker(String tableName, ArrayList<String> columnList, Set<String> tuples)
            throws BiremeException, InterruptedException {
        Future<Long> copyResult;
        long copyCount = -1L;
        PipedOutputStream pipeOut = new PipedOutputStream();
        PipedInputStream pipeIn = null;
        BiremeException temp = null;

        try {
            pipeIn = new PipedInputStream(pipeOut);
        } catch (IOException e) {
            throw new BiremeException("I/O error occurs while create PipedInputStream.", e);
        }

        String sql = getCopySql(tableName, columnList);
        copyResult = copyThread.submit(new TupleCopyer(pipeIn, sql, conn));

        try {
            tupleWriter(pipeOut, tuples);
        } catch (BiremeException e) {
            temp = e;
        }

        try {
            while (!copyResult.isDone() && !cxt.stop) {
                Thread.sleep(5);
            }
            copyCount = copyResult.get();
        } catch (ExecutionException e) {
            logger.error("Copy failed. Message: {}", e.getMessage());
            throw new BiremeException("Copy failed.", e.getCause());
        }

        if (temp != null) {
            throw temp;
        }

        return copyCount;
    }

    private String getCopySql(String tableName, List<String> columnList) {
        StringBuilder sb =
                new StringBuilder()
                        .append("COPY ")
                        .append(tableName)
                        .append(" (")
                        .append(StringUtils.join(columnList, ","))
                        .append(") FROM STDIN WITH DELIMITER '|' NULL '' CSV QUOTE '\"' ESCAPE E'\\\\';");
        String sql = sb.toString();
        return sql;
    }

    private Long deleteWorker(String table, String tmpTable, ArrayList<String> columnList)
            throws BiremeException {
        StringBuilder sb = new StringBuilder();
        Long count = 0L;

        for (int i = 0; i < columnList.size(); i++) {
            if (i != 0) {
                sb.append(" and ");
            }

            sb.append(table + "." + columnList.get(i) + "=" + tmpTable + "." + columnList.get(i));
        }

        String sql = "DELETE FROM " + table + " WHERE EXISTS (SELECT 1 FROM " + tmpTable + " WHERE "
                + sb.toString() + ");";

        try {
            count = (long) conn.createStatement().executeUpdate(sql);
        } catch (SQLException e) {
            throw new BiremeException("Delete failed.", e);
        }

        return count;
    }

    private String deletePlan(String table, String tmpTable, ArrayList<String> columnList)
            throws BiremeException {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < columnList.size(); i++) {
            if (i != 0) {
                sb.append(" and ");
            }

            sb.append(table + "." + columnList.get(i) + "=" + tmpTable + "." + columnList.get(i));
        }

        String sql = "EXPLAIN DELETE FROM " + table + " WHERE EXISTS (SELECT 1 FROM " + tmpTable
                + " WHERE " + sb.toString() + ");";

        try {
            ResultSet rs = conn.createStatement().executeQuery(sql);

            if (!rs.wasNull()) {
                sb.setLength(0);

                while (rs.next()) {
                    sb.append(rs.getString(1) + "\n");
                }

                return sb.toString();

            } else {
                return "Can not get plan.";
            }

        } catch (SQLException e) {
            throw new BiremeException("Fail to get delete plan.", e);
        }
    }

    private void tupleWriter(PipedOutputStream pipeOut, Set<String> tuples) throws BiremeException {
        byte[] data = null;

        try {
            Iterator<String> iterator = tuples.iterator();

            while (iterator.hasNext() && !cxt.stop) {
                data = iterator.next().getBytes(StandardCharsets.UTF_8);
                pipeOut.write(data);
            }

            pipeOut.flush();
        } catch (IOException e) {
            throw new BiremeException("I/O error occurs while write to pipe.", e);
        } finally {
            try {
                pipeOut.close();
            } catch (IOException ignore) {
            }
        }
    }

    private String getTemporaryTableName() {
        return mappedTable.replace('.', '_');
    }

    private void createTemporaryTable(Connection conn) throws BiremeException {
        String sql = "CREATE TEMP TABLE " + getTemporaryTableName()
                + " ON COMMIT DELETE ROWS AS SELECT * FROM " + mappedTable + " LIMIT 0;";

        try {
            conn.createStatement().executeUpdate(sql);
            conn.commit();
        } catch (SQLException e) {
            throw new BiremeException("Fail to create tmporary table.", e);
        }
    }

    private class TupleCopyer implements Callable<Long> {
        PipedInputStream pipeIn;
        String sql;
        Connection conn;

        public TupleCopyer(PipedInputStream pipeIn, String sql, Connection conn) {
            this.pipeIn = pipeIn;
            this.sql = sql;
            this.conn = conn;
        }

        @Override
        public Long call() throws SQLException, IOException {
            try {
                CopyManager mgr = new CopyManager((BaseConnection) conn);
                return mgr.copyIn(sql, pipeIn);
            } finally {
                try {
                    pipeIn.close();
                } catch (IOException ignore) {
                }
            }
        }
    }
}
