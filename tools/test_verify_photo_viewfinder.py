"""Positive/negative controls for the desktop framing measurements."""

import unittest

import numpy as np
from PIL import Image, ImageDraw

from verify_photo_viewfinder import canvas, compare


class PhotoFramingMeasurementsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rng = np.random.default_rng(31415)
        cls.image = Image.new("RGB", (960, 720), "#607080")
        draw = ImageDraw.Draw(cls.image)
        for _ in range(400):
            x, y = rng.integers(0, 900), rng.integers(0, 660)
            color = tuple(int(v) for v in rng.integers(0, 256, 3))
            draw.ellipse((x, y, x + 30, y + 30), fill=color, outline="black")

    def test_identical_whole_frames_measure_identity(self):
        result = compare(canvas(self.image), canvas(self.image))
        self.assertAlmostEqual(1, result["scale"], places=4)
        self.assertAlmostEqual(1, result["wholeFrameCorrelation"], places=4)
        self.assertLess(max(abs(v) for v in result["centerOffsetFraction"]), .001)
        self.assertGreater(min(result["inlierSpanFraction"]), .8)

    def test_real_center_crop_measures_magnification(self):
        cropped = self.image.crop((96, 72, 864, 648))
        result = compare(canvas(self.image), canvas(cropped))
        # Removing 20% in each dimension magnifies by 1 / .8, not identity.
        self.assertAlmostEqual(1.25, result["scale"], delta=.01)
        self.assertLess(result["wholeFrameCorrelation"], .95)

    def test_real_translation_is_reported_instead_of_warped_away(self):
        shifted = Image.new("RGB", self.image.size, "black")
        shifted.paste(self.image, (96, 0))
        result = compare(canvas(self.image), canvas(shifted))
        self.assertAlmostEqual(.1, result["centerOffsetFraction"][0], delta=.005)
        self.assertAlmostEqual(1, result["scale"], delta=.005)
        self.assertLess(result["wholeFrameCorrelation"], .95)


if __name__ == "__main__":
    unittest.main()
