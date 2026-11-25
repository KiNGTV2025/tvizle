import java.util.Base64

// Otomatik güncelleme testi: bu satır 1.7 sürümünü tetiklemek için eklendi
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.umitm0d.tvizle"
    compileSdk = 36   // Media3 1.11.x ve güncel AndroidX bağımlılıkları için (targetSdk 30 aynen kalır)
    ndkVersion = "27.0.12077973"   // CI'daki (build.yml) sdkmanager kurulumuyla AYNI sürüm olmalı
    defaultConfig {
        applicationId = "com.umitm0d.tvizle"
        minSdk = 23   // Media3 1.9+ minSdk 23 (Android 6.0) istiyor
        targetSdk = 30
        versionCode = (project.findProperty("vcode") as String?)?.toIntOrNull() ?: 905
        versionName = (project.findProperty("vname") as String?) ?: "9.5"
        // Sayfa anahtarı isteğini imzalayan gizli (CI secret'ı APP_HMAC_SECRET; kaynakta yok, APK'da XOR'lu)
        val appSec = System.getenv("APP_HMAC_SECRET") ?: ""
        val appSecEnc = Base64.getEncoder().encodeToString(
            ByteArray(appSec.length) { i -> (appSec[i].code xor ((0x5A + i) and 0xFF)).toByte() })
        buildConfigField("String", "APP_SEC", "\"$appSecEnc\"")
        // Gizli anahtar çözme + imza doğrulama artık native (.so) tarafında (NativeGuard) —
        // dex'teki kolay smali-patch yöntemlerine karşı. Yaygın cihazları kapsayan ABI'ler.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            cmake { cppFlags += "-std=c++17" }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures { buildConfig = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Dex'in MT Manager / jadx gibi araçlarla okunsa bile anlamsız kalması için:
            // R8 ile kod küçültme + isim karartma (obfuscation) + kullanılmayan kaynakları silme.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
// Kotlin 2.2'de eski kotlinOptions DSL'i hata veriyor; yenisi:
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android Auto (Car App Library)
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.lifecycle:lifecycle-common:2.6.2")
    implementation("androidx.core:core-ktx:1.13.1")
    // Kendi oynatıcımız (Televizo da ExoPlayer motorunu kullanıyor; bu açık kaynak sürümü)
    // GERİ ALINDI (build log'da görüldü): io.put:media3-ffmpeg-decoder:1.11.1+1 kendi AndroidManifest'inde
    // minSdk 26 istiyor → "uses-sdk:minSdkVersion 23 cannot be smaller than version 26" ile derleme patlıyor,
    // çünkü uygulama minSdk 23 (eski TV box'lar için). io.put'ın şu an minSdk 23 ile uyumlu sürümü yok.
    // Bu yüzden media3 1.9.0 + onunla AYNI sürümde, minSdk 23 ile derlendiği doğrulanan Jellyfin FFmpeg
    // dekoderine (org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1, GPLv3) dönüldü. "En güncel" isteniyorsa
    // tek yol: minSdk'yı 26'ya çıkarıp io.put:media3-ffmpeg-decoder:1.11.1+1'e geri dönmek (Android 6/7 TV
    // box'ları artık açılmaz) — isterseniz bunu yaparım, söylemeniz yeterli.
    val media3 = "1.9.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-rtmp:$media3")
    // FFmpeg ses dekoderi (media3 1.9.0 ile aynı sürüm, minSdk 23 uyumlu, GPLv3 FFmpeg derlemesi):
    // AC3/E-AC3/DTS/AAC/MP3/Opus/Vorbis/FLAC vb. DefaultRenderersFactory (EXTENSION_RENDERER_MODE_PREFER)
    // bunu sınıf yolunda bulunca kendisi yükler.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
}
