# sandbox-kotlin

A playground repository for prototyping isolated architectural components in Kotlin. These lightweight scripts serve as blueprints for future Android application features.

## Project Structure

* `proxyParser.kts` — Downloads, cleans, and rotates a text-based proxy list from a remote GitHub source with SHA-256 caching.
* `proxyChecker.kts` — High-performance concurrent proxy validator built with an execution thread pool and `ThreadLocal` storage.
* `downloaded-proxies.txt` — Raw storage for the compiled lists downloaded by the parser script.
* `valid-proxies.txt` — Destination storage containing only verified, actively responding proxy nodes.

## Requirements

Ensure you have the following installed on your system:
* JDK 21
* Kotlin Compiler
* `make` utility

## Quick Start

1. Clone the repository and navigate to the project directory:
   ```bash
   git clone https://github.com/vtfry/sandbox-kotlin.git
   cd sandbox-kotlin
   ```

2. Run `make help` to see all available commands.

3. Run `make proxies` to fetch and validate the proxy list in one command.

## Operational Markers

If you route logs to `/dev/null`, you can still monitor validation activity without updating your production proxy list:
* `valid-proxies.txt.bak` - Automatically rotates previous working lists on data updates.
* `valid-proxies.txt.tested` - An empty heartbeat file updated after every execution run, confirming the script successfully completed even if zero valid nodes were found.
