"""Writes changes.json: the build's number and its newest commits, the app's "What's new".

Usage: changes.py OUT [APK]  (APK adds its size, for the copy published next to it)
"""
import json
import os
import subprocess
import sys

out = sys.argv[1]
build = int(os.environ.get("GITHUB_RUN_NUMBER", "1"))
log = subprocess.run(
    ["git", "log", "--no-merges", "-n", "120", "--format=%H%x1f%cI%x1f%s"],
    check=True, capture_output=True, text=True,
).stdout
changes = []
for line in log.splitlines():
    sha, date, subject = line.split("\x1f", 2)
    changes.append({"sha": sha, "date": date, "subject": subject})
info = {"build": build, "version": f"0.2.{build}", "changes": changes}
if len(sys.argv) > 2:
    info["apkBytes"] = os.path.getsize(sys.argv[2])
os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
with open(out, "w", encoding="utf-8") as f:
    json.dump(info, f, ensure_ascii=False)
