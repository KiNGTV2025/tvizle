/* ÜmitTV – ExoPlayer köprüsü (sayfa tarafı)
 * Yalnızca uygulamanın içinde (window.UmitVideo varsa) çalışır; tarayıcıda hiçbir şey yapmaz ve sayfa eskisi gibi hls.js kullanır.
 * - #vid <video> öğesinin davranışı ExoPlayer'a yönlendirilir. Sayfadaki tüm butonlar (oynat/duraklat, sar, ses, kalite,
 *   ses dili, en-boy, hız) hiç değişmeden çalışır; çünkü sayfa yine vid.play()/pause()/currentTime/volume... kullanır.
 * - window.Hls ve window.mpegts yerine sahte sınıflar konur: hls.js / mpegts.js hiç yüklenmez, kaynaklar ExoPlayer'a gider.
 * - ExoPlayer görüntüyü WebView'in ARKASINDAKİ SurfaceView'e çizer; #vid'in bulunduğu dikdörtgen native tarafa bildirilir. */
(function () {
  var B = window.UmitVideo;
  if (!B || typeof B.load !== 'function') return;
  if (window.__umitNV) return;
  window.__umitNativeOwn = true;   // eski 'ayrı ekranda aç' kancası devre dışı kalsın
  window.__umitNoPreroll = true;   // sayfanın canlı-yayın ön tamponu (hls.js için) gereksiz: ExoPlayer kendi tamponunu yönetir, kanal daha hızlı açılır

  var vid = null, S = fresh(), hlsActive = null, mtsActive = null;
  var lastRect = '', rectT = 0, pwrLock = 0, seekT = 0, seekWait = 0;

  function fresh() {
    return { native: false, src: '', paused: true, ended: false, pos: 0, posAt: 0, dur: NaN, buf: 0, rs: 0, w: 0, h: 0,
      live: false, win: 0, dp: NaN, vol: 1, muted: false, rate: 1, seeking: false, err: null, st: 0, pwr: false,
      levels: [], level: -1, audio: [], audioSel: 0, dropped: 0, meta: false, ready1: false, curDur: NaN, retried: 0 };
  }
  function now() { return (window.performance && performance.now) ? performance.now() : Date.now(); }
  function log() { try { console.log.apply(console, ['[ExoPlayer]'].concat([].slice.call(arguments))); } catch (e) {} }

  /* ───────── Hls sahte sınıfı ───────── */
  var HE = { MEDIA_ATTACHING: 'hlsMediaAttaching', MEDIA_ATTACHED: 'hlsMediaAttached', MEDIA_DETACHED: 'hlsMediaDetached',
    MANIFEST_LOADING: 'hlsManifestLoading', MANIFEST_LOADED: 'hlsManifestLoaded', MANIFEST_PARSED: 'hlsManifestParsed',
    LEVEL_LOADED: 'hlsLevelLoaded', LEVEL_UPDATED: 'hlsLevelUpdated', LEVEL_SWITCHED: 'hlsLevelSwitched',
    AUDIO_TRACKS_UPDATED: 'hlsAudioTracksUpdated', AUDIO_TRACK_SWITCHED: 'hlsAudioTrackSwitched',
    FRAG_LOADED: 'hlsFragLoaded', ERROR: 'hlsError', DESTROYING: 'hlsDestroying' };
  var HT = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError', KEY_SYSTEM_ERROR: 'keySystemError', MUX_ERROR: 'muxError', OTHER_ERROR: 'otherError' };
  var HD = { MANIFEST_LOAD_ERROR: 'manifestLoadError', MANIFEST_LOAD_TIMEOUT: 'manifestLoadTimeOut', MANIFEST_PARSING_ERROR: 'manifestParsingError',
    LEVEL_LOAD_ERROR: 'levelLoadError', FRAG_LOAD_ERROR: 'fragLoadError', FRAG_LOAD_TIMEOUT: 'fragLoadTimeOut',
    FRAG_DECRYPT_ERROR: 'fragDecryptError', FRAG_PARSING_ERROR: 'fragParsingError', BUFFER_STALLED_ERROR: 'bufferStalledError',
    BUFFER_APPEND_ERROR: 'bufferAppendError', BUFFER_ADD_CODEC_ERROR: 'bufferAddCodecError', INTERNAL_EXCEPTION: 'internalException' };

  function Hls(cfg) {
    this.config = Object.assign({}, cfg || {});
    this._l = {}; this.media = null; this.url = null; this._started = false; this._dead = false;
    this.__nv = true;
  }
  Hls.isSupported = function () { return true; };
  Hls.version = 'exoplayer';
  Hls.Events = HE; Hls.ErrorTypes = HT; Hls.ErrorDetails = HD; Hls.DefaultConfig = {};
  var HP = Hls.prototype;
  HP.on = function (e, f) { (this._l[e] = this._l[e] || []).push(f); };
  HP.once = function (e, f) { var s = this; function w() { s.off(e, w); f.apply(null, arguments); } w._o = f; this.on(e, w); };
  HP.off = function (e, f) { var a = this._l[e]; if (!a) return; if (!f) { delete this._l[e]; return; } this._l[e] = a.filter(function (x) { return x !== f && x._o !== f; }); };
  HP.removeAllListeners = function () { this._l = {}; };
  HP.trigger = HP.emit = function (e, d) { var a = (this._l[e] || []).slice(); for (var i = 0; i < a.length; i++) { try { a[i](e, d); } catch (x) { try { console.error(x); } catch (_) {} } } };
  HP.attachMedia = function (m) { this.media = m; this.emit(HE.MEDIA_ATTACHED, { media: m }); this._go(); };
  HP.detachMedia = function () { if (hlsActive === this && S.native) stopNative(); this.media = null; this._started = false; };
  HP.loadSource = function (u) { this.url = String(u || ''); this._started = false; this.emit(HE.MANIFEST_LOADING, { url: this.url }); this._go(); };
  HP._go = function () {
    if (this._dead || this._started || !this.url || !this.media) return;
    this._started = true;
    if (this.media === vid) { hlsActive = this; mtsActive = null; startNative(this.url, 'hls'); }
    else { var m = this.media, s = this; m.src = this.url; /* ızgara hücreleri: WebView'in kendi HLS desteği */
      m.addEventListener('loadedmetadata', function f() { m.removeEventListener('loadedmetadata', f); s.emit(HE.MANIFEST_PARSED, { levels: [], firstLevel: 0 }); }); }
  };
  HP.startLoad = function () { if (hlsActive === this && S.native) B.retry(); };
  HP.stopLoad = function () { /* ExoPlayer kendi tamponunu yönetir */ };
  HP.recoverMediaError = function () { if (hlsActive === this && S.native) B.retry(); };
  HP.swapAudioCodec = function () {};
  HP.destroy = function () {
    this._dead = true; this.emit(HE.DESTROYING); this._l = {};
    if (hlsActive === this) { hlsActive = null; if (S.native) stopNative(); }
  };
  function defp(o, n, g, s) { Object.defineProperty(o, n, { configurable: true, get: g, set: s }); }
  defp(HP, 'levels', function () { return hlsActive === this ? levelsFor() : []; });
  defp(HP, 'currentLevel', function () { return hlsActive === this ? S.level : -1; },
    function (i) { if (hlsActive === this) { i = (i == null ? -1 : i | 0); this._manual = i >= 0; B.level(i); if (i >= 0) { S.level = i; } } });
  defp(HP, 'loadLevel', function () { return this.currentLevel; }, function (i) { this.currentLevel = i; });
  defp(HP, 'nextLevel', function () { return this.currentLevel; }, function (i) { this.currentLevel = i; });
  defp(HP, 'autoLevelEnabled', function () { return !(this._manual); });
  defp(HP, 'autoLevelCapping', function () { return -1; }, function () {});
  defp(HP, 'audioTracks', function () { return hlsActive === this ? S.audio : []; });
  defp(HP, 'audioTrack', function () { return S.audioSel; }, function (i) { if (hlsActive === this) { B.audio(i | 0); S.audioSel = i | 0; } });
  defp(HP, 'subtitleTracks', function () { return []; });
  defp(HP, 'liveSyncPosition', function () { return (hlsActive === this && S.live && isFinite(S.dp)) ? S.dp : NaN; });
  defp(HP, 'bandwidthEstimate', function () { return 0; });

  function levelsFor() {
    var L = S.levels;
    for (var i = 0; i < L.length; i++) {
      if (S.live && S.win > 0) L[i].details = { live: true, totalduration: 0, fragments: [{ start: 0, duration: S.win }] };
      else L[i].details = { live: false, totalduration: isFinite(S.dur) ? S.dur : 0, fragments: [] };
    }
    return L;
  }

  /* ───────── mpegts.js / flv.js sahte sınıfı ───────── */
  var ME = { ERROR: 'error', LOADING_COMPLETE: 'loading_complete', RECOVERED_EARLY_EOF: 'recovered_early_eof', MEDIA_INFO: 'media_info',
    METADATA_ARRIVED: 'metadata_arrived', STATISTICS_INFO: 'statistics_info' };
  var MT = { NETWORK_ERROR: 'NetworkError', MEDIA_ERROR: 'MediaError', OTHER_ERROR: 'OtherError' };
  function MtsPlayer(cfg) { this._cfg = cfg || {}; this._l = {}; this.media = null; this._dead = false; this.mediaInfo = null; this.__nv = true; }
  var MP = MtsPlayer.prototype;
  MP.on = HP.on; MP.off = HP.off;
  MP._e = function () { var a = (this._l[arguments[0]] || []).slice(), r = [].slice.call(arguments, 1); for (var i = 0; i < a.length; i++) { try { a[i].apply(null, r); } catch (x) {} } };
  MP.attachMediaElement = function (m) { this.media = m; };
  MP.detachMediaElement = function () { if (mtsActive === this && S.native) stopNative(); this.media = null; };
  MP.load = function () {
    if (this._dead) return;
    if (this.media === vid) { mtsActive = this; hlsActive = null; startNative(this._cfg.url, 'mpegts'); }
    else if (this.media) { this.media.src = this._cfg.url; }
  };
  MP.unload = function () { if (mtsActive === this && S.native) stopNative(); };
  MP.play = function () { return vid ? vid.play() : Promise.resolve(); };
  MP.pause = function () { if (vid) vid.pause(); };
  MP.destroy = function () { this._dead = true; this._l = {}; if (mtsActive === this) { mtsActive = null; if (S.native) stopNative(); } this.media = null; };
  defp(MP, 'currentTime', function () { return vid ? vid.currentTime : 0; }, function (t) { if (vid) vid.currentTime = t; });
  defp(MP, 'volume', function () { return vid ? vid.volume : 1; }, function (v) { if (vid) vid.volume = v; });
  defp(MP, 'muted', function () { return vid ? vid.muted : false; }, function (v) { if (vid) vid.muted = v; });
  defp(MP, 'buffered', function () { return vid ? vid.buffered : ranges(0, 0); });
  defp(MP, 'statisticsInfo', function () { return { speed: 0 }; });
  var mpegtsStub = {
    isSupported: function () { return true; },
    getFeatureList: function () { return { mseLivePlayback: true, networkStreamIO: true }; },
    createPlayer: function (md, cfg) { var p = new MtsPlayer(md); return p; },
    Events: ME, ErrorTypes: MT, ErrorDetails: { NETWORK_EXCEPTION: 'NetworkException', NETWORK_UNRECOVERABLE_EARLY_EOF: 'NetworkUnrecoverableEarlyEof', MEDIA_FORMAT_UNSUPPORTED: 'MediaFormatUnsupported' },
    LoggingControl: { applyConfig: function () {}, enableAll: false }, version: 'exoplayer'
  };

  /* Gerçek hls.js / mpegts.js yüklenirse bile sahte sınıf kazansın */
  function lock(name, val) {
    try { Object.defineProperty(window, name, { configurable: true, get: function () { return val; }, set: function () {} }); }
    catch (e) { try { window[name] = val; } catch (_) {} }
  }
  lock('Hls', Hls); lock('mpegts', mpegtsStub); lock('flvjs', mpegtsStub);
  window.HlsLoaded = true; window.__umitHlsExo = true;

  /* ───────── yardımcılar ───────── */
  function ranges(a, b) {
    var n = (b > a) ? 1 : 0;
    return { length: n, start: function (i) { if (i >= n) throw new Error('index'); return a; }, end: function (i) { if (i >= n) throw new Error('index'); return b; } };
  }
  function curPos() {
    var p = S.pos;
    if (S.native && !S.paused && S.st === 2) p += (now() - S.posAt) / 1000 * S.rate;
    var mx = S.live ? S.win : S.dur;
    if (isFinite(mx) && mx > 0 && p > mx) p = mx;
    return p;
  }
  function ev(n) { if (!vid) return; try { vid.dispatchEvent(new Event(n)); } catch (e) {} }
  function nat() { return S.native; }

  function startNative(url, why) {
    url = String(url || '');
    if (!/^https?:/i.test(url)) { try { url = new URL(url, location.href).href; } catch (e) {} }
    var ua = '', ref = '';
    try {
      var k = (typeof lookupKnownRefererUa === 'function') ? lookupKnownRefererUa(url) : null;
      if (k) { ua = k.userAgent || ''; ref = k.referrer || ''; }
      if (!ua && typeof getCustomUserAgent === 'function') ua = getCustomUserAgent() || '';
    } catch (e) {}
    var vol = S.vol, muted = S.muted, rate = S.rate;
    S = fresh(); S.native = true; S.src = url; S.vol = vol; S.muted = muted; S.rate = rate;
    var auto = !vid || vid.hasAttribute('autoplay') || vid.autoplay;
    S.paused = !auto; S.pwr = !!auto; S.posAt = now();
    markActive(true);
    B.volume(S.vol, S.muted); B.rate(S.rate);
    log('load', why || '', url);
    B.load(url, ua, ref, 0, !!auto, why || '');
    ev('emptied'); ev('loadstart');
    if (auto && vid && !vid.autoplay) { S.paused = true; S.pwr = false; B.pause(); }   // sayfa loadstart'ta autoplay'i kapattıysa (ön tampon vb.) beklet
    else if (auto) ev('play');
    pushRect(true);
  }
  function stopNative() {
    if (!S.native) return;
    var vol = S.vol, muted = S.muted, rate = S.rate, wasP = !S.paused;
    S = fresh(); S.vol = vol; S.muted = muted; S.rate = rate;
    try { B.stop(); } catch (e) {}
    markActive(false);
    ev('emptied');
  }

  /* ───────── şeffaf WebView: görüntü arkadaki SurfaceView'den görünsün ───────── */
  var styleEl = null, cleared = [], bgCache = '';
  function markActive(on) {
    var de = document.documentElement;
    if (on) {
      if (!styleEl) {
        styleEl = document.createElement('style'); styleEl.id = 'umit-nv-css';
        styleEl.textContent = 'html.umit-nv [data-umit-nvc],html.umit-nv #vid{background:transparent!important;background-color:transparent!important;background-image:none!important;transition:none!important}' +
          'html.umit-nv #vid{opacity:1}' +
          /* <html>/<body> kendisi de saydam olmalı: tam ekranda html.umk-watch{background:#000!important} gibi kurallar WebView'i opak boyayıp arkadaki videoyu kapatır */
          'html.umit-nv:root,html.umit-nv:root body{background:transparent!important;background-color:transparent!important;background-image:none!important;transition:none!important}';
        (document.head || de).appendChild(styleEl);
      }
      try {
        var c = getComputedStyle(document.body).backgroundColor;
        if (!c || !/^rgb\(/.test(c)) c = getComputedStyle(de).backgroundColor;
        if (c && /^rgb\(/.test(c)) bgCache = c;   // yalnızca tam opak renk (geçiş sürerken yarı saydam okunmasın)
        if (bgCache) B.bg(bgCache);
      } catch (e) {}
      de.classList.add('umit-nv');
      clearAncestors();
    } else {
      de.classList.remove('umit-nv');
      for (var i = 0; i < cleared.length; i++) { try { cleared[i].removeAttribute('data-umit-nvc'); } catch (e) {} }
      cleared = [];
      try { B.rect(0, 0, 0, 0, 1, 1, false, 'contain'); } catch (e) {}
      lastRect = '';
    }
  }
  function clearAncestors() {
    try {
      for (var el = vid; el && el.nodeType === 1; el = el.parentElement) {
        if (el === vid) continue;
        var bg = getComputedStyle(el).backgroundColor, img = getComputedStyle(el).backgroundImage;
        if ((bg && !/rgba\(0, 0, 0, 0\)|transparent/.test(bg)) || (img && img !== 'none')) {
          if (!el.hasAttribute('data-umit-nvc')) { el.setAttribute('data-umit-nvc', '1'); cleared.push(el); }
        }
      }
    } catch (e) {}
  }
  function pushRect(force) {
    if (!S.native || !vid) return;
    var r = vid.getBoundingClientRect(), cs = getComputedStyle(vid);
    var show = r.width > 2 && r.height > 2 && cs.visibility !== 'hidden' && cs.display !== 'none' && !document.hidden && vid.isConnected;
    var of = cs.objectFit, fit = (of === 'cover') ? 'cover' : (of === 'fill') ? 'fill' : 'contain';
    var iw = window.innerWidth || 1, ih = window.innerHeight || 1;
    var key = [r.left | 0, r.top | 0, r.width | 0, r.height | 0, iw, ih, show, fit].join(',');
    if (!force && key === lastRect) return;
    lastRect = key;
    B.rect(r.left, r.top, r.width, r.height, iw, ih, show, fit);
  }
  setInterval(function () { if (S.native) { pushRect(false); if (++rectT % 10 === 0) clearAncestors(); } }, 100);
  window.addEventListener('resize', function () { pushRect(false); });
  document.addEventListener('visibilitychange', function () { pushRect(true); });

  /* ───────── #vid öğesini ExoPlayer'a bağla ───────── */
  function origDesc(n) {
    return Object.getOwnPropertyDescriptor(HTMLVideoElement.prototype, n) || Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, n);
  }
  function attach() {
    if (vid) return true;
    var v = document.getElementById('vid');
    if (!v || v.__nv) return !!v;
    vid = v; v.__nv = true;
    var O = {};
    ['src', 'paused', 'ended', 'currentTime', 'duration', 'volume', 'muted', 'playbackRate', 'readyState', 'networkState', 'videoWidth', 'videoHeight',
      'buffered', 'seekable', 'played', 'seeking', 'error', 'currentSrc', 'defaultPlaybackRate'].forEach(function (n) { O[n] = origDesc(n); });
    var oPlay = HTMLMediaElement.prototype.play, oPause = HTMLMediaElement.prototype.pause, oLoad = HTMLMediaElement.prototype.load;
    var oRemove = Element.prototype.removeAttribute, oSet = Element.prototype.setAttribute, oQ = HTMLVideoElement.prototype.getVideoPlaybackQuality;

    function prop(n, get, set) {
      var d = O[n];
      try {
        Object.defineProperty(v, n, { configurable: true, enumerable: true,
          get: function () { return S.native ? get() : (d && d.get ? d.get.call(v) : undefined); },
          set: set ? function (x) { if (S.native) set(x); else if (d && d.set) d.set.call(v, x); } : (d && d.set ? function (x) { d.set.call(v, x); } : undefined) });
      } catch (e) {}
    }
    /* src */
    try {
      Object.defineProperty(v, 'src', { configurable: true, enumerable: true,
        get: function () { return S.native ? S.src : O.src.get.call(v); },
        set: function (u) { routeSrc(u, function (x) { O.src.set.call(v, x); }); } });
    } catch (e) {}
    v.setAttribute = function (n, val) { if (String(n).toLowerCase() === 'src') { routeSrc(val, function (x) { oSet.call(v, 'src', x); }); return; } return oSet.apply(v, arguments); };
    v.removeAttribute = function (n) {
      if (String(n).toLowerCase() === 'src') { if (S.native) stopNative(); }
      return oRemove.apply(v, arguments);
    };
    function routeSrc(u, real) {
      u = String(u == null ? '' : u);
      if (!u) { if (S.native) stopNative(); real(''); return; }
      if (/^(blob|data):/i.test(u)) { if (S.native) stopNative(); real(u); return; }   // yerel dosya/kayıt oynatma: WebView'in kendi <video>'su
      if (S.native) stopNative();
      startNative(u, 'src');
    }
    v.load = function () { if (S.native) return; return oLoad.call(v); };
    v.play = function () {
      if (!S.native) return oPlay.call(v);
      if (S.err) { S.err = null; B.retry(); }
      var was = S.paused; S.paused = false; S.pwr = true; pwrLock = now() + 700; S.ended = false;
      B.play();
      if (was) { ev('play'); if (S.st === 2) ev('playing'); }
      return Promise.resolve();
    };
    v.pause = function () {
      if (!S.native) return oPause.call(v);
      var was = !S.paused; S.paused = true; S.pwr = false; pwrLock = now() + 700;
      B.pause();
      if (was) ev('pause');
    };
    v.getVideoPlaybackQuality = function () {
      if (!S.native) return oQ ? oQ.call(v) : { totalVideoFrames: 0, droppedVideoFrames: 0 };
      var tot = Math.round(curPos() * (S.fps || 25));
      return { totalVideoFrames: tot, droppedVideoFrames: S.dropped, corruptedVideoFrames: 0, creationTime: now() };
    };
    prop('paused', function () { return S.paused; });
    prop('ended', function () { return S.ended; });
    prop('currentTime', curPos, function (t) {
      t = +t; if (!isFinite(t) || t < 0) t = 0;
      S.pos = t; S.posAt = now(); S.ended = false;
      if (!S.seeking) { S.seeking = true; ev('seeking'); }
      seekWait = now(); clearTimeout(seekT); seekT = setTimeout(doneSeek, 4000);
      B.seek(Math.round(t * 1000)); ev('timeupdate');
    });
    prop('duration', function () { return S.dur; });
    prop('volume', function () { return S.vol; }, function (x) { x = Math.max(0, Math.min(1, +x || 0)); S.vol = x; B.volume(x, S.muted); ev('volumechange'); });
    prop('muted', function () { return S.muted; }, function (x) { S.muted = !!x; B.volume(S.vol, S.muted); ev('volumechange'); });
    prop('playbackRate', function () { return S.rate; }, function (x) { x = +x || 1; S.rate = x; B.rate(x); ev('ratechange'); });
    prop('defaultPlaybackRate', function () { return 1; }, function () {});
    prop('readyState', function () { return S.st === 0 ? 0 : (S.st === 1 ? (S.meta ? 1 : 0) : 4); });
    prop('networkState', function () { return S.st === 1 ? 2 : (S.st === 0 ? 1 : 1); });
    prop('videoWidth', function () { return S.w; });
    prop('videoHeight', function () { return S.h; });
    prop('buffered', function () { var p = curPos(); return ranges(S.live ? p : 0, Math.max(S.buf, p)); });
    prop('seekable', function () {
      if (S.live) return ranges(0, S.win);
      return isFinite(S.dur) ? ranges(0, S.dur) : ranges(0, 0);
    });
    prop('played', function () { return ranges(0, curPos()); });
    prop('seeking', function () { return S.seeking; });
    prop('error', function () { return S.err; });
    prop('currentSrc', function () { return S.src; });
    /* sayfa mpegts/Hls ile native <video> arasında geçerken öğenin bayatlamaması için */
    log('vid bağlandı');
    return true;
  }
  function doneSeek() { if (S.seeking) { S.seeking = false; ev('seeked'); } }

  /* ───────── native → JS olayları ───────── */
  window.__umitNV = function (j) {
    try {
      if (!S.native || !j) return;
      if (j.t === 'st') onState(j);
      else if (j.t === 'tr') onTracks(j);
      else if (j.t === 'er') onError(j);
    } catch (e) { try { console.error('umitNV', e); } catch (_) {} }
  };
  function onTracks(j) {
    S.levels = (j.levels || []).map(function (l) {
      return { width: l.w, height: l.h, bitrate: l.b, name: (l.h ? l.h + 'p' : ''), videoCodec: l.vc || '', audioCodec: l.ac || '', frameRate: l.fps || 0, attrs: { 'FRAME-RATE': String(l.fps || '') } };
    });
    S.fps = j.fps || (S.levels[S.level] && S.levels[S.level].frameRate) || 0;
    var oldL = S.level; S.level = (j.lv == null ? -1 : j.lv);
    S.audio = (j.audio || []).map(function (a) { return { name: a.n || a.l || '', lang: a.l || '', default: !!a.sel, channels: a.ch || 0, id: a.i, groupId: 'exo' }; });
    for (var i = 0; i < (j.audio || []).length; i++) if (j.audio[i].sel) S.audioSel = i;
    var H = hlsActive;
    if (H) {
      if (!S.ready1) { S.ready1 = true; S.manifest = true; H.emit(HE.MANIFEST_PARSED, { levels: levelsFor(), firstLevel: Math.max(0, S.level), audioTracks: S.audio }); H.emit(HE.LEVEL_LOADED, { level: Math.max(0, S.level), details: levelDetails() }); }
      else if (oldL !== S.level) H.emit(HE.LEVEL_SWITCHED, { level: S.level });
      if (S.audio.length) H.emit(HE.AUDIO_TRACKS_UPDATED, { audioTracks: S.audio });
    }
    if (mtsActive && !S.mi) { S.mi = true; var cur = S.levels[Math.max(0, S.level)] || {}; mtsActive.mediaInfo = { width: cur.width, height: cur.height, videoCodec: cur.videoCodec, audioCodec: cur.audioCodec, hasVideo: true, hasAudio: true, fps: cur.frameRate };
      mtsActive._e(ME.MEDIA_INFO, mtsActive.mediaInfo); }
    ev('resize');
  }
  function levelDetails() {
    return S.live && S.win > 0 ? { live: true, totalduration: 0, targetduration: 6, fragments: [{ start: 0, duration: S.win }] }
      : { live: false, totalduration: isFinite(S.dur) ? S.dur : 0, fragments: [] };
  }
  function onState(j) {
    var oldSt = S.st, oldDur = S.dur, oldW = S.w, oldH = S.h, oldBuf = S.buf;
    S.st = j.s; S.pos = j.p / 1000; S.posAt = now(); S.buf = Math.max(0, j.b / 1000);
    S.live = !!j.lv; S.win = (j.lv && j.d > 0) ? j.d / 1000 : 0; S.dp = (j.dp != null && j.dp >= 0) ? j.dp / 1000 : NaN;
    S.dropped = j.df | 0; S.w = j.w | 0; S.h = j.h | 0;
    if (j.fps) S.fps = j.fps;
    var nd = (j.s === 0) ? NaN : ((j.d > 0 && !j.lv) ? j.d / 1000 : Infinity);
    S.dur = nd;
    if (S.dropped < 0) S.dropped = 0;

    /* meta / hazır */
    if (j.s >= 2 && !S.meta) {
      S.meta = true;
      ev('durationchange'); ev('loadedmetadata'); ev('loadeddata'); ev('canplay'); ev('canplaythrough'); ev('resize');
      if (hlsActive && !S.ready1 && !S.levels.length) { S.ready1 = true; hlsActive.emit(HE.MANIFEST_PARSED, { levels: levelsFor(), firstLevel: 0 }); hlsActive.emit(HE.LEVEL_LOADED, { level: 0, details: levelDetails() }); }
    } else if (oldDur !== nd && !(isNaN(oldDur) && isNaN(nd))) { ev('durationchange'); if (hlsActive) hlsActive.emit(HE.LEVEL_UPDATED, { level: Math.max(0, S.level), details: levelDetails() }); }
    if (S.w !== oldW || S.h !== oldH) ev('resize');

    /* oynat / duraklat (JS tarafı komut verdiyse kısa süre native'e uyma) */
    if (now() > pwrLock && !!j.pw !== S.pwr) {
      S.pwr = !!j.pw;
      if (S.pwr && S.paused) { S.paused = false; ev('play'); }
      else if (!S.pwr && !S.paused && j.s !== 3) { S.paused = true; ev('pause'); }
    }
    if (j.s === 1 && oldSt === 2 && S.meta) { ev('waiting'); }
    if (j.s === 2 && (oldSt !== 2) && S.meta && !S.paused) { ev('canplay'); ev('playing'); }
    if (j.s === 2 && oldSt !== 2 && S.paused) ev('canplay');
    if (j.s === 3 && oldSt !== 3) { S.ended = true; if (!S.paused) { S.paused = true; S.pwr = false; ev('pause'); } ev('timeupdate'); ev('ended'); }
    if (j.s !== 3) S.ended = false;

    if (S.seeking && j.s === 2 && now() - seekWait > 200) { clearTimeout(seekT); doneSeek(); }
    if (S.buf !== oldBuf) ev('progress');
    ev('timeupdate');
    if (j.lvc != null && j.lvc !== S.level) { S.level = j.lvc; if (hlsActive) hlsActive.emit(HE.LEVEL_SWITCHED, { level: S.level }); }
  }
  function onError(j) {
    S.st = 0; S.err = { code: j.cat === 'net' || j.cat === 'timeout' ? 2 : (j.cat === 'decode' || j.cat === 'parse' ? 3 : 4), message: j.m || '', MEDIA_ERR_ABORTED: 1, MEDIA_ERR_NETWORK: 2, MEDIA_ERR_DECODE: 3, MEDIA_ERR_SRC_NOT_SUPPORTED: 4 };
    log('hata', j.cat, j.c, j.m);
    var net = (j.cat === 'net' || j.cat === 'timeout');
    var early = !S.meta;
    if (hlsActive) {
      hlsActive.emit(HE.ERROR, { type: net ? HT.NETWORK_ERROR : HT.MEDIA_ERROR, fatal: true,
        details: net ? (early ? (j.cat === 'timeout' ? HD.MANIFEST_LOAD_TIMEOUT : HD.MANIFEST_LOAD_ERROR) : (j.cat === 'timeout' ? HD.FRAG_LOAD_TIMEOUT : HD.FRAG_LOAD_ERROR))
          : (j.cat === 'parse' && early ? HD.MANIFEST_PARSING_ERROR : HD.FRAG_PARSING_ERROR),
        response: { code: j.hc || 0, text: j.m || '' }, error: new Error(j.m || 'exoplayer'), reason: j.m || '' });
    }
    if (mtsActive) mtsActive._e(ME.ERROR, net ? MT.NETWORK_ERROR : MT.MEDIA_ERROR, net ? 'NetworkException' : 'MediaFormatUnsupported', { code: j.c, msg: j.m });
    ev('error');
  }

  window.__umitNVAttach = attach;
  window.__umitNV_state = function () { return S; };
  if (!attach()) {
    try {
      var mo = new MutationObserver(function () { if (attach()) mo.disconnect(); });
      mo.observe(document, { childList: true, subtree: true });
    } catch (e) {}
    document.addEventListener('DOMContentLoaded', attach);
  }
})();
