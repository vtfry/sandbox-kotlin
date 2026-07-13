/**
 * Proxy parser utility.
 *
 * Downloads, cleans, and syncs a text-based proxy list from a GitHub raw URL.
 * Uses SHA-256 caching to prevent redundant disk writes and manages file rotation.
 */
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import kotlin.system.exitProcess

// Logging and identification
val logTag = "proxyParser"

// Default configuration values
val defaultFileName = "downloaded-proxies.txt"

val targetUrl =
    System.getenv("TARGET_URL")
        ?: run {
            System.err.println("[$logTag] ERROR: TARGET_URL is not set in .envrc")
            exitProcess(ExitCode.UsageError.value)
        }

val fileName = System.getenv("PROXY_FILE_NAME") ?: defaultFileName

/**
 * System exit codes adhering to `Unix` standards.
 */
enum class ExitCode(
    val value: Int,
) {
    Success(0),
    Failure(1),
    UsageError(64),
    NoHostError(68),
    ValidationError(78),
    IoError(74),
}

/**
 * Calculates SHA-256 hash of a given string.
 *
 * @param input the [String] to be hashed.
 * @return the hex-encoded string of the calculated digest.
 */
fun calculateHash(input: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(input.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

/**
 * Backs up the existing file if it exists and writes the fresh proxy list.
 *
 * @param content the clean proxy list string to write.
 * @param proxyCount the total number of successfully parsed proxies.
 * @param file the destination [File] object.
 * @throws RuntimeException via [exitProcess] if a critical I/O failure occurs.
 */
fun saveProxiesWithBackup(
    content: String,
    proxyCount: Int,
    file: File,
) {
    try {
        if (file.exists()) {
            val backupFile = File("${file.absolutePath}.bak")
            file.copyTo(backupFile, overwrite = true)
            file.delete()
            println("[$logTag] INFO: Rotated to: ${backupFile.absolutePath}")
        }

        file.writeText(content)
        println("[$logTag] SUCCESS: Saved $proxyCount proxies to: ${file.absolutePath}")
    } catch (e: Exception) {
        System.err.println("[$logTag] ERROR: Failed to write output file: ${e.message}")
        exitProcess(ExitCode.IoError.value)
    }
}

/**
 * Downloads proxy list from the given URL and cleans the data.
 *
 * @param url the URL to download the proxy list from.
 * @return the list of clean, non-empty proxy strings.
 */
fun downloadProxies(url: String): List<String> {
    val client = HttpClient.newHttpClient()
    val request =
        HttpRequest
            .newBuilder()
            .uri(URI.create(url))
            .GET()
            .build()

    return try {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == 200) {
            val rawBody = response.body()
            val rawLines = rawBody.lines()
            val cleanLines =
                rawLines
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

            val skippedLines = rawLines.size - cleanLines.size
            if (skippedLines > 0) {
                println("[$logTag] INFO: Filtered $skippedLines empty/invalid rows.")
            }
            cleanLines
        } else {
            System.err.println(
                "[$logTag] ERROR: Unexpected server response code: ${response.statusCode()}",
            )
            emptyList()
        }
    } catch (e: Exception) {
        System.err.println("[$logTag] ERROR: Network request failed: ${e.message}")
        emptyList()
    }
}

fun main() {
    println("[$logTag] INFO: Starting proxy parser...")

    val outFile = File(fileName)

    // Analyze existing file
    val oldHash =
        if (outFile.exists()) {
            val content = outFile.readText()
            val hash = calculateHash(content)
            val linesCount = content.lines().filter { it.isNotBlank() }.count()
            println(
                "[$logTag] INFO: Local: ${outFile.name} ($linesCount proxies, sha256: ${hash.take(8)})",
            )
            hash
        } else {
            println("[$logTag] INFO: Local cache not found: ${outFile.name}.")
            ""
        }

    // Download and validate new data
    println("[$logTag] INFO: Fetching: $targetUrl")
    val proxyList = downloadProxies(targetUrl)
    if (proxyList.isEmpty()) {
        System.err.println("[$logTag] ERROR: Downloaded proxy list is empty or unreachable.")
        exitProcess(ExitCode.ValidationError.value)
    }

    // Analyze new data
    val newContent = proxyList.joinToString("\n")
    val newHash = calculateHash(newContent)
    println("[$logTag] INFO: Remote: ${proxyList.size} proxies (sha256: ${newHash.take(8)})")

    if (newHash != oldHash) {
        saveProxiesWithBackup(newContent, proxyList.size, outFile)
    } else {
        println("[$logTag] INFO: Remote content is identical to local file. Skipping update.")
    }
}

main()
