# Java SDK licensing / Lisans kapsamı

## Existing SDK license

The Java SDK/JDBC implementation remains under
[StarDataParadise SDK Free-Use License 1.0](LICENSE), effective 22 November 2026.
The existing LICENSE text, copyright notice and Maven license identity are
unchanged.

This license permits no-cost personal and commercial use and redistribution
of the unmodified SDK binary with its required notices. It is not an
open-source license and does not grant general modification or redistribution
rights in the SDK implementation source. Public GitHub visibility does not
replace those terms. Server, other SDKs, Enterprise and Cloud have their own
licenses; an MIT license in another repository does not relicense this SDK.

[build.gradle](build.gradle) includes the SDK license in `META-INF/LICENSE`
and identifies it in the Maven POM. The source-classifier JAR follows
[publishing/SOURCES_README.md](publishing/SOURCES_README.md); it must not be
assumed to grant implementation-source rights.

## Independently licensed components

| Component | Role | Applicable upstream information |
| --- | --- | --- |
| Gson 2.13.2 | Runtime dependency | [Apache License 2.0](https://github.com/google/gson/blob/gson-parent-2.13.2/LICENSE) |
| Gradle 9.3.1 and wrapper scripts | Build tooling | [Upstream LICENSE and bundled notices](https://github.com/gradle/gradle/blob/v9.3.1/LICENSE); retain the script headers |
| JUnit Platform Console Standalone 1.13.4 | Test tooling | Its own distribution license and bundled dependency notices |

The SDK Free-Use License does not restrict rights independently granted by
these components. Gson is declared as a separate dependency; the ordinary
SDK JAR task does not shade it into the SDK. Anyone redistributing dependency
artifacts must preserve their own license and notice files. A build or test
dependency is not automatically included in the SDK binary.

No additional trademark, endorsement, Enterprise, hosted Cloud, support or
credential rights are granted by this explanation. Previously granted rights
remain in effect.

## Türkçe özet

Mevcut Java SDK Free-Use 1.0 lisansı aynen korunur. Değiştirilmemiş ikilinin
ücretsiz kişisel ve ticari kullanımı ile lisans bildirimleri korunarak
dağıtımı mümkündür. Kaynak kodunu değiştirme veya yeniden dağıtma hakkı
verilmez. Gson ve derleme/test araçlarının kendi lisansları geçerlidir.
