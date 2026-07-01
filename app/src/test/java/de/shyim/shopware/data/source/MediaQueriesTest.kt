package de.shyim.shopware.data.source

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaQueriesTest {

    @Test
    fun rebaseSwapsSchemeAndAuthorityForTheConnectedBaseUrl() {
        // dev case: APP_URL says localhost, the app connects through 10.0.2.2
        assertEquals(
            "http://10.0.2.2:8000/media/0a/af/74/1780203019/x.jpg?ts=1780203019",
            rebaseMediaUrl(
                "http://localhost:8000/media/0a/af/74/1780203019/x.jpg?ts=1780203019",
                "http://10.0.2.2:8000",
            ),
        )
        // hosts agree → path unchanged, including https swap
        assertEquals(
            "https://shop.example/media/a/b.png",
            rebaseMediaUrl("https://shop.example/media/a/b.png", "https://shop.example/"),
        )
    }

    @Test
    fun rebaseLeavesNonHttpOrMalformedUrlsAlone() {
        assertEquals("data:image/png;base64,xyz", rebaseMediaUrl("data:image/png;base64,xyz", "https://s.example"))
        assertEquals("https://hostonly", rebaseMediaUrl("https://hostonly", "https://s.example"))
    }
}
