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
}
