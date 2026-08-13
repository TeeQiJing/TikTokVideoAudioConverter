"""TikTok -> MP3 batch downloader.

Workflow:
  1. Paste TikTok share links (or whole WhatsApp messages containing them)
     into links.txt - one or many per line, junk text is fine.
  2. Double-click "Download Songs.bat".
  3. MP3s land in the Songs folder, and are auto-copied to any pendrive
     that is plugged in (into a "TikTok Songs" folder).

Already-downloaded videos are remembered in downloaded_archive.txt and
skipped, so links.txt never needs to be cleaned up.
"""

import ctypes
import re
import shutil
import string
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).parent
LINKS_FILE = HERE / "links.txt"
SONGS_DIR = HERE / "Songs"
ARCHIVE_FILE = HERE / "downloaded_archive.txt"
PENDRIVE_FOLDER_NAME = "TikTok Songs"

URL_RE = re.compile(r"https?://(?:www\.|vm\.|vt\.|m\.)?tiktok\.com/\S+")


def find_ffmpeg() -> str | None:
    found = shutil.which("ffmpeg")
    if found:
        return str(Path(found).parent)
    packages = Path.home() / "AppData/Local/Microsoft/WinGet/Packages"
    if packages.exists():
        for pkg in packages.glob("Gyan.FFmpeg*"):
            for exe in pkg.rglob("ffmpeg.exe"):
                return str(exe.parent)
    return None


def read_links() -> list[str]:
    if not LINKS_FILE.exists():
        LINKS_FILE.write_text(
            "Paste your TikTok links below this line (one or more per line):\n",
            encoding="utf-8",
        )
        return []
    text = LINKS_FILE.read_text(encoding="utf-8", errors="ignore")
    seen: dict[str, None] = {}
    for url in URL_RE.findall(text):
        seen.setdefault(url.rstrip(".,;)!\"'"), None)
    return list(seen)


def download(urls: list[str], ffmpeg_dir: str) -> None:
    SONGS_DIR.mkdir(exist_ok=True)
    # Plain browser user-agent sidesteps TikTok's Aug 2026 bot detection
    # that breaks yt-dlp's default UA (yt-dlp/yt-dlp#17403).
    browser_ua = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")
    cmd = [
        sys.executable, "-m", "yt_dlp",
        "--user-agent", browser_ua,
        "--ffmpeg-location", ffmpeg_dir,
        "-x", "--audio-format", "mp3", "--audio-quality", "0",
        # TikTok's h265 ("bytevc1") streams often arrive with no audio track
        # despite advertising AAC, so prefer audio-only, then h264, then best.
        "-f", "ba/b[vcodec^=h264]/b",
        "--embed-metadata",
        "--download-archive", str(ARCHIVE_FILE),
        "--no-overwrites",
        "--ignore-errors",
        "--windows-filenames",
        "-o", str(SONGS_DIR / "%(title).80s [%(id)s].%(ext)s"),
        *urls,
    ]
    # TikTok extraction is flaky; the archive skips finished songs, so a
    # retry pass only re-attempts the ones that failed.
    for attempt in range(3):
        if subprocess.run(cmd).returncode == 0:
            break
        if attempt < 2:
            print("\nSome downloads failed - retrying those...\n")


def removable_drives() -> list[Path]:
    DRIVE_REMOVABLE = 2
    drives = []
    for letter in string.ascii_uppercase:
        root = f"{letter}:\\"
        if ctypes.windll.kernel32.GetDriveTypeW(root) == DRIVE_REMOVABLE:
            drives.append(Path(root))
    return drives


def copy_to_pendrives() -> None:
    songs = sorted(SONGS_DIR.glob("*.mp3"))
    if not songs:
        return
    for drive in removable_drives():
        dest = drive / PENDRIVE_FOLDER_NAME
        try:
            dest.mkdir(exist_ok=True)
            copied = 0
            for song in songs:
                target = dest / song.name
                if not target.exists():
                    shutil.copy2(song, target)
                    copied += 1
            print(f"\nPendrive {drive}: copied {copied} new song(s) "
                  f"into '{PENDRIVE_FOLDER_NAME}' ({len(songs)} total in library).")
        except OSError as e:
            print(f"\nCould not copy to {drive}: {e}")


def main() -> None:
    ffmpeg_dir = find_ffmpeg()
    if not ffmpeg_dir:
        print("ERROR: ffmpeg not found. Install it with:")
        print("  winget install Gyan.FFmpeg.Essentials")
        return

    urls = read_links()
    if not urls:
        print(f"No TikTok links found in {LINKS_FILE.name}.")
        print("Paste links into that file, save it, then run this again.")
        return

    print(f"Found {len(urls)} TikTok link(s). Downloading audio as MP3...\n")
    download(urls, ffmpeg_dir)
    copy_to_pendrives()
    print(f"\nDone! Songs are in: {SONGS_DIR}")


if __name__ == "__main__":
    main()
    input("\nPress Enter to close...")
