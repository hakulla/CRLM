# Third-party notices

CRLM packages or invokes the following third-party components. The rootfs packages are built and
distributed by the Termux project; their licenses follow the upstream projects.

## Bundled in the app

| Component | Purpose | License |
| --- | --- | --- |
| Eclipse JDT Parser (javaparser-core 3.26.2) | Java source analysis | Apache-2.0 (see `deps/LICENSE-javaparser-Apache-2.0.txt`) |
| llama.cpp (`libllama.so`, `libggml*.so`, `libc++_shared.so`) | On-device model inference | MIT |
| local_llm (`native/local_llm.cpp`, JNI wrapper) | llama.cpp bridge | Distributed with this app |
| Android platform (`android.jar`, API references) | Runtime | Apache-2.0 |

Users who configure a third-party model service do so under that provider's own terms. No model
service credentials are included in this repository.

## Packages inside the bootstrap rootfs

OpenJDK is GPL-2.0 with the Classpath Exception. git, gawk, findutils, coreutils, make, patch,
tar, and gzip are GPL-2.0/GPL-3.0. openssl uses an Apache-2.0-style dual license. zlib, libpng,
expat, and similar libraries use permissive licenses. Distributing GPL components requires making
the corresponding source or a written offer available; the Termux upstream sources are the source
of these packages.

| Package | Version |
| --- | --- |
| aapt2 | 16.0.0.4-4 |
| abseil-cpp | 20260817.0 |
| alsa-lib | 1.2.16.1 |
| alsa-plugins | 1.2.12-1 |
| attr | 2.6.0 |
| bash | 5.3.20 |
| bzip2 | 1.0.8-8 |
| ca-certificates | 1:2026.10.20-1 |
| coreutils | 9.12 |
| curl | 8.23.0 |
| d8 | 37.0.0 |
| dash | 0.5.13.5 |
| dbus | 1.16.2-3 |
| dialog | 1.3-20260721-0 |
| diffutils | 3.12-2 |
| findutils | 4.11.0 |
| fmt | 1:12.2.0 |
| gawk | 5.4.1 |
| git | 2.56.0 |
| grep | 3.12-3 |
| gzip | 1.15 |
| less | 710 |
| libacl | 2.4.0 |
| libandroid-execinfo | 0.1-3 |
| libandroid-glob | 0.6-3 |
| libandroid-posix-semaphore | 0.1-4 |
| libandroid-selinux | 14.0.0.11-1 |
| libandroid-shmem | 0.7 |
| libandroid-spawn | 0.3 |
| libandroid-stub | 30-3 |
| libandroid-support | 29-1 |
| libandroid-sysv-semaphore | 0.1-1 |
| libbz2 | 1.0.8-8 |
| libc++ | 30 |
| libcap-ng | 2:0.9.6 |
| libcurl | 8.23.0 |
| libexpat | 2.10.0 |
| libflac | 1.5.0-1 |
| libgmp | 6.3.0-2 |
| libiconv | 1.19 |
| libjpeg-turbo | 3.2.1 |
| libltdl | 2.6.2 |
| liblzma | 5.8.4 |
| libmp3lame | 4.0-1 |
| libmpfr | 4.2.2 |
| libmpg123 | 1.33.7 |
| libnghttp2 | 1.71.0 |
| libnghttp3 | 1.19.0 |
| libngtcp2 | 1.26.0 |
| libogg | 1.3.6-1 |
| libopus | 1.6.1 |
| libpng | 1.6.59 |
| libprotobuf | 2:36.2-1 |
| libsmartcols | 2.42.4 |
| libsndfile | 1.2.2-3 |
| libsoxr | 0.1.3-8 |
| libssh2 | 1.11.1-2 |
| libvorbis | 1.3.7-4 |
| libwebrtc-audio-processing | 2.1 |
| libx11 | 1.8.14 |
| libxau | 1.0.12-2 |
| libxcb | 1.17.0-1 |
| libxdmcp | 1.1.5-2 |
| libzopfli | 1.0.3-5 |
| littlecms | 2.19.1 |
| make | 4.4.1-1 |
| ncurses | 6.6.20260307+really6.5.20250830 |
| openjdk-17 | 17.0.21-1 |
| openjdk-21 | 21.0.13-1 |
| openssl | 1:3.6.5 |
| patch | 2.8.1 |
| pcre2 | 10.49 |
| procps | 4.0.7-2 |
| psmisc | 23.7-1 |
| pulseaudio | 17.0-5 |
| readline | 8.3.6 |
| sed | 4.10 |
| speexdsp | 1.2.1-1 |
| tar | 1.35-3 |
| termux-am | 0.8.1 |
| termux-am-socket | 1.5.0-1 |
| termux-core | 0.4.0-1 |
| termux-exec | 1:2.5.0-1 |
| termux-tools | 1.46.0+really1.45.0-1 |
| unzip | 6.0-10 |
| util-linux | 2.42.4 |
| which | 2.25 |
| xorg-util-macros | 1.20.2 |
| xorgproto | 2026.1 |
| xz-utils | 5.8.4 |
| zip | 3.0-7 |
| zlib | 1.3.2 |
| zstd | 1.5.7-1 |
