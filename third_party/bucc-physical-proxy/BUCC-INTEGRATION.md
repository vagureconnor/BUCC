# BUCC physical campus proxy

Upstream: https://github.com/Mythologyli/zju-connect
Pinned revision: c19eb4ed34b9cf6e8e8ea3315fe9973c8411eb63
Source archive: https://codeload.github.com/Mythologyli/zju-connect/tar.gz/c19eb4ed34b9cf6e8e8ea3315fe9973c8411eb63

This subtree is a minimal extraction of the credential-free physical campus
proxy from the upstream BUCC integration. It is licensed AGPL-3.0-only; see
LICENSE and retain the upstream notices. It is not relicensed under BUCC's
root MIT license.

BUCC changes:
- Extracted `physicalproxy.go`, `proxy.go`, `physical_darwin.go`, and
  `physical_other.go` into the standalone module `bucc.local/physical-proxy`.
- Added a line-delimited stdin/stdout entry point that only supports the
  `endpoint` and `disconnect` operations.

The helper binds only to `127.0.0.1`, accepts only `*.buaa.edu.cn`
destinations, requires a private campus network discovered through an active
`en*` interface and its DHCP DNS server, and fails closed when that network is
unavailable. It never changes system DNS, routes, VPN settings, or proxies.
Normal TLS verification and SNI remain in the requesting client.

Build with `script/build_campus_proxy.sh desktop` from the BUCC root. The output
is `build/campus-proxy/desktop/bucc-campus-proxy`.
