package dev.jev.wechatmood.updates

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ReleaseUpdatesTest {
    private fun release(tag: String = "v1.2.0", extra: String = "") =
        """{"tag_name":"$tag","draft":false,"prerelease":false,"html_url":"https://untrusted.example/download","assets":[{"name":"yanwai.apk","state":"uploaded","size":123}]$extra}"""

    @Test fun `versions compare numeric parts not text or decimals`() {
        assertTrue(StableVersion.parse("v1.10.0")!! > StableVersion.parse("1.9.9")!!)
        assertTrue(StableVersion.parse("2.0.0")!! > StableVersion.parse("1.99.99")!!)
        assertEquals(StableVersion.parse("V1.2.3"), StableVersion.parse("1.2.3"))
        assertNull(StableVersion.parse("1.2.3-beta.1"))
        assertNull(StableVersion.parse("latest"))
        assertNull(StableVersion.parse("1.2.9999999999999999999"))
    }

    @Test fun `only a newer stable release with an uploaded apk prompts`() {
        val parsed = ReleaseInfo.parse(release())!!
        assertTrue(parsed.isUpdateFor("1.1.1"))
        assertFalse(parsed.isUpdateFor("1.2.0"))
        assertFalse(parsed.isUpdateFor("1.3.0"))
        assertNull(ReleaseInfo.parse(release().replace("\"draft\":false", "\"draft\":true")))
        assertNull(ReleaseInfo.parse(release().replace("\"prerelease\":false", "\"prerelease\":true")))
        assertFalse(ReleaseInfo.parse(release().replace("yanwai.apk", "source.zip"))!!.isUpdateFor("1.1.1"))
        assertFalse(ReleaseInfo.parse(release().replace("uploaded", "new"))!!.isUpdateFor("1.1.1"))
        assertFalse(ReleaseInfo.parse(release().replace("123", "0"))!!.isUpdateFor("1.1.1"))
    }

    @Test fun `download destination always belongs to this repository`() {
        assertEquals("https://github.com/YIRC99/yanwai/releases/tag/v1.2.0", ReleaseInfo.parse(release())!!.pageUrl)
        assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.parse(release("../../evil")) }
        assertThrows(Exception::class.java) { ReleaseInfo.parse("{}") }
    }

    @Test fun `automatic checks throttle daily and failures back off while manual checks can retry`() {
        val last = 1_000_000L
        assertTrue(UpdateSchedule.shouldCheck(last, 0, false, false, 0))
        assertFalse(UpdateSchedule.shouldCheck(last + 60_000, last, false, false, 0))
        assertTrue(UpdateSchedule.shouldCheck(last + 86_400_000, last, false, false, 0))
        assertTrue(UpdateSchedule.shouldCheck(last + 900_000, last, false, true, 0))
        assertTrue(UpdateSchedule.shouldCheck(last + 60_000, last, true, false, 0))
        assertFalse(UpdateSchedule.shouldCheck(last + 59_999, last, true, false, 0))
        assertFalse(UpdateSchedule.shouldCheck(last + 60_000, last, true, true, last + 120_000))
        assertTrue(UpdateSchedule.shouldCheck(last - 1, last, false, false, 0))
    }

    @Test fun `client sends anonymous conditional get and accepts not modified`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(release()).setHeader("ETag", "\"release-1\""))
            server.enqueue(MockResponse().setResponseCode(304))
            server.start()
            val client = ReleaseClient("1.1.2", server.url("/latest").toString())
            val first = client.fetch(null) as ReleaseReply.Found
            assertEquals("\"release-1\"", first.etag)
            assertEquals("v1.2.0", ReleaseInfo.parse(first.json)!!.tag)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            assertEquals(0L, request.bodySize)
            assertEquals(ReleaseReply.Unchanged, client.fetch(first.etag))
            assertEquals(first.etag, server.takeRequest().getHeader("If-None-Match"))
        }
    }

    @Test fun `no release is distinct from network failure and rate limiting`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("private error"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/other"))
            server.enqueue(MockResponse().setBody("not json"))
            server.start()
            val client = ReleaseClient("1.1.2", server.url("/latest").toString())
            assertEquals(ReleaseReply.NotPublished, client.fetch(null))
            val limited = assertThrows(UpdateCheckException::class.java) { client.fetch(null) }
            assertEquals(120_000L, limited.retryAfterMs)
            assertFalse(limited.message!!.contains("private error"))
            assertThrows(UpdateCheckException::class.java) { client.fetch(null) }
            assertThrows(UpdateCheckException::class.java) { client.fetch(null) }
            assertEquals(4, server.requestCount)
        }
    }
}
