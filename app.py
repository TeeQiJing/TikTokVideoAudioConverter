"""TikTok / Douyin Video Audio Converter - desktop app.

Paste TikTok links (single videos or a public collection link) or Douyin
video links and get MP3s.

The download engine is the official standalone yt-dlp.exe (bundled by the
installer in bin\\). Before every run the app lets yt-dlp update itself,
so fixes for TikTok changes arrive without reinstalling this app.

Douyin note: Douyin has no public collection/playlist link (a shared
collection is an in-app share code, not a URL), so Douyin works with
individual video links only. Douyin's web API also rejects requests that
carry no browser-issued signature cookies, so before Douyin downloads the
app harvests guest cookies by loading douyin.com once in headless
Edge/Chrome - no Douyin login or account is involved.

Run normally for the GUI. Hidden flags for testing/packaging:
  --cli <url>... [-o <folder>]   run one download without the GUI
  --smoke                        open the GUI and close it after 2 seconds
"""

import json
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

import douyin_collection

APP_NAME = "TikTok Video Audio Converter"
CONFIG_DIR = Path(os.environ.get("APPDATA", Path.home())) / "TikTokVideoAudioConverter"
CONFIG_FILE = CONFIG_DIR / "config.json"
ARCHIVE_NAME = ".downloaded_archive.txt"
DEFAULT_OUTPUT = Path.home() / "Music" / "TikTok Songs"

TIKTOK_RE = re.compile(r"https?://(?:www\.|vm\.|vt\.|m\.)?tiktok\.com/\S+")
# Douyin share sheets hand out v.douyin.com short links; those and the older
# iesdouyin share pages both redirect to www.douyin.com/video/<id>, which is
# the only Douyin URL shape yt-dlp's extractor accepts.
DOUYIN_RE = re.compile(r"https?://(?:www\.|v\.|m\.)?(?:douyin|iesdouyin)\.com/\S+")
URL_RE = re.compile(f"{TIKTOK_RE.pattern}|{DOUYIN_RE.pattern}")
DOUYIN_ID_RE = re.compile(r"(?:douyin|iesdouyin)\.com/(?:share/)?(?:video|note)/(\d+)")

CREATE_NO_WINDOW = 0x08000000 if os.name == "nt" else 0

# yt-dlp/yt-dlp#17403 (TikTok's Aug 2026 bot detection, "Unexpected response
# from webpage request") was fixed upstream in yt-dlp 2026.08.19, which solves
# the JS challenge properly. So the first pass now uses yt-dlp's own default
# transport - None below - and only the later retry passes fall back to
# overriding the user-agent, which was the pre-fix workaround and still helps
# if TikTok bans a particular Chrome version range again.
PLAIN_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")

BROWSER_UAS = [
    None,
    *("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      f"(KHTML, like Gecko) Chrome/{v} Safari/537.36"
      for v in ("150.0.0.0", "139.0.0.0", "136.0.0.0")),
]

# Douyin's web API answers with an empty payload unless the request carries
# the signature cookies (s_v_web_id, ttwid, msToken) that douyin.com's own
# JavaScript sets. yt-dlp cannot mint those itself ("Fresh cookies (not
# necessarily logged in) are needed"), and they cannot be forged - but they
# are *guest* cookies, so simply loading douyin.com once in a headless
# browser produces a working set without any Douyin account or login.
# Windows 11 always ships Edge, so this needs nothing extra installed.
DOUYIN_PROFILE = CONFIG_DIR / "douyin_browser_profile"
# A second, separate profile holds the user's own Douyin login, used only to
# read their 收藏夹 listing. Downloads keep using the anonymous guest profile
# above, so the account is never attached to the downloading itself.
DOUYIN_LOGIN_PROFILE = CONFIG_DIR / "douyin_login_profile"
DOUYIN_COOKIE_MAX_AGE = 30 * 60  # seconds; refresh before Douyin expires them
DOUYIN_BOOTSTRAP_URL = "https://www.douyin.com/discover"

CHROMIUM_BROWSERS = [
    # (yt-dlp browser name, env var holding the install root, relative exe)
    ("edge", "ProgramFiles(x86)", r"Microsoft\Edge\Application\msedge.exe"),
    ("edge", "ProgramFiles", r"Microsoft\Edge\Application\msedge.exe"),
    ("chrome", "ProgramFiles", r"Google\Chrome\Application\chrome.exe"),
    ("chrome", "ProgramFiles(x86)", r"Google\Chrome\Application\chrome.exe"),
    ("chrome", "LOCALAPPDATA", r"Google\Chrome\Application\chrome.exe"),
]

