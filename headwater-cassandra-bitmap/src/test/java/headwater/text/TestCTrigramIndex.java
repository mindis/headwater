package headwater.text;

import headwater.bitmap.FakeCassandraIO;
import headwater.cassandra.IO;
import headwater.data.DataAccess;
import headwater.data.MemoryDataAccess;

public class TestCTrigramIndex extends AbstractTrigramIndexTest {
    
    @Override
    public ITrigramIndex<String, String> makeIndex() {
        
        DataAccess<String, String, String> observer = new MemoryDataAccess<String, String, String>();
        IO io = new FakeCassandraIO();
        // a 1Gbit index with 4Mbit segments.
        return new CTrigramIndex<String, String>(1073741824L, 4194304)
                .withIO(io)
                .withObserver(observer);
    }
}
