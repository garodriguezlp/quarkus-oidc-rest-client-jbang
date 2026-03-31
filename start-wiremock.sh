#!/usr/bin/env bash
# start-wiremock.sh
#
# Starts WireMock as a CF API + UAA simulator.
#
# Pre-defined stubs (under ./wiremock-data/mappings/):
#   oauth-token.json      POST /oauth/token       — returns a fake bearer token
#   v3-organizations.json GET  /v3/organizations  — returns two fake orgs
#                          (requires Authorization: Bearer ... header,
#                           verifying that the OIDC filter is active)
#
# Once WireMock is running, open a second terminal and run the app:
#   jbang CfOrgs.java
#
# All matched/unmatched requests are printed to stdout (--verbose).
# Check the logs to confirm:
#   1. POST /oauth/token was called with grant_type=password
#   2. GET  /v3/organizations was called with Authorization: Bearer ...
#
# ─── Recording mode (optional, for a real CF environment) ──────────────────
# If you ever have a real CF instance available you can record live traffic
# instead of using the pre-built stubs:
#
#   jbang org.wiremock:wiremock-standalone:${VERSION} \
#     --port 9090 \
#     --proxy-all "https://api.cf.example.com" \
#     --record-mappings \
#     --root-dir ./wiremock-data \
#     --verbose
#
# WireMock will write the captured interactions to ./wiremock-data/mappings/
# so you can replay them offline afterwards.
# ───────────────────────────────────────────────────────────────────────────

set -euo pipefail

VERSION=3.5.3

echo "================================================================"
echo " WireMock ${VERSION} — CF API / UAA Simulator"
echo "================================================================"
echo " Token endpoint  : POST http://localhost:9090/oauth/token"
echo " Organizations   : GET  http://localhost:9090/v3/organizations"
echo " Mappings dir    : ./wiremock-data/mappings"
echo "================================================================"
echo ""

jbang org.wiremock:wiremock-standalone:${VERSION} \
  --port 9090 \
  --root-dir ./wiremock-data \
  --verbose