# TikTok brand-ish palette
C_BG = "#ffffff"
C_HEADER = "#161823"
C_PINK = "#fe2c55"
C_PINK_DARK = "#d9224a"
C_CYAN = "#25f4ee"
C_TEXT = "#161823"
C_SUBTLE = "#6b6e7b"
C_OK = "#0a8f3c"
C_WARN = "#c26a00"

STRINGS = {
    "en": {
        "title": "TikTok / Douyin Song Downloader",
        "header": "🎵  TikTok / Douyin Song Downloader",
        "subheader": "Turn your favourite TikTok and 抖音 videos into music files",
        "step1": "1.  Paste your TikTok or 抖音 link here",
        "mode_collection": "My TikTok collection (all my saved songs)",
        "mode_videos": "Single videos (TikTok or 抖音)",
        "mode_douyin": "My 抖音 collection (收藏夹)",
        "hint_douyin": ("Reads a 收藏夹 straight from your 抖音 account. "
                        "Choose the folder once - the app remembers it, and "
                        "each run only fetches songs added since last time."),
        "choose_folder": "Choose collection…",
        "no_folder": "No collection chosen yet",
        "folder_is": "Collection: {name}",
        "pick_title": "Choose a 抖音 collection",
        "pick_prompt": "Which collection should the app download?",
        "pick_ok": "Use this one",
        "pick_cancel": "Cancel",
        "reading_folders": "Opening 抖音… a browser window will appear.",
        "login_note": ("If it asks you to log in, please log in to 抖音 in "
                       "that window (扫码登录 is quickest). You only need "
                       "to do this once."),
        "need_folder": "Please choose a 抖音 collection first.",
        "no_browser": ("Microsoft Edge or Google Chrome is needed to read your "
                       "抖音 collection. Please install one and try again."),
        "dy_failed": "Could not read your 抖音 collection: {err}",
        "hint_collection": ("In TikTok: Profile → Saved 🔖 → open your collection "
                            "→ Share → Copy link. Paste it once - the app "
                            "remembers it. (抖音 has no collection link - use "
                            "\"Single videos\" for 抖音.)"),
        "hint_videos": ("On each video: Share → Copy link (抖音: 分享 → "
                        "复制链接). You can paste several links at once."),
        "step2": "2.  Where to save the songs",
        "browse": "Choose folder…",
        "step3": "3.  Press the big button!",
        "download": "⬇   Get my songs",
        "cancel": "Stop",
        "open_folder": "📂  Open my songs",
        "details_show": "Show details ▾",
        "details_hide": "Hide details ▴",
        "working": "Downloading your songs… please wait ⏳",
        "done_new": "Done! {n} new song(s) saved 🎉",
        "done_none": "Done! No new songs - you already have them all 👍",
        "failed": ("Some songs could not be downloaded. TikTok may be having "
                   "problems - please try again later. 🙂"),
        "cancelled": "Stopped.",
        "no_links": "Please paste a TikTok or 抖音 link into the box first.",
        "many_links": ("You pasted several links. Choose \"Single videos\" "
                       "for those, or paste just one collection link."),
        "douyin_collection": ("抖音 has no shareable collection link - a shared "
                              "收藏夹 only opens inside the 抖音 app. "
                              "Please choose \"Single videos\" and paste the "
                              "抖音 video links instead."),
        "tools_missing": "App files are missing. Please reinstall the app.",
        "language": "Language",
    },
    "zh": {
        "title": "TikTok / 抖音 歌曲下载器",
        "header": "🎵  TikTok / 抖音 歌曲下载器",
        "subheader": "把你喜欢的 TikTok 和抖音视频变成音乐文件",
        "step1": "1.  把 TikTok 或抖音链接贴在这里",
        "mode_collection": "我的 TikTok 收藏夹（所有收藏的歌曲）",
        "mode_videos": "单个视频（TikTok 或抖音）",
        "mode_douyin": "我的抖音收藏夹",
        "hint_douyin": ("直接从你的抖音账号读取收藏夹。"
                        "选一次就行，软件会记住，"
                        "以后每次只下载新增的歌曲。"),
        "choose_folder": "选择收藏夹…",
        "no_folder": "还没有选收藏夹",
        "folder_is": "收藏夹：{name}",
        "pick_title": "选择抖音收藏夹",
        "pick_prompt": "要下载哪个收藏夹？",
        "pick_ok": "就用这个",
        "pick_cancel": "取消",
        "reading_folders": "正在打开抖音…会弹出一个浏览器窗口。",
        "login_note": ("如果要求登录，请在那个窗口登录抖音"
                       "（扫码登录最快）。只需要登录一次。"),
        "need_folder": "请先选择一个抖音收藏夹。",
        "no_browser": ("读取抖音收藏夹需要 Microsoft Edge "
                       "或 Google Chrome，请先安装。"),
        "dy_failed": "无法读取抖音收藏夹：{err}",
        "hint_collection": ("在 TikTok 里：个人资料 → 收藏 🔖 → 打开收藏夹 "
                            "→ 分享 → 复制链接。贴一次就行，软件会记住它。"
                            "（抖音没有收藏夹链接，抖音请用“单个视频”。）"),
        "hint_videos": ("在每个视频上：分享 → 复制链接。"
                        "可以一次贴多个链接。"),
        "step2": "2.  歌曲保存到哪里",
        "browse": "选择文件夹…",
        "step3": "3.  按下面的大按钮！",
        "download": "⬇   下载我的歌曲",
        "cancel": "停止",
        "open_folder": "📂  打开歌曲文件夹",
        "details_show": "显示详细信息 ▾",
        "details_hide": "隐藏详细信息 ▴",
        "working": "正在下载歌曲… 请稍等 ⏳",
        "done_new": "完成！新增 {n} 首歌曲 🎉",
        "done_none": "完成！没有新歌曲——都已经下载过了 👍",
        "failed": "有些歌曲下载失败。TikTok 可能暂时有问题，请过一会儿再试。🙂",
        "cancelled": "已停止。",
        "no_links": "请先把 TikTok 或抖音链接贴到框里。",
        "many_links": "你贴了多个链接。请选择“单个视频”，或者只贴一个收藏夹链接。",
        "douyin_collection": ("抖音没有可以分享的收藏夹链接，"
                              "分享出来的只能在抖音 App 里打开。"
                              "请选择“单个视频”，"
                              "然后贴抖音视频链接。"),
        "tools_missing": "软件文件丢失，请重新安装。",
        "language": "语言",
    },
}


