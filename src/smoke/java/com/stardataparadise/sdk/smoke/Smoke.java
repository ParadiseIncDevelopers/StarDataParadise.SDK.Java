package com.stardataparadise.sdk.smoke;

import com.stardataparadise.sdk.SdkVersion;

public final class Smoke {
    public static void main(String[] args) {
        String version = SdkVersion.implementationVersion();
        if (!"0.3.0-preview.1".equals(version)) {
            throw new IllegalStateException("Unexpected JAR version: " + version);
        }
        System.out.println(version);
    }
}
