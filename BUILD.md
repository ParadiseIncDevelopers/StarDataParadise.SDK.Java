# StarDataParadise JDBC

Gereksinimler Java 17 ve Gradle 9.3.1. Wrapper, Gradle dağıtımını ilk kullanımda indirir ve SHA-256 değerini doğrular. Yerel Gradle kurulumunu kullanmak için `-Gradle` ile `gradle.bat` yolunu belirt. [Resmi Gradle sağlama toplamları](https://gradle.org/release-checksums/)

`SDP_SDK_DEPS` ortam değişkeni veya `-DependencyDir` seçeneği aşağıdaki iki JAR'ın bulunduğu dizini gösterir. Gradle dosyaları derlemeden önce doğrular. Test bağımlılıkları SDK'ya eklenmez.

| Dosya | SHA-256 |
| --- | --- |
| `gson-2.13.2.jar` | `dd0ce1b55a3ed2080cb70f9c655850cda86c206862310009dcb5e5c95265a5e0` |
| `junit-platform-console-standalone-1.13.4.jar` | `3fdfc37e29744a9a67dd5365e81467e26fbde0b7aa204e6f8bbe79eeaa7ae892` |

~~~powershell
powershell -NoProfile -File .\ci.ps1 -JavaHome 'C:/Program Files/Java/jdk-17' -DependencyDir 'C:/sdk-dependencies' -PublishLocal
~~~

`build`, JUnit sözleşme testlerini ve oluşturulan JAR'ı ayrı Java sürecinde yükleyen sürüm testini çalıştırır. `-PublishLocal` ile Maven dizini, POM, Gradle modül metadata'sı, çalışma zamanı, kaynak ve Javadoc JAR'ları `out/maven` altına yazılır. Uygulamanın tükettiği koordinat `com.stardataparadise:stardataparadise-jdbc:0.3.0`, bağımlılığı `com.google.code.gson:gson:2.13.2` olur. Java export istemcisi SDK 0.3.0 ile wire v1 sözleşmesinin sabitlenmiş SHA-256 değerini `SdkVersion` üzerinden okuyabilir.

Eclipse Buildship ile içe aktarmadan önce `SDP_SDK_DEPS` değişkenini Eclipse sürecine ver ve Gradle JVM olarak JDK 17'yi seç. **File → Import → Gradle → Existing Gradle Project** menüsünde depo kökünü ve Gradle Wrapper'ı kullan.

## Parametreli JDBC

~~~java
var properties = new java.util.Properties();
properties.setProperty("user", System.getenv("SDP_USER"));
properties.setProperty("password", System.getenv("SDP_PASSWORD"));
properties.setProperty("caFile", "C:/certs/star-data-ca.pem");
properties.setProperty("tlsName", "localhost");
try (var connection = java.sql.DriverManager.getConnection("jdbc:sdp://127.0.0.1:7443", properties);
     var query = connection.prepareStatement("SELECT note, amount FROM notes WHERE id = ?")) {
    query.setLong(1, 42);
    try (var rows = query.executeQuery()) {
        while (rows.next()) {
            System.out.println(rows.getString("note") + " " + rows.getBigDecimal("amount"));
        }
    }
}
~~~

- Bağlantı TLS gerektirir. `caFile` güvenilen PEM zincirini ve `tlsName` sertifikadaki DNS adını belirtir. `connectTimeoutMs` ve `queryTimeoutMs` bağlantı özellikleri, `Statement.setQueryTimeout` ve `Connection.setNetworkTimeout` JDBC yöntemleriyle birlikte uygulanır.
- Sorgular ileri yönlü, salt okunur `ResultSet` üretir. `setFetchSize` tek istekte alınan en fazla satır sayısını ayarlar. Her bağlantıda tek bir açık sunucu sonucu bulunabilir. Sonraki ifadeden önce sonucu kapat veya tüket.
- `prepareCall("{call my_proc(?,?)}")` ile saf OUT için `registerOutParameter` çağır ve bağımsız bir giriş değeri atama. IN OUT için hem değeri bağla hem kaydet. `getMoreResults()` açık sonucu kapatıp sıradaki sonuç kümesine geçer.
- `DECIMAL` için `BigDecimal`, tamsayı ondalıkları için `BigInteger`, saat dilimli zaman için `OffsetDateTime` veya `SdpValues.Timestamp` kullan. İkili girdiler `setBinaryStream` ve `setBytes` ile, çıktılar `getBinaryStream` ve `getBytes` ile taşınır. İç içe PL/SQL değerleri `SdpValues.RecordValue` ve `SdpValues.CollectionValue` olarak temsil edilir.
- `setAutoCommit(false)` ile transaction aç, `setSavepoint`, `commit` ve `rollback` kullan. Bağlantıyı havuza vermeden önce `unwrap(SdpConnection.class).resetSession()` çağır ve hata durumunda bağlantıyı kapat.
- `Statement.cancel()` bekleyen isteği iptal edip bağlantıyı kapatır. İzinli ayrı bir oturum `SdpConnection.cancelQuery` ile sunucudaki istek kimliğini hedefleyebilir.
- Yeni MySQL tabanlı Server, ilgili hesaba `wire_sql_transactions_v1` bildirdiğinde JDBC `SELECT`, `INSERT`, `UPDATE` ve `DELETE` ifadelerini, autocommit ve `SERIALIZABLE` işlemleri destekler. Yazılacak InnoDB tablolarını MySQL tarafında oluştur. `wire_sql_basic_v1` ile JDBC autocommit okuması kullanılabilir.
- Yazma veya commit cevabı kaybolduğunda `SdpException.isOutcomeUnknown()` ve `requestToken()` kontrol edilir. Yeni oturumdan `SdpConnection.transactionOutcome(token)` çağır. SDK yazmayı otomatik tekrarlamaz.
- `SdpConnection` metadata, model tahmini, job durumu, Decision inceleme, History okuma ve yönetici sağlık verisini sunucudaki izinlerle sunar. Kaydırılabilir sonuç, batch, üretilen anahtar, XA ve diğer desteklenmeyen JDBC çağrıları `SQLFeatureNotSupportedException` üretir.

## Native SQL ve AI

`SdpNativeConnection.open(...)`, `native_sql_v1` ilan eden sunucuya ayrı bir
authenticated TLS SQL bağlantısı açar. Var olan `java.sql.Connection` yaşam
döngüsünden bağımsızdır; positional `?` parametreleri native prepared protocol'e
aktarılır. Prosedür gövdesi tek statement olarak gönderilir.

```java
import com.stardataparadise.sdk.SdpNativeConnection;
import java.nio.file.Path;

try (var db = SdpNativeConnection.open("127.0.0.1", 7443, "localhost",
        Path.of(System.getenv("SDP_CA")), System.getenv("SDP_USER"),
        System.getenv("SDP_PASSWORD"), 75000, true)) {
    db.execute("START AI CONVERSATION app.assistant"); // Mevcut konuşma.
    db.execute("APPEND PROMPT ?", "Onaylanmış bağlamı özetle.");
    db.execute("APPEND RESPONSE INTO @answer AS JSON RETURNING METADATA INTO @meta");
    db.commit(false, null, 20000);
    var row = db.execute("SELECT @answer,@meta").sets().get(0).rows().get(0);
    System.out.println(((SdpNativeConnection.Json) row.get(0)).text());
}
```

Model tanımı OpenAI, Claude veya içe aktarılmış yerel GGUF runtime kullanabilir;
API anahtarı SDK sorgusuna taşınmaz. `commit(true, requestKey, null)` async run
belgesi döndürür; `fetchResponse(runId)` ve `cancelRun(runId)` aynı durable run'ı
izler.

Native SQL `HIST` (SQLgit dal/remote) ve SQL `JOB` zamanlama komutları ertelenmiştir;
bu sürümde etkin değildir. Mevcut wire History/Compute/job API'leri sunucunun ilan
ettiği capability ve yetkilere bağlı olarak korunur. Bu API'ler ertelenen SQL
komutlarından ayrıdır; AI inference ve model import kendi iç kuyruğunu kullanır.

Native sonuçlar `sets/columns/rows` ve affectedRows metadata'sı içerir. Signed
integer Long, uint64 BigInteger, DECIMAL BigDecimal, binary byte[], SQL NULL null,
JSON `SdpNativeConnection.Json`, temporal değer `Temporal` olarak korunur.
`Json.text()` ve Gson lexical-number ağacı büyük JSON sayılarının basamaklarını
korur; `double` dönüşümü uygulamanın bilinçli seçimidir. Büyük sonuçlar SHA ile
doğrulanan parçalarla, sunucunun toplam bütçesi içinde birleştirilir.

Native statement otomatik tekrarlanmaz veya yeniden bağlanmaz. `SdpException`
`nativeErrno()`, `phase()`, `commitState()`, `runState()` ve `runId()` taşır.
Bekleme hatasındaki `commitState=committed` iş SQL'inin geri alındığı anlamına
gelmez; run sorgulanır. Native belirsiz transport sonucu legacy CRUD receipt
token'ı üretmez. Var olan JDBC API ve receipt davranışı korunur.
