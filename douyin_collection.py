"""Read a Douyin 收藏夹 (favourites folder) through a logged-in browser.

Douyin has no public collection link - a shared 收藏夹 is an in-app share
code, not a URL - and its web API rejects requests that are not signed by
Douyin's own JavaScript (the `a_bogus` parameter). Both problems disappear
if the listing is read the way a person reads it: open douyin.com in a real
browser the user has logged into, and record the JSON the page itself
fetches.

So this module drives Edge/Chrome over the DevTools protocol:

  1. launch the browser against an app-owned profile (separate from the
     user's everyday browser, and separate from the guest-cookie profile
     used for downloading)
  2. open the user's 收藏 page and record the `collects/list/` response,
     which names every folder
  3. click the wanted folder, then scroll until `has_more` is 0, recording
     each `collects/video/list/` page

Nothing is forged and no password is handled here: the user logs in once,
in a browser window they can see, and the session stays in that profile.
"""

import json
import socket
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

COLLECTION_URL = ("https://www.douyin.com/user/self?from_tab_name=main"
                  "&showSubTab=favorite_folder&showTab=favorite_collection")
FOLDERS_EP = "collects/list"
VIDEOS_EP = "collects/video/list"

# How long to leave the window open waiting for a first-time login.
LOGIN_TIMEOUT = 300
# Douyin returns this in `status_code` when the session is not usable.
NOT_LOGGED_IN = 4

CREATE_NO_WINDOW = 0x08000000


class DouyinError(Exception):
    """Something went wrong that the user can act on."""


def _free_port(start: int = 9333) -> int:
    for port in range(start, start + 40):
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", port)) != 0:
                return port
    raise DouyinError("No free port for the browser connection.")


def _click_folder_js(name: str) -> str:
    """JS that clicks the folder tile with this exact label.

    The 收藏夹 tab does not load from the URL alone - it has to be clicked,
    so the folder tile is clicked the same way a person would.
    """
    return """
(() => {
  const want = %s;
  const hit = Array.from(document.querySelectorAll('div,span,a,li'))
    .filter(e => (e.textContent || '').trim() === want
                 && e.getBoundingClientRect().width > 0)
    .pop();
  if (!hit) return 'NOT_FOUND';
  // Walk up to the clickable tile around the label.
  let n = hit;
  for (let i = 0; i < 6 && n; i++) {
    const r = n.getBoundingClientRect();
    if (r.width > 120 && r.height > 40) break;
    n = n.parentElement;
  }
  (n || hit).click();
  return 'CLICKED';
})()
""" % json.dumps(name)


# Douyin loads the rest of a folder only as it is scrolled, and the page is
# not the window scroller - the grid lives inside a route container. Jumping
# straight to the bottom does not work either: the "load more" sentinel is an
# IntersectionObserver, so the scroll has to travel past it in steps.
SCROLL_JS = """
(() => {
  let best = null;
  document.querySelectorAll('div').forEach(e => {
    // Skip the sidebar, which is scrollable too but holds no videos.
    if (e.getAttribute('data-e2e')) return;
    if (e.scrollHeight > e.clientHeight + 100 && e.clientHeight > 300) {
      if (!best || e.scrollHeight > best.scrollHeight) best = e;
    }
  });
  const box = best || document.scrollingElement;
  const max = box.scrollHeight - box.clientHeight;
  // Already pinned to the bottom? Back off so the sentinel can re-arm.
  if (box.scrollTop >= max - 5) box.scrollTop = Math.max(0, max - 700);
  let step = 0;
  const timer = setInterval(() => {
    box.scrollTop = Math.min(max + 2000, box.scrollTop + 350);
    box.dispatchEvent(new Event('scroll', {bubbles: true}));
    if (++step > 12) clearInterval(timer);
  }, 120);
  return box.scrollHeight;
})()
"""



