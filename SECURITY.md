# Security policy

The maintained development version is LifeCore 2.1.x for Minecraft 26.3/Java25.
Report vulnerabilities through this repository's private GitHub security-advisory
flow. Do not include API keys, database passwords or player data in public issues.

Marketplace content is declarative data, validated and digest-checked before
installation. Updates require a compatible stable release, matching manifest,
SHA256 and plugin identity; they are staged for restart, never hot-loaded.
Operators control catalogue/release URLs and the explicit HTTPS host allowlist.
