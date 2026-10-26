# Repository Agent Rules

- Treat `gradle.properties` `project.version` and `project.version.code` as canonical. Run `python3 .github/scripts/version_sync.py --check` when changing release metadata.
- Do not add release credentials, SDK archives, native output, generated Harmony resources, or signing material to Git.
- GitHub CI runs the `Source contract` Python checks only; it must not package platforms or install native SDKs/JDKs. The five-platform result is reported from the developer's existing local toolchains as the `Local five platform gate` status for the exact commit SHA. Required branch contexts are `Source contract` and `Local five platform gate`.
- `release.yml` only verifies that the current SHA already has a successful `Local five platform gate` status. It must not build, sign, create, upload, or publish releases, and must not offer platform bypass inputs.
- Do not register self-hosted runners or download additional Harmony tooling. Do not add release credentials, SDK archives, native output, generated Harmony resources, or signing material to Git.
- Public release requires a complete asset manifest, matching source SHA/version, verified SHA-256 hashes, the expected Android and Harmony signing fingerprints, and explicit real-acceptance proof for the same source SHA. Missing evidence blocks publication.

- Keep business rules, DTOs, state and primary pages shared across iOS, macOS, Android, Windows and HarmonyOS. iOS is the behavioral baseline; native adapters may implement system differences. Calendar import is limited to iOS/macOS. Do not reintroduce library camera/QR reservation.
- Run `script/run_local_tests.py` from a clean commit before local packaging. Its Gradle step must include read-only `spotlessCheck`; never commit formatting automatically from a cloud workflow. Do not hand-author successful test evidence or waive missing platforms.
- Android upgrade identity is the approved certificate fingerprint, not a filename. Reject a different existing key too. Never create replacement signing keys. Deliver iOS unsigned; label macOS ad-hoc and Windows unsigned accurately.
