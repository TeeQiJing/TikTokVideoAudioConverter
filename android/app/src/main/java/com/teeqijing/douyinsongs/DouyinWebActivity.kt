package com.teeqijing.douyinsongs

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Opens douyin.com so the user can log in, and records the collection
 * listings the page fetches for itself.
 *
 * Two modes:
 *  - [MODE_FOLDERS] just wants the list of 收藏夹.
 *  - [MODE_SONGS] opens one folder and scrolls it to the end.
 */
class DouyinWebActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_FOLDER_ID = "folder_id"
        const val EXTRA_FOLDER_NAME = "folder_name"
        const val MODE_FOLDERS = "folders"
        const val MODE_SONGS = "songs"

        // ~40s of retries before asking the user to tap the folder himself.
        private const val MAX_CLICK_TRIES = 20
        // Rounds with no new rows before re-opening the folder.
        private const val STALL_ROUNDS = 12

        const val COLLECTION_URL =
            "https://www.douyin.com/user/self?from_tab_name=main" +
                "&showSubTab=favorite_folder&showTab=favorite_collection"

        // Douyin's mobile site hides 收藏夹 behind an "open the app" wall, so
        // ask for the desktop site, which exposes the folder grid we need.
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
    }

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var done: Button

    private val ui = Handler(Looper.getMainLooper())
    private var mode = MODE_FOLDERS
    private var folderId: String? = null
    private var folderName: String? = null
    private var folderOpened = false
    private var clickTries = 0
    private var idleRounds = 0
    private var stalledRounds = 0
    private var lastSeenCount = -1
    private var reloadedAfterLogin = false
    private var blocked = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FOLDERS
        folderId = intent.getStringExtra(EXTRA_FOLDER_ID)
        folderName = intent.getStringExtra(EXTRA_FOLDER_NAME)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        status = TextView(this).apply {
            textSize = 15f
            setPadding(28, 28, 28, 20)
            text = getString(R.string.web_loading)
        }
        root.addView(status)

        done = Button(this).apply {
            text = getString(R.string.web_done)
            setOnClickListener { finishWithResult() }
        }
        val bar = FrameLayout(this).apply {
            setPadding(28, 0, 28, 12)
            addView(done, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        }
        root.addView(bar)

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        configureWebView()
        web.loadUrl(COLLECTION_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        // Debug builds only: lets the page be inspected over adb while
        // working out why Douyin's grid is or is not loading more rows.
        if (0 != (applicationInfo.flags and
                  android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
        }
        web.addJavascriptInterface(Bridge(), "DSBridge")

        // The listing request can fire before onPageFinished, so the hook has
        // to be in place before any of Douyin's scripts run.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                web, HOOK_JS, setOf("https://www.douyin.com"))
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView?, url: String?, f: android.graphics.Bitmap?) {
                // Belt and braces for devices without DOCUMENT_START_SCRIPT.
                web.evaluateJavascript(HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                web.evaluateJavascript(HOOK_JS, null)
                web.evaluateJavascript(
                    "(document.body ? document.body.innerText : '').slice(0,200)"
                ) { text ->
                    if (text.contains("Access Denied") ||
                        text.contains("X-TT-System-Error")) {
                        blocked = true
                        status.text = getString(R.string.web_blocked)
                    }
                }
                ui.postDelayed({ tick() }, 2500)
            }

            override fun shouldOverrideUrlLoading(
                v: WebView?, r: WebResourceRequest?,
            ): Boolean = false
        }
    }

    /** Runs every couple of seconds to drive the page and report progress. */
    private fun tick() {
        if (isFinishing) return
        when (mode) {
            MODE_FOLDERS -> {
                val n = Capture.folders().size
                status.text = if (n > 0) getString(R.string.web_found_folders, n)
                else getString(R.string.web_please_login)
                if (n > 0) {
                    finishWithResult()
                    return
                }
                if (blocked) {
                    status.text = getString(R.string.web_blocked)
                    return
                }
                // Watch the cookie jar rather than reloading on a timer:
                // repeatedly refetching douyin.com while the user is still
                // typing gets the whole device rate-limited ("Access Denied,
                // X-TT-System-Error: 3"). One reload, once a session exists.
                if (!reloadedAfterLogin && hasSession()) {
                    reloadedAfterLogin = true
                    status.text = getString(R.string.web_loading)
                    web.loadUrl(COLLECTION_URL)
                }
            }
            MODE_SONGS -> driveSongs()
        }
        ui.postDelayed({ tick() }, 2000)
    }

    /** True once Douyin has issued a logged-in session cookie. */
    private fun hasSession(): Boolean {
        val jar = CookieManager.getInstance()
            .getCookie("https://www.douyin.com").orEmpty()
        return jar.contains("sessionid")
    }

    private fun driveSongs() {
        if (blocked) {
            status.text = getString(R.string.web_blocked)
            return
        }
        val id = folderId ?: return
        val name = folderName ?: return
        val found = Capture.songCount(id)
        status.text = getString(R.string.web_found_songs, name, found)

        // The tile does not exist yet on the first tick - Douyin renders the
        // folder list well after the page "finishes" loading - so keep trying
        // until the click actually lands rather than firing once and hoping.
        if (!folderOpened) {
            if (clickTries++ > MAX_CLICK_TRIES) {
                status.text = getString(R.string.web_pick_folder_yourself, name)
                return
            }
            web.evaluateJavascript(clickFolderJs(name)) { result ->
                if (result != null && result.contains("CLICKED")) {
                    folderOpened = true
                    idleRounds = 0
                    stalledRounds = 0
                }
            }
            return
        }

        // Once the folder is open, keep scrolling so Douyin loads the rest.
        web.evaluateJavascript(SCROLL_JS, null)

        if (found > 0 && !Capture.hasMore(id)) {
            idleRounds++
            if (idleRounds >= 3) finishWithResult()
            return
        }
        idleRounds = 0

        // Still claiming more rows but nothing new arriving: the click may
        // have opened the wrong view, so go back and try to open it again.
        if (found == lastSeenCount) {
            if (++stalledRounds >= STALL_ROUNDS) {
                stalledRounds = 0
                folderOpened = false
                web.loadUrl(COLLECTION_URL)
            }
        } else {
            stalledRounds = 0
            lastSeenCount = found
        }
    }

    override fun onPause() {
        super.onPause()
        // The login only survives to the next run if the cookies reach disk.
        CookieManager.getInstance().flush()
    }

    private fun finishWithResult() {
        CookieManager.getInstance().flush()
        // Browsing douyin.com leaves a large media cache behind - well over
        // 100MB after a few runs. The point of this app is that nothing
        // piles up on the phone, and clearing the cache does not touch the
        // cookies, so the login still survives.
        runCatching { web.clearCache(true) }
        if (isFinishing) return
        setResult(Activity.RESULT_OK, Intent())
        finish()
    }

    inner class Bridge {
        @JavascriptInterface
        fun onData(url: String, body: String) {
            Capture.accept(url, body)
        }
    }
}

