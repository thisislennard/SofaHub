package de.sofahub.app.integration.updates

import de.sofahub.app.core.network.BlockedRequestException
import de.sofahub.app.core.network.HttpsOnlyInterceptor
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

class ManifestValidatorTest {
    private val apk = ReleaseAsset("SofaHub-v1.2.0-universal.apk", 1000, "https://github.com/o/r/releases/download/v1.2.0/SofaHub-v1.2.0-universal.apk")
    private val release = ReleaseInfo("v1.2.0", "1.2.0", "- Neu", "2026-10-31T10:00:00Z", false, false, listOf(apk))
    private val manifest = UpdateManifest(1, "de.sofahub.app", "1.2.0", 12, 28, apk.name, 1000, "a".repeat(64))

    private fun problem(m: UpdateManifest, r: ReleaseInfo = release, sdk: Int = 30) =
        (ManifestValidator.validate(m, r, "de.sofahub.app", sdk).exceptionOrNull() as? ManifestException)?.problem

    @Test fun validManifestYieldsOfferWithAssetUrlFromRelease() {
        val offer = ManifestValidator.validate(manifest, release, "de.sofahub.app", 30).getOrThrow()
        assertEquals(apk.downloadUrl, offer.apk.downloadUrl)
    }

    @Test fun rejectsEveryInconsistency() {
        assertEquals(ManifestProblem.WrongSchema, problem(manifest.copy(schemaVersion = 2)))
        assertEquals(ManifestProblem.WrongApplication, problem(manifest.copy(applicationId = "de.evil")))
        assertEquals(ManifestProblem.TagMismatch, problem(manifest, release.copy(tag = "v1.3.0")))
        assertEquals(ManifestProblem.BadAssetName, problem(manifest.copy(apkAssetName = "evil.apk")))
        assertEquals(ManifestProblem.BadHash, problem(manifest.copy(sha256 = "XYZ")))
        assertEquals(ManifestProblem.SizeMismatch, problem(manifest.copy(apkSizeBytes = 999)))
        assertEquals(ManifestProblem.AssetMissing, problem(manifest, release.copy(assets = emptyList())))
        assertEquals(ManifestProblem.MinSdkTooHigh(31), problem(manifest.copy(minSdk = 31)))
        assertEquals(ManifestProblem.BadVersion, problem(manifest.copy(versionCode = 0)))
    }

    @Test fun parseIgnoresUnknownButRejectsMissingFields() {
        assertTrue(ManifestValidator.parse("""{"schemaVersion":1,"applicationId":"a","versionName":"1.0.0","versionCode":1,"minSdk":28,"apkAssetName":"x","apkSizeBytes":1,"sha256":"s","extra":true}""") != null)
        assertEquals(null, ManifestValidator.parse("""{"schemaVersion":1}"""))
        assertEquals(null, ManifestValidator.parse("kein json"))
    }

    @Test fun releaseParsing() {
        val json = """{"tag_name":"v1.2.0","name":"x","body":"b","draft":false,"prerelease":true,"published_at":"t","assets":[{"name":"update.json","size":200,"browser_download_url":"https://github.com/u"}]}"""
        val r = GitHubReleaseClient.parseRelease(json.replace("\"browser_download_url\":\"https://github.com/u\"", "\"browser_download_url\":\"https://github.com/u\",\"url\":\"https://api.github.com/repos/o/r/releases/assets/1\""))!!
        assertEquals("https://api.github.com/repos/o/r/releases/assets/1", r.assets.single().apiUrl)
        assertTrue(r.prerelease)
        assertEquals("update.json", r.assets.single().name)
    }
}

class VerifyRulesTest {
    private val facts = ApkFacts("de.sofahub.app", 12, 28, setOf("cert"))
    private fun check(a: ApkFacts?, installedVc: Long = 11, signers: Set<String> = setOf("cert")) =
        VerifyRules.check(a, "de.sofahub.app", 12, installedVc, signers, 30)

    @Test fun acceptsOnlyMatchingNewerSameSigner() {
        assertEquals(VerifyResult.Ok, check(facts))
        assertEquals(VerifyResult.Unparsable, check(null))
        assertEquals(VerifyResult.WrongPackage, check(facts.copy(packageName = "de.evil")))
        assertEquals(VerifyResult.WrongVersion, check(facts.copy(versionCode = 13)))
        assertEquals(VerifyResult.NotNewer, check(facts, installedVc = 12))
        assertEquals(VerifyResult.MinSdkTooHigh, check(facts.copy(minSdk = 31)))
        assertEquals(VerifyResult.Unsigned, check(facts.copy(signerSha256 = emptySet())))
        assertEquals(VerifyResult.SignatureMismatch, check(facts.copy(signerSha256 = setOf("andere"))))
        assertEquals(VerifyResult.SignatureMismatch, check(facts, signers = emptySet()))
    }
}

class GitHubReleaseClientTest {
    private lateinit var server: MockWebServer
    private val plain = OkHttpClient()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private fun client() = GitHubReleaseClient("owner/repo", "SofaHub/test", plain, server.url("/"))

