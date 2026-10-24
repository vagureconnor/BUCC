#!/usr/bin/env python3
"""Generate synthetic avatar orientation and scaling fixtures plus Kotlin metadata."""

from __future__ import annotations

import base64
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps


ROOT = Path(__file__).resolve().parents[4]
FIXTURE_DIR = Path(__file__).resolve().parent
KOTLIN_FILE = ROOT / "harmonyBuild/deviceProbe/src/ohosArm64Main/kotlin/AvatarFixtures.kt"
QUADRANT_COLORS = (
    (238, 42, 52),
    (35, 190, 76),
    (42, 92, 232),
    (238, 196, 40),
)


def quadrant_image(width: int, height: int) -> Image.Image:
    image = Image.new("RGB", (width, height))
    draw = ImageDraw.Draw(image)
    middle_x = width // 2
    middle_y = height // 2
    draw.rectangle((0, 0, middle_x - 1, middle_y - 1), fill=QUADRANT_COLORS[0])
    draw.rectangle((middle_x, 0, width - 1, middle_y - 1), fill=QUADRANT_COLORS[1])
    draw.rectangle((0, middle_y, middle_x - 1, height - 1), fill=QUADRANT_COLORS[2])
    draw.rectangle((middle_x, middle_y, width - 1, height - 1), fill=QUADRANT_COLORS[3])
    return image


def sample_points(image: Image.Image) -> list[tuple[int, int, tuple[int, int, int]]]:
    width, height = image.size
    coordinates = (
        (width // 4, height // 4),
        ((width * 3) // 4, height // 4),
        (width // 4, (height * 3) // 4),
        ((width * 3) // 4, (height * 3) // 4),
    )
    return [(x, y, image.getpixel((x, y))) for x, y in coordinates]


def make_jpegs() -> list[dict[str, object]]:
    fixtures: list[dict[str, object]] = []
    source = quadrant_image(36, 20)
    for orientation in range(1, 9):
        path = FIXTURE_DIR / f"orientation-{orientation}.jpg"
        exif = Image.Exif()
        exif[274] = orientation
        source.save(path, format="JPEG", quality=100, subsampling=0, optimize=False, exif=exif)
        with Image.open(path) as encoded:
            display_image = ImageOps.exif_transpose(encoded).convert("RGB")
            samples = sample_points(display_image)
            fixtures.append(
                {
                    "name": path.name,
                    "bytes": path.read_bytes(),
                    "width": display_image.width,
                    "height": display_image.height,
                    "samples": samples,
                }
            )
    return fixtures


def make_scale_png() -> dict[str, object]:
    path = FIXTURE_DIR / "scale-2112x1024.png"
    source = quadrant_image(2112, 1024)
    source.save(path, format="PNG", optimize=False)
    with Image.open(path) as encoded:
        decoded = ImageOps.exif_transpose(encoded).convert("RGB")
        expected = decoded.resize((1024, 496), Image.Resampling.LANCZOS)
        return {
            "name": path.name,
            "bytes": path.read_bytes(),
            "width": expected.width,
            "height": expected.height,
            "samples": sample_points(expected),
        }


def kotlin_string(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def kotlin_fixture(fixture: dict[str, object]) -> list[str]:
    name = str(fixture["name"])
    encoded = base64.b64encode(fixture["bytes"]).decode("ascii")
    samples = fixture["samples"]
    lines = ["    AvatarFixture("]
    lines.append(f"      name = {kotlin_string(name)},")
    lines.append(f'      bytes = Base64.decode("{encoded}"),')
    lines.append(f"      width = {fixture['width']},")
    lines.append(f"      height = {fixture['height']},")
    lines.append("      samples = listOf(")
    for x, y, rgb in samples:
        lines.append(f"        AvatarSample({x}, {y}, {rgb[0]}, {rgb[1]}, {rgb[2]}),")
    lines.extend(["      )", "    ),"])
    return lines


def write_kotlin(fixtures: list[dict[str, object]]) -> None:
    lines = [
        "package app.bucc.probe",
        "",
        "import kotlin.io.encoding.Base64",
        "",
        "internal data class AvatarSample(val x: Int, val y: Int, val r: Int, val g: Int, val b: Int)",
        "",
        "internal data class AvatarFixture(",
        "  val name: String,",
        "  val bytes: ByteArray,",
        "  val width: Int,",
        "  val height: Int,",
        "  val samples: List<AvatarSample>",
        ")",
        "",
        "internal object AvatarFixtures {",
        "  const val EXPECTED_JPEG_COLOR_TOLERANCE: Int = 35",
        "  val images: List<AvatarFixture> = listOf(",
    ]
    for fixture in fixtures:
        lines.extend(kotlin_fixture(fixture))
    lines.extend(["  )", "}", ""])
    KOTLIN_FILE.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN_FILE.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    fixtures = make_jpegs()
    fixtures.append(make_scale_png())
    write_kotlin(fixtures)
    for fixture in fixtures:
        sample_count = len(fixture["samples"])
        print(f"{fixture['name']}: {fixture['width']}x{fixture['height']}, {sample_count} independent samples")


if __name__ == "__main__":
    main()
