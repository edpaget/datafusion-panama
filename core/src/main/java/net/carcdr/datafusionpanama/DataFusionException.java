package net.carcdr.datafusionpanama;

/** Checked exception thrown when a DataFusion FFI call returns an error. */
public class DataFusionException extends Exception {

    /**
     * Creates a new exception with the given error message from DataFusion.
     *
     * @param message the error message
     */
    public DataFusionException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the given error message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public DataFusionException(String message, Throwable cause) {
        super(message, cause);
    }
}
