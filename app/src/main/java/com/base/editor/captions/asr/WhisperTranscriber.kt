package com.base.editor.captions.asr

import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Запуск модели на устройстве (TFLite). Не потокобезопасен: один поток — один экземпляр. */
class WhisperTranscriber(modelFile: File, private val frontend: WhisperFrontend, private val threads: Int = 4) : AutoCloseable {
    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(WhisperFrontend.N_MEL * WhisperFrontend.N_FRAMES * 4).order(ByteOrder.nativeOrder())
    private val output: ByteBuffer
    private val outputLen: Int

    init {
        val mapped = RandomAccessFile(modelFile, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        interpreter = Interpreter(mapped, Interpreter.Options().setNumThreads(threads))
        outputLen = interpreter.getOutputTensor(0).shape().last()
        output = ByteBuffer.allocateDirect(outputLen * 4).order(ByteOrder.nativeOrder())
    }

    /** Окно до 30 с звука 16 кГц → текст. */
    fun transcribe(samples: FloatArray): WhisperFrontend.Decoded {
        val mel = frontend.melSpectrogram(samples, samples.size, threads)
        input.clear(); input.asFloatBuffer().put(mel); input.rewind()
        output.clear()
        interpreter.run(input, output)
        output.rewind()
        val tokens = IntArray(outputLen).also { output.asIntBuffer().get(it) }
        return frontend.decode(tokens)
    }

    override fun close() { interpreter.close() }
}
