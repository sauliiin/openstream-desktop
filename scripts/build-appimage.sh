#!/usr/bin/env bash
# Builds release/OmniStream-<version>-x86_64.AppImage.
#
#   1. Gradle/jpackage produce a self-contained app image (app + trimmed JRE).
#   2. It is laid out as an AppDir (AppRun, .desktop, icon, AppStream data).
#   3. appimagetool squashes it into a single executable file.
#
# libmpv is used from the system (`mpv`/`libmpv2` package): bundling it would
# tie the AppImage to the glibc of the build machine. The app says so in its
# own words if it is missing.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="$(sed -n 's/^val appVersion = "\(.*\)"/\1/p' desktop/build.gradle.kts)"
ARCH="${ARCH:-x86_64}"
OUT_DIR="$ROOT/release"
TOOLS="$ROOT/.tools"
APPDIR="$ROOT/desktop/build/appimage/OmniStream.AppDir"
DIST="$ROOT/desktop/build/compose/binaries/main-release/app/OmniStream"

echo "==> OmniStream $VERSION ($ARCH)"
./gradlew --console=plain :desktop:createReleaseDistributable

echo "==> AppDir"
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/lib" \
         "$APPDIR/usr/share/applications" \
         "$APPDIR/usr/share/icons/hicolor/512x512/apps" \
         "$APPDIR/usr/share/metainfo"
cp -a "$DIST" "$APPDIR/usr/lib/omnistream"

cp desktop/packaging/icon.png "$APPDIR/omnistream.png"
cp desktop/packaging/icon.png "$APPDIR/usr/share/icons/hicolor/512x512/apps/omnistream.png"
ln -sf omnistream.png "$APPDIR/.DirIcon"

cat > "$APPDIR/omnistream.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=OmniStream
GenericName=Media Center
Comment=Your lists, a cinematic home and a native player
Exec=omnistream %U
Icon=omnistream
Terminal=false
Categories=AudioVideo;Video;Player;
StartupWMClass=omnistream
X-AppImage-Version=$VERSION
EOF
cp "$APPDIR/omnistream.desktop" "$APPDIR/usr/share/applications/omnistream.desktop"

cat > "$APPDIR/usr/share/metainfo/omnistream.appdata.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<component type="desktop-application">
  <id>omnistream</id>
  <metadata_license>MIT</metadata_license>
  <project_license>MIT</project_license>
  <name>OmniStream</name>
  <summary>Your lists, a cinematic home and a native player</summary>
  <description>
    <p>OmniStream for the desktop: the Android TV app's interface and features
    (MDBList, Trakt and Simkl libraries, Stremio-protocol addons, autotrailer,
    skip intro) with a native mpv-based player.</p>
  </description>
  <launchable type="desktop-id">omnistream.desktop</launchable>
  <url type="homepage">https://github.com/sauliiin/openstream-desktop</url>
  <releases>
    <release version="$VERSION"/>
  </releases>
  <content_rating type="oars-1.1"/>
</component>
EOF

# AppRun starts the bundled JVM itself instead of going through jpackage's
# native launcher. That launcher reads its .cfg in a forked helper process,
# and with recent libstdc++ the helper aborts on exit ("pure virtual method
# called"): a core dump on every launch, and inside an AppImage the abort can
# race the FUSE mount. The .cfg it would have read becomes the command line
# here instead, so the app still starts exactly as configured in Gradle.
APP_LIB="$APPDIR/usr/lib/omnistream/lib"
CFG="$APP_LIB/app/OmniStream.cfg"
RUNTIME="$APP_LIB/runtime"

# The jlink'd runtime has no bin/ (Compose strips native commands); the java
# launcher of the very JDK that built the runtime is added back.
JAVA_SRC=""
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && JAVA_SRC="$(readlink -f "$JAVA_HOME/bin/java")"
[ -n "$JAVA_SRC" ] || JAVA_SRC="$(readlink -f "$(command -v java)")"
WANT="$(sed -n 's/^JAVA_VERSION="\(.*\)"/\1/p' "$RUNTIME/release")"
HAVE="$("$JAVA_SRC" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.version = //p')"
if [ "$WANT" != "$HAVE" ]; then
  echo "java at $JAVA_SRC is $HAVE but the bundled runtime is $WANT; point JAVA_HOME at the build JDK" >&2
  exit 1
