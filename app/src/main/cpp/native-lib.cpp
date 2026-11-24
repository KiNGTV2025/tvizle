// ÜmitTV / tvizle — native (.so) tarafı.
//
// Neden burada: dex/smali içindeki "if (localSig != expected) kilitle" gibi
// TEK SATIRLIK bir karşılaştırma, apktool + bir metin editörüyle saniyeler
// içinde bulunup tersine çevrilebilir (mod/crack araçlarının standart
// yöntemi budur). Aynı karşılaştırma derlenmiş ARM/x86 makine koduna
// taşınınca: (a) bulunması disassembler + çaba ister, (b) burada GitHub
// token'ı / HMAC gizli anahtarını ÇÖZEN kod da aynı yerde olduğu için, dex
// tarafındaki kilit ekranı birileri tarafından atlatılsa bile (örn.
// showModBlockedScreen çağrısı smali'den silinse bile) token hâlâ native'den
// boş döner ve uygulama gerçek işlevini (API'ye bağlanma) yerine getiremez.
//
// Bu "kırılmaz" yapmaz — sadece dex-seviyesi kolay yöntemleri işe yaramaz
// kılar, saldırganı native tersine mühendisliğe zorlar.

#include <jni.h>
#include <string>
#include <cstring>
#include <cstdint>

#include <cstdio>
#include <cstdlib>

namespace {

// Android Base64 (NO_WRAP) ile üretilmiş metni çözer. Harici bağımlılık
// eklememek için standart Base64 alfabesini burada minimal şekilde çözüyoruz.
const int8_t* base64Table() {
    static int8_t t[256];
    static bool init = false;
    if (!init) {
        for (auto& v : t) v = -1;
        const char* alphabet =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < 64; i++) t[(unsigned char)alphabet[i]] = (int8_t)i;
        init = true;
    }
    return t;
}

std::string base64Decode(const std::string& in) {
    const int8_t* T = base64Table();
    std::string out;
    out.reserve(in.size() * 3 / 4 + 3);
    int val = 0, bits = -8;
    for (unsigned char c : in) {
        if (c == '=' || T[c] == -1) continue;
        val = (val << 6) + T[c];
        bits += 6;
        if (bits >= 0) {
            out.push_back((char)((val >> bits) & 0xFF));
            bits -= 8;
        }
    }
    return out;
}

// gradle (build.gradle.kts) ve Kotlin deobf() ile AYNI şema:
// byte[i] xor ((key + i) & 0xFF). Şema değişirse burası da güncellenmeli.
std::string xorDeobf(const std::string& base64, int key) {
    std::string raw = base64Decode(base64);
    std::string out(raw.size(), '\0');
    for (size_t i = 0; i < raw.size(); i++) {
        out[i] = (char)((unsigned char)raw[i] ^ ((key + (int)i) & 0xFF));
    }
    return out;
}

// Basit "zamanlama saldırısına az duyarlı" string karşılaştırma (sabit süre
// değil ama erken çıkışlı değil — statik analiz/patch için önemli olan asıl
// nokta bu karşılaştırmanın native tarafta olması).
bool constantTimeEq(const std::string& a, const std::string& b) {
    if (a.size() != b.size()) return false;
    unsigned char diff = 0;
    for (size_t i = 0; i < a.size(); i++) diff |= (unsigned char)(a[i] ^ b[i]);
    return diff == 0;
}

// Pasif debugger sinyali: /proc/self/status içindeki TracerPid'e bakar. Eskiden
// ptrace(PTRACE_TRACEME) çağrılıyordu; bu süreci kalıcı olarak "izlenen" yapıp
// bazı cihazlarda/SELinux politikalarında EPERM ile yanlış pozitif üretiyor,
// crash raporlayıcıları ve sistem araçlarını da bozabiliyordu. Artık yalnızca
// bilgi amaçlıdır; sigOk/unlockSecret ARTIK buna bağlı DEĞİL (gerçek kullanıcıyı
// kilitlememek için). Hata durumunda "debugger yok" varsayılır.
bool antiDebugOkCached() {
    static const bool result = [] {
        FILE* f = fopen("/proc/self/status", "r");
        if (!f) return true;
        char line[256];
        bool ok = true;
        while (fgets(line, sizeof line, f)) {
            if (strncmp(line, "TracerPid:", 10) == 0) {
                ok = (atoi(line + 10) == 0);
                break;
            }
        }
        fclose(f);
        return ok;
    }();
    return result;
}

} // namespace

extern "C" {

// İmza karşılaştırması: dex'teki kozmetik kilit ekranının YANINDA, bağımsız
// ikinci bir katman. (Katman 1 = Kotlin'deki hızlı kontrol / UX; katman 2 =
// burası + unlockSecret, gerçek işlevi kilitleyen katman.)
JNIEXPORT jboolean JNICALL
Java_com_umitm0d_tvizle_NativeGuard_sigOk(JNIEnv* env, jobject /*thiz*/,
                                           jstring localSigHex, jstring expectedSigHex) {
    const char* lc = env->GetStringUTFChars(localSigHex, nullptr);
    const char* ex = env->GetStringUTFChars(expectedSigHex, nullptr);
    bool ok = lc && ex && constantTimeEq(std::string(lc), std::string(ex));
    if (lc) env->ReleaseStringUTFChars(localSigHex, lc);
    if (ex) env->ReleaseStringUTFChars(expectedSigHex, ex);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// İmza eşleşmiyorsa HER ZAMAN "" döner —
// gradle'da XOR'lanmış REL_TOKEN/APP_SEC yalnızca burada, imza doğrulandıktan
// SONRA çözülür. Böylece dex'teki kilidi atlatan bir mod kopyası bile
// çalışan bir GitHub token'ı / HMAC anahtarı elde edemez.
JNIEXPORT jstring JNICALL
Java_com_umitm0d_tvizle_NativeGuard_unlockSecret(JNIEnv* env, jobject /*thiz*/,
                                                  jstring localSigHex, jstring expectedSigHex,
                                                  jstring obfBase64, jint key) {
    const char* lc = env->GetStringUTFChars(localSigHex, nullptr);
    const char* ex = env->GetStringUTFChars(expectedSigHex, nullptr);
    bool ok = lc && ex && constantTimeEq(std::string(lc), std::string(ex));
    if (lc) env->ReleaseStringUTFChars(localSigHex, lc);
    if (ex) env->ReleaseStringUTFChars(expectedSigHex, ex);
    if (!ok) return env->NewStringUTF("");

    const char* b64 = env->GetStringUTFChars(obfBase64, nullptr);
    std::string decoded = b64 ? xorDeobf(std::string(b64), (int)key) : std::string();
    if (b64) env->ReleaseStringUTFChars(obfBase64, b64);
    return env->NewStringUTF(decoded.c_str());
}

// Kotlin tarafından bağımsız olarak da çağrılabilsin diye (ileride ek
// kilitleme noktaları eklemek isterseniz) ayrı fonksiyon olarak dışa açık.
JNIEXPORT jboolean JNICALL
Java_com_umitm0d_tvizle_NativeGuard_antiDebugOk(JNIEnv* /*env*/, jobject /*thiz*/) {
    return antiDebugOkCached() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
