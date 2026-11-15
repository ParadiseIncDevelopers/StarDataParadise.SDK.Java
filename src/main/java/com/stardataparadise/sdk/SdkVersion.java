package com.stardataparadise.sdk;

public final class SdkVersion {
    private SdkVersion() {}

    public static String implementationVersion() {
        return SdkVersion.class.getPackage().getImplementationVersion();
    }
}
