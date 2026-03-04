package net.carcdr.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.junit.jupiter.api.Test;

class ColumnAccessorTest {

    @Test
    void int64Column() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 42 AS v");
                RecordBatchReader reader = df.collect()) {
            assertTrue(reader.next());
            BigIntVector vec = (BigIntVector) reader.getCurrentBatch().getVector("v");
            assertEquals(42L, vec.get(0));
        }
    }

    @Test
    void float64Column() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 3.14 AS v");
                RecordBatchReader reader = df.collect()) {
            assertTrue(reader.next());
            Float8Vector vec = (Float8Vector) reader.getCurrentBatch().getVector("v");
            assertEquals(3.14, vec.get(0), 0.001);
        }
    }

    @Test
    void utf8Column() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 'hello' AS v");
                RecordBatchReader reader = df.collect()) {
            assertTrue(reader.next());
            VarCharVector vec = (VarCharVector) reader.getCurrentBatch().getVector("v");
            assertEquals("hello", vec.getObject(0).toString());
        }
    }

    @Test
    void booleanColumn() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT true AS v");
                RecordBatchReader reader = df.collect()) {
            assertTrue(reader.next());
            BitVector vec = (BitVector) reader.getCurrentBatch().getVector("v");
            assertEquals(1, vec.get(0));
        }
    }

    @Test
    void nullHandling() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT CAST(NULL AS BIGINT) AS v");
                RecordBatchReader reader = df.collect()) {
            assertTrue(reader.next());
            BigIntVector vec = (BigIntVector) reader.getCurrentBatch().getVector("v");
            assertTrue(vec.isNull(0));
        }
    }

    @Test
    void multiRow() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql(
                                "SELECT * FROM (VALUES (1, 'a'), (2, 'b'), (3, 'c')) AS t(x, y)");
                RecordBatchReader reader = df.collect()) {
            int totalRows = 0;
            while (reader.next()) {
                totalRows += reader.getCurrentBatch().getRowCount();
            }
            assertEquals(3, totalRows);
        }
    }

    @Test
    void mixedTypes() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql(
                                "SELECT CAST(1 AS BIGINT) AS i, 1.5 AS f, 'hello' AS s, true AS b");
                RecordBatchReader reader = df.collect()) {
            assertTrue(reader.next());
            var batch = reader.getCurrentBatch();

            BigIntVector intVec = (BigIntVector) batch.getVector("i");
            assertEquals(1L, intVec.get(0));

            Float8Vector floatVec = (Float8Vector) batch.getVector("f");
            assertEquals(1.5, floatVec.get(0), 0.001);

            VarCharVector strVec = (VarCharVector) batch.getVector("s");
            assertEquals("hello", strVec.getObject(0).toString());

            BitVector boolVec = (BitVector) batch.getVector("b");
            assertEquals(1, boolVec.get(0));

            assertFalse(reader.next());
        }
    }

    @Test
    void emptyString() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT '' AS v");
                RecordBatchReader reader = df.collect()) {
            assertTrue(reader.next());
            VarCharVector vec = (VarCharVector) reader.getCurrentBatch().getVector("v");
            assertEquals("", vec.getObject(0).toString());
        }
    }
}
