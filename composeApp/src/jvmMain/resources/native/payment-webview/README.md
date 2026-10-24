# Vendored webview core headers

This directory vendors the complete `core/include` header tree from the
`webview/webview` 0.12.0 release at commit
`3ab4b5d722438fc8a13e6ca830c5e2372d19a01d`.

Upstream source: <https://github.com/webview/webview/tree/3ab4b5d722438fc8a13e6ca830c5e2372d19a01d/core/include>
License: MIT; see `LICENSE` copied from the same commit.

The pinned commit does not contain `scripts/amalgamate/amalgamate.py`, and its
`core/include/webview.h` is a compatibility forwarding header. To keep the
vendored code exactly reproducible from this commit, the original two-header
`core/include` tree is preserved here under `include/` rather than generated
with a script from a different revision. No source or binary is downloaded at
build or runtime.

SHA-256 of the pinned upstream headers:

- `include/webview.h`: `eceef9bd47cb9e99afd9dae8b2e82995a1cd3709a2dff39955f1a5f495f2b36c`
- `include/webview/webview.h`: `b1ff6e11431d031e5f7917e5301f4bcdf3910a2763a0720abfd291994eee7f7b`
