/**
 * patches/web_optimizations/viewport.js - keep --agy-vvh honest while the
 * soft keyboard opens, and adapt viewport scaling when Desktop Mode is active.
 */
function applyDesktopViewport(isDesktop, doc) {
  try {
    var meta = doc.querySelector('meta[name="viewport"]');
    if (!meta) return;
    if (isDesktop) {
      meta.setAttribute('content', 'width=1024, initial-scale=0.75, minimum-scale=0.25, maximum-scale=3.0, user-scalable=yes, viewport-fit=cover');
    } else {
      meta.setAttribute('content', 'width=device-width, initial-scale=1.0, viewport-fit=cover, maximum-scale=1.0, user-scalable=no');
    }
  } catch (e) {}
}

function installViewport(global, doc) {
  try {
    var checkDesktop = function () {
      var isDesktop = false;
      try {
        if (global.Android && typeof global.Android.isDesktopMode === 'function') {
          isDesktop = global.Android.isDesktopMode();
        } else {
          var s = global.localStorage ? global.localStorage.getItem('ag_desktop_mode') : null;
          isDesktop = (s === 'true');
        }
      } catch (err) {}
      applyDesktopViewport(isDesktop, doc);
    };

    checkDesktop();
    if (global.addEventListener) {
      global.addEventListener('storage', checkDesktop);
      global.addEventListener('agy:host-resume', checkDesktop);
    }

    if (global.visualViewport) {
      global.visualViewport.addEventListener('resize', function () {
        var vv = global.visualViewport;
        if (vv && vv.height && doc.documentElement) {
          doc.documentElement.style.setProperty(
            '--agy-vvh', Math.round(vv.height) + 'px');
        }
      }, { passive: true });
    }
  } catch (e) {}
}
