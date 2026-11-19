"""Tests of scripts/ci/fetch-verified.sh: a pinned download tries its mirrors in order and is published only verified.

Each case runs the real script with bash on file:// URLs in a temporary directory, retries disabled.
"""
import hashlib
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().with_name("fetch-verified.sh")
BASH = shutil.which("bash")


def sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


@unittest.skipIf(BASH is None, "bash is required to run the download script")
class FetchVerifiedTest(unittest.TestCase):
    def setUp(self):
        self.work = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.work, True)
        self.payload = b"pinned tool archive\n"
        self.served = self.work / "served.zip"
        self.served.write_bytes(self.payload)
        self.output = self.work / "out" / "tool.zip"
        self.output.parent.mkdir()

    def url(self, path: Path) -> str:
        return path.as_uri()

    def run_script(self, *args):
        env = dict(os.environ, FETCH_VERIFIED_RETRIES="0", FETCH_VERIFIED_RETRY_DELAY="0")
        return subprocess.run([BASH, SCRIPT.as_posix(), *args], capture_output=True, text=True, env=env, check=False)

    def test_the_first_mirror_serves_the_pinned_file(self):
        result = self.run_script(sha256(self.payload), str(self.output), self.url(self.served))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.payload, self.output.read_bytes())

    def test_an_unavailable_mirror_falls_back_to_the_next_one(self):
        missing = self.work / "missing.zip"
        result = self.run_script(sha256(self.payload), str(self.output), self.url(missing), self.url(self.served))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.payload, self.output.read_bytes())
        self.assertIn("trying the next mirror", result.stderr)

    def test_a_file_that_does_not_match_the_pinned_digest_is_refused_without_trying_another_mirror(self):
        tampered = self.work / "tampered.zip"
        tampered.write_bytes(b"something else\n")
        result = self.run_script(sha256(self.payload), str(self.output), self.url(tampered), self.url(self.served))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("SHA-256", result.stderr)
        self.assertFalse(self.output.exists())
        self.assertEqual([], [p.name for p in self.output.parent.iterdir()])

    def test_every_mirror_unavailable_fails_and_publishes_nothing(self):
        result = self.run_script(sha256(self.payload), str(self.output), self.url(self.work / "a.zip"),
                                 self.url(self.work / "b.zip"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("no mirror", result.stderr)
        self.assertFalse(self.output.exists())

    def test_an_existing_output_is_not_overwritten(self):
        self.output.write_bytes(b"already there\n")
        result = self.run_script(sha256(self.payload), str(self.output), self.url(self.served))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b"already there\n", self.output.read_bytes())

    def test_a_malformed_digest_or_no_url_is_a_usage_error(self):
        self.assertEqual(2, self.run_script("not-a-digest", str(self.output), self.url(self.served)).returncode)
        self.assertEqual(2, self.run_script(sha256(self.payload), str(self.output)).returncode)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
