package headwater.io;

import com.google.common.primitives.UnsignedBytes;
import com.netflix.astyanax.connectionpool.exceptions.NotFoundException;
import headwater.Utils;
import headwater.bitmap.BitmapFactory;
import headwater.bitmap.BitmapUtils;
import headwater.bitmap.IBitmap;

import java.util.Map;
import java.util.TreeMap;

public class MemoryIO implements IO {
    
    private Map<byte[], Map<Long, IBitmap>> data = new TreeMap<byte[], Map<Long, IBitmap>>(UnsignedBytes.lexicographicalComparator());
    private BitmapFactory bitmapFactory = null;
    
    public MemoryIO() {}
        
    private Map<Long, IBitmap> getRow(byte[] key) {
        synchronized (data) {
            Map<Long, IBitmap> cols = data.get(key);
            if (cols == null) {
                cols = new TreeMap<Long, IBitmap>();
                data.put(key, cols);
            }
            return cols;
        }
    }
    
    public MemoryIO withBitmapFactory(BitmapFactory factory) {
        this.bitmapFactory = factory;
        return this;
    }
    
    public void put(byte[] key, long col, IBitmap value) throws Exception {
        getRow(key).put(col, value);
    }

    public IBitmap get(byte[] key, long col) throws Exception {
        IBitmap value = getRow(key).get(col);
        if (value != null)
            return value;
        if (bitmapFactory == null)
            throw new NotFoundException("Not found!");
        
        // generate an emtpy bitmap and return it.
        value = bitmapFactory.make();
        getRow(key).put(col, value);
        return value;
        
    }

    public void visitAllColumns(byte[] key, int pageSize, ColumnObserver observer) throws Exception {
        for (Map.Entry<Long, IBitmap> entry : getRow(key).entrySet()) {
            observer.observe(key, entry.getKey(), entry.getValue());
        }
    }

    public void del(byte[] key, long col) throws Exception {
        getRow(key).remove(Utils.longToBytes(col));
    }
    
    public int getRowCountUnsafe() {
        return data.size();
    }
    
    // this is basically an OR operation on all common bitsets. Afterward, we get rid of everything.
    // todo: think about concurrency. we'll want to be able to put while we are flushing.
    public void flushTo(IO receiver) throws Exception {
        for (byte[] key : data.keySet()) {
            for (Map.Entry<Long, IBitmap> col : data.get(key).entrySet()) {
                IBitmap ored = receiver.get(key, col.getKey());
                BitmapUtils.mutatingOR(ored, col.getValue());
                receiver.put(key, col.getKey(), ored);
            }
        }
        data.clear();
    }
}
