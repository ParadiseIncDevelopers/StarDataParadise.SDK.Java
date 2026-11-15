# Java SDK derleme ve test

JDK 17 (doğrulama ortamında 17.0.12) ve Gradle 9.3.1 kullanılır. Gradle `java-library` eklentisi dağıtımla gelir. Kütüphanenin harici Maven bağımlılığı yoktur.

```powershell
powershell -NoProfile -File .\ci.ps1
```

`gradle` PATH üzerinde yoksa `-Gradle "C:/gradle-9.3.1/bin/gradle.bat"` belirt. Çıktı `out/build/libs/StarDataParadise.SDK.Java-0.1.0.jar` olur. `check` görevi üretilen JAR'ı ayrı Java sürecinde yükler ve manifest sürümünü kontrol eder.

Gradle Apache-2.0 lisanslıdır. [Gradle 9.3.1 SHA-256](https://gradle.org/release-checksums/). JDK dağıtımının kendi lisansı uygulanır.
