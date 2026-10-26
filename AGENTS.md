# Repository Agent Rules

- Treat `gradle.properties` `project.version` and `project.version.code` as canonical. Run `python3 .github/scripts/version_sync.py --check` when changing release metadata.
- Do not add release credentials, SDK archives, native output, generated Harmony resources, or signing material to Git.
- CI build jobs must use read-only repository permissions. Only the draft staging job may receive `contents: write`; no automated job may publish a release or upload to Apple services.
- The five-platform gate requires every declared platform job to conclude with `success`. Do not add optional platform bypass inputs.
- Do not use self-hosted runners. Pin externally downloaded toolchains by official source URL and SHA-256 before treating a platform build as available.
- Public release requires a complete asset manifest, matching source SHA/version, verified SHA-256 hashes, the expected Android and Harmony signing fingerprints, and explicit real-acceptance proof for the same source SHA. Missing evidence blocks publication.
