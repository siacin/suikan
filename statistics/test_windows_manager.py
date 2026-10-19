from pathlib import Path
import tempfile
import unittest
from windows_manager import load_token
from server import UsageApplication


class WindowsLauncherTests(unittest.TestCase):
    def test_first_start_creates_strong_private_token(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)/"private"
            token = load_token(root)
            self.assertGreaterEqual(len(token), 48)
            self.assertEqual((root/"admin-token.txt").read_text().strip(), token)

    def test_restart_preserves_token(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = load_token(root)
            self.assertEqual(load_token(root), first)

    def test_different_data_directories_get_different_tokens(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertNotEqual(load_token(Path(directory)/"a"), load_token(Path(directory)/"b"))

    def test_existing_weak_token_not_silently_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory);path = root/"admin-token.txt";path.write_text("weak")
            with self.assertRaises(ValueError): load_token(root)
            self.assertEqual(path.read_text(), "weak")

    def test_existing_database_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory);token = load_token(root)
            UsageApplication(root/"usage.sqlite3", token)
            before = (root/"usage.sqlite3").read_bytes()
            self.assertEqual(load_token(root), token)
            self.assertEqual((root/"usage.sqlite3").read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