# ---------------------------------------------------------------- tool paths

def app_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent
    return Path(__file__).parent


def find_ffmpeg() -> str | None:
    """Locate the folder holding ffmpeg.exe/ffprobe.exe."""
    for c in (app_dir() / "ffmpeg", app_dir() / "installer" / "ffmpeg"):
        if (c / "ffmpeg.exe").exists():
            return str(c)
    found = shutil.which("ffmpeg")
    if found:
        return str(Path(found).parent)
    packages = Path.home() / "AppData/Local/Microsoft/WinGet/Packages"
    if packages.exists():
        for pkg in packages.glob("Gyan.FFmpeg*"):
            for exe in pkg.rglob("ffmpeg.exe"):
                return str(exe.parent)
    return None


def find_ytdlp() -> str | None:
    """Locate the standalone yt-dlp.exe."""
    for c in (app_dir() / "bin" / "yt-dlp.exe",
              app_dir() / "installer" / "bin" / "yt-dlp.exe"):
        if c.exists():
            return str(c)
    return shutil.which("yt-dlp")


# ---------------------------------------------------------------- core logic

def extract_urls(text: str) -> list[str]:
    seen: dict[str, None] = {}
    for m in URL_RE.finditer(text):
        seen.setdefault(m.group(0).rstrip(".,;)!\"'"), None)
    return list(seen)


def is_douyin(url: str) -> bool:
    return bool(DOUYIN_RE.match(url))


def canonical_douyin(url: str) -> str:
    """Turn any Douyin share link into https://www.douyin.com/video/<id>.

    yt-dlp only recognises that one shape, but the Douyin app's "copy link"
    gives a v.douyin.com short link, so follow the redirect ourselves.
    """
    m = DOUYIN_ID_RE.search(url)
    if m:
        return f"https://www.douyin.com/video/{m.group(1)}"
    import urllib.request
    req = urllib.request.Request(url, headers={"User-Agent": PLAIN_UA})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            final = resp.geturl()
    except OSError:
        return url
    m = DOUYIN_ID_RE.search(final)
    return f"https://www.douyin.com/video/{m.group(1)}" if m else final


def _stream(cmd: list[str], log, is_cancelled) -> int:
    """Run cmd, feeding output lines to log. Returns exit code (-1 = cancelled)."""
    proc = subprocess.Popen(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace",
        # Make yt-dlp emit UTF-8 into the pipe so non-ASCII titles survive
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
        creationflags=CREATE_NO_WINDOW)
    for line in proc.stdout:
        line = line.rstrip()
        if line:
            log(line)
        if is_cancelled():
            proc.terminate()
            proc.wait()
            return -1
    return proc.wait()


def find_chromium() -> tuple[str, str] | None:
    """Locate a Chromium-based browser to harvest Douyin guest cookies with."""
    for name, env_var, rel in CHROMIUM_BROWSERS:
        root = os.environ.get(env_var)
        if root and (Path(root) / rel).exists():
            return name, str(Path(root) / rel)
    for name, exe in (("chrome", "chrome"), ("edge", "msedge")):
        found = shutil.which(exe)
        if found:
            return name, found
    return None


