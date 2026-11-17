# The Brotli encoder with a JNI wrapper: the library behind BrotliEncoder.encode (app/src/main/java/app/lensly/app/export/BrotliEncoder.kt).
# Only :app builds it. Lumio ships the Brotli decoder alone, so Lumio never carries this library.
#
# Vendored from google/brotli v1.2.1 (MIT, see brotli/LICENSE). The GitHub tarball
# https://github.com/google/brotli/archive/refs/tags/v1.2.1.tar.gz has SHA-256 98d35d11a78dc453aebb7d10c3748f3f73ff78c8267c0dc697b8631f67bf7e8f.
# brotli/common and brotli/enc are unchanged, and brotli/include/brotli holds the public headers. The decoder and the command-line tool are not copied.

cmake_minimum_required(VERSION 3.22.1)
project(brotlienc_jni C)

file(GLOB BROTLI_SOURCES CONFIGURE_DEPENDS brotli/common/*.c brotli/enc/*.c)

add_library(brotlienc_jni SHARED brotli_jni.c ${BROTLI_SOURCES})
target_include_directories(brotlienc_jni PRIVATE brotli/include)

# -O2 in every build type: an unoptimised encoder at quality 11 would make Pair take seconds. Only the JNI entry point is exported.
# Stack canaries on every function with a buffer, and a read-only GOT after relocation (RELRO, bound at load).
target_compile_options(brotlienc_jni PRIVATE -O2 -fvisibility=hidden -ffunction-sections -fdata-sections -fstack-protector-strong)
target_link_options(brotlienc_jni PRIVATE -Wl,--gc-sections -Wl,-z,relro,-z,now)
