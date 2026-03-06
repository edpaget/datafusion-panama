package net.carcdr.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.junit.jupiter.api.Test;

class DataFrameApiTest {

    // -- filter --

    @Test
    void filterByExpression() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql(
                                "SELECT * FROM (VALUES (1, 'a'), (2, 'b'), (3, 'c'))"
                                        + " AS t(id, name)");
                DataFusionDataFrame filtered = df.filter("id > 1");
                RecordBatchReader reader = filtered.collect()) {
            long rows = 0;
            while (reader.next()) {
                rows += reader.getCurrentBatch().getRowCount();
            }
            assertEquals(2, rows);
        }
    }

    @Test
    void filterInvalidExpressionThrows() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 1 AS a")) {
            assertThrows(DataFusionException.class, () -> df.filter("nonexistent > 0"));
        }
    }

    // -- select --

    @Test
    void selectExpressions() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql("SELECT * FROM (VALUES (1, 10), (2, 20)) AS t(id, value)");
                DataFusionDataFrame selected = df.select("id", "value * 2");
                RecordBatchReader reader = selected.collect()) {
            assertTrue(reader.next());
            assertEquals(2, reader.getCurrentBatch().getSchema().getFields().size());
        }
    }

    // -- limit --

    @Test
    void limitSkipAndFetch() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql("SELECT * FROM (VALUES (1), (2), (3), (4), (5)) AS t(id)");
                DataFusionDataFrame limited = df.limit(1, 2);
                RecordBatchReader reader = limited.collect()) {
            long rows = 0;
            while (reader.next()) {
                rows += reader.getCurrentBatch().getRowCount();
            }
            assertEquals(2, rows);
        }
    }

    @Test
    void limitNoFetch() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql("SELECT * FROM (VALUES (1), (2), (3)) AS t(id)");
                DataFusionDataFrame limited = df.limit(1, -1);
                RecordBatchReader reader = limited.collect()) {
            long rows = 0;
            while (reader.next()) {
                rows += reader.getCurrentBatch().getRowCount();
            }
            assertEquals(2, rows);
        }
    }

    // -- sort --

    @Test
    void sortDescending() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql("SELECT * FROM (VALUES (3), (1), (2)) AS t(id)");
                DataFusionDataFrame sorted = df.sort("id DESC");
                RecordBatchReader reader = sorted.collect()) {
            assertTrue(reader.next());
            BigIntVector vec = (BigIntVector) reader.getCurrentBatch().getVector("id");
            assertEquals(3L, vec.get(0));
            assertEquals(2L, vec.get(1));
            assertEquals(1L, vec.get(2));
        }
    }

    // -- distinct --

    @Test
    void distinctRows() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql("SELECT * FROM (VALUES (1), (2), (1), (3), (2)) AS t(id)");
                DataFusionDataFrame distinct = df.distinct();
                RecordBatchReader reader = distinct.collect()) {
            long rows = 0;
            while (reader.next()) {
                rows += reader.getCurrentBatch().getRowCount();
            }
            assertEquals(3, rows);
        }
    }

    // -- count --

    @Test
    void countRows() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql("SELECT * FROM (VALUES (1), (2), (3)) AS t(id)")) {
            assertEquals(3L, df.count());
        }
    }

    // -- aggregate --

    @Test
    void aggregateGroupBySum() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql(
                                "SELECT * FROM (VALUES ('a', 10), ('b', 20),"
                                        + " ('a', 30)) AS t(grp, val)");
                DataFusionDataFrame agg =
                        df.aggregate(new String[] {"grp"}, new String[] {"SUM(val)"});
                RecordBatchReader reader = agg.collect()) {
            long rows = 0;
            while (reader.next()) {
                rows += reader.getCurrentBatch().getRowCount();
            }
            assertEquals(2, rows);
        }
    }

    // -- selectColumns --

    @Test
    void selectColumnsByName() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql("SELECT * FROM (VALUES (1, 'a', 10)) AS t(id, name, value)");
                DataFusionDataFrame selected = df.selectColumns("id", "name");
                RecordBatchReader reader = selected.collect()) {
            assertTrue(reader.next());
            assertEquals(2, reader.getCurrentBatch().getSchema().getFields().size());
            assertEquals("id", reader.getCurrentBatch().getSchema().getFields().get(0).getName());
            assertEquals("name", reader.getCurrentBatch().getSchema().getFields().get(1).getName());
        }
    }

    // -- dropColumns --

    @Test
    void dropColumnsByName() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql("SELECT * FROM (VALUES (1, 'a', 10)) AS t(id, name, value)");
                DataFusionDataFrame dropped = df.dropColumns("name");
                RecordBatchReader reader = dropped.collect()) {
            assertTrue(reader.next());
            assertEquals(2, reader.getCurrentBatch().getSchema().getFields().size());
            assertEquals("id", reader.getCurrentBatch().getSchema().getFields().get(0).getName());
            assertEquals(
                    "value", reader.getCurrentBatch().getSchema().getFields().get(1).getName());
        }
    }

    // -- withColumn --

    @Test
    void withColumnAddsComputed() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql("SELECT * FROM (VALUES (1, 10), (2, 20)) AS t(id, value)");
                DataFusionDataFrame withCol = df.withColumn("doubled", "value * 2");
                RecordBatchReader reader = withCol.collect()) {
            assertTrue(reader.next());
            assertEquals(3, reader.getCurrentBatch().getSchema().getFields().size());
            assertEquals(
                    "doubled", reader.getCurrentBatch().getSchema().getFields().get(2).getName());
            BigIntVector vec = (BigIntVector) reader.getCurrentBatch().getVector("doubled");
            assertEquals(20L, vec.get(0));
            assertEquals(40L, vec.get(1));
        }
    }

    // -- withColumnRenamed --

    @Test
    void withColumnRenamedChangesName() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 1 AS old_name");
                DataFusionDataFrame renamed = df.withColumnRenamed("old_name", "new_name");
                RecordBatchReader reader = renamed.collect()) {
            assertTrue(reader.next());
            assertEquals(
                    "new_name", reader.getCurrentBatch().getSchema().getFields().get(0).getName());
        }
    }

    // -- explain --

    @Test
    void explainReturnsNonEmptyPlan() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 1 AS a");
                DataFusionDataFrame explained = df.explain(false, false);
                RecordBatchReader reader = explained.collect()) {
            assertTrue(reader.next());
            assertTrue(reader.getCurrentBatch().getRowCount() > 0);
            VarCharVector planCol = (VarCharVector) reader.getCurrentBatch().getVector("plan");
            assertTrue(planCol.getValueCount() > 0);
        }
    }

    // -- join --

    @Test
    void innerJoin() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame left =
                        session.sql(
                                "SELECT * FROM (VALUES (1, 'a'), (2, 'b'),"
                                        + " (3, 'c')) AS l(id, name)");
                DataFusionDataFrame right =
                        session.sql("SELECT * FROM (VALUES (1, 100), (2, 200)) AS r(id, score)");
                DataFusionDataFrame joined =
                        left.join(right, JoinType.INNER, new String[] {"id"}, new String[] {"id"});
                RecordBatchReader reader = joined.collect()) {
            long rows = 0;
            while (reader.next()) {
                rows += reader.getCurrentBatch().getRowCount();
            }
            assertEquals(2, rows);
        }
    }

    @Test
    void leftJoinIncludesUnmatched() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame left =
                        session.sql(
                                "SELECT * FROM (VALUES (1, 'a'), (2, 'b'),"
                                        + " (3, 'c')) AS l(id, name)");
                DataFusionDataFrame right =
                        session.sql("SELECT * FROM (VALUES (1, 100)) AS r(id, score)");
                DataFusionDataFrame joined =
                        left.join(right, JoinType.LEFT, new String[] {"id"}, new String[] {"id"});
                RecordBatchReader reader = joined.collect()) {
            long rows = 0;
            while (reader.next()) {
                rows += reader.getCurrentBatch().getRowCount();
            }
            assertEquals(3, rows);
        }
    }

    // -- union --

    @Test
    void unionCombinesRows() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame left = session.sql("SELECT * FROM (VALUES (1), (2)) AS l(id)");
                DataFusionDataFrame right =
                        session.sql("SELECT * FROM (VALUES (3), (4)) AS r(id)");
                DataFusionDataFrame unioned = left.union(right);
                RecordBatchReader reader = unioned.collect()) {
            long rows = 0;
            while (reader.next()) {
                rows += reader.getCurrentBatch().getRowCount();
            }
            assertEquals(4, rows);
        }
    }

    // -- unionDistinct --

    @Test
    void unionDistinctRemovesDuplicates() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame left = session.sql("SELECT * FROM (VALUES (1), (2)) AS l(id)");
                DataFusionDataFrame right =
                        session.sql("SELECT * FROM (VALUES (2), (3)) AS r(id)");
                DataFusionDataFrame unioned = left.unionDistinct(right);
                RecordBatchReader reader = unioned.collect()) {
            long rows = 0;
            while (reader.next()) {
                rows += reader.getCurrentBatch().getRowCount();
            }
            assertEquals(3, rows);
        }
    }

    // -- except --

    @Test
    void exceptRemovesCommonRows() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame left =
                        session.sql("SELECT * FROM (VALUES (1), (2), (3)) AS l(id)");
                DataFusionDataFrame right = session.sql("SELECT * FROM (VALUES (2)) AS r(id)");
                DataFusionDataFrame excepted = left.except(right);
                RecordBatchReader reader = excepted.collect()) {
            long rows = 0;
            while (reader.next()) {
                rows += reader.getCurrentBatch().getRowCount();
            }
            assertEquals(2, rows);
        }
    }

    // -- intersect --

    @Test
    void intersectKeepsCommonRows() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame left =
                        session.sql("SELECT * FROM (VALUES (1), (2), (3)) AS l(id)");
                DataFusionDataFrame right =
                        session.sql("SELECT * FROM (VALUES (2), (3), (4)) AS r(id)");
                DataFusionDataFrame intersected = left.intersect(right);
                RecordBatchReader reader = intersected.collect()) {
            long rows = 0;
            while (reader.next()) {
                rows += reader.getCurrentBatch().getRowCount();
            }
            assertEquals(2, rows);
        }
    }

    // -- clone semantics --

    @Test
    void sourceDataFrameUnchangedAfterTransformation() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql("SELECT * FROM (VALUES (1), (2), (3)) AS t(id)");
                DataFusionDataFrame filtered = df.filter("id > 1")) {
            assertEquals(2L, filtered.count());
            assertEquals(3L, df.count());
        }
    }

    // -- empty result --

    @Test
    void emptyResultSet() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql("SELECT * FROM (VALUES (1), (2), (3)) AS t(id)");
                DataFusionDataFrame filtered = df.filter("id > 100");
                RecordBatchReader reader = filtered.collect()) {
            long rows = 0;
            while (reader.next()) {
                rows += reader.getCurrentBatch().getRowCount();
            }
            assertEquals(0, rows);
        }
    }

    // -- reuse --

    @Test
    void dataFrameReuseForMultipleOperations() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql("SELECT * FROM (VALUES (1), (2), (3)) AS t(id)");
                DataFusionDataFrame a = df.filter("id > 1");
                DataFusionDataFrame b = df.filter("id < 3")) {
            assertEquals(2L, a.count());
            assertEquals(2L, b.count());
        }
    }

    // -- global aggregation --

    @Test
    void globalAggregationNoGroupBy() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql("SELECT * FROM (VALUES (10), (20), (30)) AS t(val)");
                DataFusionDataFrame agg =
                        df.aggregate(new String[] {}, new String[] {"SUM(val)", "COUNT(val)"});
                RecordBatchReader reader = agg.collect()) {
            assertTrue(reader.next());
            assertEquals(1, reader.getCurrentBatch().getRowCount());
            // DataFusion names aggregate columns like "sum(t.val)" / "count(t.val)"
            BigIntVector sumVec = (BigIntVector) reader.getCurrentBatch().getVector(0);
            BigIntVector countVec = (BigIntVector) reader.getCurrentBatch().getVector(1);
            assertEquals(60L, sumVec.get(0));
            assertEquals(3L, countVec.get(0));
        }
    }

    // -- multi-key sort --

    @Test
    void multipleSortKeysWithMixedDirection() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql(
                                "SELECT * FROM (VALUES ('a', 2), ('a', 1),"
                                        + " ('b', 1)) AS t(grp, val)");
                DataFusionDataFrame sorted = df.sort("grp ASC", "val DESC");
                RecordBatchReader reader = sorted.collect()) {
            assertTrue(reader.next());
            VarCharVector grpVec = (VarCharVector) reader.getCurrentBatch().getVector("grp");
            BigIntVector valVec = (BigIntVector) reader.getCurrentBatch().getVector("val");
            assertEquals("a", grpVec.getObject(0).toString());
            assertEquals(2L, valVec.get(0));
            assertEquals("a", grpVec.getObject(1).toString());
            assertEquals(1L, valVec.get(1));
            assertEquals("b", grpVec.getObject(2).toString());
            assertEquals(1L, valVec.get(2));
        }
    }

    // -- closed DataFrame --

    @Test
    void operationOnClosedDataFrameThrows() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession()) {
            DataFusionDataFrame df = session.sql("SELECT 1 AS a");
            df.close();
            AssertionError err = assertThrows(AssertionError.class, () -> df.filter("true"));
            assertTrue(err.getCause() instanceof IllegalStateException);
        }
    }

    // -- count edge case --

    @Test
    void countOnEmptyDataFrame() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT * FROM (VALUES (1)) AS t(id)");
                DataFusionDataFrame empty = df.filter("id > 100")) {
            assertEquals(0L, empty.count());
        }
    }

    // -- close idempotency --

    @Test
    void doubleCloseIsIdempotent() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession()) {
            DataFusionDataFrame df = session.sql("SELECT 1 AS a");
            df.close();
            df.close(); // second close must not throw
        }
    }

    @Test
    void closeAfterGcHintDoesNotThrow() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession()) {
            DataFusionDataFrame df = session.sql("SELECT 1 AS a");
            System.gc();
            df.close(); // must not throw even if GC ran
        }
    }

    // -- chaining --

    @Test
    void chainedOperations() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df =
                        session.sql(
                                "SELECT * FROM (VALUES (1, 'a'), (2, 'b'),"
                                        + " (3, 'c'), (4, 'd'), (5, 'e'))"
                                        + " AS t(id, name)");
                DataFusionDataFrame result = df.filter("id > 1").sort("id DESC").limit(0, 2);
                RecordBatchReader reader = result.collect()) {
            assertTrue(reader.next());
            BigIntVector vec = (BigIntVector) reader.getCurrentBatch().getVector("id");
            assertEquals(2, reader.getCurrentBatch().getRowCount());
            assertEquals(5L, vec.get(0));
            assertEquals(4L, vec.get(1));
        }
    }
}
