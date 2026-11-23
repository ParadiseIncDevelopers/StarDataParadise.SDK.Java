# StarDataParadise JDBC

Java 17 uygulamalarını StarDataParadise Server’a TLS üzerinden wire v1 ile bağlayan JDBC sürücüsü. Parametreli SQL, ileri yönlü sonuç kümeleri, işlemler, savepoint’ler ve PL/SQL çağrıları sunar.

## Paketi ekleme

Bu kaynak ağacının Maven koordinatı `io.github.paradiseincdevelopers:stardataparadise-jdbc:0.3.0`. Paketi ve POM dosyasını içeren bir Maven deposu kullanın. Aşağıdaki kaynak derleme adımı Maven dizinini `out/maven` altında üretir. Sürücü çalışma zamanında Gson 2.13.2 kullanır. Maven Central yayını tamamlanana kadar bu koordinatı yerel `out/maven` deposundan çözümleyin; aşağıdaki bağımlılık bildirimleri tek başına Central'da yayımlandığı anlamına gelmez.

```xml
<dependency>
  <groupId>io.github.paradiseincdevelopers</groupId>
  <artifactId>stardataparadise-jdbc</artifactId>
  <version>0.3.0</version>
</dependency>
```

Gradle Kotlin DSL için:

```kotlin
dependencies {
    implementation("io.github.paradiseincdevelopers:stardataparadise-jdbc:0.3.0")
}
```

Java import adları `com.stardataparadise.sdk` olarak kalır. Maven Central için
lisans, imzalama ve yükleme adımları [PUBLISHING.md](PUBLISHING.md) içindedir.

## Lisans

Java SDK, ücretsiz ve ticari kullanım ile lisans bildirimini korumak koşuluyla değiştirilmeyen ikili dağıtım için [StarDataParadise SDK Free-Use License 1.0](LICENSE) altında sunulur. Bu, açık kaynak yazılım lisansı değildir; Server ve Enterprise/Cloud ürünleri kendi koşullarına tabidir. Lisans ana JAR dosyasının `META-INF/LICENSE` yoluna da gömülüdür. Özgün SDK ile Gson ve diğer bağımlılıkların ayrı lisans kapsamları [LICENSING.md](LICENSING.md) dosyasında açıklanır. Paketleme ve imza işlemleri için [PUBLISHING.md](PUBLISHING.md) dosyasına bakın.

## İlk bağlantı

`jdbc:sdp://host[:port]` biçimini kullanın. Örnekte `items` tablosundaki `id` ve `note` sütunlarına okuma yetkisi gerekir. `SDP_USER`, `SDP_PASSWORD` ve CA PEM dosyasına işaret eden `SDP_CA` ortam değişkenlerini sağlayın. `tlsName` sunucu sertifikasının adıyla eşleşmeli.

```java
var properties = new java.util.Properties();
properties.setProperty("user", System.getenv("SDP_USER"));
properties.setProperty("password", System.getenv("SDP_PASSWORD"));
properties.setProperty("caFile", System.getenv("SDP_CA"));
properties.setProperty("tlsName", "localhost");

try (var connection = java.sql.DriverManager.getConnection("jdbc:sdp://127.0.0.1:7443", properties);
     var statement = connection.prepareStatement("SELECT note FROM items WHERE id = ?")) {
    statement.setLong(1, 7);
    try (var rows = statement.executeQuery()) {
        while (rows.next()) {
            System.out.println(rows.getString("note"));
        }
    }
}
```

Bir bağlantıda tek açık sunucu sonucu kullanın. Sonraki ifadeden önce sonucu tüketin veya kapatın. `setFetchSize` tek istekte alınacak en fazla satır sayısını ayarlar.

## İşlemler ve uyumluluk

- `setAutoCommit(false)`, `setSavepoint`, `commit` ve `rollback` JDBC işlem akışını sağlar. `BigDecimal` kesin DECIMAL değerlerini, `setBytes` ve `getBytes` ikili veriyi taşır.
- MySQL tabanlı uyumluluk servisinde `wire_sql_basic_v1` autocommit okumalarını, `wire_sql_transactions_v1` desteklenen InnoDB tablolarında CRUD ve işlemleri etkinleştirir. [Server uyumluluk servisi](https://github.com/ParadiseIncDevelopers/StarDataParadiseServer/tree/master/compatibility/wire_v1)
- Yazma veya commit cevabı alınamazsa `SdpException.isOutcomeUnknown()` ve `requestToken()` değerlerini kontrol edin. Aynı hesapla yeni bağlantıdan `SdpConnection.transactionOutcome(token)` çağırın.

## Native SQL ve AI kapsamı

`SdpNativeConnection` ayrı bir native SQL/AI oturumu sağlar; kullanım örneği [BUILD.md](BUILD.md) içindedir. Yeni SQL `HIST`/SQLgit ve SQL `JOB` zamanlama özellikleri ertelenmiştir ve bu sürümde etkin değildir. Mevcut `SdpConnection.historyRead()` ve `jobStatus()` wire API'leri sunucunun capability/yetki kontrolleriyle korunur.

## Kaynaktan derleme ve test

JDK 17, Gradle 9.3.1 ve doğrulanmış Gson ile JUnit JAR’ları gerekir. JAR adları ve SHA-256 değerleri [derleme kılavuzundadır](BUILD.md).

```powershell
pwsh -NoProfile -File .\ci.ps1 -JavaHome 'C:/Program Files/Java/jdk-17' -DependencyDir 'C:/sdk-dependencies' -PublishLocal
```

Betik `build` adımında sözleşme testlerini ve derlenen JAR’ın ayrı bir Java sürecinde yüklenmesini doğrular. `-PublishLocal` Maven tüketicileri için POM, çalışma zamanı ve Javadoc JAR’larını üretir. `sources` JAR'ı yalnızca açıklayıcı README içerir; Java uygulama kaynaklarını içermez. [JDBC API, testler ve Eclipse kurulumu](BUILD.md) · [Server ve diğer SDK’lar](https://github.com/ParadiseIncDevelopers/StarDataParadiseServer)
