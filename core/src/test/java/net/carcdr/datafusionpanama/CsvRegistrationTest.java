package net.carcdr.datafusionpanama;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.junit.jupiter.api.Test;

class CsvRegistrationTest {

    private Path csvPath() throws URISyntaxException {
        return Path.of(getClass().getClassLoader().getResource("test-data.csv").toURI());
    }

    @Test
    void registerAndQueryCsv() throws DataFusionException, URISyntaxException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession()) {
            session.registerCsv("test_data", csvPath());
            try (DataFusionDataFrame df =
                            session.sql("SELECT id, name FROM test_data ORDER BY id");
                    RecordBatchReader reader = df.collect()) {
                assertTrue(reader.next());
                var batch = reader.getCurrentBatch();
                assertEquals(3, batch.getRowCount());
                BigIntVector ids = (BigIntVector) batch.getVector("id");
                VarCharVector names = (VarCharVector) batch.getVector("name");
                assertEquals(1L, ids.get(0));
                assertEquals(2L, ids.get(1));
                assertEquals(3L, ids.get(2));
                assertEquals("alice", new String(names.get(0), UTF_8));
                assertEquals("bob", new String(names.get(1), UTF_8));
                assertEquals("charlie", new String(names.get(2), UTF_8));
            }
        }
    }

    @Test
    void registerAndFilterCsv() throws DataFusionException, URISyntaxException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession()) {
            session.registerCsv("test_data", csvPath());
            try (DataFusionDataFrame df = session.sql("SELECT name FROM test_data WHERE id = 2");
                    RecordBatchReader reader = df.collect()) {
                assertTrue(reader.next());
                var batch = reader.getCurrentBatch();
                assertEquals(1, batch.getRowCount());
                VarCharVector names = (VarCharVector) batch.getVector("name");
                assertEquals("bob", new String(names.get(0), UTF_8));
            }
        }
    }
}