/**
 * Wraps XMLHttpRequest and fetch so every collection response is copied to
 * the app. Reading the page's own responses is what keeps Douyin's request
 * signing intact - the app never builds a signed request itself.
 */
private val HOOK_JS = """
(function () {
  if (window.__dsHooked) return;
  window.__dsHooked = true;
  var want = function (u) {
    return u && (u.indexOf('collects/list') >= 0 ||
                 u.indexOf('collects/video/list') >= 0);
  };
  var open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (m, u) {
    this.__dsUrl = u;
    return open.apply(this, arguments);
  };
  var send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function () {
    var xhr = this;
    xhr.addEventListener('load', function () {
      try {
        if (want(xhr.__dsUrl)) DSBridge.onData(xhr.__dsUrl, xhr.responseText);
      } catch (e) {}
    });
    return send.apply(this, arguments);
  };
  var of = window.fetch;
  if (of) {
    window.fetch = function () {
      var args = arguments;
      var u = (typeof args[0] === 'string') ? args[0]
            : (args[0] && args[0].url) || '';
      return of.apply(this, args).then(function (res) {
        try {
          if (want(u)) res.clone().text().then(function (t) {
            DSBridge.onData(u, t);
          });
        } catch (e) {}
        return res;
      });
    };
  }
})();
"""