    @Test fun mapsResponses() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(ReleaseFetch.NoRelease, client().latest())
        server.enqueue(MockResponse.Builder().code(403).addHeader("x-ratelimit-remaining", "0").addHeader("x-ratelimit-reset", "1800000000").build())
        assertEquals(ReleaseFetch.RateLimited(1800000000), client().latest())
        server.enqueue(MockResponse.Builder().body("""{"tag_name":"v1.0.0","draft":true,"assets":[]}""").build())
        assertEquals(ReleaseFetch.NoRelease, client().latest())
        server.enqueue(MockResponse.Builder().body("Unsinn").build())
        assertEquals(ReleaseFetch.Invalid, client().latest())
        server.enqueue(MockResponse.Builder().body("""{"tag_name":"v1.0.0","draft":false,"prerelease":false,"assets":[]}""").build())
        assertTrue(client().latest() is ReleaseFetch.Found)
        val req = server.takeRequest()
        assertEquals("/repos/owner/repo/releases/latest", req.url.encodedPath)
        assertEquals("SofaHub/test", req.headers["User-Agent"])
        assertEquals(null, req.headers["Authorization"])
    }

    @Test fun manifestDownloadIsSizeLimited() = runBlocking {
        server.enqueue(MockResponse.Builder().body("x".repeat(100)).build())
        assertEquals(null, client().downloadText(AssetRequest(server.url("/u").toString(), emptyMap()), 50))
        server.enqueue(MockResponse.Builder().body("ok").build())
        assertEquals("ok", client().downloadText(AssetRequest(server.url("/u").toString(), emptyMap()), 50))
    }

    @Test fun tokenIsSentOnlyToApiAndMapsUnauthorized() = runBlocking {
        val c = GitHubReleaseClient("owner/repo", "SofaHub/test", plain, server.url("/"), token = { "github_pat_test" })
        server.enqueue(MockResponse.Builder().code(401).build())
        assertEquals(ReleaseFetch.Unauthorized, c.latest())
        assertEquals("Bearer github_pat_test", server.takeRequest().headers["Authorization"])
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        assertEquals(TokenCheck.Ok, c.checkToken(" kandidat "))
        val check = server.takeRequest()
        assertEquals("/repos/owner/repo", check.url.encodedPath)
        assertEquals("Bearer kandidat", check.headers["Authorization"])
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(TokenCheck.NoAccess, c.checkToken("x"))
        server.enqueue(MockResponse.Builder().code(401).build())
        assertEquals(TokenCheck.Invalid, c.checkToken("x"))
    }

    @Test fun privateAssetsUseApiUrlOnlyForThisRepository() {
        val base = server.url("/")
        val withToken = GitHubReleaseClient("owner/repo", "ua", plain, base, token = { "t" })
        val own = ReleaseAsset("a.apk", 1, "https://github.com/x", base.toString() + "repos/owner/repo/releases/assets/7")
        val req = withToken.assetRequest(own)
        assertEquals(own.apiUrl, req.url)
        assertEquals("Bearer t", req.headers["Authorization"])
        assertEquals("application/octet-stream", req.headers["Accept"])
        // Fremdes Repository in der Asset-Adresse: kein Token, öffentlicher Link
        val foreign = own.copy(apiUrl = base.toString() + "repos/evil/repo/releases/assets/7")
        assertEquals(null, withToken.assetRequest(foreign).headers["Authorization"])
        // Ohne Token: öffentlicher Download
        val noToken = GitHubReleaseClient("owner/repo", "ua", plain, base)
        assertEquals("https://github.com/x", noToken.assetRequest(own).url)
    }

    @Test fun httpsOnlyInterceptorBlocksHttpAndForeignHosts() {
        val i = HttpsOnlyInterceptor(GitHubReleaseClient.ALLOWED_HOSTS)
        i.check("https://objects.githubusercontent.com/x".toHttpUrl())
        listOf("http://github.com/x", "https://evil.example/x", "https://github.com.evil.example/x").forEach {
            try {
                i.check(it.toHttpUrl()); fail(it)
            } catch (_: BlockedRequestException) {
            }
        }
    }
}

class ApkDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private fun downloader() = ApkDownloader(OkHttpClient(), tmp.newFolder("updates"), "test")
    private val payload = ByteArray(200_000) { (it % 251).toByte() }

    @Test fun completeDownloadWithMatchingHash() = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(payload)).build())
        val r = downloader().download(server.url("/a.apk").toString(), expectedSize = payload.size.toLong(), expectedSha256 = sha(payload), targetName = "a.apk", onProgress = {})
        assertTrue(r is DownloadResult.Done)
        assertEquals(sha(payload), (r as DownloadResult.Done).file.sha256())
    }

    @Test fun hashMismatchLeavesNoFile() = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(payload)).build())
        val d = downloader()
        val r = d.download(server.url("/a.apk").toString(), expectedSize = payload.size.toLong(), expectedSha256 = "0".repeat(64), targetName = "a.apk", onProgress = {})
        assertEquals(DownloadResult.HashMismatch, r)
        assertFalse(tmp.root.walk().any { it.isFile })
    }

    @Test fun oversizedDownloadIsAborted() = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(payload)).build())
        val r = downloader().download(server.url("/a.apk").toString(), expectedSize = 1000, expectedSha256 = sha(payload), targetName = "a.apk", onProgress = {})
        assertEquals(DownloadResult.SizeMismatch, r)
        assertFalse(tmp.root.walk().any { it.isFile })
    }

    @Test fun cancelledDownloadLeavesNoPartialFile() = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(payload)).throttleBody(10_000, 1, java.util.concurrent.TimeUnit.SECONDS).build())
        val d = downloader()
        var job: Job? = null
        job = launch { d.download(server.url("/a.apk").toString(), expectedSize = payload.size.toLong(), expectedSha256 = sha(payload), targetName = "a.apk", onProgress = {}) }
        delay(1500)
        job.cancelAndJoin()
        assertFalse(tmp.root.walk().any { it.isFile })
    }
}
