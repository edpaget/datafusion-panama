package net.carcdr.datafusionpanama;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Cleaner;

/** Package-private implementation of {@link DataFusionDataFrame}. */
final class DataFusionDataFrameImpl implements DataFusionDataFrame {

    private static final MethodHandle DATAFRAME_FREE =
            NativeLibrary.downcallHandle(
                    "dataframe_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    // -- Tier 1 --

    private static final MethodHandle DATAFRAME_FILTER =
            NativeLibrary.downcallHandle(
                    "dataframe_filter",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

    private static final MethodHandle DATAFRAME_SELECT =
            NativeLibrary.downcallHandle(
                    "dataframe_select",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG));

    private static final MethodHandle DATAFRAME_LIMIT =
            NativeLibrary.downcallHandle(
                    "dataframe_limit",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT));

    private static final MethodHandle DATAFRAME_SORT =
            NativeLibrary.downcallHandle(
                    "dataframe_sort",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG));

    private static final MethodHandle DATAFRAME_DISTINCT =
            NativeLibrary.downcallHandle(
                    "dataframe_distinct",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final MethodHandle DATAFRAME_COUNT =
            NativeLibrary.downcallHandle(
                    "dataframe_count",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // -- Tier 2 --

    private static final MethodHandle DATAFRAME_AGGREGATE =
            NativeLibrary.downcallHandle(
                    "dataframe_aggregate",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG));

    private static final MethodHandle DATAFRAME_SELECT_COLUMNS =
            NativeLibrary.downcallHandle(
                    "dataframe_select_columns",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG));

    private static final MethodHandle DATAFRAME_DROP_COLUMNS =
            NativeLibrary.downcallHandle(
                    "dataframe_drop_columns",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG));

    private static final MethodHandle DATAFRAME_WITH_COLUMN =
            NativeLibrary.downcallHandle(
                    "dataframe_with_column",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

    private static final MethodHandle DATAFRAME_WITH_COLUMN_RENAMED =
            NativeLibrary.downcallHandle(
                    "dataframe_with_column_renamed",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

    private static final MethodHandle DATAFRAME_EXPLAIN =
            NativeLibrary.downcallHandle(
                    "dataframe_explain",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_BOOLEAN,
                            ValueLayout.JAVA_BOOLEAN));

    // -- Tier 3 --

    private static final MethodHandle DATAFRAME_JOIN =
            NativeLibrary.downcallHandle(
                    "dataframe_join",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG));

    private static final MethodHandle DATAFRAME_UNION =
            NativeLibrary.downcallHandle(
                    "dataframe_union",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

    private static final MethodHandle DATAFRAME_UNION_DISTINCT =
            NativeLibrary.downcallHandle(
                    "dataframe_union_distinct",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

    private static final MethodHandle DATAFRAME_EXCEPT =
            NativeLibrary.downcallHandle(
                    "dataframe_except",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

    private static final MethodHandle DATAFRAME_INTERSECT =
            NativeLibrary.downcallHandle(
                    "dataframe_intersect",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

    private final MemorySegment runtimePointer;
    private final CleaningAction cleaningAction;
    private final Cleaner.Cleanable cleanable;

    DataFusionDataFrameImpl(MemorySegment pointer, MemorySegment runtimePointer) {
        this.runtimePointer = runtimePointer;
        this.cleaningAction = new CleaningAction(pointer);
        this.cleanable = NativeCleaner.CLEANER.register(this, cleaningAction);
    }

    /**
     * Captures the native pointer for cleanup. Must NOT reference the enclosing {@code
     * DataFusionDataFrameImpl} instance — doing so would prevent GC.
     */
    static final class CleaningAction implements Runnable {
        private volatile MemorySegment pointer;

        CleaningAction(MemorySegment pointer) {
            this.pointer = pointer;
        }

        MemorySegment pointer() {
            MemorySegment p = pointer;
            if (p == null || p.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("DataFrame is closed");
            }
            return p;
        }

        @Override
        public void run() {
            MemorySegment p = pointer;
            if (p != null && !p.equals(MemorySegment.NULL)) {
                try {
                    DATAFRAME_FREE.invokeExact(p);
                } catch (Throwable t) {
                    throw new AssertionError("failed to free DataFrame", t);
                }
                pointer = MemorySegment.NULL;
            }
        }
    }

    @Override
    public RecordBatchReader collect() throws DataFusionException {
        return RecordBatchReaderImpl.create(runtimePointer, nativePointer());
    }

    @Override
    public DataFusionDataFrame filter(String expr) throws DataFusionException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment exprSegment = arena.allocateFrom(expr);
            MemorySegment resultPtr =
                    (MemorySegment)
                            DATAFRAME_FILTER.invokeExact(
                                    runtimePointer, nativePointer(), exprSegment);
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame select(String... exprs) throws DataFusionException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment arrayPtr = allocateStringArray(arena, exprs);
            MemorySegment resultPtr =
                    (MemorySegment)
                            DATAFRAME_SELECT.invokeExact(
                                    runtimePointer, nativePointer(), arrayPtr, (long) exprs.length);
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame limit(int skip, int fetch) throws DataFusionException {
        try {
            MemorySegment resultPtr =
                    (MemorySegment)
                            DATAFRAME_LIMIT.invokeExact(
                                    runtimePointer, nativePointer(), skip, fetch);
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame sort(String... exprs) throws DataFusionException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment arrayPtr = allocateStringArray(arena, exprs);
            MemorySegment resultPtr =
                    (MemorySegment)
                            DATAFRAME_SORT.invokeExact(
                                    runtimePointer, nativePointer(), arrayPtr, (long) exprs.length);
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame distinct() throws DataFusionException {
        try {
            MemorySegment resultPtr =
                    (MemorySegment) DATAFRAME_DISTINCT.invokeExact(runtimePointer, nativePointer());
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public long count() throws DataFusionException {
        try {
            MemorySegment resultPtr =
                    (MemorySegment) DATAFRAME_COUNT.invokeExact(runtimePointer, nativePointer());
            MemorySegment valuePtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return valuePtr.address();
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame aggregate(String[] groupExprs, String[] aggrExprs)
            throws DataFusionException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment groupPtr = allocateStringArray(arena, groupExprs);
            MemorySegment aggrPtr = allocateStringArray(arena, aggrExprs);
            MemorySegment resultPtr =
                    (MemorySegment)
                            DATAFRAME_AGGREGATE.invokeExact(
                                    runtimePointer,
                                    nativePointer(),
                                    groupPtr,
                                    (long) groupExprs.length,
                                    aggrPtr,
                                    (long) aggrExprs.length);
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame selectColumns(String... columns) throws DataFusionException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment arrayPtr = allocateStringArray(arena, columns);
            MemorySegment resultPtr =
                    (MemorySegment)
                            DATAFRAME_SELECT_COLUMNS.invokeExact(
                                    runtimePointer,
                                    nativePointer(),
                                    arrayPtr,
                                    (long) columns.length);
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame dropColumns(String... columns) throws DataFusionException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment arrayPtr = allocateStringArray(arena, columns);
            MemorySegment resultPtr =
                    (MemorySegment)
                            DATAFRAME_DROP_COLUMNS.invokeExact(
                                    runtimePointer,
                                    nativePointer(),
                                    arrayPtr,
                                    (long) columns.length);
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame withColumn(String name, String expr) throws DataFusionException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nameSegment = arena.allocateFrom(name);
            MemorySegment exprSegment = arena.allocateFrom(expr);
            MemorySegment resultPtr =
                    (MemorySegment)
                            DATAFRAME_WITH_COLUMN.invokeExact(
                                    runtimePointer, nativePointer(), nameSegment, exprSegment);
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame withColumnRenamed(String oldName, String newName)
            throws DataFusionException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment oldSegment = arena.allocateFrom(oldName);
            MemorySegment newSegment = arena.allocateFrom(newName);
            MemorySegment resultPtr =
                    (MemorySegment)
                            DATAFRAME_WITH_COLUMN_RENAMED.invokeExact(
                                    runtimePointer, nativePointer(), oldSegment, newSegment);
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame explain(boolean verbose, boolean analyze)
            throws DataFusionException {
        try {
            MemorySegment resultPtr =
                    (MemorySegment)
                            DATAFRAME_EXPLAIN.invokeExact(
                                    runtimePointer, nativePointer(), verbose, analyze);
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame join(
            DataFusionDataFrame right,
            JoinType joinType,
            String[] leftColumns,
            String[] rightColumns)
            throws DataFusionException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment leftPtr = allocateStringArray(arena, leftColumns);
            MemorySegment rightPtr = allocateStringArray(arena, rightColumns);
            MemorySegment resultPtr =
                    (MemorySegment)
                            DATAFRAME_JOIN.invokeExact(
                                    runtimePointer,
                                    nativePointer(),
                                    right.nativePointer(),
                                    joinType.nativeValue(),
                                    leftPtr,
                                    (long) leftColumns.length,
                                    rightPtr,
                                    (long) rightColumns.length);
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame union(DataFusionDataFrame other) throws DataFusionException {
        try {
            MemorySegment resultPtr =
                    (MemorySegment)
                            DATAFRAME_UNION.invokeExact(
                                    runtimePointer, nativePointer(), other.nativePointer());
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame unionDistinct(DataFusionDataFrame other) throws DataFusionException {
        try {
            MemorySegment resultPtr =
                    (MemorySegment)
                            DATAFRAME_UNION_DISTINCT.invokeExact(
                                    runtimePointer, nativePointer(), other.nativePointer());
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame except(DataFusionDataFrame other) throws DataFusionException {
        try {
            MemorySegment resultPtr =
                    (MemorySegment)
                            DATAFRAME_EXCEPT.invokeExact(
                                    runtimePointer, nativePointer(), other.nativePointer());
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame intersect(DataFusionDataFrame other) throws DataFusionException {
        try {
            MemorySegment resultPtr =
                    (MemorySegment)
                            DATAFRAME_INTERSECT.invokeExact(
                                    runtimePointer, nativePointer(), other.nativePointer());
            MemorySegment dfPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dfPtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public MemorySegment nativePointer() {
        return cleaningAction.pointer();
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    /**
     * Allocates a C-style array of string pointers in the given arena.
     *
     * @param arena the arena to allocate in
     * @param strings the strings to allocate
     * @return a memory segment pointing to the array of string pointers
     */
    static MemorySegment allocateStringArray(Arena arena, String[] strings) {
        MemorySegment array = arena.allocate(ValueLayout.ADDRESS, strings.length);
        for (int i = 0; i < strings.length; i++) {
            MemorySegment str = arena.allocateFrom(strings[i]);
            array.setAtIndex(ValueLayout.ADDRESS, i, str);
        }
        return array;
    }
}
