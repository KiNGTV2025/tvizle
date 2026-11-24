// ÜmitTV · Güncelleme Worker'ı
const CACHE_SEC = 5;

// ───── EPG kaynakları (worker üzerinden çekilir) ─────
const EPG_TTL = 900; // 15 dk önbellek
const EPG_URLS = [
  'https://raw.githubusercontent.com/umitm0d/Liveinlive/refs/heads/main/epg.xml',
  'https://www.dropbox.com/scl/fi/3pjlobi13kyuhe2x7tgzh/kabloepg.xml?rlkey=x94qwq7bkaleh58dqmav05487&st=soajh7i7&dl=1',
  'https://www.dropbox.com/scl/fi/g8vofipawb6oogz2jbjel/umitmod_tivibu.xml?rlkey=lzj7l4nh8nph23emal6nct5zz&st=hgsj445s&dl=1',
  'https://www.dropbox.com/scl/fi/m8c1bid23uve14dxz9t5i/umitmod_dsmart.xml?rlkey=504vtun1pvpv4dj4e04v7v3zl&st=hb4sd2vz&dl=1',
  'https://www.dropbox.com/scl/fi/ecj51i4f6oizzvf8snys1/umitmod_tvplus.xml?rlkey=7czl2oz9tlgmen7o13jqvjkld&st=5srjr1av&dl=1',
  'https://www.dropbox.com/scl/fi/o78wibe7dhsm8mkygwsfr/epg.xml?rlkey=bdl2dyjc41h5hpkbur0egkm93&st=rppnx4a4&dl=1',
];

// ───── /pk : şifreli sayfanın anahtarını sadece doğrulanmış APK'ya verir ─────
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
const unhex = (h) => new Uint8Array(h.match(/../g).map((x) => parseInt(x, 16)));

async function hmac(keyBytes, msg) {
  const k = await crypto.subtle.importKey('raw', keyBytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return new Uint8Array(await crypto.subtle.sign('HMAC', k, new TextEncoder().encode(msg)));
}

function safeEq(a, b) {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

async function pageKey(env, u) {
  const deny = () => new Response('yok', { status: 404, headers: { 'cache-control': 'no-store' } });
  const b = u.searchParams.get('b') || '',
    t = u.searchParams.get('t') || '',
    n = u.searchParams.get('n') || '',
    c = (u.searchParams.get('c') || '').toUpperCase(),
    s = (u.searchParams.get('s') || '').toLowerCase();
  if (!env.PAGE_MASTER_KEY || !env.APP_HMAC_SECRET) return deny();
  if (!/^[0-9a-f]{32}$/.test(b) || !/^\d{9,11}$/.test(t) || !/^[0-9a-f]{16,32}$/.test(n) || !/^[0-9A-F]{64}$/.test(c)) return deny();
  if (Math.abs(Date.now() / 1000 - parseInt(t, 10)) > 120) return deny();
  const certs = String(env.ALLOWED_CERTS || '').toUpperCase().split(',').map((x) => x.trim()).filter(Boolean);
  if (!certs.includes(c)) return deny();
  const want = hex(await hmac(new TextEncoder().encode(env.APP_HMAC_SECRET), [b, t, n, c].join('.')));
  if (!safeEq(want, s)) return deny();
  const key = await hmac(unhex(env.PAGE_MASTER_KEY), 'utv-page|' + b);
  return new Response(hex(key), { headers: { 'content-type': 'text/plain', 'cache-control': 'no-store' } });
}

const json = (o, s = 200) =>
  new Response(JSON.stringify(o), {
    status: s,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'access-control-allow-origin': '*',
    },
  });

// ───── /epg?i=0..5 : EPG XML dosyalarını tek tek aktarır ─────
async function epg(u) {
  const i = u.searchParams.get('i');
  if (i === null) return json({ count: EPG_URLS.length, usage: '/epg?i=0..' + (EPG_URLS.length - 1) });
  const src = EPG_URLS[parseInt(i, 10)];
  if (!src) return json({ error: 'geçersiz kaynak' }, 400);
  const r = await fetch(src, {
    redirect: 'follow',
    cf: { cacheTtl: EPG_TTL, cacheEverything: true },
  });
  if (!r.ok) return json({ error: 'epg kaynağı hata', status: r.status }, 502);
  return new Response(r.body, {
    headers: {
      'content-type': 'application/xml; charset=utf-8',
      'cache-control': 'public, max-age=' + EPG_TTL,
      'access-control-allow-origin': '*',
    },
  });
}

// ───── GitHub release ─────
async function gh(env, path, accept, extra = {}) {
  const h = { 'User-Agent': 'umittv-update-worker', Accept: accept || 'application/vnd.github+json' };
  if (env.GH_TOKEN) h.Authorization = 'Bearer ' + env.GH_TOKEN;
  return fetch('https://api.github.com/repos/' + (env.REPO || 'KiNGTV2025/tvizle-releases') + path, {
    headers: h,
    cf: { cacheTtl: CACHE_SEC, cacheEverything: true },
    ...extra,
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
      if (u.pathname === '/epg') return await epg(u);

      if (u.pathname === '/latest') {
        const r = await latestRelease(env);
        const k = env.APP_KEY ? '?k=' + encodeURIComponent(env.APP_KEY) : '';
        return json({
          versionCode: r.versionCode,
          versionName: r.versionName,
          notes: r.notes,
          size: r.size,
          url: u.origin + '/apk' + k,
        });
      }

      if (u.pathname === '/apk') {
        const r = await latestRelease(env);
        if (env.GH_TOKEN) {
          const res = await gh(env, '/releases/assets/' + r.assetId, 'application/octet-stream', {
            redirect: 'manual',
            cf: { cacheTtl: 0 },
          });
          const loc = res.headers.get('location');
          if (loc) return Response.redirect(loc, 302);
          return json({ error: 'indirme adresi alınamadı', status: res.status }, 502);
        }
        return Response.redirect(r.browserUrl, 302);
      }

      return json({ ok: true, endpoints: ['/latest', '/apk', '/epg'] });
    } catch (e) {
      return json({ error: String((e && e.message) || e) }, 502);
    }
  },
};
