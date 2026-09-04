package com.marginfuse;

/** What this build was verified against. */
public final class Contract {

    private Contract() {}

    /**
     * The version of the shared SDK contract this build passed.
     *
     * <p>Artifact versions differ per language, because each tracks its own
     * breaking changes: a rename in Python must not tell Java users something
     * broke. What makes the SDKs interchangeable is this, not the artifact
     * version. Two SDKs reporting the same contract version have passed the
     * same scenarios and the same vectors.
     *
     * <p>See github.com/marginfuse/sdk-contract
     */
    public static final int VERSION = 2;
}
