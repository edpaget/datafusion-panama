package net.carcdr.datafusionpanama;

/** Checked exception thrown when a DataFusion FFI call returns an error. */
public class DataFusionException extends Exception {
    public DataFusionException(String message) {
        super(message);
    }
}
