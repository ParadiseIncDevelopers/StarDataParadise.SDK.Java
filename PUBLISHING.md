# Maven Central yayını

Yayın kimliği `io.github.paradiseincdevelopers:stardataparadise-jdbc:0.3.0`.
Java paketleri ve otomatik modül adı `com.stardataparadise.sdk` olarak kalır.
Maven ve Gradle aynı Central yayınını tüketir; iki ayrı yükleme yapılmaz.

## Ön koşullar

- Central Portal'da `io.github.paradiseincdevelopers` namespace'i doğrulanmış olmalıdır.
- SDK için `LICENSE` dosyasındaki **StarDataParadise SDK Free-Use License 1.0** geçerlidir. Lisans, ana JAR içinde `META-INF/LICENSE`, kaynak arşivinde `META-INF/LICENSE` ve Maven POM içinde doğrulanabilir [GitHub LICENSE](https://github.com/ParadiseIncDevelopers/StarDataParadise.SDK.Java/blob/master/LICENSE) bağlantısı olarak yer alır. Bu lisans, Server'ın MIT lisansından ayrıdır ve kaynak kodunu açık kaynak yapmaz. Hukuki gözden geçirme ve hak sahibinin onayı önerilir.
- Yayıncıya ait PGP imzalama anahtarı gerekir. Özel anahtar ve parolası depoya
  konmaz. Açık anahtar Sonatype'ın desteklediği bir anahtar sunucusunda erişilebilir
  olmalıdır. GPG kimliği ve açık anahtar yayımla birlikte kamuya açık olur.
- Hesabın güncel yayın koşulları kontrol edilmelidir. Sonatype, 1 Ekim 2026'dan
  itibaren ticari bir ürün veya hizmetin benimsenmesini, entegrasyonunu ya da
  işletilmesini destekleyen artefaktlar için yayın hacminden bağımsız Publisher Pro
  şartı bildirir. Tek paket veya ücretsiz indirilebilen paket olmak tek başına
  muafiyet sağlamaz. Ücret gerekmemesi şartıyla ilerleyen bir yayın, uygunluğu
  netleşmeden ücretli plana geçirilmemelidir.

Kaynaklar: [Sonatype yayın gereksinimleri](https://central.sonatype.org/publish/requirements/),
[PGP kılavuzu](https://central.sonatype.org/publish/requirements/gpg/),
[yayın koşulları](https://central.sonatype.org/publish/maven-central-publishing-limits/).

## Yerel doğrulama

[BUILD.md](BUILD.md) içindeki JDK 17, Gradle 9.3.1 ve doğrulanmış bağımlılık
kurulumunu tamamlayın. PowerShell:

```powershell
pwsh -NoProfile -File .\ci.ps1 -JavaHome 'C:/Program Files/Java/jdk-17' -DependencyDir 'C:/sdk-dependencies' -PublishLocal
if ($LASTEXITCODE) { throw 'Derleme veya yerel yayın başarısız.' }
```

Yerel çıktı dizini:

```text
out/maven/io/github/paradiseincdevelopers/stardataparadise-jdbc/0.3.0/
```

Ana JAR, POM, Javadoc JAR'ı ve yalnızca açıklama içeren `sources` JAR'ını inceleyin.
`sources` JAR'ında Java uygulama kaynakları bulunmaz. Bu yaklaşım Sonatype'ın
kaynak arşivi gereksiniminde açıkça desteklenir. Yerel testler canlı sunucu/TLS
kabul testinin yerine geçmez.

## İmzalı yükleme arşivi

SDK lisansı doğrudan depodaki `LICENSE` dosyasına gömülüdür; lisans ortam değişkenleri gerekmez. Uygun yayıncı GPG anahtarı yerelde kurulduktan ve ücretsiz yayın uygunluğu doğrulandıktan sonra çalıştırın:

```powershell
.\gradlew.bat --offline centralBundle -PsdpSign=true
if ($LASTEXITCODE) { throw 'İmzalama veya Central arşivi hazırlanamadı.' }
```

`JAVA_HOME` JDK 17'yi, `SDP_SDK_DEPS` doğrulanmış bağımlılık dizinini göstermelidir.
GPG, sistemdeki imzalama anahtarını ve normal parola istemini kullanır.
Birden fazla anahtar varsa kullanıcıya ait Gradle ayarlarında
`signing.gnupg.keyName` ile doğru anahtar parmak izini seçin. Bu ayarları veya
parolayı proje deposuna eklemeyin. SDK lisansı ve POM lisans URL'si `build.gradle` ve kök `LICENSE` dosyasında tanımlıdır.

`centralBundle`, testleri ve yerel yayını çalıştırır. İmzalama etkin değilse veya
zorunlu dosyalar eksikse yüklenebilir ZIP üretmez. Arşivin yolu:

```text
out/central/stardataparadise-jdbc-0.3.0-central.zip
```

ZIP aşağıdaki dört zorunlu dosyayı, Gradle modül metadata'sını ve her birinin
`.asc`, `.md5`, `.sha1` dosyalarını içerir. Toplam 20 dosyanın dizin kökü `io/`
ile başlar:

- `stardataparadise-jdbc-0.3.0.jar`
- `stardataparadise-jdbc-0.3.0.pom`
- `stardataparadise-jdbc-0.3.0-sources.jar`
- `stardataparadise-jdbc-0.3.0-javadoc.jar`
- `stardataparadise-jdbc-0.3.0.module`

Yüklemeden önce beş dosyanın imzasını `gpg --verify DOSYA.asc DOSYA` ile
doğrulayın ve son POM'daki koordinat, lisans, geliştirici, SCM ve Gson bağımlılığını
kontrol edin. İmzalandıktan sonra dosyaları değiştirmeyin.

## Central Portal yüklemesi

[Central Portal](https://central.sonatype.com/publishing) içinde
**Publish Component** ile bu ZIP'i seçin. Yükleme sonrası doğrulama sonucunu
inceleyin; başarılı doğrulamadan sonra **Publish** adımını tamamlayın.
Tarayıcıdan bu yükleme için ayrıca API tokenı gerekmez. API veya otomatik CI
yayını kurulacaksa Portal'ın ayrı yayın tokenı kullanılmalıdır.

Yayın `PUBLISHED` olduktan sonra boş Maven ve Gradle önbellekleriyle gerçek
Central deposundan çözümlemeyi doğrulayın. Gradle tüketicisi `mavenCentral()`
deposunu ve README'deki bağımlılığı kullanır. Maven Central'da yayımlanmış sürüm
üzerine yazılamaz; sonraki değişiklik yeni sürüm gerektirir.

[Resmî elle yükleme kılavuzu](https://central.sonatype.org/publish/publish-portal-upload/)
· [Gradle GPG imzalama](https://docs.gradle.org/current/userguide/signing_plugin.html#sec:using_gpg_agent)

## Ücretsiz yerel paket (imzasız)

Ücretli Central aboneliği veya yayıncıya ait GPG anahtarı gerektirmeyen, **yerel Maven deposu** olarak kullanılabilen bir paket üretilebilir:

```powershell
pwsh -NoProfile -File .\ci.ps1 -JavaHome 'C:/Program Files/Java/jdk-17' -DependencyDir 'C:/sdk-dependencies' -PublishLocal
```

`.github/workflows/build-java-sdk.yml` GitHub Actions iş akışı, Java 17 ile testleri ve yerel Maven dağıtımını üretir. Çıktı `stardataparadise-jdbc-0.3.0-maven-local.zip` olarak workflow artifact'ine eklenir. Bu dosya **Maven Central'a yüklemeye uygun imzalı paket değildir**. Maven Central yüklemesi için ayrıca yayıncıya ait GPG imzaları ve koşul incelemesi gereklidir.