fi
mkdir -p "$RUNTIME/bin"
cp "$JAVA_SRC" "$RUNTIME/bin/java"

MAIN_CLASS="$(sed -n 's/^app.mainclass=//p' "$CFG")"
# shellcheck disable=SC2016 # $JARS is expanded by AppRun at launch, not here
CLASSPATH="$(sed -n 's|^app.classpath=\$APPDIR/|$JARS/|p' "$CFG" | paste -sd: -)"
# shellcheck disable=SC2016
JAVA_OPTS="$(sed -n 's/^java-options=//p' "$CFG" | sed 's|\$APPDIR|$JARS|g; s/.*/"&"/' | paste -sd' ' -)"

{
  echo '#!/bin/sh'
  echo 'HERE="$(dirname "$(readlink -f "$0")")"'
  echo 'JARS="$HERE/usr/lib/omnistream/lib/app"'
  echo "exec \"\$HERE/usr/lib/omnistream/lib/runtime/bin/java\" $JAVA_OPTS \\"
  echo "  -cp \"$CLASSPATH\" $MAIN_CLASS \"\$@\""
} > "$APPDIR/AppRun"
chmod +x "$APPDIR/AppRun"

echo "==> AppImage"
mkdir -p "$OUT_DIR"
TARGET="$OUT_DIR/OmniStream-$VERSION-$ARCH.AppImage"
rm -f "$TARGET"

# Tooling, in order of preference — nothing is downloaded unless asked:
#   1. appimagetool on PATH;
#   2. the runtime + mksquashfs electron-builder keeps in its cache (this repo
#      used electron-builder before, so a machine that built it has them);
#   3. with --download-tools, appimagetool from its GitHub releases into .tools/.
EB_CACHE="${ELECTRON_BUILDER_CACHE:-$HOME/.cache/electron-builder}"
EB_ARCH="$([ "$ARCH" = "x86_64" ] && echo x64 || echo "$ARCH")"
EB_DIR="$(find "$EB_CACHE" -maxdepth 3 -type f -name "runtime-$EB_ARCH" -printf '%h\n' 2>/dev/null | sort | tail -1 || true)"

if command -v appimagetool >/dev/null 2>&1; then
  ARCH="$ARCH" appimagetool --no-appstream --comp zstd "$APPDIR" "$TARGET"
elif [ -n "$EB_DIR" ] && [ -x "$EB_DIR/linux-$EB_ARCH/mksquashfs" ]; then
  SQUASH="$ROOT/desktop/build/appimage/OmniStream.squashfs"
  rm -f "$SQUASH"
  # gzip in 128K blocks, not xz in 1M: the JVM reads its module image at
  # random offsets, and every read through FUSE decompresses a whole block —
  # with 1M xz blocks startup took over ten seconds.
  "$EB_DIR/linux-$EB_ARCH/mksquashfs" "$APPDIR" "$SQUASH" -root-owned -noappend -comp gzip -b 128K -no-progress >/dev/null
  cat "$EB_DIR/runtime-$EB_ARCH" "$SQUASH" > "$TARGET"
  chmod +x "$TARGET"
  rm -f "$SQUASH"
elif [ "${1:-}" = "--download-tools" ]; then
  mkdir -p "$TOOLS"
  TOOL="$TOOLS/appimagetool-$ARCH.AppImage"
  if [ ! -x "$TOOL" ]; then
    curl -fL --retry 3 -o "$TOOL" \
      "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$ARCH.AppImage"
    chmod +x "$TOOL"
  fi
  # Extract-and-run: works where FUSE is unavailable (containers, CI runners).
  APPIMAGE_EXTRACT_AND_RUN=1 ARCH="$ARCH" "$TOOL" --no-appstream --comp zstd "$APPDIR" "$TARGET"
else
  echo "No AppImage tooling found. Install appimagetool, or rerun with --download-tools." >&2
  echo "The AppDir is ready at $APPDIR" >&2
  exit 1
fi

echo "==> $TARGET ($(du -h "$TARGET" | cut -f1))"
