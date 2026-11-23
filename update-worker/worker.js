// ÜmitTV · Güncelleme Worker'ı
// Uç noktalar:
//   GET /latest  -> {versionCode, versionName, notes, size, url}  (GitHub'a ~5 sn önbellekli bakar)
//   GET /apk     -> son APK'nın (imzalı) indirme adresine 302 yönlendirir
// Secret'lar:  GH_TOKEN (okuma yetkili token; depo herkese açıksa boş bırakılabilir)
//              APP_KEY  (isteğe bağlı: doluysa ?k=APP_KEY olmayan istekler reddedilir)
// Değişken:    REPO (örn. "KiNGTV2025/tvizle-releases")
const CACHE_SEC = 5;

// ───────── /pk : şifreli sayfanın anahtarını SADECE doğrulanmış istemciye verir ─────────
// Secret'lar: PAGE_MASTER_KEY (64 hex, Pages build'indekiyle aynı), APP_HMAC_SECRET (APK'daki gizli ile aynı)
// Değişken:   ALLOWED_CERTS (virgülle APK imza SHA-256 listesi, büyük harf, iki nokta yok)
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
const unhex = (h) => new Uint8Array(h.match(/../g).map((x) => parseInt(x, 16)));
async function hmac(keyBytes, msg) {
  const k = await crypto.subtle.importKey('raw', keyBytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return new Uint8Array(await crypto.subtle.sign('HMAC', k, new TextEncoder().encode(msg)));
}
function safeEq(a, b) {
  if (a.length !== b.length) return false;
  let d = 0; for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}
async function pageKey(env, u) {
  const deny = () => new Response('yok', { status: 404, headers: { 'cache-control': 'no-store' } });
  const b = u.searchParams.get('b') || '', t = u.searchParams.get('t') || '',
        n = u.searchParams.get('n') || '', c = (u.searchParams.get('c') || '').toUpperCase(),
        s = (u.searchParams.get('s') || '').toLowerCase();
  if (!env.PAGE_MASTER_KEY || !env.APP_HMAC_SECRET) return deny();
  if (!/^[0-9a-f]{32}$/.test(b) || !/^\d{9,11}$/.test(t) || !/^[0-9a-f]{16,32}$/.test(n) || !/^[0-9A-F]{64}$/.test(c)) return deny();
  if (Math.abs(Date.now() / 1000 - parseInt(t, 10)) > 120) return deny();           // eski/tekrar istek
  const certs = String(env.ALLOWED_CERTS || '').toUpperCase().split(',').map((x) => x.trim()).filter(Boolean);
  if (!certs.includes(c)) return deny();                                             // başka imzalı (modlu) APK
  const want = hex(await hmac(new TextEncoder().encode(env.APP_HMAC_SECRET), [b, t, n, c].join('.')));
  if (!safeEq(want, s)) return deny();
  const key = await hmac(unhex(env.PAGE_MASTER_KEY), 'utv-page|' + b);
  return new Response(hex(key), { headers: { 'content-type': 'text/plain', 'cache-control': 'no-store' } });
}

const json = (o, s = 200) =>
  new Response(JSON.stringify(o), {
    status: s,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'access-control-allow-origin': '*' },
  });

async function gh(env, path, accept, extra = {}) {
  const h = { 'User-Agent': 'umittv-update-worker', Accept: accept || 'application/vnd.github+json' };
  if (env.GH_TOKEN) h.Authorization = 'Bearer ' + env.GH_TOKEN;
  return fetch('https://api.github.com/repos/' + (env.REPO || 'KiNGTV2025/tvizle-releases') + path, {
    headers: h, cf: { cacheTtl: CACHE_SEC, cacheEverything: true }, ...extra,
  });
}

async function latestRelease(env) {
  const r = await gh(env, '/releases/latest');
  if (!r.ok) throw new Error('github ' + r.status);
  const j = await r.json();
  const a = (j.assets || []).find((x) => /\.apk$/i.test(x.name));
  if (!a) throw new Error('apk yok');
  return {
    versionCode: parseInt((String(j.tag_name).match(/\d+/) || ['-1'])[0], 10),
    versionName: j.name || j.tag_name,
    notes: j.body || '',
    size: a.size || 0,
    assetId: a.id,
    browserUrl: a.browser_download_url,
  };
}

export default {
  async fetch(req, env) {
    const u = new URL(req.url);
    if (u.pathname === '/pk') return pageKey(env, u);
    if (env.APP_KEY && u.searchParams.get('k') !== env.APP_KEY) return json({ error: 'yetkisiz' }, 403);
    try {
      if (u.pathname === '/latest') {
        const r = await latestRelease(env);
        const k = env.APP_KEY ? '?k=' + encodeURIComponent(env.APP_KEY) : '';
        return json({ versionCode: r.versionCode, versionName: r.versionName, notes: r.notes, size: r.size, url: u.origin + '/apk' + k });
      }
      if (u.pathname === '/apk') {
        const r = await latestRelease(env);
        if (env.GH_TOKEN) {
          // özel depo: token ile asset adresine git, kısa ömürlü imzalı adresi al
          const res = await gh(env, '/releases/assets/' + r.assetId, 'application/octet-stream', { redirect: 'manual', cf: { cacheTtl: 0 } });
          const loc = res.headers.get('location');
          if (loc) return Response.redirect(loc, 302);
          return json({ error: 'indirme adresi alınamadı', status: res.status }, 502);
        }
        return Response.redirect(r.browserUrl, 302);
      }
      return json({ ok: true, endpoints: ['/latest', '/apk'] });
    } catch (e) {
      return json({ error: String(e && e.message || e) }, 502);
    }
  },
};