def _cookies_fresh() -> bool:
    jar = DOUYIN_PROFILE / "Default" / "Network" / "Cookies"
    try:
        return (time.time() - jar.stat().st_mtime) < DOUYIN_COOKIE_MAX_AGE
    except OSError:
        return False


def douyin_cookie_args(log) -> list[str]:
    """Refresh Douyin guest cookies if stale, and return the yt-dlp flags.

    Returns [] when no Chromium browser is available - yt-dlp then fails the
    Douyin links with its own "Fresh cookies ... are needed" message.
    """
    browser = find_chromium()
    if not browser:
        log("No Edge or Chrome found - Douyin links need one of them "
            "installed to work.")
        return []
    name, exe = browser
    if not _cookies_fresh():
        log("Preparing Douyin (loading douyin.com in the background)...")
        DOUYIN_PROFILE.mkdir(parents=True, exist_ok=True)
        try:
            subprocess.run(
                [exe, "--headless=new", "--no-first-run",
                 "--no-default-browser-check", "--disable-gpu",
                 f"--user-data-dir={DOUYIN_PROFILE}",
                 # let the page's JS run long enough to set the cookies, then
                 # fast-forward its timers so the browser exits by itself
                 "--virtual-time-budget=25000",
                 DOUYIN_BOOTSTRAP_URL],
                timeout=90, creationflags=CREATE_NO_WINDOW,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except (OSError, subprocess.TimeoutExpired):
            pass
    if not _cookies_fresh():
        log("Could not prepare Douyin cookies - Douyin links may fail.")
        return []
    return ["--cookies-from-browser", f"{name}:{DOUYIN_PROFILE}"]


def _base_cmd(out_dir: Path, ytdlp: str, ffmpeg_dir: str) -> list[str]:
    return [
        ytdlp,
        "--ffmpeg-location", ffmpeg_dir,
        # CBR 192k @ 44.1kHz: safest combo for car-radio MP3 decoders (VBR
        # makes cheap decoders pop/stutter). Loudness-normalize with a
        # -1.5dB true-peak limiter so TikTok's hot masters don't clip.
        "-x", "--audio-format", "mp3", "--audio-quality", "192K",
        "--postprocessor-args",
        "ExtractAudio:-af loudnorm=I=-16:TP=-1.5:LRA=11 -ar 44100",
        # TikTok's h265 ("bytevc1") streams often arrive with no audio track
        # despite advertising AAC, so prefer audio-only, then h264, then best.
        "-f", "ba/b[vcodec^=h264]/b",
        "--embed-metadata",
        "--download-archive", str(out_dir / ARCHIVE_NAME),
        "--no-overwrites",
        "--ignore-errors",
        "--windows-filenames",
        "--no-progress",
        "-o", str(out_dir / "%(title).80s [%(id)s].%(ext)s"),
    ]


def download(urls: list[str], out_dir: Path, ytdlp: str, ffmpeg_dir: str,
             log, is_cancelled=lambda: False, attempts: int = 5) -> bool:
    """Download URLs as MP3s into out_dir. Returns True if all succeeded."""
    out_dir.mkdir(parents=True, exist_ok=True)

    # Let yt-dlp update itself so TikTok/Douyin fixes arrive automatically.
    log("Checking for downloader updates...")
    _stream([ytdlp, "-U"], log, is_cancelled)
    if is_cancelled():
        return False
    log("")

    tiktok_urls = [u for u in urls if not is_douyin(u)]
    douyin_urls = [canonical_douyin(u) for u in urls if is_douyin(u)]

    # Douyin needs its cookie jar; TikTok must not get it, so the two sites
    # run as separate yt-dlp passes.
    # (urls, extra yt-dlp args, rotate the user-agent on retries?)
    groups: list[tuple[list[str], list[str], bool]] = []
    if tiktok_urls:
        groups.append((tiktok_urls, [], True))
    if douyin_urls:
        if is_cancelled():
            return False
        # The user-agent ladder is a TikTok-specific workaround, and Douyin's
        # cookies are issued to a real browser UA, so leave Douyin's alone.
        groups.append((douyin_urls, douyin_cookie_args(log), False))

    base = _base_cmd(out_dir, ytdlp, ffmpeg_dir)
    all_ok = True
    for group_urls, extra, rotate_ua in groups:
        # Extraction is flaky; the archive makes retry passes cheap because
        # finished songs are skipped, so only failed ones are re-attempted.
        for attempt in range(attempts):
            ua = BROWSER_UAS[attempt % len(BROWSER_UAS)] if rotate_ua else None
            cmd = [*base, *extra, *(["--user-agent", ua] if ua else []),
                   *group_urls]
            code = _stream(cmd, log, is_cancelled)
            if code == -1:
                log("Cancelled.")
                return False
            if code == 0:
                break
            if attempt < attempts - 1:
                log("")
                log("Some downloads failed - retrying those...")
                log("")
        else:
            all_ok = False
    return all_ok


# ------------------------------------------------------------------ settings

def load_config() -> dict:
    try:
        return json.loads(CONFIG_FILE.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return {}


def save_config(cfg: dict) -> None:
    try:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        CONFIG_FILE.write_text(json.dumps(cfg, indent=2, ensure_ascii=False),
                               encoding="utf-8")
    except OSError:
        pass


# ----------------------------------------------------------------------- GUI

class App:
    def __init__(self, smoke: bool = False):
        import tkinter as tk

        self.tk = tk
        self.cfg = load_config()
        self.lang = self.cfg.get("lang", "zh")
        self.cancelled = threading.Event()
        self.log_q: queue.Queue[str] = queue.Queue()
        self.worker: threading.Thread | None = None
        self.result: bool | None = None
        self.songs_before: set[str] = set()
        self.details_open = False
        self.job = "download"           # which kind of worker is running
        self.pending_folders: list[dict] | None = None
        self.pending_error: str | None = None

        self.root = tk.Tk()
        self.root.configure(bg=C_BG)
        self.root.geometry("760x680")
        self.root.minsize(640, 560)
        self.build()
        self.pump()
        if smoke:
            self.root.after(2000, self.root.destroy)
        self.root.mainloop()

    def t(self, key: str) -> str:
        return STRINGS[self.lang][key]

    # ------------------------------------------------------------- layout

    def build(self):
        tk = self.tk
        from tkinter import scrolledtext, ttk

        self.root.title(self.t("title"))
        for w in self.root.winfo_children():
            w.destroy()

        base = "Microsoft YaHei UI" if self.lang == "zh" else "Segoe UI"
        f_big = (base, 15, "bold")
        f_step = (base, 13, "bold")
        f_body = (base, 12)
        f_small = (base, 10)

        # ---- header bar
        header = tk.Frame(self.root, bg=C_HEADER)
        header.pack(fill="x")
        titles = tk.Frame(header, bg=C_HEADER)
        titles.pack(side="left", padx=16, pady=10)
        tk.Label(titles, text=self.t("header"), font=(base, 18, "bold"),
                 bg=C_HEADER, fg="white", anchor="w").pack(fill="x")
        tk.Label(titles, text=self.t("subheader"), font=f_small,
                 bg=C_HEADER, fg=C_CYAN, anchor="w").pack(fill="x")

        lang_btn = tk.Button(
            header, text="中文" if self.lang == "en" else "English",
            font=f_small, bg=C_HEADER, fg="white",
            activebackground=C_HEADER, activeforeground=C_CYAN,
            relief="flat", cursor="hand2", command=self.switch_lang)
        lang_btn.pack(side="right", padx=16)

        main = tk.Frame(self.root, bg=C_BG)
        main.pack(fill="both", expand=True, padx=20, pady=10)
        self.main = main

        # ---- step 1: link
        tk.Label(main, text=self.t("step1"), font=f_step, bg=C_BG,
                 fg=C_TEXT, anchor="w").pack(fill="x", pady=(8, 2))

        mode_row = tk.Frame(main, bg=C_BG)
        mode_row.pack(fill="x")
        self.mode = tk.StringVar(value=self.cfg.get("mode", "collection"))
        for value, key in (("collection", "mode_collection"),
                           ("douyin", "mode_douyin"),
                           ("videos", "mode_videos")):
            tk.Radiobutton(mode_row, text=self.t(key), variable=self.mode,
                           value=value, font=f_body, bg=C_BG, fg=C_TEXT,
                           activebackground=C_BG, selectcolor="white",
                           cursor="hand2").pack(side="left", padx=(0, 10))

        self.hint = tk.Label(main, font=f_small, bg=C_BG, fg=C_SUBTLE,
                             anchor="w", justify="left")
        self.hint.pack(fill="x", pady=(2, 4))
        self.hint.bind("<Configure>",
                       lambda e: self.hint.config(wraplength=e.width - 8))

        self.input_area = tk.Frame(main, bg=C_BG)
        self.input_area.pack(fill="x")

        self.box_wrap = tk.Frame(self.input_area, bg=C_PINK, padx=2, pady=2)
        self.box_wrap.pack(fill="x")
        self.links_box = tk.Text(self.box_wrap, height=3, wrap="word",
                                 font=f_body, relief="flat", padx=8, pady=8)
        self.links_box.pack(fill="x")

        # Shown instead of the link box when reading a 抖音 收藏夹, which needs
        # no link at all - just the folder the user picked once.
        self.dy_row = tk.Frame(self.input_area, bg=C_BG)
        self.dy_label = tk.Label(self.dy_row, font=f_body, bg=C_BG,
                                 fg=C_TEXT, anchor="w")
        self.dy_label.pack(side="left", fill="x", expand=True)
        tk.Button(self.dy_row, text=self.t("choose_folder"), font=f_body,
                  bg="#f1f1f2", fg=C_TEXT, relief="flat", cursor="hand2",
                  padx=12, command=self.choose_folder).pack(side="left")

        self.mode.trace_add("write", self.on_mode_change)
        self.on_mode_change()

        # ---- step 2: folder
        tk.Label(main, text=self.t("step2"), font=f_step, bg=C_BG,
                 fg=C_TEXT, anchor="w").pack(fill="x", pady=(14, 2))
        out_row = tk.Frame(main, bg=C_BG)
        out_row.pack(fill="x")
        self.out_var = tk.StringVar(
            value=self.cfg.get("output_dir", str(DEFAULT_OUTPUT)))
        tk.Entry(out_row, textvariable=self.out_var, font=f_body,
                 relief="solid", bd=1).pack(side="left", fill="x",
                                            expand=True, ipady=6)
        tk.Button(out_row, text=self.t("browse"), font=f_body,
                  bg="#f1f1f2", fg=C_TEXT, relief="flat", cursor="hand2",
                  padx=12, command=self.browse).pack(side="left", padx=(8, 0))

        # ---- step 3: go!
        tk.Label(main, text=self.t("step3"), font=f_step, bg=C_BG,
                 fg=C_TEXT, anchor="w").pack(fill="x", pady=(14, 4))
        btn_row = tk.Frame(main, bg=C_BG)
        btn_row.pack(fill="x")
        self.start_btn = tk.Button(
            btn_row, text=self.t("download"), font=f_big, bg=C_PINK,
            fg="white", activebackground=C_PINK_DARK,
            activeforeground="white", relief="flat", cursor="hand2",
            padx=24, pady=10, command=self.start)
        self.start_btn.pack(side="left")
        self.cancel_btn = tk.Button(
            btn_row, text=self.t("cancel"), font=f_body, bg="#f1f1f2",
            fg=C_TEXT, relief="flat", cursor="hand2", padx=16, pady=10,
            state="disabled", command=self.cancelled.set)
        self.cancel_btn.pack(side="left", padx=8)
        tk.Button(btn_row, text=self.t("open_folder"), font=f_body,
                  bg="#f1f1f2", fg=C_TEXT, relief="flat", cursor="hand2",
                  padx=16, pady=10, command=self.open_folder).pack(side="left")

        # ---- status + progress
        self.status = tk.Label(main, text="", font=(base, 13, "bold"),
                               bg=C_BG, fg=C_OK, anchor="w", justify="left")
        self.status.pack(fill="x", pady=(12, 2))
        self.status.bind("<Configure>",
                         lambda e: self.status.config(wraplength=e.width - 8))
        self.progress = ttk.Progressbar(main, mode="indeterminate")

        # ---- collapsible details
        self.details_btn = tk.Button(
            main, text=self.t("details_show"), font=f_small, bg=C_BG,
            fg=C_SUBTLE, relief="flat", cursor="hand2", anchor="w",
            command=self.toggle_details)
        self.details_btn.pack(fill="x", pady=(8, 0))
        self.log_box = scrolledtext.ScrolledText(
            main, state="disabled", wrap="word", font=("Consolas", 9),
            height=9, relief="solid", bd=1)
        if self.details_open:
            self.log_box.pack(fill="both", expand=True)

    # ------------------------------------------------------------ actions

    def on_mode_change(self, *_):
        mode = self.mode.get()
        self.hint.config(text=self.t(
            {"collection": "hint_collection",
             "douyin": "hint_douyin"}.get(mode, "hint_videos")))
        if mode == "douyin":
            self.box_wrap.pack_forget()
            self.dy_row.pack(fill="x", pady=(2, 0))
            self.refresh_folder_label()
            return
        self.dy_row.pack_forget()
        self.box_wrap.pack(fill="x")
        self.links_box.delete("1.0", "end")
        if mode == "collection":
            self.links_box.insert("1.0", self.cfg.get("collection_link", ""))

    # ------------------------------------------------- 抖音 collection mode

    def refresh_folder_label(self) -> None:
        folder = self.cfg.get("douyin_folder")
        self.dy_label.config(
            text=self.t("folder_is").format(name=folder["name"]) if folder
            else self.t("no_folder"))

    def choose_folder(self):
        """Read the user's 收藏夹 list in the background, then offer a picker."""
        from tkinter import messagebox
        if self.worker:
            return
        browser = find_chromium()
        if not browser:
            messagebox.showerror(self.t("title"), self.t("no_browser"))
            return
        self.begin_work(self.t("reading_folders"))
        self.log_q.put(self.t("login_note"))

        def work():
            try:
                self.pending_folders = douyin_collection.list_folders(
                    browser[1], DOUYIN_LOGIN_PROFILE, self.log_q.put,
                    self.cancelled.is_set)
            except Exception as e:  # surfaced by pump() on the GUI thread
                self.pending_error = str(e)

        self.worker = threading.Thread(target=work, daemon=True)
        self.worker.start()

    def show_folder_picker(self, folders: list[dict]) -> None:
        tk = self.tk
        base = "Microsoft YaHei UI" if self.lang == "zh" else "Segoe UI"
        win = tk.Toplevel(self.root)
        win.title(self.t("pick_title"))
        win.configure(bg=C_BG)
        win.transient(self.root)
        win.grab_set()
        tk.Label(win, text=self.t("pick_prompt"), font=(base, 12, "bold"),
                 bg=C_BG, fg=C_TEXT).pack(padx=16, pady=(14, 8), anchor="w")
        box = tk.Listbox(win, font=(base, 12), height=min(10, len(folders)),
                         activestyle="none", width=40)
        for f in folders:
            count = "" if f["count"] is None else f"  ({f['count']})"
            box.insert("end", f"{f['name']}{count}")
        box.selection_set(0)
        box.pack(fill="both", expand=True, padx=16)

        def accept():
            picked = folders[box.curselection()[0]] if box.curselection() else None
            win.destroy()
            if picked:
                self.cfg["douyin_folder"] = {"id": picked["id"],
                                             "name": picked["name"],
                                             "count": picked["count"]}
                save_config(self.cfg)
                self.refresh_folder_label()

        row = tk.Frame(win, bg=C_BG)
        row.pack(fill="x", padx=16, pady=12)
        tk.Button(row, text=self.t("pick_ok"), font=(base, 11), bg=C_PINK,
                  fg="white", relief="flat", cursor="hand2", padx=16, pady=6,
                  command=accept).pack(side="left")
        tk.Button(row, text=self.t("pick_cancel"), font=(base, 11),
                  bg="#f1f1f2", fg=C_TEXT, relief="flat", cursor="hand2",
                  padx=16, pady=6, command=win.destroy).pack(side="left",
                                                             padx=8)
        box.bind("<Double-Button-1>", lambda e: accept())

    def switch_lang(self):
        self.lang = "zh" if self.lang == "en" else "en"
        self.cfg["lang"] = self.lang
        save_config(self.cfg)
        self.build()

    def browse(self):
        from tkinter import filedialog
        chosen = filedialog.askdirectory(
            initialdir=self.out_var.get() or str(Path.home()))
        if chosen:
            self.out_var.set(chosen)

    def open_folder(self):
        folder = Path(self.out_var.get())
        folder.mkdir(parents=True, exist_ok=True)
        os.startfile(folder)

    def toggle_details(self):
        self.details_open = not self.details_open
        if self.details_open:
            self.details_btn.config(text=self.t("details_hide"))
            self.log_box.pack(fill="both", expand=True)
        else:
            self.details_btn.config(text=self.t("details_show"))
            self.log_box.pack_forget()

    def begin_work(self, status_text: str, job: str = "folders") -> None:
        """Put the window into its busy state for a background job."""
        self.job = job
        self.pending_folders = None
        self.pending_error = None
        self.cancelled.clear()
        self.start_btn.config(state="disabled", bg="#f7a3b5")
        self.cancel_btn.config(state="normal")
        self.status.config(text=status_text, fg=C_WARN)
        self.progress.pack(fill="x", pady=(0, 4), after=self.status)
        self.progress.start(12)
        self.log_box.config(state="normal")
        self.log_box.delete("1.0", "end")
        self.log_box.config(state="disabled")

    def end_work(self) -> None:
        self.progress.stop()
        self.progress.pack_forget()
        self.start_btn.config(state="normal", bg=C_PINK)
        self.cancel_btn.config(state="disabled")

    def start(self):
        from tkinter import messagebox
        if self.mode.get() == "douyin":
            self.start_douyin()
            return
        urls = extract_urls(self.links_box.get("1.0", "end"))
        if not urls:
            messagebox.showwarning(self.t("title"), self.t("no_links"))
            return
        if self.mode.get() == "collection" and any(is_douyin(u) for u in urls):
            messagebox.showwarning(self.t("title"), self.t("douyin_collection"))
            return
        if self.mode.get() == "collection" and len(urls) > 1:
            messagebox.showwarning(self.t("title"), self.t("many_links"))
            return
        ytdlp = find_ytdlp()
        ffmpeg_dir = find_ffmpeg()
        if not ytdlp or not ffmpeg_dir:
            messagebox.showerror(self.t("title"), self.t("tools_missing"))
            return
        out_dir = Path(self.out_var.get().strip() or str(DEFAULT_OUTPUT))

        self.cfg.update({"mode": self.mode.get(), "output_dir": str(out_dir)})
        if self.mode.get() == "collection":
            self.cfg["collection_link"] = urls[0]
        save_config(self.cfg)

        self.songs_before = {p.name for p in out_dir.glob("*.mp3")}
        self.out_dir = out_dir
        self.result = None
        self.begin_work(self.t("working"), job="download")

        def work():
            self.result = download(urls, out_dir, ytdlp, ffmpeg_dir,
                                   self.log_q.put, self.cancelled.is_set)

        self.worker = threading.Thread(target=work, daemon=True)
        self.worker.start()

    def start_douyin(self):
        """Read the chosen 收藏夹, then download everything in it."""
        from tkinter import messagebox
        folder = self.cfg.get("douyin_folder")
        if not folder:
            messagebox.showwarning(self.t("title"), self.t("need_folder"))
            return
        browser = find_chromium()
        if not browser:
            messagebox.showerror(self.t("title"), self.t("no_browser"))
            return
        ytdlp, ffmpeg_dir = find_ytdlp(), find_ffmpeg()
        if not ytdlp or not ffmpeg_dir:
            messagebox.showerror(self.t("title"), self.t("tools_missing"))
            return
        out_dir = Path(self.out_var.get().strip() or str(DEFAULT_OUTPUT))

        self.cfg.update({"mode": "douyin", "output_dir": str(out_dir)})
        save_config(self.cfg)
        self.songs_before = {p.name for p in out_dir.glob("*.mp3")}
        self.out_dir = out_dir
        self.result = None
        self.begin_work(self.t("working"), job="download")

        def work():
            try:
                ids = douyin_collection.list_folder_videos(
                    browser[1], DOUYIN_LOGIN_PROFILE, folder["name"],
                    folder["id"], self.log_q.put, self.cancelled.is_set,
                    expected=folder.get("count"))
            except Exception as e:
                self.log_q.put(str(e))
                self.result = False
                return
            if self.cancelled.is_set():
                return
            urls = [f"https://www.douyin.com/video/{i}" for i in ids]
            self.result = download(urls, out_dir, ytdlp, ffmpeg_dir,
                                   self.log_q.put, self.cancelled.is_set)

        self.worker = threading.Thread(target=work, daemon=True)
        self.worker.start()

    def pump(self):
        try:
            while True:
                line = self.log_q.get_nowait()
                self.log_box.config(state="normal")
                self.log_box.insert("end", line + "\n")
                self.log_box.see("end")
                self.log_box.config(state="disabled")
        except queue.Empty:
            pass
        if self.worker and not self.worker.is_alive():
            from tkinter import messagebox
            self.worker = None
            self.end_work()
            if self.job == "folders":
                self.status.config(text="", fg=C_SUBTLE)
                if self.pending_error:
                    messagebox.showerror(
                        self.t("title"),
                        self.t("dy_failed").format(err=self.pending_error))
                elif self.pending_folders:
                    self.show_folder_picker(self.pending_folders)
                self.root.after(150, self.pump)
                return
            new_count = len({p.name for p in self.out_dir.glob("*.mp3")}
                            - self.songs_before)
            if self.cancelled.is_set():
                self.status.config(text=self.t("cancelled"), fg=C_SUBTLE)
            elif self.result:
                text = (self.t("done_new").format(n=new_count) if new_count
                        else self.t("done_none"))
                self.status.config(text=text, fg=C_OK)
            else:
                extra = (self.t("done_new").format(n=new_count) + "  "
                         if new_count else "")
                self.status.config(text=extra + self.t("failed"), fg=C_WARN)
        self.root.after(150, self.pump)


# ----------------------------------------------------------------------- CLI

def run_cli(argv: list[str]) -> int:
    out_dir = DEFAULT_OUTPUT
    urls: list[str] = []
    it = iter(argv)
    for a in it:
        if a == "-o":
            out_dir = Path(next(it))
        else:
            urls.extend(extract_urls(a))
    ytdlp = find_ytdlp()
    ffmpeg_dir = find_ffmpeg()
    if not ytdlp or not ffmpeg_dir:
        print("yt-dlp / ffmpeg not found")
        return 2
    if not urls:
        print("no TikTok links given")
        return 2
    ok = download(urls, out_dir, ytdlp, ffmpeg_dir, print)
    print("OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    args = sys.argv[1:]
    if args and args[0] == "--cli":
        sys.exit(run_cli(args[1:]))
    App(smoke="--smoke" in args)
