/**
 * patches/token_saver/install.js - Prevents accidental double-sends on mobile
 * touchscreens and gently warns when a chat grows long enough to burn heavy quota.
 */
(function () {
  var global = typeof window !== 'undefined' ? window : this;
  if (global.__AG_TOKEN_SAVER_INSTALLED__) return;
  global.__AG_TOKEN_SAVER_INSTALLED__ = true;

  // 1. Deduplicate accidental double-tap Send RPCs within 2.5 seconds.
  var SEND_RPC_RE = /(?:^|\/)(?:SendUserCascadeMessage|StartCascade|SendMessage|SubmitPrompt)/i;
  var lastSendKey = '';
  var lastSendAt = 0;
  var DEDUPE_WINDOW_MS = 2500;

  if (typeof global.fetch === 'function') {
    var prevFetch = global.fetch.bind(global);
    global.fetch = function (resource, init) {
      try {
        var urlStr = (typeof resource === 'string')
          ? resource
          : (resource && typeof resource.url === 'string' ? resource.url : '');
        if (urlStr && SEND_RPC_RE.test(urlStr)) {
          var now = Date.now();
          var bodySig = '';
          if (init && typeof init.body === 'string') {
            bodySig = init.body.slice(0, 256);
          } else if (init && init.body && typeof init.body.byteLength === 'number') {
            bodySig = 'bin:' + init.body.byteLength;
          }
          var key = urlStr.split('?')[0] + '|' + bodySig;
          if (key === lastSendKey && (now - lastSendAt) < DEDUPE_WINDOW_MS) {
            if (typeof console !== 'undefined' && console.warn) {
              console.warn('[AGY_TOKEN_SAVER] blocked duplicate rapid send RPC');
            }
            return Promise.reject(new DOMException('Duplicate rapid send blocked by Token-Saver', 'AbortError'));
          }
          lastSendKey = key;
          lastSendAt = now;
        }
      } catch (ignored) {}
      return prevFetch(resource, init);
    };
  }

  // 2. Non-intrusive long-chat warning banner suggesting /compact or a new chat.
  var warningDismissed = false;
  var warningVisible = false;
  var CHAR_THRESHOLD = 45000;

  function showCompactTip(estKTokens) {
    if (warningDismissed || warningVisible || !document.body) return;
    warningVisible = true;
    var bar = document.createElement('div');
    bar.id = 'agy-token-saver-tip';
    bar.style.cssText = [
      'position:fixed',
      'left:12px',
      'right:58px',
      'bottom:74px',
      'z-index:2147483000',
      'display:flex',
      'align-items:center',
      'justify-content:space-between',
      'gap:8px',
      'padding:8px 12px',
      'border-radius:10px',
      'font-size:12px',
      'line-height:1.35',
      'background:rgba(15,23,42,0.92)',
      'color:#e2e8f0',
      'border:1px solid rgba(56,189,248,0.45)',
      'box-shadow:0 4px 14px rgba(0,0,0,0.35)'
    ].join(';');

    var msg = document.createElement('span');
    msg.textContent = 'Long chat (~' + estKTokens + 'k+ ctx): run /compact or start a new chat to save quota.';
    bar.appendChild(msg);

    var close = document.createElement('button');
    close.type = 'button';
    close.textContent = 'Dismiss';
    close.style.cssText = 'background:none;border:none;color:#38bdf8;font-weight:600;font-size:12px;padding:2px 6px;cursor:pointer;flex-shrink:0;';
    close.onclick = function () {
      warningDismissed = true;
      warningVisible = false;
      if (bar.parentNode) bar.parentNode.removeChild(bar);
    };
    bar.appendChild(close);
    document.body.appendChild(bar);
  }

  function checkContextLength() {
    if (warningDismissed || warningVisible || document.hidden) return;
    try {
      var root = document.getElementById('root');
      if (!root) return;
      var textLen = (root.innerText || '').length;
      if (textLen >= CHAR_THRESHOLD) {
        var estK = Math.max(12, Math.round(textLen / 3600));
        showCompactTip(estK);
      }
    } catch (ignored) {}
  }

  setInterval(checkContextLength, 30000);
})();

