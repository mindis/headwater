package headwater.cassandra;

import org.junit.Before;


public class TestIntegrationCassIO extends AbstractIOTest {
    
    @Before
    public void createIO() throws Exception {
        io = new CassandraIO("127.0.0.1", 9160, "headwater", "my_data_index");
    }
}
