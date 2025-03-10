/**
 * Copyright HashData. All Rights Reserved.
 */

package cn.hashdata.bireme;

import cn.hashdata.bireme.pipeline.*;
import cn.hashdata.bireme.pipeline.PipeLine.PipeLineState;
import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import org.apache.commons.cli.*;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.daemon.Daemon;
import org.apache.commons.daemon.DaemonContext;
import org.apache.commons.daemon.DaemonInitException;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * {@code Bireme} is an incremental synchronization tool. It could sync update in MySQL to GreenPlum
 * / Hashdata database.
 *
 * @author yuze
 */
public class Bireme implements Daemon {
    protected static final Long TIMEOUT_MS = 1000L;
    private static final String DEFAULT_CONFIG_FILE = "etc/config.properties";
    private DaemonContext context;
    private Context cxt;
    
    private Logger logger = LogManager.getLogger(Bireme.class);
    private ConsoleReporter consoleReporter;
    private JmxReporter jmxReporter;

    public static void main(String[] args) {
        Bireme service = new Bireme();
        service.entry(args);
    }

    protected void parseCommandLine(String[] args)
            throws DaemonInitException, ConfigurationException, BiremeException {
        Option help = new Option("help", "print this message");
        Option configFile =
                Option.builder("config_file").hasArg().argName("file").desc("config file location").build();

        Options opts = new Options();
        opts.addOption(help);
        opts.addOption(configFile);
        
        CommandLine cmd = null;

        try {
            CommandLineParser parser = new DefaultParser();
            CommandLine cmd = parser.parse(opts, args);
            if (cmd.hasOption("help")) {
                throw new ParseException("print help message");
            }
        } catch (ParseException e) {
            HelpFormatter formatter = new HelpFormatter();
            StringWriter out = new StringWriter();
            PrintWriter writer = new PrintWriter(out);
            formatter.printHelp(writer, formatter.getWidth(), "Bireme", null, opts,
                    formatter.getLeftPadding(), formatter.getDescPadding(), null, true);
            writer.flush();
            throw new DaemonInitException(out.toString());
        }

        String config = cmd.getOptionValue("config_file", DEFAULT_CONFIG_FILE);

        cxt = new Context(new Config(config));
    }

    /**
     * Get metadata about the table from target database.
     *
     * @throws BiremeException when fail to connect or get the metadata
     */
    protected void getTableInfo() throws BiremeException {
        logger.info("Start getting metadata of target tables from target database.");

        Map<String, List<String>> tableInfoMap = null;

        String[] strArray;
        Connection conn = BiremeUtility.jdbcConn(cxt.conf.targetDatabase);

        try {
            tableInfoMap = GetPrimaryKeys.getPrimaryKeys(cxt.tableMap, conn);
        } catch (Exception e) {
            throw new BiremeException("GetPrimaryKeys.getPrimaryKeys failed.", e);
        }

        for (String fullname : cxt.tableMap.values()) {
            if (cxt.tablesInfo.containsKey(fullname)) {
                continue;
            }
            cxt.tablesInfo.put(fullname, new Table(fullname, tableInfoMap, conn));
        }

        try {
            conn.close();
        } catch (SQLException ignore) {
        }

        logger.info("Finish getting metadata of target tables from target database.");
    }

    /**
     * Establish connections to the target database.
     *
     * @throws BiremeException fail to connect
     */
    protected void initLoaderConnections() throws BiremeException {
        logger.info("Start establishing connections for loaders.");

        LinkedBlockingQueue<Connection> conns = cxt.loaderConnections;
        HashMap<Connection, HashSet<String>> temporaryTables = cxt.temporaryTables;

        try {
            for (int i = 0, number = cxt.conf.loader_conn_size; i < number; i++) {
                Connection conn = BiremeUtility.jdbcConn(cxt.conf.targetDatabase);
                conn.setAutoCommit(true);
                Statement stmt = conn.createStatement();

                stmt.execute("set enable_nestloop = on;");
                stmt.execute("set enable_seqscan = off;");
                stmt.execute("set enable_hashjoin = off;");

                try {
                    stmt.execute("set gp_autostats_mode = none;");
                } catch (SQLException ignore) {
                }

                conn.setAutoCommit(false);
                conns.add(conn);
                temporaryTables.put(conn, new HashSet<String>());
            }
        } catch (SQLException e) {
            for (Connection closeConn : temporaryTables.keySet()) {
                try {
                    closeConn.close();
                } catch (SQLException ignore) {
                }
            }

            throw new BiremeException("Could not establish connection to target database.", e);
        }

        logger.info("Finishing establishing {} connections for loaders.", cxt.conf.loader_conn_size);
    }

