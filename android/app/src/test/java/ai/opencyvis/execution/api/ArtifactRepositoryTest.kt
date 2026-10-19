package ai.opencyvis.execution.api

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtifactRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun importKeepsBinarySnapshotAndSourceAfterOriginalChanges() {
        val input = temporary.newFile("input.bin").apply { writeBytes(byteArrayOf(0, 1, -1, 0, 10)) }
        val repository = ArtifactRepository(temporary.newFolder("task"))
        val ref = repository.importFile(input, "input.bin", "application/octet-stream", "content://fixture/document/1")
        input.writeText("new source contents")
        assertArrayEquals(byteArrayOf(0, 1, -1, 0, 10), repository.resolve(ref).readBytes())
        assertEquals("content://fixture/document/1", ref.sourceUri)
        assertEquals(5L, ref.size)
        assertEquals(1, ref.version)
    }

    @Test fun nextVersionDoesNotReplaceOldVersionAndSurvivesRepositoryRecreation() {
        val directory = temporary.newFolder("task")
        val input = temporary.newFile("source.csv").apply { writeText("first") }
        val repository = ArtifactRepository(directory)
        val first = repository.importFile(input, "source.csv", "text/csv")
        input.writeText("second")
        val second = repository.importFile(input, "source.csv", "text/csv", artifactId = first.artifactId)
        val restored = ArtifactRepository(directory)
        assertEquals(first.artifactId, second.artifactId)
        assertEquals(2, second.version)
        assertEquals("first", restored.resolve(restored.get(first.artifactId, 1)).readText())
        assertEquals("second", restored.resolve(restored.get(second.artifactId, 2)).readText())
        assertEquals(second, restored.get(second.artifactId, 2))
    }

    @Test fun mutableWorkspaceUsesCopiesAndOutputGetsItsOwnVersion() {
        val repository = ArtifactRepository(temporary.newFolder("task"))
        val ref = repository.importStream("before".byteInputStream(), "input.txt", "text/plain")
        val workspace = temporary.newFolder("execution")
        val staged = repository.stage(ref, workspace, "inputs/input.txt")
        staged.writeText("after")
        val result = repository.captureOutput(workspace, "inputs/input.txt", "result.txt", "text/plain", ref.artifactId)
        staged.writeText("changed again")
        assertEquals("before", repository.resolve(ref).readText())
        assertEquals("after", repository.resolve(result).readText())
        assertEquals(2, result.version)
        assertNull(result.sourceUri)
    }

    @Test fun workspacePathsCannotEscapeTheirExplicitDirectory() {
        val repository = ArtifactRepository(temporary.newFolder("task"))
        val ref = repository.importStream("input".byteInputStream(), "input.txt", "text/plain")
        val workspace = temporary.newFolder("execution")
        assertThrows(IllegalArgumentException::class.java) { repository.stage(ref, workspace, "../outside.txt") }
        assertThrows(IllegalArgumentException::class.java) {
            repository.captureOutput(workspace, "../outside.txt", "out.txt", "text/plain")
        }
        assertFalse(File(workspace.parentFile, "outside.txt").exists())
    }
}
