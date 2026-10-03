"""Extract only selected MIT-licensed stroke-rounded icons from the official npm tarball.

Usage: python tools/extract_hugeicons.py path/to/core-free-icons.tgz
The package version and icon choices are intentionally fixed for reproducibility.
"""

import json
import re
import subprocess
import sys
import tarfile
from pathlib import Path
from xml.etree import ElementTree as ET

PACKAGE_VERSION = "4.3.5"
ICONS = {
    "arrow-left": "ArrowLeft02Icon", "arrow-right": "ArrowRight02Icon", "arrow-down": "ArrowDown01Icon",
    "check": "CheckIcon", "close": "Cancel01Icon", "copy-text": "Copy01Icon",
    "globe": "GlobeIcon", "keyboard": "KeyboardIcon",
    "layout-dashboard": "DashboardSquare01Icon", "notification": "Notification03Icon",
    "package": "PackageIcon", "palette": "PaletteIcon", "pen-line": "PenLineIcon",
    "pin": "PinIcon", "scan-text": "ScanTextIcon", "settings": "Settings02Icon",
    "sliders-horizontal": "SlidersHorizontalIcon", "swap": "ArrowLeftRightIcon",
    "text-align-start": "TextAlignStartIcon", "trash": "Delete02Icon",
    "volume": "VolumeIcon", "zap": "FlashIcon", "book-open": "BookOpen01Icon",
    "star": "StarIcon", "collection": "Folder01Icon", "languages": "LanguagesIcon",
    "link-2": "Link02Icon", "unlink": "Unlink02Icon",
}


def main(archive_path: str) -> None:
    target = Path(__file__).resolve().parents[1] / "ui-swing/src/main/resources/icons/hugeicons"
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive_path, "r:gz") as archive:
        metadata = json.load(archive.extractfile("package/package.json"))
        if metadata["version"] != PACKAGE_VERSION or metadata["name"] != "@hugeicons/core-free-icons":
            raise ValueError("Unexpected Hugeicons package/version")
        for role, name in ICONS.items():
            source = archive.extractfile(f"package/dist/esm/{name}.js").read().decode("utf-8")
            array = re.search(r"= (\[[\s\S]*?\]);\s*export", source)
            if not array:
                raise ValueError(f"Could not parse {name}")
            result = subprocess.run(
                ["node", "-e", "process.stdout.write(JSON.stringify(Function('return (' + process.argv[1] + ')')()))", array.group(1)],
                capture_output=True, text=True, check=True,
            )
            elements = json.loads(result.stdout)
            root = ET.Element("svg", {"xmlns": "http://www.w3.org/2000/svg", "width": "24", "height": "24", "viewBox": "0 0 24 24", "fill": "none"})
            for tag, attrs in elements:
                converted = {re.sub(r"[A-Z]", lambda m: "-" + m.group().lower(), key): str(value) for key, value in attrs.items() if key != "key"}
                ET.SubElement(root, tag, converted)
            ET.indent(root)
            ET.ElementTree(root).write(target / f"{role}.svg", encoding="utf-8", xml_declaration=True)
    print(f"Wrote {len(ICONS)} icons to {target}")


if __name__ == "__main__":
    main(sys.argv[1])