    protected void closeConnections() throws SQLException {
        for (Connection conn : cxt.loaderConnections) {
            conn.close();
        }
    }

    protected void createPipeLine() throws BiremeException {
        for (SourceConfig conf : cxt.conf.sourceConfig.values()) {
            switch (conf.type) {
                case MAXWELL:
                    KafkaConsumer<String, String> consumer =
                            KafkaPipeLine.createConsumer(conf.server, conf.groupID);
                    Iterator<PartitionInfo> iter = consumer.partitionsFor(conf.topic).iterator();

                    int num = 0;
                    while (iter.hasNext()) {
                        iter.next();
                        num++;
                        PipeLine pipeLine = new MaxwellPipeLine(cxt, conf, num);
                        cxt.pipeLines.add(pipeLine);
                        conf.pipeLines.add(pipeLine);
                    }
                    break;

                case DEBEZIUM:
                    for (String sourceTable : conf.tableMap.keySet()) {
                        String topic = conf.topic + sourceTable.substring(sourceTable.indexOf("."));
                        PipeLine pipeLine = new DebeziumPipeLine(cxt, conf, topic);
                        cxt.pipeLines.add(pipeLine);
                        conf.pipeLines.add(pipeLine);
                    }
                    break;

                default:
                    break;
            }
        }

        // pipeline state statistics
        Gauge<Integer> allCount = new Gauge<Integer>() {
            @Override
            public Integer getValue() {
                return cxt.pipeLines.size();
            }
        };

        Gauge<Integer> liveCount = new Gauge<Integer>() {
            @Override
            public Integer getValue() {
                int live = 0;
                for (PipeLine pipeline : cxt.pipeLines) {
                    if (pipeline.state == PipeLineState.NORMAL) {
                        live++;
                    }
                }
                return live;
            }
        };

        cxt.register.register(MetricRegistry.name("All Pipeline Number"), allCount);
        cxt.register.register(MetricRegistry.name("All Live Pipeline Number"), liveCount);
    }

    /**
     * Start metrics reporter.
     */
    protected void startReporter() {
        switch (cxt.conf.reporter) {
            case "console":
                consoleReporter = ConsoleReporter.forRegistry(cxt.register)
                        .convertRatesTo(TimeUnit.SECONDS)
                        .convertDurationsTo(TimeUnit.MILLISECONDS)
                        .build();
                consoleReporter.start(cxt.conf.report_interval, TimeUnit.SECONDS);
                break;
            case "jmx":
                jmxReporter = JmxReporter.forRegistry(cxt.register).build();
                jmxReporter.start();
                break;
            default:
                break;
        }
    }

    @Override
    public void init(DaemonContext context) throws Exception {
        logger.info("initialize Bireme daemon");
        this.context = context;
        try {
            parseCommandLine(context.getArguments());
        } catch (Exception e) {
            logger.fatal("start failed. Message: {}.", e.getMessage());
            logger.fatal("Stack Trace: ", e);
        }
    }

    @Override
    public void start() throws BiremeException {
        logger.info("start Bireme daemon.");
        try {
            getTableInfo();
            initLoaderConnections();
        } catch (BiremeException e) {
            logger.fatal("start failed. Message: {}.", e.getMessage());
            logger.fatal("Stack Trace: ", e);
            throw e;
        }

        createPipeLine();
        cxt.startScheduler();
        startReporter();

        if (context != null) {
            cxt.startWatchDog(context.getController());
        }
    }

    @Override
    public void stop() {
        logger.info("stop Bireme daemon");

        if (cxt == null) {
            return;
        }

        cxt.stop = true;
        logger.info("set stop flag to true");

        cxt.waitForExit();

        try {
            closeConnections();
        } catch (SQLException e) {
            logger.warn(e.getMessage());
        }

        logger.info("Bireme exit");
    }

    @Override
    public void destroy() {
        logger.info("destroy Bireme daemon");
    }

    /**
     * An entry to start {@code Bireme}.
     *
     * @param args arguments from command line.
     */
    public void entry(String[] args) {
        try {
            parseCommandLine(args);
        } catch (Exception e) {
            logger.fatal("Parse command line failed: {}.", e.getMessage());
            logger.fatal("Stack Trace: ", e);
            System.exit(1);
        }

        try {
            start();
            cxt.waitForStop();
        } catch (Exception e) {
            logger.fatal("Bireme stop abnormally since: {}", e.getMessage());
            logger.fatal("Stack Trace: ", e);
        }

        cxt.waitForExit();

        try {
            closeConnections();
        } catch (SQLException e) {
            logger.warn(e.getMessage());
        }

        logger.info("Bireme exit");
    }
}
