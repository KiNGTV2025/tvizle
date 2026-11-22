plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.umitm0d.tvizle"
    compileSdk = 36   // Media3 1.11.x ve güncel AndroidX bağımlılıkları için (targetSdk 30 aynen kalır)
    defaultConfig {
        applicationId = "com.umitm0d.tvizle"
        minSdk = 23   // Media3 1.9+ minSdk 23 (Android 6.0) istiyor
        targetSdk = 30
        versionCode = (project.findProperty("vcode") as String?)?.toIntOrNull() ?: 6
        versionName = (project.findProperty("vname") as String?) ?: "1.5"
    }
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
    // Android Auto (Car App Library)
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.lifecycle:lifecycle-common:2.6.2")
    implementation("androidx.core:core-ktx:1.13.1")
    // Kendi oynatıcımız (Televizo da ExoPlayer motorunu kullanıyor; bu açık kaynak sürümü)
    // Media3 1.11.1 = en güncel KARARLI sürüm (10 Eylül 2026). FFmpeg ses dekoderi ile AYNI sürüm olmalı; ikisi birlikte yükseltilir.
    // (1.12 henüz kararlı değil ve minSdk 24 ister; uygulama minSdk 23)
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-rtmp:$media3")
    // FFmpeg ses dekoderi (media3 1.11.1 ile birebir eşleşen sürüm, LGPL FFmpeg derlemesi): AC3/E-AC3/DTS/AAC/MP3/Opus/Vorbis/FLAC vb.
    // DefaultRenderersFactory (EXTENSION_RENDERER_MODE_PREFER) bunu sınıf yolunda bulunca kendisi yükler.
    implementation("io.put:media3-ffmpeg-decoder:1.11.1+1")
    // Çözülemezse yedek (GPLv3, 1.9.0+1; 1.11.1 ile çalıştığı üçüncü kişilerce bildirildi): implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
}