private fun clickFolderJs(name: String): String = """
(function () {
  var want = ${org.json.JSONObject.quote(name)};
  var norm = function (s) { return (s || '').replace(/\s+/g, ' ').trim(); };

  // A folder tile reads like "音乐共50作品". Matching on the name alone is
  // not safe: Douyin's own filter chips are called 视频 / 音乐 / 合集 / 短剧,
  // so a folder named 音乐 collides with one, and clicking the chip opens an
  // empty tab instead of the folder. The chips live in a tab strip, and only
  // the real tile carries the item count, so require both.
  var all = document.querySelectorAll('li,div,a');
  var best = null;
  for (var i = 0; i < all.length; i++) {
    var e = all[i];
    if (e.closest('[role="tablist"], .semi-tabs')) continue;
    var t = norm(e.textContent);
    if (t.indexOf(want) !== 0) continue;
    if (!/共\s*\d+\s*作品/.test(t)) continue;
    var r = e.getBoundingClientRect();
    if (r.width < 80 || r.height < 40) continue;
    if (!best || e.tagName === 'LI') { best = e; if (e.tagName === 'LI') break; }
  }
  if (!best) {
    // Older layouts show the bare name; still avoid the tab strip.
    for (var j = all.length - 1; j >= 0; j--) {
      var c = all[j];
      if (c.closest('[role="tablist"], .semi-tabs')) continue;
      if (norm(c.textContent) === want &&
          c.getBoundingClientRect().width > 0) { best = c; break; }
    }
  }
  if (!best) return 'NOT_FOUND';

  best.scrollIntoView({block: 'center'});
  var box = best.getBoundingClientRect();
  var x = box.x + box.width / 2, y = box.y + box.height / 2;
  var target = document.elementFromPoint(x, y) || best;

  // A bare .click() is ignored here - the tile listens for pointer events -
  // so play back the whole press/release sequence at its centre.
  var base = {bubbles: true, cancelable: true, composed: true,
              clientX: x, clientY: y, pointerId: 1, isPrimary: true,
              pointerType: 'touch', button: 0};
  ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']
    .forEach(function (type) {
      var Ctor = type.indexOf('pointer') === 0 ? PointerEvent : MouseEvent;
      var opt = {};
      for (var k in base) opt[k] = base[k];
      opt.buttons = (type === 'pointerdown' || type === 'mousedown') ? 1 : 0;
      target.dispatchEvent(new Ctor(type, opt));
    });
  return 'CLICKED';
})();
"""

/**
 * Scrolls the grid container, not the window - Douyin renders the folder in
 * an inner scroll box, and its "load more" trigger only fires when the scroll
 * passes it, so a jump straight to the bottom is ignored.
 */
private val SCROLL_JS = """
(function () {
  var best = null;
  var divs = document.querySelectorAll('div');
  for (var i = 0; i < divs.length; i++) {
    var e = divs[i];
    if (e.getAttribute('data-e2e')) continue;
    if (e.scrollHeight > e.clientHeight + 100 && e.clientHeight > 200) {
      if (!best || e.scrollHeight > best.scrollHeight) best = e;
    }
  }
  var box = best || document.scrollingElement;
  var max = box.scrollHeight - box.clientHeight;
  if (box.scrollTop >= max - 5) box.scrollTop = Math.max(0, max - 700);
  var step = 0;
  var timer = setInterval(function () {
    box.scrollTop = Math.min(max + 2000, box.scrollTop + 350);
    box.dispatchEvent(new Event('scroll', {bubbles: true}));
    if (++step > 10) clearInterval(timer);
  }, 120);
  window.scrollTo(0, document.body.scrollHeight);
})();
"""
