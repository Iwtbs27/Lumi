package ai.opencyvis.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextArtifactStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exactTextIsRestoredFromBoundedPagesWithSourceMetadata() {
        val store = ContextArtifactStore(temporary.newFolder("artifacts"), "run-one")
        val original = "商品：蓝色\n价格：129.90\n".repeat(50)
        val reference = store.save(original, sourceStep = 7, label = "task_memory.products", version = 2)
        val restored = StringBuilder()
        var offset = 0
        do {
            val page = store.read(reference.artifactRef, offset, 37)
            assertEquals(reference.artifactRef, page.reference)
            assertEquals(original.length, page.totalChars)
            assertTrue(page.text.length <= 37)
            assertEquals(offset, page.offset)
            restored.append(page.text)
            offset = page.nextOffset ?: break
        } while (true)

        assertEquals(original, restored.toString())
        assertEquals(original.length, reference.charLength)
        assertEquals(7, reference.sourceStep)
        assertEquals("task_memory.products", reference.label)
        assertEquals(2, reference.version)
        assertEquals(reference.artifactRef, reference.toMap()["artifact_ref"])
    }

    @Test fun sameContentAndSourceReuseReferenceAfterReopeningStore() {
        val directory = temporary.newFolder("artifacts")
        val firstStore = ContextArtifactStore(directory, "run-one")
        val first = firstStore.save("准确值 123.456", 3, "price", 1)
        assertEquals(first, firstStore.save("准确值 123.456", 3, "price", 1))

        val reopened = ContextArtifactStore(directory, "run-one")
        assertEquals(first, reopened.save("准确值 123.456", 3, "price", 1))
        assertEquals("准确值 123.456", reopened.read(first.artifactRef).text)
    }

    @Test fun changedContentOrSourceVersionKeepsPreviousRecordReadable() {
        val store = ContextArtifactStore(temporary.newFolder("artifacts"), "run-one")
        val first = store.save("旧价格 100", 3, "price", 1)
        val edited = store.save("新价格 120", 3, "price", 1)
        val versioned = store.save("新价格 120", 3, "price", 2)
        val anotherSource = store.save("新价格 120", 4, "price", 2)

        assertEquals(4, listOf(first, edited, versioned, anotherSource).map { it.artifactRef }.distinct().size)
        assertEquals("旧价格 100", store.read(first.artifactRef).text)
        assertEquals("新价格 120", store.read(edited.artifactRef).text)
    }

    @Test fun finalAndEmptyPagesExposeEndWithoutLosingText() {
        val store = ContextArtifactStore(temporary.newFolder("artifacts"), "run-one")
        val reference = store.save("abcdef", 1, "text")
        val finalPage = store.read(reference.artifactRef, 4, 20)
        assertEquals("ef", finalPage.text)
        assertNull(finalPage.nextOffset)
        assertNull(finalPage.toMap()["next_offset"])
        assertEquals(6, finalPage.toMap()["total_chars"])
        assertEquals("", store.read(reference.artifactRef, 6, 20).text)

        val empty = store.save("", 1, "empty")
        assertEquals(0, empty.charLength)
        assertNull(store.read(empty.artifactRef).nextOffset)
    }

    @Test fun pagesKeepEmojiIntactWhenSerializedForTheModel() {
        val original = "ab😀cd😀ef"
        val restored = StringBuilder()
        var offset = 0
        do {
            val page = ContextTextPage.from("example", original, offset, 3)
            val transported = JSONObject(JSONObject(page.toMap()).toString()
                .toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
            restored.append(transported.getString("text"))
            offset = page.nextOffset ?: break
        } while (true)
        assertEquals(original, restored.toString())
    }

    @Test fun invalidReferenceAndPageRangeAreReportedExplicitly() {
        val store = ContextArtifactStore(temporary.newFolder("artifacts"), "run-one")
        val reference = store.save("abc", 1, "text")
        val other = ContextArtifactStore(temporary.newFolder("other"), "run-two")
            .save("abc", 1, "text")

        assertThrows(IllegalArgumentException::class.java) { store.read(other.artifactRef) }
        assertThrows(IllegalArgumentException::class.java) { store.read(reference.artifactRef, -1, 2) }
        assertThrows(IllegalArgumentException::class.java) { store.read(reference.artifactRef, 4, 2) }
        assertThrows(IllegalArgumentException::class.java) { store.read(reference.artifactRef, 0, 0) }
    }
}
