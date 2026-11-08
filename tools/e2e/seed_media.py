# Seeds the four E2E audio files with Chinese metadata.
#
# Used by tools/e2e/prepare-media.ps1. Reads the current working directory
# (an ASCII temp directory) so no non-ASCII paths ever cross a command line.
# mutagen is only used to build/verify test fixtures - it is NOT part of the app.

import sys
from pathlib import Path

from mutagen.flac import FLAC, Picture
from mutagen.id3 import ID3, TALB, TCON, TIT2, TPE1, TRCK, TYER, ID3NoHeaderError
from mutagen.mp4 import MP4, MP4Cover
from mutagen.oggvorbis import OggVorbis

sys.stdout.reconfigure(encoding="utf-8")


def seed_mp3(path: Path) -> None:
    try:
        tags = ID3(path)
    except ID3NoHeaderError:
        tags = ID3()
    tags.delall("TIT2")
    tags.delall("TPE1")
    tags.delall("TALB")
    tags.delall("TYER")
    tags.delall("TRCK")
    tags.delall("TCON")
    tags.delall("APIC")
    tags.delall("USLT")
    tags.add(TIT2(encoding=3, text="晴天"))
    tags.add(TPE1(encoding=3, text="周杰伦"))
    tags.add(TALB(encoding=3, text="叶惠美"))
    tags.add(TYER(encoding=3, text="2003"))
    tags.add(TRCK(encoding=3, text="3"))
    tags.add(TCON(encoding=3, text="Pop"))
    tags.save(path, v2_version=3)


def seed_flac(path: Path, cover: Path) -> None:
    audio = FLAC(path)
    audio.delete()
    audio["title"] = "夜曲"
    audio["artist"] = "周杰伦"
    audio["album"] = "十一月的萧邦"
    audio["date"] = "2005"
    audio["tracknumber"] = "1"
    picture = Picture()
    picture.type = 3
    picture.mime = "image/jpeg"
    picture.desc = ""
    picture.data = cover.read_bytes()
    audio.add_picture(picture)
    audio.save()


def seed_ogg(path: Path) -> None:
    audio = OggVorbis(path)
    audio.delete()
    audio["title"] = "稻香"
    audio["artist"] = "周杰伦"
    audio["album"] = "魔杰座"
    audio["date"] = "2008"
    audio.save()


def seed_m4a(path: Path, cover: Path) -> None:
    audio = MP4(path)
    audio.delete()
    audio["\xa9nam"] = "七里香"
    audio["\xa9ART"] = "周杰伦"
    audio["\xa9alb"] = "七里香"
    audio["\xa9day"] = "2004"
    audio["trkn"] = [(1, 0)]
    audio["covr"] = [MP4Cover(cover.read_bytes(), imageformat=MP4Cover.FORMAT_JPEG)]
    audio.save()


def verify(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix == ".mp3":
        tags = ID3(path)
        return f"{tags.getall('TIT2')[0].text[0]} / {tags.getall('TPE1')[0].text[0]}"
    if suffix == ".flac":
        audio = FLAC(path)
        return f"{audio['title'][0]} / {audio['artist'][0]}"
    if suffix == ".ogg":
        audio = OggVorbis(path)
        return f"{audio['title'][0]} / {audio['artist'][0]}"
    if suffix == ".m4a":
        audio = MP4(path)
        return f"{audio['\xa9nam'][0]} / {audio['\xa9ART'][0]}"
    return "unknown"


def main() -> None:
    root = Path.cwd()
    cover = root / "cover.jpg"
    files = {
        "sunny.mp3": None,
        "night.flac": cover,
        "rice.ogg": None,
        "love.m4a": cover,
    }
    report = []
    for name, cover_file in files.items():
        path = root / name
        if not path.exists():
            raise SystemExit(f"missing audio fixture: {path}")
        if name.endswith(".mp3"):
            seed_mp3(path)
        elif name.endswith(".flac"):
            seed_flac(path, cover_file)
        elif name.endswith(".ogg"):
            seed_ogg(path)
        elif name.endswith(".m4a"):
            seed_m4a(path, cover_file)
        summary = f"{name}: {verify(path)}"
        print(summary)
        report.append(summary)

    report.append("SEED_OK")
    (root / "seed_report.txt").write_text("\n".join(report), encoding="utf-8")


if __name__ == "__main__":
    main()
