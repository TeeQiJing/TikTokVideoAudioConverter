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
        return json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def save_config(cfg: dict) -> None:
    try:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        CONFIG_FILE.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    except OSError:
        pass


# ----------------------------------------------------------------------- GUI

def run_gui(smoke: bool = False) -> None:
    import tkinter as tk
    from tkinter import filedialog, messagebox, scrolledtext, ttk

    cfg = load_config()

    root = tk.Tk()
    root.title(APP_NAME)
    root.geometry("720x560")
    root.minsize(600, 480)

    main = ttk.Frame(root, padding=12)
    main.pack(fill="both", expand=True)

    # --- mode choice
    mode = tk.StringVar(value=cfg.get("mode", "collection"))
    mode_row = ttk.Frame(main)
    mode_row.pack(fill="x")
    ttk.Label(mode_row, text="What are you pasting?").pack(side="left")
    ttk.Radiobutton(mode_row, text="Collection link (recommended)",
                    variable=mode, value="collection").pack(side="left", padx=8)
    ttk.Radiobutton(mode_row, text="Video link(s), one per line",
                    variable=mode, value="videos").pack(side="left")

    hint = ttk.Label(main, foreground="#666")
    hint.pack(fill="x", pady=(4, 2))

    # --- link box
    links_box = tk.Text(main, height=4, wrap="word")
    links_box.pack(fill="x")

    def on_mode_change(*_):
        if mode.get() == "collection":
            hint.config(text="Paste your public TikTok collection's share link "
                             "(Profile > Saved > open collection > Share > Copy link). "
                             "New songs in the collection are picked up every run.")
            links_box.delete("1.0", "end")
            links_box.insert("1.0", cfg.get("collection_link", ""))
        else:
            hint.config(text="Paste one or more TikTok video links "
                             "(Share > Copy link on each video). Extra text is okay.")
            links_box.delete("1.0", "end")

    mode.trace_add("write", on_mode_change)
    on_mode_change()

    # --- output folder
    out_row = ttk.Frame(main)
    out_row.pack(fill="x", pady=(10, 0))
    ttk.Label(out_row, text="Save MP3s to:").pack(side="left")
    out_var = tk.StringVar(value=cfg.get("output_dir", str(DEFAULT_OUTPUT)))
    ttk.Entry(out_row, textvariable=out_var).pack(
        side="left", fill="x", expand=True, padx=6)

    def browse():
        chosen = filedialog.askdirectory(initialdir=out_var.get() or str(Path.home()))
        if chosen:
            out_var.set(chosen)

    ttk.Button(out_row, text="Browse...", command=browse).pack(side="left")

    # --- buttons + status
    btn_row = ttk.Frame(main)
    btn_row.pack(fill="x", pady=10)
    start_btn = ttk.Button(btn_row, text="Download MP3s")
    start_btn.pack(side="left")
    cancel_btn = ttk.Button(btn_row, text="Cancel", state="disabled")
    cancel_btn.pack(side="left", padx=6)
    open_btn = ttk.Button(btn_row, text="Open songs folder",
                          command=lambda: os.startfile(out_var.get())
                          if Path(out_var.get()).exists() else None)
    open_btn.pack(side="left")
    status = ttk.Label(btn_row, text="")
    status.pack(side="left", padx=10)

    log_box = scrolledtext.ScrolledText(main, state="disabled", wrap="word",
                                        font=("Consolas", 9))
    log_box.pack(fill="both", expand=True)

    log_q: queue.Queue[str] = queue.Queue()
    cancelled = threading.Event()
    worker: list[threading.Thread] = []

    def pump():
        try:
            while True:
                line = log_q.get_nowait()
                log_box.config(state="normal")
                log_box.insert("end", line + "\n")
                log_box.see("end")
                log_box.config(state="disabled")
        except queue.Empty:
            pass
        if worker and not worker[0].is_alive():
            worker.clear()
            start_btn.config(state="normal")
            cancel_btn.config(state="disabled")
            status.config(text="Done" if not cancelled.is_set() else "Cancelled")
        root.after(150, pump)

    def start():
        urls = extract_urls(links_box.get("1.0", "end"))
        if not urls:
            messagebox.showwarning(APP_NAME, "No TikTok links found - paste a "
                                   "link into the box first.")
            return
        if mode.get() == "collection" and len(urls) > 1:
            messagebox.showwarning(APP_NAME, "Collection mode expects a single "
                                   "collection link. Found several links - "
                                   "switch to 'Video link(s)' mode for those.")
            return
        ytdlp = find_ytdlp()
        ffmpeg_dir = find_ffmpeg()
        if not ytdlp or not ffmpeg_dir:
            messagebox.showerror(APP_NAME, "Bundled tools are missing. Please "
                                 "reinstall the app.")
            return
        out_dir = Path(out_var.get().strip() or str(DEFAULT_OUTPUT))

        cfg.update({"mode": mode.get(), "output_dir": str(out_dir)})
        if mode.get() == "collection":
            cfg["collection_link"] = urls[0]
        save_config(cfg)

        cancelled.clear()
        start_btn.config(state="disabled")
        cancel_btn.config(state="normal")
        status.config(text="Working...")
        log_box.config(state="normal")
        log_box.delete("1.0", "end")
        log_box.config(state="disabled")

        t = threading.Thread(
            target=lambda: download(urls, out_dir, ytdlp, ffmpeg_dir,
                                    log_q.put, cancelled.is_set),
            daemon=True)
        worker.append(t)
        t.start()

    start_btn.config(command=start)
    cancel_btn.config(command=cancelled.set)

    pump()
    if smoke:
        root.after(2000, root.destroy)
    root.mainloop()


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
    run_gui(smoke="--smoke" in args)
