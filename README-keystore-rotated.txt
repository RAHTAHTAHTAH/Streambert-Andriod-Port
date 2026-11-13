ROTATED: release keystore no longer committed.

- New keystore: streambert-release.keystore (PKCS12, RSA 4096, 10000 days)
- Store the base64 of it (streambert-release.keystore.b64) ONLY in GitHub Actions
  Secrets as RELEASE_KEYSTORE_BASE64, with RELEASE_KEYSTORE_PASSWORD and
  RELEASE_KEYSTORE_ALIAS.
- CI decodes it at build time: base64 -d <<< "$RELEASE_KEYSTORE_BASE64" > keystore
- Local builds that need signing should use the same mechanism or supply their own.
