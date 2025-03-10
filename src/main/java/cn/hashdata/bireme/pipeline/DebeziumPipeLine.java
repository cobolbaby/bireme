package cn.hashdata.bireme.pipeline;

import cn.hashdata.bireme.*;
import cn.hashdata.bireme.Row.RowType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.logging.log4j.LogManager;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * {@code DebeziumPipeLine} is a kind of {@code KafkaPipeLine} whose change data coming from
 * Debezium.
 *
 * @author yuze
 */
public class DebeziumPipeLine extends KafkaPipeLine {
    public DebeziumPipeLine(Context cxt, SourceConfig conf, String topic) {
        // Notice: myName的命名会对 PipeLineStat 产生影响
        super(cxt, conf, "Debezium-" + conf.name + "-" + topic);
        consumer.subscribe(Arrays.asList(topic));

        logger = LogManager.getLogger(DebeziumPipeLine.class);
        logger.info("Create new Debezium PipeLine. Name: {}", myName);
    }

    @Override
    public Transformer createTransformer() {
        return new DebeziumTransformer();
    }

    class DebeziumTransformer extends KafkaTransformer {
        public DebeziumTransformer() {
            super();
        }

        private String getMappedTableName(DebeziumRecord record) {
            String dataSource = conf.name + record.topic.substring(record.topic.indexOf("."));

            return cxt.tableMap.get(dataSource);
        }

        private String getOriginTableName(DebeziumRecord record) {
            return record.topic;
        }

        @Override
        public boolean transform(ConsumerRecord<String, String> change, Row row)
                throws BiremeException {
            JsonParser jsonParser = new JsonParser();
            JsonObject value = (JsonObject) jsonParser.parse(change.value());

            if (!value.has("payload") || value.get("payload").isJsonNull()) {
                return false;
            }

            JsonObject payLoad = value.getAsJsonObject("payload");
            DebeziumRecord record = new DebeziumRecord(change.topic(), payLoad);

            Table table = cxt.tablesInfo.get(getMappedTableName(record));

            row.type = record.type;
            row.produceTime = record.produceTime;
            row.originTable = getOriginTableName(record);
            row.mappedTable = getMappedTableName(record);
            row.keys = formatColumns(record, table, table.keyNames, false);

            if (row.type != RowType.DELETE) {
                row.tuple = formatColumns(record, table, table.columnName, false);
            }

            return true;
        }

        @Override
        protected byte[] decodeToBinary(String data) {
            return Base64.decodeBase64(data);
        }

        @Override
        protected String decodeToBit(String data, int precision) {
            switch (data) {
                case "true":
                    return "1";
                case "false":
                    return "0";
            }

            StringBuilder sb = new StringBuilder();
            String oneByte;
            String result;
            byte[] decoded = Base64.decodeBase64(data);

            ArrayUtils.reverse(decoded);

            for (byte b : decoded) {
                oneByte = String.format("%8s", Integer.toBinaryString(b & 0xFF)).replace(' ', '0');
                sb.append(oneByte);
            }
            result = sb.toString();

            return result.substring(result.length() - precision);
        }

        @Override
        protected String decodeToTime(String data, int fieldType, int precision) {
            StringBuilder sb = new StringBuilder();

            switch (fieldType) {
                case Types.TIME:
                case Types.TIMESTAMP: {
                    // For TIMETZ and TIMESTAMPTZ, debezium will send the right format which can be load
                    // directly.
                    if (data.contains(String.valueOf('Z'))) {
                        return data;
                    }

                    // Debezium convert the timestamp to mill seconds
                    int sec = Integer.parseInt(data.substring(0, data.length() - 3));
                    String fraction = data.substring(data.length() - 3);

                    SimpleDateFormat df;
                    if (fieldType == Types.TIME) {
                        df = new SimpleDateFormat("HH:mm:ss");
                    } else {
                        df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    }
                    df.setTimeZone(TimeZone.getTimeZone("UTC"));

                    Date d = new Date(sec * 1000L);
                    sb.append(df.format(d));
                    sb.append('.' + fraction);
                    break;
                }

                case Types.DATE: {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                    Calendar c = Calendar.getInstance();

                    try {
                        c.setTime(sdf.parse("1970-01-01"));
                    } catch (ParseException e) {
                        logger.error("Can not decode Data/Time {}, message{}.", data, e.getMessage());
                        return "";
                    }

                    c.add(Calendar.DATE, Integer.parseInt(data));
                    sb.append(sdf.format(c.getTime()));
                    break;
                }

                default:
                    sb.append(data);
                    break;
            }

            return sb.toString();
        }

        @Override
        protected String decodeToNumeric(String data, int fieldType, int precision) {
            byte[] value = Base64.decodeBase64(data);
            BigDecimal bigDecimal = new BigDecimal(new BigInteger(value), precision);

            return bigDecimal.toString();
        }

        class DebeziumRecord implements Record {
            public String topic;
            public Long produceTime;
            public RowType type;
            public JsonObject data;

            public DebeziumRecord(String topic, JsonObject payLoad) {
                this.topic = topic;
                char op = payLoad.get("op").getAsCharacter();
                this.produceTime = payLoad.get("ts_ms").getAsLong();

                JsonElement element = null;
                switch (op) {
                    case 'r':
                    case 'c':
                        type = RowType.INSERT;
                        element = payLoad.get("after");
                        break;

                    case 'u':
                        type = RowType.UPDATE;
                        element = payLoad.get("after");
                        break;

                    case 'd':
                        type = RowType.DELETE;
                        element = payLoad.get("before");
                        break;
                }

                this.data = element.getAsJsonObject();
            }

            @Override
            public String getField(String fieldName, boolean oldValue) throws BiremeException {
                return BiremeUtility.jsonGetIgnoreCase(data, fieldName);
            }
        }
    }
}
