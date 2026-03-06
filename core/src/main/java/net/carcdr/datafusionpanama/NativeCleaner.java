package net.carcdr.datafusionpanama;

import java.lang.ref.Cleaner;

/**
 * Shared {@link Cleaner} instance for releasing native resources when objects become unreachable.
 */
final class NativeCleaner {

    /** Single daemon thread cleaner shared by all native wrapper objects. */
    static final Cleaner CLEANER = Cleaner.create();

    private NativeCleaner() {}
}
