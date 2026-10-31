"""Local packager checks. Synthetic ext4 fixtures are NOT real Android ROMs."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

REPO_ROOT = Path(__file__).resolve().parent.parent
PACKER = REPO_ROOT / "rom-tools/pack_android9_vrom.py"

def fixture(root: Path, sdk: int=28, with_su: bool=True) -> Path:
    staged = root / "fixture-tree"
    (staged / "system" / "xbin").mkdir(parents=True)
    (staged / "system" / "build.prop").write_text(
        f"ro.build.version.sdk={sdk}\n"
        "ro.build.version.release=9\n"
        "ro.product.cpu.abi=arm64-v8a\n"
    )
    (staged / "init").write_text("#!/system/bin/sh\n")
    if with_su:
        (staged / "system/xbin/su").write_text("synthetic test fixture, NOT A REAL SU BINARY\n")
    img = root / "rootfs.img"
    subprocess.run(["truncate", "-s", "16M", str(img)], check=True)
    subprocess.run(
        ["mke2fs", "-q", "-t", "ext4", "-F", "-d", str(staged), str(img)],
        check=True, capture_output=True,
    )
    return img

@unittest.skipUnless(
    all(__import__("shutil").which(c) for c in ("truncate", "mke2fs", "debugfs")),
    "e2fsprogs required",
)
class ROM9PackagerTest(unittest.TestCase):
    def run_packer(self, img: Path, output: Path, *args: str):
        return subprocess.run(
            [sys.executable, str(PACKER), "--rootfs", str(img), "--output", str(output), *args],
            text=True, capture_output=True, timeout=90,
        )

    def test_android9_package_contains_real_rootfs_and_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            img = fixture(root)
            out = root / "vine-rom-9-test.vrom"
            proc = self.run_packer(img, out, "--require-su")
            self.assertEqual(proc.returncode, 0, proc.stderr)
            with zipfile.ZipFile(out) as z:
                manifest = json.loads(z.read("manifest.json"))
                self.assertEqual(manifest["apiLevel"], 28)
                self.assertFalse(manifest["rootFunctionalTested"])
                self.assertFalse(manifest["bootFunctionalTested"])
                self.assertEqual(z.getinfo("rootfs.img").file_size, img.stat().st_size)

    def test_other_api_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            img = fixture(root, sdk=29)
            proc = self.run_packer(img, root / "invalid.vrom")
            self.assertNotEqual(proc.returncode, 0)
            self.assertIn("API 28", proc.stderr)

    def test_require_su_rejects_unrooted_image(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            img = fixture(root, with_su=False)
            proc = self.run_packer(img, root / "invalid.vrom", "--require-su")
            self.assertNotEqual(proc.returncode, 0)
            self.assertIn("No su executable", proc.stderr)

if __name__ == "__main__":
    unittest.main()
