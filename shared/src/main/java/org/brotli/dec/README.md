# Brotli Java decoder (vendored)

- Source: https://github.com/google/brotli, tag `v1.2.1`, commit `db24a98aed38a16de78cc05ca15cdd8dc2a0d92d`, path `java/org/brotli/dec/`.
- Licence: MIT. The upstream `LICENSE` is kept beside these files, and the notice is in `licenses/THIRD-PARTY-NOTICES.txt`.
- Vendored: the 13 main sources of package `org.brotli.dec`, byte for byte, plus the upstream `LICENSE`.
- Not vendored: the tests, the Bazel and Maven build files, `proguard.pgcfg`, the Kotlin port under `java/org/brotli/dec/kt`, and the JNI wrapper.
- Local changes: none. Lumio's `BrotliDecoder` is the only caller.
- Verification: the SHA-256 of each file matches the same path in a fresh clone checked out at the commit above.

To update, copy the 13 files and `LICENSE` from a newer tag, record the new tag and commit here, and rerun the hash check.
