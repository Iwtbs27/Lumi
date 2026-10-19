package ai.opencyvis.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SherpaOnnxModelFilesTest {
    @Test
    fun `paths resolve requested bilingual int8 model files under app files directory`() {
        val baseDir = File("/tmp/opencyvis-test-files")

        val paths = SherpaOnnxModelFiles.paths(baseDir)

        assertEquals(File(baseDir, "asr/zh_en_small_int8"), paths.modelDir)
        assertEquals(File(paths.modelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath, paths.encoder)
        assertEquals(File(paths.modelDir, "decoder-epoch-99-avg-1.onnx").absolutePath, paths.decoder)
        assertEquals(File(paths.modelDir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath, paths.joiner)
        assertEquals(File(paths.modelDir, "tokens.txt").absolutePath, paths.tokens)
        assertEquals(File(paths.modelDir, "bpe.model").absolutePath, paths.bpeModel)
    }

    @Test
    fun `required files match bundled runtime payload`() {
        assertEquals(
            listOf(
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt",
                "bpe.model"
            ),
            SherpaOnnxModelFiles.requiredFiles
        )
    }

    @Test
    fun `recognizer config declares bundled transducer model type`() {
        val paths = SherpaOnnxModelFiles.paths(File("/tmp/opencyvis-test-files"))

        val config = SherpaOnnxSpeechInputEngine.recognizerConfig(paths, numThreads = 8)

        assertEquals("zipformer", config.modelConfig.modelType)
        assertEquals(2, config.modelConfig.numThreads)
        assertEquals(paths.encoder, config.modelConfig.transducer.encoder)
        assertEquals(paths.decoder, config.modelConfig.transducer.decoder)
        assertEquals(paths.joiner, config.modelConfig.transducer.joiner)
        assertEquals(paths.tokens, config.modelConfig.tokens)
    }
}
