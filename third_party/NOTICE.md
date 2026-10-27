# BUCC local campus connection notices

BUCC's existing source carries its root MIT notice. The integrated physical
campus helper is not covered solely by that license.

- UBAA classroom sign-in flow: portions of
  `shared/src/commonMain/kotlin/app/bucc/api/local/LocalSigninApi.kt` are
  derived from BUAASubnet/UBAA, pinned commit
  `155e8b56ae76348e67c70414e3a9ab7fa54f2dda` (MIT; see
  `third_party/ubaa/LICENSE`). Reused logic includes `getTodayClasses`,
  `performSignin`, `currentSession`, `login`, `resolveLoginName`, class
  response mapping, and the iClass timestamp / `stu_scan_sign.action` flow
  with one retry after an expired sign-in session. BUCC adaptations retain
  `sessionForAttendance()` and internal `LocalSigninSession` for attendance
  lookup, route requests through `localUpstreamUrl`, and select direct versus
  WebVPN endpoints with `localBusinessUsesWebVpnGateway()` and separate path
  forms. The class-list request reports login, transport, HTTP, and parse
  failures distinctly from a valid empty result, and uses the Shanghai date.
  The BUCC ViewModel keeps retryable loading state; no unrelated UBAA features
  were migrated.
- UBAA sunshine check-in (YGDK): `shared/src/commonMain/kotlin/app/bucc/api/local/LocalYgdkApi.kt`
  is derived from the same pinned UBAA source (MIT). The overview keeps UBAA's
  best-effort treatment of the optional `Clockin/getCount` call: a failed count
  no longer hides classes, items or the term summary. BUCC adaptations retain
  the WebVPN gateway preparation, mode-scoped session cache, local upstream
  routing and Asia/Shanghai clock.
- UBAA credential preference semantics: `CredentialStore` now reads and writes
  the upstream `remember_password` and `auto_login` keys (see
  `shared/src/commonMain/kotlin/app/bucc/api/storage/CredentialStore.kt`) so
  existing UBAA-style preferences trigger the same startup auto-login decision.
  BUCC still keeps the password itself in the platform secure credential store
  rather than in plain preferences.
- UBAA startup preference refresh: `AuthViewModel.initializeApp()` in the same
  pinned source reloads saved credential preferences after its connection-mode
  guard. BUCC uses that ordering to refresh the remembered-username preference
  before startup recovery, while its secure credential storage adapter remains
  responsible for password retrieval.
- bucc-physical-proxy: minimal extraction from
  https://github.com/Mythologyli/zju-connect, pinned commit
  c19eb4ed34b9cf6e8e8ea3315fe9973c8411eb63, GNU Affero General Public
  License v3 only. Full retained source and license are in
  third_party/bucc-physical-proxy. BUCC changes and reproducible build
  commands are documented in BUCC-INTEGRATION.md.
- EZ4Connect: local reference application, GNU General Public License v3.
  Its connection workflow informed earlier integration work. Its Qt GUI,
  credential storage and binaries are not linked, copied or launched by BUCC.
- Go modules retain their upstream licenses. Pinned dependencies are recorded
  in third_party/bucc-physical-proxy/go.mod and go.sum; source can be obtained
  with `go mod download` using the documented Go toolchain.

The desktop app bundle includes the retained helper source, license, and
integration notes. Public source and release distributions retain these notices
and the corresponding helper source.
