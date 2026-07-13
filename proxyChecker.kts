/**
 * Proxy checker utility.
 *
 * This script reads a list of proxies from a local file, verifies their availability
 * concurrently against a target URL using a fixed thread pool, and outputs functional
 * nodes to a validated storage file.
 */
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

// Logging and identification
val logTag = "proxyChecker"

// Default configuration values
val defaultFileName = "downloaded-proxies.txt"
val defaultValidFileName = "valid-proxies.txt"
val defaultTargetUrl = "http://medium.com/"
val defaultThreadPoolSize = 120
val defaultConnectTimeout = 3L
val defaultRequestTimeout = 3L

// Target parameters resolved from the system environment
val fileName = System.getenv("PROXY_FILE_NAME") ?: defaultFileName
val validFileName = System.getenv("VALID_PROXY_FILE_NAME") ?: defaultValidFileName
val targetUrl = System.getenv("CHECK_TARGET_URL") ?: defaultTargetUrl

// Threading and subset limits
val threadPoolSize = System.getenv("CHECK_THREADS")?.toIntOrNull() ?: defaultThreadPoolSize
val checkLimit = System.getenv("CHECK_LIMIT")?.toIntOrNull()

// Network timeouts in seconds
val connectTimeout = System.getenv("CHECK_CONNECT_TIMEOUT")?.toLongOrNull() ?: defaultConnectTimeout
val requestTimeout = System.getenv("CHECK_REQUEST_TIMEOUT")?.toLongOrNull() ?: defaultRequestTimeout

/**
 * System exit codes adhering to `Unix` standards.
 */
enum class ExitCode(
    val value: Int,
) {
    Success(0),
    Failure(1),
    UsageError(64),
    ValidationError(78),
    IoError(74),
}

/**
 * Thread-local storage holding a single-element [Proxy] list for the active thread.
 */
val threadProxyList = ThreadLocal<List<Proxy>>()

/**
 * A routing proxy selector that dynamically yields the proxy assigned to the current executing thread.
 * This allows a single global [HttpClient] to route requests via different proxies concurrently.
 */
class ThreadLocalProxySelector : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> = threadProxyList.get() ?: listOf(Proxy.NO_PROXY)

    override fun connectFailed(
        uri: URI?,
        sa: SocketAddress?,
        ioe: IOException?,
    ) {}
}

/**
 * Single global HTTP client instance used for all checks to prevent thread and socket leakage.
 * It serves as a resource engine, routing concurrent requests via [ThreadLocalProxySelector].
 */
val baseHttpClient: HttpClient =
    HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(connectTimeout))
        .proxy(ThreadLocalProxySelector())
        .build()

/**
 * Safely reads and cleans proxy strings from the specified file.
 *
 * This function trims whitespaces from each line and filters out any empty rows.
 * If an I/O error occurs, it logs the failure and returns an empty list.
 *
 * @param file the [File] to read proxies from.
 * @return a list of cleaned, non-empty proxy strings.
 */
fun loadProxies(file: File): List<String> =
    try {
        file
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    } catch (e: Exception) {
        System.err.println("[$logTag] ERROR: Failed to read proxy file: ${e.message}")
        emptyList()
    }

/**
 * Backs up the existing proxy file and writes the newly filtered list.
 *
 * If the target file already exists, it rotates it by renaming it with a `.bak` extension.
 *
 * @param proxies the list of working proxy strings to save.
 * @param fileName the path to the destination file.
 * @throws RuntimeException via [exitProcess] if a critical I/O failure occurs.
 */
