"""TikTok Video Audio Converter - desktop app.

Paste TikTok links (single videos or a public collection link) and get MP3s.

The download engine is the official standalone yt-dlp.exe (bundled by the
installer in bin\\). Before every run the app lets yt-dlp update itself,
so fixes for TikTok changes arrive without reinstalling this app.

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
from pathlib import Path

APP_NAME = "TikTok Video Audio Converter"
CONFIG_DIR = Path(os.environ.get("APPDATA", Path.home())) / "TikTokVideoAudioConverter"
CONFIG_FILE = CONFIG_DIR / "config.json"
ARCHIVE_NAME = ".downloaded_archive.txt"
DEFAULT_OUTPUT = Path.home() / "Music" / "TikTok Songs"

URL_RE = re.compile(r"https?://(?:www\.|vm\.|vt\.|m\.)?tiktok\.com/\S+")

CREATE_NO_WINDOW = 0x08000000 if os.name == "nt" else 0

# Plain browser user-agent sidesteps TikTok's Aug 2026 bot detection that
# breaks yt-dlp's default UA (yt-dlp/yt-dlp#17403); harmless otherwise.
BROWSER_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")

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
        "title": "TikTok Song Downloader",
        "header": "🎵  TikTok Song Downloader",
        "subheader": "Turn your favourite TikTok videos into music files",
        "step1": "1.  Paste your TikTok link here",
        "mode_collection": "My collection (all my saved songs)",
        "mode_videos": "Single videos",
        "hint_collection": ("In TikTok: Profile → Saved 🔖 → open your collection "
                            "→ Share → Copy link. Paste it once - the app "
                            "remembers it."),
        "hint_videos": ("On each TikTok video: Share → Copy link. "
                        "You can paste several links."),
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
        "no_links": "Please paste a TikTok link into the box first.",
        "many_links": ("You pasted several links. Choose \"Single videos\" "
                       "for those, or paste just one collection link."),
        "tools_missing": "App files are missing. Please reinstall the app.",
        "language": "Language",
    },
    "zh": {
        "title": "TikTok 歌曲下载器",
        "header": "🎵  TikTok 歌曲下载器",
        "subheader": "把你喜欢的 TikTok 视频变成音乐文件",
        "step1": "1.  把 TikTok 链接贴在这里",
        "mode_collection": "我的收藏夹（所有收藏的歌曲）",
        "mode_videos": "单个视频",
        "hint_collection": ("在 TikTok 里：个人资料 → 收藏 🔖 → 打开收藏夹 "
                            "→ 分享 → 复制链接。贴一次就行，软件会记住它。"),
        "hint_videos": "在每个视频上：分享 → 复制链接。可以贴多个链接。",
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
        "no_links": "请先把 TikTok 链接贴到框里。",
        "many_links": "你贴了多个链接。请选择“单个视频”，或者只贴一个收藏夹链接。",
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
    for url in URL_RE.findall(text):
        seen.setdefault(url.rstrip(".,;)!\"'"), None)
    return list(seen)


def _stream(cmd: list[str], log, is_cancelled) -> int:
    """Run cmd, feeding output lines to log. Returns exit code (-1 = cancelled)."""
    proc = subprocess.Popen(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace",
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


def download(urls: list[str], out_dir: Path, ytdlp: str, ffmpeg_dir: str,
             log, is_cancelled=lambda: False, attempts: int = 3) -> bool:
    """Download URLs as MP3s into out_dir. Returns True if all succeeded."""
    out_dir.mkdir(parents=True, exist_ok=True)

    # Let yt-dlp update itself so TikTok fixes arrive automatically.
    log("Checking for downloader updates...")
    _stream([ytdlp, "-U"], log, is_cancelled)
    if is_cancelled():
        return False
    log("")

    cmd = [
        ytdlp,
        "--user-agent", BROWSER_UA,
        "--ffmpeg-location", ffmpeg_dir,
        "-x", "--audio-format", "mp3", "--audio-quality", "0",
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
        *urls,
    ]

    # TikTok extraction is flaky; the archive makes retry passes cheap because
    # finished songs are skipped, so only failed ones are re-attempted.
    for attempt in range(attempts):
        code = _stream(cmd, log, is_cancelled)
        if code == -1:
            log("Cancelled.")
            return False
        if code == 0:
            return True
        if attempt < attempts - 1:
            log("")
            log("Some downloads failed - retrying those...")
            log("")
    return False


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
                           ("videos", "mode_videos")):
            tk.Radiobutton(mode_row, text=self.t(key), variable=self.mode,
                           value=value, font=f_body, bg=C_BG, fg=C_TEXT,
                           activebackground=C_BG, selectcolor="white",
                           cursor="hand2").pack(side="left", padx=(0, 14))

        self.hint = tk.Label(main, font=f_small, bg=C_BG, fg=C_SUBTLE,
                             anchor="w", justify="left")
        self.hint.pack(fill="x", pady=(2, 4))
        self.hint.bind("<Configure>",
                       lambda e: self.hint.config(wraplength=e.width - 8))

        box_wrap = tk.Frame(main, bg=C_PINK, padx=2, pady=2)
        box_wrap.pack(fill="x")
        self.links_box = tk.Text(box_wrap, height=3, wrap="word", font=f_body,
                                 relief="flat", padx=8, pady=8)
        self.links_box.pack(fill="x")

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
        self.hint.config(text=self.t(
            "hint_collection" if self.mode.get() == "collection"
            else "hint_videos"))
        self.links_box.delete("1.0", "end")
        if self.mode.get() == "collection":
            self.links_box.insert("1.0", self.cfg.get("collection_link", ""))

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

    def start(self):
        from tkinter import messagebox
        urls = extract_urls(self.links_box.get("1.0", "end"))
        if not urls:
            messagebox.showwarning(self.t("title"), self.t("no_links"))
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
        self.cancelled.clear()
        self.start_btn.config(state="disabled", bg="#f7a3b5")
        self.cancel_btn.config(state="normal")
        self.status.config(text=self.t("working"), fg=C_WARN)
        self.progress.pack(fill="x", pady=(0, 4), after=self.status)
        self.progress.start(12)
        self.log_box.config(state="normal")
        self.log_box.delete("1.0", "end")
        self.log_box.config(state="disabled")

        def work():
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
            self.worker = None
            self.progress.stop()
            self.progress.pack_forget()
            self.start_btn.config(state="normal", bg=C_PINK)
            self.cancel_btn.config(state="disabled")
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
