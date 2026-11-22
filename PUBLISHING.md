# Maven Central yayını

Yayın kimliği `io.github.paradiseincdevelopers:stardataparadise-jdbc:0.3.0`.
Java paketleri ve otomatik modül adı `com.stardataparadise.sdk` olarak kalır.
Maven ve Gradle aynı Central yayınını tüketir; iki ayrı yükleme yapılmaz.

## Ön koşullar

- Central Portal'da `io.github.paradiseincdevelopers` namespace'i doğrulanmış olmalıdır.
- SDK sahibinin onayladığı gerçek dağıtım lisansı ve bu lisansın URL'si gerekir.
  Bu depo kendiliğinden MIT, Apache veya başka bir lisans atamaz. Lisans adı ve
  URL'si verilmeden yerel derleme yapılabilir; imzalı yayın hazırlanamaz.
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

Yalnızca gerçek lisans seçilip URL'si yayımlandıktan ve uygun GPG anahtarı yerelde
kurulduktan sonra çalıştırın. Aşağıdaki ortam değişkenlerinin değerleri örnek
lisans değil, yayıncı tarafından sağlanan gerçek lisans adı ve URL'si olmalıdır:

```powershell
if (-not $env:SDP_SDK_LICENSE_NAME -or -not $env:SDP_SDK_LICENSE_URL) {
    throw 'Onaylanmış SDK lisans adı ve URL ortam değişkenlerini ayarlayın.'
}
.\gradlew.bat --offline centralBundle -PsdpSign=true
if ($LASTEXITCODE) { throw 'İmzalama veya Central arşivi hazırlanamadı.' }
```

`JAVA_HOME` JDK 17'yi, `SDP_SDK_DEPS` doğrulanmış bağımlılık dizinini göstermelidir.
GPG, sistemdeki imzalama anahtarını ve normal parola istemini kullanır.
Birden fazla anahtar varsa kullanıcıya ait Gradle ayarlarında
`signing.gnupg.keyName` ile doğru anahtar parmak izini seçin. Bu ayarları veya
parolayı proje deposuna eklemeyin. Gradle lisans özellikleri
`sdpLicenseName` ve `sdpLicenseUrl`, yukarıdaki ortam değişkenlerine alternatiftir.

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