fun saveValidProxies(
    proxies: List<String>,
    fileName: String,
) {
    try {
        val file = File(fileName)
        if (file.exists()) {
            val backupFile = File("$fileName.bak")
            file.copyTo(backupFile, overwrite = true)
            file.delete()
            println("[$logTag] INFO: Rotated old valid file to: ${backupFile.name}")
        }

        file.bufferedWriter().use { writer ->
            proxies.forEach { proxy ->
                writer.write(proxy)
                writer.write("\n")
            }
        }
        println("[$logTag] SUCCESS: Wrote ${proxies.size} working proxies to: ${file.absolutePath}")
    } catch (e: Exception) {
        System.err.println(
            "[$logTag] ERROR: Critical failure during saving valid proxies: ${e.message}",
        )
        exitProcess(ExitCode.IoError.value)
    }
}

/**
 * Verifies the availability of a single proxy by making a test HTTP request.
 *
 * It parses the [proxyUri], injects the configuration into the shared [threadProxyList],
 * and attempts to reach the [targetUrl] using the global client.
 *
 * @param proxyUri the proxy connection string (e.g., "socks5://1.2.3.4:1080").
 * @param targetUrl the URL used to test the connection.
 * @return `true` if the proxy successfully returns a 200 HTTP status code, `false` otherwise.
 */
fun checkSingleProxy(
    proxyUri: String,
    targetUrl: String,
): Boolean {
    return try {
        val uri = URI.create(proxyUri)
        val host = uri.host ?: return false
        val scheme = uri.scheme?.lowercase() ?: "http"
        val port = uri.port.let { if (it == -1) 80 else it }

        val proxyType =
            when (scheme) {
                "socks4", "socks5" -> Proxy.Type.SOCKS
                else -> Proxy.Type.HTTP
            }

        val proxyConfig = Proxy(proxyType, InetSocketAddress(host, port))
        threadProxyList.set(listOf(proxyConfig))

        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(targetUrl))
                .GET()
                .timeout(Duration.ofSeconds(requestTimeout))
                .build()

        val response = baseHttpClient.send(request, HttpResponse.BodyHandlers.discarding())
        response.statusCode() == 200
    } catch (e: Exception) {
        false
    } finally {
        threadProxyList.remove()
    }
}

fun main() {
    println("[$logTag] INFO: Starting proxy checker...")
    println("[$logTag] INFO: Target URL to verify: $targetUrl")

    val inFile = File(fileName)
    if (!inFile.exists()) {
        System.err.println("[$logTag] ERROR: Target file not found: ${inFile.absolutePath}")
        System.err.println("[$logTag] ERROR: Run proxyParser first to fetch the data.")
        exitProcess(ExitCode.IoError.value)
    }

    println("[$logTag] INFO: Reading proxies from: ${inFile.name}")

    val proxies = loadProxies(inFile)
    if (proxies.isEmpty()) {
        System.err.println("[$logTag] ERROR: No valid proxy strings found in the file.")
        exitProcess(ExitCode.ValidationError.value)
    }

    val finalLimit = checkLimit ?: proxies.size
    val workingSubset = proxies.take(finalLimit)

    println("[$logTag] INFO: Total proxies loaded: ${proxies.size}. Subset size: $finalLimit")

    val validProxiesQueue = ConcurrentLinkedQueue<String>()
    println("[$logTag] INFO: Launching concurrent verification tasks (threads: $threadPoolSize)")

    val executor = Executors.newFixedThreadPool(threadPoolSize)

    for (proxyString in workingSubset) {
        executor.submit {
            val isValid = checkSingleProxy(proxyString, targetUrl)
            if (isValid) {
                println("[$logTag] VALID: $proxyString")
                validProxiesQueue.add(proxyString)
            } else {
                println("[$logTag] INVALID: $proxyString")
            }
        }
    }

    executor.shutdown()
    executor.awaitTermination(10, TimeUnit.MINUTES)

    println("[$logTag] INFO: Verification completed. Valid proxies: ${validProxiesQueue.size}")

    if (validProxiesQueue.isNotEmpty()) {
        saveValidProxies(validProxiesQueue.toList(), validFileName)
    } else {
        System.err.println("[$logTag] WARNING: No valid proxies found during this round.")
    }
}

main()
