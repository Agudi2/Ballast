package dev.ballast.env;

/** Time source; wall time may jump, while elapsed readings are used only as differences. */
public interface Clock {
    /** Reads wall time, which may jump in either direction.
     * @return signed milliseconds since the Unix epoch */
    long wallTimeMillis();

    /** Reads the elapsed-time source.
     * @return process-local elapsed nanoseconds; never persist this origin across restart */
    long elapsedNanos();
}
