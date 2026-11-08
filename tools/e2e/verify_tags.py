# Verifies tag writes performed by the app on the emulator.
#
# Reads files pulled from the device and writes a UTF-8 report next to them.
# Usage: python verify_tags.py <file> [<file> ...]

import sys
from pathlib import Path

import mutagen
from mutagen.flac import FLAC
from mutagen.id3 import ID3
from mutagen.mp4 import MP4
from mutagen.oggopus import OggOpus
from mutagen.oggvorbis import OggVorbis

sys.stdout.reconfigure(encoding="utf-8")


def report_lines(path: Path) -> list[str]:
    suffix = path.suffix.lower()
    lines = [f"FILE {path.name} suffix={suffix} size={path.stat().st_size}"]
    audio = mutagen.File(path)
    if audio is None:
        return lines + ["ERROR unrecognized file"]
    length = getattr(audio.info, "length", None)
    lines.append(f"duration={length if length is None else round(length, 3)}")

    if suffix == ".mp3":
        tags = ID3(path)

        def first(frame_id: str) -> str:
            frames = tags.getall(frame_id)
            if not frames:
                return ""
            frame = frames[0]
            return frame.text[0] if getattr(frame, "text", None) else ""

        lines.append(f"title={first('TIT2')}")
        lines.append(f"artist={first('TPE1')}")
        lines.append(f"album={first('TALB')}")
        lines.append(f"year={first('TYER') or first('TDRC')}")
        lines.append(f"track={first('TRCK')}")
        apic = tags.getall("APIC")
        lines.append(f"cover_count={len(apic)}")
        if apic:
            lines.append(f"cover_mime={apic[0].mime} cover_bytes={len(apic[0].data)}")
        uslt = tags.getall("USLT")
        lines.append(f"lyrics_count={len(uslt)}")
        if uslt:
            lines.append(f"lyrics_head={uslt[0].text[:60]!r}")
    elif suffix == ".flac":
        flac = FLAC(path)
        lines.append(f"title={flac.get('title', [''])[0]}")
        lines.append(f"artist={flac.get('artist', [''])[0]}")
        lines.append(f"album={flac.get('album', [''])[0]}")
        lines.append(f"cover_count={len(flac.pictures)}")
        if flac.pictures:
            lines.append(f"cover_mime={flac.pictures[0].mime} cover_bytes={len(flac.pictures[0].data)}")
        lines.append(f"lyrics_count={len(flac.get('lyrics', []))}")
        if flac.get("lyrics"):
            lines.append(f"lyrics_head={flac['lyrics'][0][:60]!r}")
    elif suffix in (".ogg", ".oga", ".opus"):
        ogg = OggOpus(path) if suffix == ".opus" else OggVorbis(path)
        lines.append(f"title={ogg.get('title', [''])[0]}")
        lines.append(f"artist={ogg.get('artist', [''])[0]}")
        lines.append(f"lyrics_count={len(ogg.get('lyrics', []))}")
        if ogg.get("lyrics"):
            lines.append(f"lyrics_head={ogg['lyrics'][0][:60]!r}")
        cover = ogg.get("metadata_block_picture")
        lines.append(f"cover_blocks={len(cover) if cover else 0}")
        if cover:
            lines.append(f"cover_bytes={len(cover[0])}")
    elif suffix in (".m4a", ".mp4"):
        mp4 = MP4(path)
        lines.append(f"title={mp4.get('\xa9nam', [''])[0]}")
        lines.append(f"artist={mp4.get('\xa9ART', [''])[0]}")
        lines.append(f"album={mp4.get('\xa9alb', [''])[0]}")
        cover = mp4.get("covr", [])
        lines.append(f"cover_count={len(cover)}")
        if cover:
            lines.append(f"cover_bytes={len(bytes(cover[0]))}")
        lyrics = mp4.get("\xa9lyr", [])
        lines.append(f"lyrics_count={len(lyrics)}")
        if lyrics:
            lines.append(f"lyrics_head={lyrics[0][:60]!r}")
    return lines


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: verify_tags.py <audio file> [...]")
    out: list[str] = []
    for arg in sys.argv[1:]:
        path = Path(arg)
        if not path.exists():
            out.append(f"FILE {path.name} MISSING")
            continue
        try:
            out.extend(report_lines(path))
        except Exception as exc:  # noqa: BLE001 - diagnostic helper
            out.append(f"FILE {path.name} ERROR {exc}")
        out.append("---")
    out.append("VERIFY_DONE")
    text = "\n".join(out)
    print(text)
    target = Path("verify_report.txt")
    target.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
