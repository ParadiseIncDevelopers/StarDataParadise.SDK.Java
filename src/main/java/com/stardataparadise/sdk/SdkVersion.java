package com.stardataparadise.sdk;

/** SDK artifact and pinned wire contract versions. */
public final class SdkVersion {
    public static final int WIRE_MAJOR = 1;
    public static final String CONTRACT_SHA256 = "e6500900a3fa34910781ea92d5e41fe8716511fac6f6d6c689ff9cce15d578ab";

    private SdkVersion() { }

    public static String implementationVersion() {
        return SdkVersion.class.getPackage().getImplementationVersion();
    }
}
