# Security policy

## Reporting a vulnerability

Please do not open a public issue. Report privately through GitHub:
**Security > Advisories > Report a vulnerability** on https://github.com/pasichdev/sealite.

You can expect an acknowledgement within a few days. Issues in the bundled SQLCipher or OpenSSL
are fixed by a patch release once the upstream fix is available.

## Supported versions

| Version | Supported |
|---------|-----------|
| 1.x (latest minor) | yes |
| older | no |

## Verifying a release

Every GitHub release lists the SHA-256 of the native libraries and the library files in `SHA256SUMS`, and
attaches the CycloneDX SBOM. The native libraries, the library files (jar, aar, klib, pom, module) and the
SBOM carry GitHub build-provenance attestations, so a file can be checked against the workflow run that
built it:

```sh
gh attestation verify <file> --repo pasichdev/sealite
```
