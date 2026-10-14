import os
import sys
import zipfile

# Append APK payloads into the unsigned APK before zipalign/sign.
#   usage: pack_extra.py <apk> <classes.dex> <resources-dir|-> [<extra-file:arcname> ...]
# Adds classes.dex at the archive root and every file under <resources-dir>
# (typically app/src/main/resources, i.e. META-INF/xposed/*) at its relative path.
apk = sys.argv[1]
dex = sys.argv[2]
res_dir = sys.argv[3] if len(sys.argv) > 3 else "-"

added = []
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
    added.append("classes.dex")
    if res_dir not in ("", "-") and os.path.isdir(res_dir):
        for root, _dirs, files in os.walk(res_dir):
            for name in files:
                full = os.path.join(root, name)
                arc = os.path.relpath(full, res_dir).replace(os.sep, "/")
                z.write(full, arc)
                added.append(arc)

print("packed:", ", ".join(added))
