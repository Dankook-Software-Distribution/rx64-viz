#!/bin/sh
# build-kernels.sh OS_DIR EXTRA_DIR OUT_DIR
# Copies each kernel tree from OS_DIR to OUT_DIR, adds the user
# programs in EXTRA_DIR/user to the copy (source and UPROGS), and
# builds kernel/kernel and fs.img. OS_DIR itself is not modified.
set -eu

os=$1
extra=$2
out=$3

mkdir -p "$out"
for src in "$os"/rx64-*; do
  name=$(basename "$src")
  dst="$out/$name"
  rm -rf "$dst"
  cp -a "$src" "$dst"
  rm -rf "$dst/.git"

  for prog in "$extra"/user/*.c; do
    base=$(basename "$prog" .c)
    cp "$prog" "$dst/user/$base.c"
    # Add $U/_<base> to UPROGS, right after its first entry.
    sed -i "0,/^UPROGS=\\\\\$/s//UPROGS=\\\\\n\t\$U\/_$base\\\\/" "$dst/Makefile"
  done

  make -C "$dst" CPUS=1 kernel/kernel fs.img
  echo "built $name"
done