class _Browser:
    """A browser process plus one DevTools websocket onto the Douyin tab."""

    def __init__(self, exe: str, profile: Path, log):
        self.exe, self.profile, self.log = exe, profile, log
        self.port = _free_port()
        self.proc = None
        self.ws = None
        self._msg_id = 100
        self._inflight: dict[str, str] = {}      # requestId -> url
        self._body_requests: dict[int, str] = {}  # message id -> url
        self._replies: dict[int, dict] = {}       # message id -> reply
        self.pages: list[tuple[str, dict]] = []

    # ---------------------------------------------------------- lifecycle

    def __enter__(self):
        self.profile.mkdir(parents=True, exist_ok=True)
        # Visible on purpose: the user may need to log in, and a window they
        # can see is far less alarming than a hidden one driving their account.
        self.proc = subprocess.Popen(
            [self.exe,
             f"--remote-debugging-port={self.port}",
             f"--user-data-dir={self.profile}",
             "--no-first-run", "--no-default-browser-check",
             "--window-size=1100,860",
             COLLECTION_URL],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self._attach()
        return self

    def __exit__(self, *_):
        try:
            if self.ws:
                self.ws.close()
        except Exception:
            pass
        if self.proc:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.proc.kill()

    def _targets(self) -> list[dict]:
        url = f"http://127.0.0.1:{self.port}/json/list"
        try:
            with urllib.request.urlopen(url, timeout=5) as r:
                return json.load(r)
        except (OSError, urllib.error.URLError, ValueError):
            return []

    def _attach(self, timeout: int = 60) -> None:
        import websockets.sync.client as wsc

        deadline = time.time() + timeout
        while time.time() < deadline:
            for t in self._targets():
                if t.get("type") == "page" and "douyin.com" in t.get("url", ""):
                    self.ws = wsc.connect(t["webSocketDebuggerUrl"],
                                          max_size=96 * 1024 * 1024)
                    self._send("Network.enable")
                    self._send("Page.enable")
                    self._send("Runtime.enable")
                    return
            time.sleep(1)
        raise DouyinError("The browser did not open douyin.com.")

    # ------------------------------------------------------------- DevTools
    #
    # Everything funnels through one dispatcher. An earlier version read the
    # socket from inside several nested loops, and each loop threw away the
    # messages the others were waiting for - which silently lost whole pages
    # of the listing. So: never read the socket except through _drain().

    def _send(self, method: str, params: dict | None = None) -> int:
        self._msg_id += 1
        self.ws.send(json.dumps({"id": self._msg_id, "method": method,
                                 "params": params or {}}))
        return self._msg_id

    def _dispatch(self, msg: dict) -> None:
        mid = msg.get("id")
        if mid is not None:
            if mid in self._body_requests:
                url = self._body_requests.pop(mid)
                body = msg.get("result", {}).get("body", "")
                try:
                    self.pages.append((url, json.loads(body)))
                except ValueError:
                    pass
            else:
                self._replies[mid] = msg
            return

        method = msg.get("method")
        if method == "Network.responseReceived":
            url = msg["params"]["response"]["url"]
            if FOLDERS_EP in url or VIDEOS_EP in url:
                self._inflight[msg["params"]["requestId"]] = url
        elif method == "Network.loadingFinished":
            rid = msg["params"]["requestId"]
            url = self._inflight.pop(rid, None)
            if url:
                want = self._send("Network.getResponseBody",
                                  {"requestId": rid})
                self._body_requests[want] = url

    def _drain(self, seconds: float, is_cancelled=lambda: False) -> None:
        end = time.time() + seconds
        while time.time() < end and not is_cancelled():
            try:
                msg = json.loads(self.ws.recv(timeout=1))
            except Exception:
                continue
            self._dispatch(msg)

    def pump(self, seconds: float, is_cancelled=lambda: False) -> None:
        """Read events for a while, decoding any listing JSON that arrives."""
        self._drain(seconds, is_cancelled)

    def evaluate(self, expression: str, is_cancelled=lambda: False,
                 timeout: float = 15):
        want = self._send("Runtime.evaluate",
                          {"expression": expression, "returnByValue": True})
        end = time.time() + timeout
        while time.time() < end and not is_cancelled():
            if want in self._replies:
                reply = self._replies.pop(want)
                return reply.get("result", {}).get("result", {}).get("value")
            self._drain(0.5, is_cancelled)
        return None

    def reload(self) -> None:
        self._send("Page.navigate", {"url": COLLECTION_URL})

    # --------------------------------------------------------- page results

    def folders(self) -> list[dict]:
        """Folders seen so far, newest response wins."""
        out: dict[str, dict] = {}
        for url, data in self.pages:
            if FOLDERS_EP not in url:
                continue
            for c in (data.get("collects_list") or []):
                cid = c.get("collects_id_str") or str(c.get("collects_id"))
                out[cid] = {
                    "id": cid,
                    "name": c.get("collects_name") or "(unnamed)",
                    "count": c.get("total_number"),
                }
        return list(out.values())

    def saw_login_failure(self) -> bool:
        return any(FOLDERS_EP in url and data.get("status_code") == NOT_LOGGED_IN
                   for url, data in self.pages)

    def videos(self, collects_id: str | None = None) -> list[dict]:
        """Video ids collected from every `collects/video/list` page seen."""
        out: dict[str, dict] = {}
        for url, data in self.pages:
            if VIDEOS_EP not in url:
                continue
            if collects_id and f"collects_id={collects_id}" not in url:
                continue
            for a in (data.get("aweme_list") or []):
                aid = a.get("aweme_id")
                if aid:
                    out[aid] = {"id": aid, "desc": a.get("desc") or ""}
        return list(out.values())

    def more_pages_pending(self, collects_id: str | None = None) -> bool:
        """True while the folder's most recent page says more rows follow."""
        latest = None
        for url, data in self.pages:
            if VIDEOS_EP in url and (
                    not collects_id or f"collects_id={collects_id}" in url):
                latest = data
        return bool(latest and latest.get("has_more"))

    def saw_any_page(self, collects_id: str) -> bool:
        return any(VIDEOS_EP in url and f"collects_id={collects_id}" in url
                   for url, _ in self.pages)


# ------------------------------------------------------------------- public

def list_folders(browser_exe: str, profile: Path, log,
                 is_cancelled=lambda: False) -> list[dict]:
    """Return [{'id', 'name', 'count'}] for the user's 收藏夹."""
    with _Browser(browser_exe, profile, log) as b:
        log("Opening Douyin...")
        b.pump(12, is_cancelled)

        if not b.folders():
            # Either the page is just slow, or there is no usable session yet.
            # Only bring up the login instructions once it looks like the
            # latter, so a returning user never sees them.
            told = False
            deadline = time.time() + LOGIN_TIMEOUT
            while time.time() < deadline and not is_cancelled():
                b.pump(8, is_cancelled)
                if b.folders():
                    break
                if not told and (b.saw_login_failure()
                                 or time.time() > deadline - LOGIN_TIMEOUT + 20):
                    log("Please log in to Douyin in the window that just "
                        "opened (扫码登录 is quickest). Waiting...")
                    told = True
                b.reload()
                b.pump(8, is_cancelled)

        folders = b.folders()
        if not folders:
            raise DouyinError(
                "Could not read your Douyin collections - not logged in yet?")
        log(f"Found {len(folders)} collection folder(s).")
        return folders


def list_folder_videos(browser_exe: str, profile: Path, folder_name: str,
                       folder_id: str, log,
                       is_cancelled=lambda: False,
                       expected: int | None = None,
                       max_scrolls: int = 60) -> list[str]:
    """Return every video id inside one 收藏夹, scrolling to load them all.

    `expected` is the folder's own item count, which lets the scroll loop
    stop as soon as everything has arrived instead of probing for the end.
    """
    with _Browser(browser_exe, profile, log) as b:
        log(f"Opening collection '{folder_name}'...")
        b.pump(12, is_cancelled)
        if not b.folders():
            raise DouyinError(
                "Could not read your Douyin collections - not logged in yet?")

        result = b.evaluate(_click_folder_js(folder_name), is_cancelled)
        if result != "CLICKED":
            log(f"Could not open '{folder_name}' automatically - "
                "please click it in the browser window.")
        b.pump(8, is_cancelled)

        # Douyin pages the listing; scrolling makes the page fetch the rest.
        # Stop when the folder's own last page reports no more rows and
        # nothing new has arrived. `expected` is only an upper bound: it
        # counts saved slots, including videos that have since been deleted
        # or made private, which Douyin quietly omits from the listing.
        stale = 0
        previous = 0
        for _ in range(max_scrolls):
            if is_cancelled():
                break
            count = len(b.videos(folder_id))
            if expected and count >= expected:
                break
            if count == previous:
                stale += 1
                if stale >= 3 and b.saw_any_page(folder_id)                         and not b.more_pages_pending(folder_id):
                    break
                if stale >= 8:
                    break
            else:
                stale = 0
            previous = count
            b.evaluate(SCROLL_JS, is_cancelled)
            b.pump(5, is_cancelled)

        videos = b.videos(folder_id)
        if not videos:
            # A folder click that silently failed still leaves the default
            # folder's rows around, so be explicit rather than guessing.
            raise DouyinError(f"No videos found in '{folder_name}'.")
        if expected and len(videos) < expected:
            log(f"Collection '{folder_name}': {len(videos)} of {expected} "
                "saved videos are still available on Douyin.")
        else:
            log(f"Collection '{folder_name}': {len(videos)} video(s).")
        return [v["id"] for v in videos]
