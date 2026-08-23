"""Render the standalone reader/trigger hint animation used for design review."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "artifacts" / "rfid-reader-trigger.gif"

SCALE = 4
WIDTH, HEIGHT = 480, 320
FRAME_COUNT = 32
FRAME_DURATION_MS = 50

BACKGROUND = "#F7F4EC"
OUTLINE = "#17324D"
WAVE = "#52B9AD"
TRIGGER = "#F18A5B"


def smoothstep(value: float) -> float:
    value = max(0.0, min(1.0, value))
    return value * value * (3.0 - 2.0 * value)


def press_progress(time: float) -> float:
    """A short hold at the bottom makes the trigger action readable."""
    if time < 0.14:
        return 0.0
    if time < 0.40:
        return smoothstep((time - 0.14) / 0.26)
    if time < 0.58:
        return 1.0
    if time < 0.84:
        return 1.0 - smoothstep((time - 0.58) / 0.26)
    return 0.0


def xy(point: tuple[float, float]) -> tuple[int, int]:
    return tuple(round(value * SCALE) for value in point)


def box(values: tuple[float, float, float, float]) -> tuple[int, int, int, int]:
    return tuple(round(value * SCALE) for value in values)


def quadratic_curve(
    start: tuple[float, float],
    control: tuple[float, float],
    end: tuple[float, float],
    steps: int = 8,
) -> list[tuple[float, float]]:
    points = []
    for index in range(1, steps + 1):
        t = index / steps
        inverse = 1.0 - t
        points.append((
            inverse * inverse * start[0] + 2 * inverse * t * control[0] + t * t * end[0],
            inverse * inverse * start[1] + 2 * inverse * t * control[1] + t * t * end[1],
        ))
    return points


def blend_color(start: str, end: str, amount: float) -> str:
    amount = max(0.0, min(1.0, amount))
    start_rgb = tuple(int(start[index:index + 2], 16) for index in (1, 3, 5))
    end_rgb = tuple(int(end[index:index + 2], 16) for index in (1, 3, 5))
    mixed = tuple(round(a + (b - a) * amount) for a, b in zip(start_rgb, end_rgb))
    return "#" + "".join(f"{channel:02X}" for channel in mixed)


def draw_frame(progress: float, time: float) -> Image.Image:
    image = Image.new("RGB", (WIDTH * SCALE, HEIGHT * SCALE), BACKGROUND)
    draw = ImageDraw.Draw(image)

    # Each wave is emitted separately and keeps travelling away from the reader.
    # A short interval between emissions makes the RFID response feel energetic.
    wave_lifetime = 0.34
    for emitted_at in (0.30, 0.39, 0.48, 0.57, 0.66):
        age = time - emitted_at
        if 0.0 <= age <= wave_lifetime:
            travel = age / wave_lifetime
            radius = 18 + 166 * travel
            fade = smoothstep((travel - 0.58) / 0.42)
            draw.arc(
                box((126 - radius, 119 - radius, 126 + radius, 119 + radius)),
                120,
                240,
                fill=blend_color(WAVE, BACKGROUND, fade),
                width=4 * SCALE,
            )

    # The slanted trigger is drawn behind the grip. As it moves inward, the grip
    # masks it naturally instead of changing its shape or covering the outline.
    # The centre line is offset from the grip by exactly half the trigger width,
    # so its right edge touches the grip contour without a visible gap.
    trigger_shift_x = 38 * progress
    trigger_shift_y = -7.6 * progress
    trigger_start = (253.6 + trigger_shift_x, 165 + trigger_shift_y)
    trigger_end = (261.2 + trigger_shift_x, 203 + trigger_shift_y)
    trigger_width = 18 * SCALE
    draw.line(
        [xy(trigger_start), xy(trigger_end)],
        fill=TRIGGER,
        width=trigger_width,
    )
    trigger_radius = trigger_width // 2
    for point in (trigger_start, trigger_end):
        px, py = xy(point)
        draw.ellipse(
            (px - trigger_radius, py - trigger_radius, px + trigger_radius, py + trigger_radius),
            fill=TRIGGER,
        )

    # Side profile: a clean contour is intentionally the only detail of the reader.
    grip = [
        (258, 143),
        (322, 143),
        (342, 258),
        *quadratic_curve((342, 258), (345, 276), (327, 276)),
        (296, 276),
        *quadratic_curve((296, 276), (278, 276), (281, 258)),
    ]
    draw.polygon([xy(point) for point in grip], fill=BACKGROUND)
    draw.line([xy(point) for point in (*grip, grip[0])], fill=OUTLINE, width=8 * SCALE, joint="curve")
    draw.rounded_rectangle(
        box((128, 66, 408, 171)),
        radius=28 * SCALE,
        fill=BACKGROUND,
        outline=OUTLINE,
        width=8 * SCALE,
    )

    return image.resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS)


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    frames = [draw_frame(press_progress(index / FRAME_COUNT), index / FRAME_COUNT) for index in range(FRAME_COUNT)]
    frames[0].save(
        OUTPUT,
        save_all=True,
        append_images=frames[1:],
        duration=FRAME_DURATION_MS,
        loop=0,
        optimize=True,
    )
    print(f"Wrote {OUTPUT} ({OUTPUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
