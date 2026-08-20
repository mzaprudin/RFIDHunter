#!/usr/bin/env python3
"""Small stdlib regression suite for :mod:`vad_reference`."""

from __future__ import annotations

import math
import random
import unittest

from vad_reference import VadConfig, detect


RATE = 16_000


def tone(duration_ms: int, amplitude: int = 4_000, frequency: float = 180.0) -> list[int]:
    count = round(RATE * duration_ms / 1_000)
    return [round(amplitude * math.sin(2 * math.pi * frequency * index / RATE)) for index in range(count)]


def noisy_silence(duration_ms: int, amplitude: int = 40, seed: int = 7) -> list[int]:
    randomizer = random.Random(seed)
    count = round(RATE * duration_ms / 1_000)
    return [randomizer.randint(-amplitude, amplitude) for _ in range(count)]


class VadReferenceTest(unittest.TestCase):
    def test_silence_is_no_speech(self) -> None:
        result = detect(noisy_silence(10_000), RATE)
        self.assertEqual(result.status, "NO_SPEECH")
        self.assertEqual(result.trimmed_pcm, [])

    def test_short_phrase_keeps_preroll_and_postroll(self) -> None:
        samples = noisy_silence(1_000) + tone(600) + noisy_silence(2_000)
        result = detect(samples, RATE)
        self.assertEqual(result.status, "SPEECH")
        self.assertIsNotNone(result.start_ms)
        self.assertIsNotNone(result.end_ms)
        self.assertLess(result.start_ms, 1_000)
        self.assertGreater(result.end_ms, 1_600)
        self.assertLess(result.end_ms, 3_000)

    def test_single_click_does_not_start_speech(self) -> None:
        samples = noisy_silence(1_000) + [20_000] * round(RATE * 0.02) + noisy_silence(8_000)
        result = detect(samples, RATE)
        self.assertEqual(result.status, "NO_SPEECH")

    def test_long_phrase_ends_after_quiet_tail(self) -> None:
        samples = noisy_silence(500) + tone(2_000) + noisy_silence(4_000)
        result = detect(samples, RATE)
        self.assertEqual(result.status, "SPEECH")
        self.assertLess(result.end_ms or 10_000, 4_000)

    def test_custom_end_silence_is_respected(self) -> None:
        samples = noisy_silence(500) + tone(600) + noisy_silence(2_000)
        result = detect(samples, RATE, VadConfig(end_silence_ms=500))
        self.assertEqual(result.status, "SPEECH")
        self.assertLess(result.end_ms or 10_000, 2_000)


if __name__ == "__main__":
    unittest.main()
