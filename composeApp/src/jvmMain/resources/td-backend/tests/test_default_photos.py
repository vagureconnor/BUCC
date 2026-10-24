import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch
from auto_td.scheduler import _random_checkin_photo

class DefaultPhotoTest(unittest.TestCase):
    def test_uses_only_original_three_and_selects_anew_for_every_upload(self):
        storage = MagicMock()
        selected = []
        def choose(images):
            self.assertEqual([p.name for p in images], ["image1.jpg", "image2.jpg", "image3.jpg"])
            selected.append(images[len(selected) % 3])
            return selected[-1]
        with patch("auto_td.scheduler.random.choice", side_effect=choose):
            result = [_random_checkin_photo(storage) for _ in range(6)]
        self.assertEqual(len(selected), 6)
        self.assertEqual(result, [p.read_bytes() for p in selected])
        self.assertGreater(len(set(result)), 1)
        storage.list_images.assert_not_called()
