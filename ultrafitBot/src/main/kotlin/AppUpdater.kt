import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.jar.JarFile

internal data class UpdateInfo(
    val version: String,
    val jarUrl: String,
    val checksumUrl: String
)

internal data class PreparedUpdate(
    val version: String,
    val updaterScript: Path
)

private data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String = "",
    val assets: List<GitHubAsset> = emptyList()
)

private data class GitHubAsset(
    val name: String = "",
    @SerializedName("browser_download_url") val downloadUrl: String = ""
)

internal object AppUpdater {
    private const val JAR_ASSET = "ultrafitBot.jar"
    private const val CHECKSUM_ASSET = "ultrafitBot.jar.sha256"

    private val gson = Gson()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    val currentVersion: String by lazy {
        System.getProperty("ultrafit.version")
            ?.takeIf { it.isNotBlank() }
            ?: manifestAttribute("Implementation-Version")
            ?: "dev"
    }

    private val repository: String by lazy {
        System.getProperty("ultrafit.updateRepository")
            ?.takeIf { it.isNotBlank() }
            ?: manifestAttribute("Update-Repository")
            ?: ""
    }

    fun findUpdate(): UpdateInfo? {
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            "В этой сборке не настроен адрес репозитория обновлений"
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/repos/$repository/releases/latest"))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "UltrafitBot/$currentVersion")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            error("GitHub вернул ошибку HTTP ${response.statusCode()}")
        }

        val release = gson.fromJson(response.body(), GitHubRelease::class.java)
        val latestVersion = release.tagName.removePrefix("v").trim()
        if (latestVersion.isBlank()) {
            error("В последнем релизе не указан номер версии")
        }
        if (!isNewerVersion(latestVersion, currentVersion)) {
            return null
        }

        val jar = release.assets.firstOrNull { it.name == JAR_ASSET }
            ?: error("В релизе ${release.tagName} нет файла $JAR_ASSET")
        val checksum = release.assets.firstOrNull { it.name == CHECKSUM_ASSET }
            ?: error("В релизе ${release.tagName} нет файла $CHECKSUM_ASSET")

        return UpdateInfo(
            version = latestVersion,
            jarUrl = jar.downloadUrl,
            checksumUrl = checksum.downloadUrl
        )
    }

    fun downloadAndPrepare(update: UpdateInfo): PreparedUpdate {
        val runningJar = runningJarPath()
        val updateDirectory = runningJar.parent.resolve(".update")
        Files.createDirectories(updateDirectory)

        val temporaryJar = updateDirectory.resolve("$JAR_ASSET.download")
        val readyJar = updateDirectory.resolve("$JAR_ASSET.new")
        val checksumFile = updateDirectory.resolve(CHECKSUM_ASSET)

        Files.deleteIfExists(temporaryJar)
        Files.deleteIfExists(readyJar)
        download(update.jarUrl, temporaryJar)
        download(update.checksumUrl, checksumFile)

        val expectedChecksum = Files.readString(checksumFile, StandardCharsets.UTF_8)
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            ?.lowercase()
            ?: error("Файл контрольной суммы пуст")
        require(expectedChecksum.matches(Regex("[0-9a-f]{64}"))) {
            "Файл контрольной суммы имеет неверный формат"
        }

        val actualChecksum = sha256(temporaryJar)
        require(actualChecksum == expectedChecksum) {
            "Контрольная сумма обновления не совпала. Файл не будет установлен"
        }

        Files.move(
            temporaryJar,
            readyJar,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )

        val updaterScript = updateDirectory.resolve("apply-update.bat")
        val script = """
            @echo off
            chcp 65001 > nul
            setlocal
            powershell -NoProfile -Command "Wait-Process -Id ${ProcessHandle.current().pid()} -ErrorAction SilentlyContinue"
            move /Y "%~dp0$JAR_ASSET.new" "%~dp0..\$JAR_ASSET" > nul
            if errorlevel 1 (
                echo Не удалось установить обновление Ultrafit Bot.
                pause
                exit /b 1
            )
            start "" "%~dp0..\run-ultrafit.bat"
            del "%~f0"
        """.trimIndent().replace("\n", "\r\n")

        Files.writeString(
            updaterScript,
            script,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )

        return PreparedUpdate(update.version, updaterScript)
    }

    fun launchPreparedUpdate(update: PreparedUpdate) {
        ProcessBuilder("cmd.exe", "/c", update.updaterScript.toAbsolutePath().toString())
            .directory(update.updaterScript.parent.toFile())
            .start()
    }

    private fun download(url: String, destination: Path) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(3))
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "UltrafitBot/$currentVersion")
            .GET()
            .build()

        val response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofFile(
                destination,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
        )
        if (response.statusCode() !in 200..299) {
            Files.deleteIfExists(destination)
            error("Не удалось скачать обновление: HTTP ${response.statusCode()}")
        }
    }

    private fun runningJarPath(): Path {
        val location = AppUpdater::class.java.protectionDomain.codeSource.location.toURI()
        val path = Path.of(location).toAbsolutePath().normalize()
        require(Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar", ignoreCase = true)) {
            "Обновление доступно только в установленной JAR-версии программы"
        }
        return path
    }

    private fun manifestAttribute(name: String): String? {
        return try {
            val location = AppUpdater::class.java.protectionDomain.codeSource.location.toURI()
            val file = File(location)
            if (!file.isFile) return null
            JarFile(file).use { jar ->
                jar.manifest?.mainAttributes?.getValue(name)?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }
}

internal fun isNewerVersion(candidate: String, current: String): Boolean {
    if (current == "dev") return false

    fun parts(value: String): List<Int> = value
        .removePrefix("v")
        .substringBefore('-')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }

    val candidateParts = parts(candidate)
    val currentParts = parts(current)
    val count = maxOf(candidateParts.size, currentParts.size)

    for (index in 0 until count) {
        val candidatePart = candidateParts.getOrElse(index) { 0 }
        val currentPart = currentParts.getOrElse(index) { 0 }
        if (candidatePart != currentPart) {
            return candidatePart > currentPart
        }
    }
    return false
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
