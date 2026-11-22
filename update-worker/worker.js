// ÜmitTV · Güncelleme Worker'ı
// Uç noktalar:
//   GET /latest  -> {versionCode, versionName, notes, size, url}  (GitHub'a ~5 sn önbellekli bakar)
//   GET /apk     -> son APK'nın (imzalı) indirme adresine 302 yönlendirir
// Secret'lar:  GH_TOKEN (okuma yetkili token; depo herkese açıksa boş bırakılabilir)
//              APP_KEY  (isteğe bağlı: doluysa ?k=APP_KEY olmayan istekler reddedilir)
// Değişken:    REPO (örn. "KiNGTV2025/tvizle-releases")
const CACHE_SEC = 5;

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
