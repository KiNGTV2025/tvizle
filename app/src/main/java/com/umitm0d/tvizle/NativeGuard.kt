package com.umitm0d.tvizle

/**
 * REL_TOKEN/APP_SEC açma işlemi ve imza karşılaştırması artık BURADA (Kotlin'de)
 * değil, native/src/main/cpp/native-lib.cpp içinde, derlenmiş .so'da yapılır.
 *
 * Neden: dex (smali) içindeki "if (sig != expected) kilitle" gibi tek satırlık
 * bir kontrol, apktool + metin editörüyle saniyeler içinde bulunup tersine
 * çevrilebilir — mod/crack araçlarının standart yöntemi tam olarak budur.
 * Aynı kontrol native tarafa taşınınca hem bulunması zorlaşır, hem de daha
 * önemlisi: GitHub token'ını / HMAC gizli anahtarını ÇÖZEN kod da aynı native
 * fonksiyonun içinde olduğu için, biri dex'teki kilit ekranını (MainActivity
 * içindeki showModBlockedScreen çağrısını) smali'den silse/atlatsa bile token
 * native'den DOĞRU imza olmadan asla gelmez — uygulama gerçek anlamda
 * işlevsiz kalır. Bu "kırılmaz" yapmaz, sadece dex-seviyesi kolay patch'i
 * işe yaramaz kılıp saldırganı native tersine mühendisliğe zorlar.
 */
object NativeGuard {
    init { System.loadLibrary("umitguard") }

    /** localSigHex == expectedSigHex ise true. (Ek/ikinci katman; birincisi MainActivity'deki hızlı Kotlin kontrolü.) */
    external fun sigOk(localSigHex: String, expectedSigHex: String): Boolean

    /** İmza uyuşmuyorsa (ya da native bir debugger tespit ettiyse) HER ZAMAN "" döner.
     *  Uyuşuyorsa obfBase64'ü gradle'daki/Kotlin deobf() ile AYNI şemayla
     *  (byte xor ((key + i) & 0xFF)) çözüp döner. */
    external fun unlockSecret(localSigHex: String, expectedSigHex: String, obfBase64: String, key: Int): String

    /** Basit/yumuşak anti-debug sinyali (ptrace self-attach). Yanlış pozitif
     *  riskine karşı yalnızca AÇIKÇA bir tracer varsa false döner. */
    external fun antiDebugOk(): Boolean
}
