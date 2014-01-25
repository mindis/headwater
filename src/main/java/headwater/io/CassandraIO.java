package headwater.io;

import com.netflix.astyanax.AstyanaxContext;
import com.netflix.astyanax.ColumnListMutation;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.connectionpool.NodeDiscoveryType;
import com.netflix.astyanax.connectionpool.impl.ConnectionPoolConfigurationImpl;
import com.netflix.astyanax.connectionpool.impl.ConnectionPoolType;
import com.netflix.astyanax.impl.AstyanaxConfigurationImpl;
import com.netflix.astyanax.model.Column;
import com.netflix.astyanax.model.ColumnFamily;
import com.netflix.astyanax.model.ColumnList;
import com.netflix.astyanax.query.RowQuery;
import com.netflix.astyanax.serializers.BytesArraySerializer;
import com.netflix.astyanax.thrift.ThriftFamilyFactory;
import com.netflix.astyanax.util.RangeBuilder;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.core.TimerContext;
import headwater.Utils;
import headwater.bitmap.IBitmap;
import headwater.bitmap.MemoryBitmap2;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class CassandraIO implements IO<IBitmap> {

    private Keyspace keyspace;
    private final ColumnFamily<byte[], byte[]> columnFamily;
    private final AstyanaxContext.Builder builder;
    
    private final Timer putTimer = makeTimer(CassandraIO.class, "put", "cassandra");
    private final Timer getTimer = makeTimer(CassandraIO.class, "get", "cassandra");
    private final Timer visitAllTimer = makeTimer(CassandraIO.class, "visit", "cassandra");

    public CassandraIO(String host, int port, String keyspace, String columnFamily) {
        builder = new AstyanaxContext.Builder()
                .withAstyanaxConfiguration(
                        new AstyanaxConfigurationImpl()
                                .setDiscoveryType(NodeDiscoveryType.RING_DESCRIBE)
                                .setCqlVersion("3.0.0")
                                .setTargetCassandraVersion("1.2")
                                .setDiscoveryType(NodeDiscoveryType.NONE)
                                .setConnectionPoolType(ConnectionPoolType.ROUND_ROBIN)
                )
                .forKeyspace(keyspace)
                .withConnectionPoolConfiguration(new ConnectionPoolConfigurationImpl(String.format("%s:%d", host, port))
                        .setPort(port)
                        .setMaxConnsPerHost(10)
                        .setSeeds(String.format("%s:%d", host, port))
                );
        
        AstyanaxContext<Keyspace> context = builder.buildKeyspace(ThriftFamilyFactory.getInstance());
        context.start();
        this.keyspace = context.getEntity();
        this.columnFamily = ColumnFamily.newColumnFamily(columnFamily, BytesArraySerializer.get(), BytesArraySerializer.get(), BytesArraySerializer.get());
    }
    
    public void put(byte[] key, long col, IBitmap value) throws Exception {
        TimerContext ctx = putTimer.time();
        try {
            keyspace.prepareColumnMutation(columnFamily, key, Utils.longToBytes(col)).putValue(value.toBytes(), null).execute();
        } finally {
            ctx.stop();
        }
    }
    
    public void flush(Map<byte[], Map<Long, IBitmap>> data) throws Exception {
        int colCount = 0, maxCols = 1024;
        MutationBatch batch = keyspace.prepareMutationBatch().lockCurrentTimestamp();
        for (byte[] key : data.keySet()) {
            
            if (colCount >= maxCols) {
                tryBatch(batch, colCount);
                batch = keyspace.prepareMutationBatch().lockCurrentTimestamp();
            }
            
            ColumnListMutation<byte[]> mutation = batch.withRow(columnFamily, key);
            for (Map.Entry<Long, IBitmap> entry : data.get(key).entrySet()) {
                mutation = mutation.putColumn(Utils.longToBytes(entry.getKey()), entry.getValue().toBytes());
                colCount += 1;
            }
        }
        
        tryBatch(batch, colCount);
    }
    
    private void tryBatch(MutationBatch batch, int colCount) throws Exception {
        try {
            batch.execute().getResult();
        } catch (Exception ex) {
            System.err.println(String.format("Busted with %d", colCount));
            throw ex;
        }
    }
    
    public IBitmap get(byte[] key, long col) throws Exception {
        TimerContext ctx = getTimer.time();
        try {
            byte[] buf = keyspace.prepareQuery(columnFamily)
                    .getKey(key)
                    .getColumn(Utils.longToBytes(col))
                    .execute().getResult().getByteArrayValue();
            return MemoryBitmap2.wrap(buf);
        } finally {
            ctx.stop();
        }
    }
    
    // iterate over all columns, paging through data in a row.
    public void visitAllColumns(byte[] key, int pageSize, ColumnObserver observer) throws Exception {
        
        TimerContext ctx = visitAllTimer.time();
        try {
            RowQuery<byte[], byte[]> query = keyspace
                    .prepareQuery(columnFamily)
                    .getKey(key)
                    .autoPaginate(true)
                    .withColumnRange(new RangeBuilder().setLimit(pageSize).build());
            
            ColumnList<byte[]> columnList;
            while (!(columnList = query.execute().getResult()).isEmpty()) {
                for (Column<byte[]> col : columnList) {
                    long colName = Utils.bytesToLong(col.getName());
                    IBitmap bitmap = MemoryBitmap2.wrap(col.getByteArrayValue());
                    observer.observe(key, colName, bitmap);
                }
            }
        } finally {
            ctx.stop();
        }
    }

    public void del(byte[] key, long col) throws Exception {
        keyspace.prepareColumnMutation(columnFamily, key, Utils.longToBytes(col)).deleteColumn().execute();
    }
    
    private static final Map<MetricName, Timer> metrics = new HashMap<MetricName, Timer>();
    private static Timer makeTimer(Class cls, String name, String scope) {
        MetricName metricName = new MetricName(cls, name, scope);
        if (metrics.containsKey(metricName))
            return metrics.get(metricName);
        else {
            Timer timer = Metrics.newTimer(metricName, TimeUnit.MILLISECONDS, TimeUnit.MINUTES);
            metrics.put(metricName, timer);
            return timer;
        }
    }
}

