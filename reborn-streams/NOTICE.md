# Stream-only Reborn port

This module vendors the provider/extractor layer from the Streamflix Reborn2
project requested by the user:

- Upstream: https://git.gay/streamflix-reborn2/streamflix
- Accessible mirror used for the port: https://github.com/pwpp08/Streamable
- Pinned commit: `7ae9a33495c70fda56f77a7f7f3123c49c0057c2` (2026-10-26)
- License: Apache-2.0 (see `LICENSE`).

The original `git.gay` HEAD could not be reached from the build sandbox, so the
mirror commit above is the provenance record for this port. The module is used
only through `StreamOnlyResolver`; catalog/home/meta browsing methods are never
called by the app integration.
