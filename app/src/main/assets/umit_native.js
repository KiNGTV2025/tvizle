/* ÜmitTV – Android SpeechRecognizer köprüsü.
 * WebView'de webkitSpeechRecognition çalışmaz; bu betik aynı arayüzü sağlayıp
 * işi MainActivity'deki yerel SpeechRecognizer'a (window.UmitNative) devreder. */
(function () {
  'use strict';
  if (!window.UmitNative || window.__umitSRShim) return;
  window.__umitSRShim = 1;

  var cur = null; // aktif SR örneği

  function makeResult(alts, isFinal) {
    var r = [];
    for (var i = 0; i < alts.length; i++) r.push({ transcript: alts[i].t, confidence: alts[i].c });
    r.isFinal = !!isFinal;
    r.item = function (n) { return r[n]; };
    return r;
  }

  function SR() {
    this.lang = 'tr-TR';
    this.continuous = false;
    this.interimResults = false;
    this.maxAlternatives = 1;
    this.grammars = null;
    this.onstart = this.onend = this.onerror = this.onresult = null;
    this.onaudiostart = this.onaudioend = this.onspeechstart = this.onspeechend = this.onnomatch = null;
    this._l = {};
  }
  SR.prototype.addEventListener = function (t, f) { (this._l[t] = this._l[t] || []).push(f); };
  SR.prototype.removeEventListener = function (t, f) {
    var a = this._l[t]; if (!a) return;
    var i = a.indexOf(f); if (i >= 0) a.splice(i, 1);
  };
  SR.prototype._emit = function (t, ev) {
    ev = ev || {}; ev.type = t; ev.target = this;
    var h = this['on' + t];
    if (typeof h === 'function') { try { h.call(this, ev); } catch (e) {} }
    var a = (this._l[t] || []).slice();
    for (var i = 0; i < a.length; i++) { try { a[i].call(this, ev); } catch (e) {} }
  };
  SR.prototype.start = function () {
    if (cur && cur !== this) {
      var old = cur; cur = null;
      try { window.UmitNative.cancelListening(); } catch (e) {}
      old._emit('error', { error: 'aborted' }); old._emit('end');
    }
    cur = this;
    var lang = this.lang || 'tr-TR';
    var n = parseInt(this.maxAlternatives, 10) || 1;
    window.UmitNative.startListening(String(lang), !!this.interimResults, n);
  };
  SR.prototype.stop = function () {
    if (cur === this) { try { window.UmitNative.stopListening(); } catch (e) { try { window.UmitNative.cancelListening(); } catch (_) {} } }
  };
  SR.prototype.abort = function () {
    if (cur !== this) return;
    cur = null;
    try { window.UmitNative.cancelListening(); } catch (e) {}
    this._emit('error', { error: 'aborted' });
    this._emit('end');
  };

  /* Yerel taraftan gelen olaylar */
  window.__umitSR = function (type, d) {
    var r = cur; if (!r) return;
    d = d || {};
    switch (type) {
      case 'start': r._emit('start'); break;
      case 'audiostart': r._emit('audiostart'); break;
      case 'speechstart': r._emit('speechstart'); break;
      case 'speechend': r._emit('speechend'); break;
      case 'result':
        var res = makeResult(d.alts || [], d.final);
        var results = [res]; results.item = function (n) { return results[n]; };
        r._emit('result', { resultIndex: 0, results: results });
        break;
      case 'error': r._emit('error', { error: d.error || 'unknown' }); break;
      case 'end': cur = null; r._emit('audioend'); r._emit('end'); break;
    }
  };

  try { Object.defineProperty(window, 'SpeechRecognition', { value: SR, configurable: true, writable: true }); }
  catch (e) { window.SpeechRecognition = SR; }
  try { Object.defineProperty(window, 'webkitSpeechRecognition', { value: SR, configurable: true, writable: true }); }
  catch (e) { window.webkitSpeechRecognition = SR; }
})();
