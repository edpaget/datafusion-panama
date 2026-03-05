package net.carcdr.datafusionpanama;

/** Supported join types for DataFrame join operations. */
public enum JoinType {
    /** Inner join: only matching rows from both sides. */
    INNER(0),
    /** Left join: all left rows, NULLs for unmatched right. */
    LEFT(1),
    /** Right join: all right rows, NULLs for unmatched left. */
    RIGHT(2),
    /** Full outer join: all rows from both sides. */
    FULL(3),
    /** Left semi join: left rows that have a match (only left columns). */
    LEFT_SEMI(4),
    /** Right semi join: right rows that have a match (only right columns). */
    RIGHT_SEMI(5),
    /** Left anti join: left rows with no match. */
    LEFT_ANTI(6),
    /** Right anti join: right rows with no match. */
    RIGHT_ANTI(7);

    private final int nativeValue;

    JoinType(int nativeValue) {
        this.nativeValue = nativeValue;
    }

    /**
     * Returns the integer value passed to the native FFI layer.
     *
     * @return the native integer representation
     */
    int nativeValue() {
        return nativeValue;
    }
}
