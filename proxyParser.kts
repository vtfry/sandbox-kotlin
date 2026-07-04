/**
 * @file ProxyParser
 * Downloads, cleans, and syncs a text-based proxy list from a GitHub Raw URL.
 * Uses SHA-256 caching to prevent redundant disk writes and manages file rotation.
 */

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.system.exitProcess

val DEFAULT_FILE_NAME = "downloaded-proxies.txt"

/**
 * System exit codes adhering to Unix standards.
 */
enum class ExitCode(val value: Int) {
    SUCCESS(0),
    ERROR(1),
    ERROR_USAGE(64),
    ERROR_NOHOST(68),
    ERROR_VALIDATION(78),
    ERROR_IO(74)
}

fun main() {
    println("INFO: Starting proxy parser...")

    val targetUrl = System.getenv("TARGET_URL")
    if (targetUrl == null) {
        System.err.println("ERROR: TARGET_URL is not set in .envrc")
        exitProcess(ExitCode.ERROR_USAGE.value)
    }

    val fileName = System.getenv("PROXY_FILE_NAME") ?: DEFAULT_FILE_NAME
    val outFile = File(fileName)

    // Analyze existing file
    val oldHash = if (outFile.exists()) {
        val content = outFile.readText()
        val hash = calculateHash(content)
        val linesCount = content.lines().filter { it.isNotBlank() }.count()
        println("INFO: Found existing file: ${outFile.name} " +
            "($linesCount proxies, sha256: ${hash.take(8)})")
        hash
    } else {
        println("INFO: No existing file found at ${outFile.absolutePath}. Will create a new one.")
        ""
    }

    // Download and validate new data
    println("INFO: Fetching data from URL: $targetUrl")
    val proxyList = downloadProxies(targetUrl)
    if (proxyList.isEmpty()) {
        System.err.println("ERROR: Downloaded proxy list is empty or unreachable.")
        exitProcess(ExitCode.ERROR_VALIDATION.value)
    }

    // Analyze new data
    val newContent = proxyList.joinToString("\n")
    val newHash = calculateHash(newContent)
    println("INFO: Successfully parsed ${proxyList.size} clean proxies from remote (sha256: ${newHash.take(8)})")

    if (newHash != oldHash) {
        println("INFO: Remote content changed. Initiating file update...")
        saveProxiesWithBackup(newContent, proxyList.size, outFile)
    } else {
        println("INFO: Remote content is identical to local file. Skipping update.")
    }
}

/**
 * Downloads proxy list from the given URL and cleans the data.
 * Returns the list of clean, non-empty proxy strings.
 */
fun downloadProxies(url: String): List<String> {
    val client = HttpClient.newHttpClient()
    val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .GET()
        .build()

    return try {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == 200) {
            val rawBody = response.body()
            val rawLines = rawBody.split("\n")
            val cleanLines = rawLines.map { it.trim() }.filter { it.isNotEmpty() }

            val skippedLines = rawLines.size - cleanLines.size
            if (skippedLines > 0) {
                println("INFO: Removed $skippedLines empty lines/whitespaces from raw response.")
            }
            cleanLines
        } else {
            System.err.println("ERROR: Server responded with unexpected status code: ${response.statusCode()}")
            emptyList()
        }
    } catch (e: Exception) {
        System.err.println("ERROR: Network request failed: ${e.message}")
        emptyList()
    }
}

/**
 * Backs up the existing file if it exists and writes the fresh proxy list.
 */
fun saveProxiesWithBackup(content: String, proxyCount: Int, file: File) {
    try {
        if (file.exists()) {
            val backupFile = File("${file.absolutePath}.bak")

            Files.move(
                file.toPath(),
                backupFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            println("INFO: Rotated old file to: ${backupFile.absolutePath}")
        }

        file.writeText(content)
        println("SUCCESS: [IO] Wrote $proxyCount proxies to: ${file.absolutePath}")
    } catch (e: Exception) {
        System.err.println("ERROR: Failed to write output file: ${e.message}")
        exitProcess(ExitCode.ERROR_IO.value)
    }
}

/**
 * Calculates SHA-256 hash of a given string.
 */
fun calculateHash(inp: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(inp.toByteArray())
    return hashBytes.joinToString("") { "%02x".format(it) }
}

main()
