package net.carcdr.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParquetRegistrationTest {

    @TempDir static Path tempDir;

    private static Path parquetPath;

    @BeforeAll
    static void generateParquetFixture() throws DataFusionException, URISyntaxException {
        parquetPath = tempDir.resolve("test-data.parquet");
        Path csvPath =
                Path.of(
                        ParquetRegistrationTest.class
                                .getClassLoader()
                                .getResource("test-data.csv")
                                .toURI());
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession()) {
            session.registerCsv("test_data", csvPath);
            String copySql =
                    String.format(
                            "COPY (SELECT * FROM test_data) TO '%s' STORED AS PARQUET",
                            parquetPath);
            try (DataFusionDataFrame df = session.sql(copySql);
                    RecordBatchReader reader = df.collect()) {
                while (reader.next()) {
                    // drain to execute the COPY
                }
            }
        }
        assertTrue(Files.exists(parquetPath), "Parquet fixture should have been created");
    }

    @Test
    void registerAndQueryParquet() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession()) {
            session.registerParquet("test_data", parquetPath);
            try (DataFusionDataFrame df =
                            session.sql("SELECT id, name FROM test_data ORDER BY id");
                    RecordBatchReader reader = df.collect()) {
                assertTrue(reader.next());
                var batch = reader.getCurrentBatch();
                assertEquals(3, batch.getRowCount());
                BigIntVector ids = (BigIntVector) batch.getVector("id");
                FieldVector names = batch.getVector("name");
                assertEquals(1L, ids.get(0));
                assertEquals(2L, ids.get(1));
                assertEquals(3L, ids.get(2));
                assertEquals("alice", names.getObject(0).toString());
                assertEquals("bob", names.getObject(1).toString());
                assertEquals("charlie", names.getObject(2).toString());
            }
        }
    }

    @Test
    void registerAndFilterParquet() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession()) {
            session.registerParquet("test_data", parquetPath);
            try (DataFusionDataFrame df = session.sql("SELECT name FROM test_data WHERE id = 2");
                    RecordBatchReader reader = df.collect()) {
                assertTrue(reader.next());
                var batch = reader.getCurrentBatch();
                assertEquals(1, batch.getRowCount());
                FieldVector names = batch.getVector("name");
                assertEquals("bob", names.getObject(0).toString());
            }
        }
    }
}
