package top.stevezmt.calsync.llm

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * LlamaCpp: Thread-safe JNI wrapper for llama.cpp inference.
 * 
 * Features:
 * - Automatic model loading and caching
 * - Thread-safe inference (serialized via lock)
 * - Proper resource cleanup
 * - Detailed logging for debugging
 * - Graceful error handling
 */
object LlamaCpp {
    private const val TAG = "LlamaCpp"
    private const val DEFAULT_CONTEXT_SIZE = 2048
    private const val DEFAULT_THREADS = 4  // Use 4 threads for better performance

    init {
        try {
            System.loadLibrary("llama_jni")
            Log.d(TAG, "llama_jni library loaded successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load llama_jni: ${t.message}", t)
        }
    }

    // JNI declarations
    @JvmStatic
    external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int): Long

    @JvmStatic
    external fun nativeFree(handle: Long)

    @JvmStatic
    external fun nativeComplete(handle: Long, prompt: String, maxTokens: Int): String

    // ===== State Management =====
    private data class ModelState(
        val handle: Long = 0,
        val modelPath: String = "",
        val nCtx: Int = 0,
        val nThreads: Int = 0
    ) {
        fun isValid() = handle != 0L && modelPath.isNotEmpty()
    }

    @Volatile
    private var currentState = ModelState()

    private val stateLock = ReentrantReadWriteLock()

    // Inference lock: serialize all inference calls since llama.cpp is not thread-safe per-context
    private val inferenceLock = Any()

    // ===== Public API =====

    /**
     * Get or initialize model handle with automatic caching.
     * 
     * @param context Application context
     * @param modelUri URI to model file (file:// or content://)
     * @param nCtx Context window size (default: 2048)
     * @param nThreads Thread count (default: 2)
     * @return Model handle (0 on failure)
     */
    fun getOrInitHandle(
        context: Context,
        modelUri: String,
        nCtx: Int = DEFAULT_CONTEXT_SIZE,
        nThreads: Int = DEFAULT_THREADS
    ): Long {
        Log.d(TAG, "getOrInitHandle: uri=$modelUri nCtx=$nCtx nThreads=$nThreads")

        // Fast path: check if we have a valid cached model
        stateLock.read {
            if (currentState.isValid() && currentState.modelPath == modelUri) {
                Log.d(TAG, "getOrInitHandle: using cached handle=${currentState.handle}")
                return currentState.handle
            }
        }

        // Need to load new model
        val modelFile = materializeModelToFile(context, modelUri)
        if (modelFile == null) {
            Log.e(TAG, "getOrInitHandle: failed to materialize model file")
            return 0
        }

        val modelPath = modelFile.absolutePath
        Log.d(TAG, "getOrInitHandle: materializing model to $modelPath")

        stateLock.write {
            // Double-check: another thread may have loaded while we were materializing
            if (currentState.isValid() && currentState.modelPath == modelPath) {
                Log.d(TAG, "getOrInitHandle: another thread loaded first, reusing handle=${currentState.handle}")
                return@write
            }

            // Free old handle if present
            if (currentState.isValid()) {
                Log.d(TAG, "getOrInitHandle: freeing old handle=${currentState.handle}")
                try {
                    nativeFree(currentState.handle)
                } catch (e: Exception) {
                    Log.w(TAG, "getOrInitHandle: error freeing old handle: ${e.message}")
                }
            }

            // Load new model
            val handle = nativeInit(modelPath, nCtx, nThreads)
            if (handle == 0L) {
                Log.e(TAG, "getOrInitHandle: nativeInit failed")
                currentState = ModelState()
                return@write
            }

            Log.d(TAG, "getOrInitHandle: new model loaded handle=$handle")
            currentState = ModelState(
                handle = handle,
                modelPath = modelPath,
                nCtx = nCtx,
                nThreads = nThreads
            )
        }

        return stateLock.read { currentState.handle }
    }

    /**
     * Run inference with the loaded model.
     * Thread-safe: serializes all inference calls.
     * 
     * @param handle Model handle from getOrInitHandle
     * @param prompt Input text prompt
     * @param maxTokens Maximum tokens to generate (capped at 256)
     * @return Generated text (empty string on error)
     */
    fun complete(handle: Long, prompt: String, maxTokens: Int): String {
        if (handle == 0L) {
            Log.w(TAG, "complete: invalid handle")
            return ""
        }

        if (prompt.isEmpty()) {
            Log.w(TAG, "complete: empty prompt")
            return ""
        }

        return synchronized(inferenceLock) {
            try {
                Log.d(TAG, "complete: start handle=$handle promptLen=${prompt.length} maxTokens=$maxTokens")
                val startMs = System.currentTimeMillis()

                val result = nativeComplete(handle, prompt, maxTokens.coerceIn(1, 256))

                val elapsedMs = System.currentTimeMillis() - startMs
                Log.d(TAG, "complete: done resultLen=${result.length} elapsedMs=$elapsedMs")

                result
            } catch (e: Exception) {
                Log.e(TAG, "complete: exception: ${e.message}", e)
                ""
            }
        }
    }

    /**
     * Free the currently loaded model.
     * Safe to call multiple times.
     */
    fun freeModel() {
        stateLock.write {
            if (currentState.isValid()) {
                Log.d(TAG, "freeModel: freeing handle=${currentState.handle}")
                try {
                    nativeFree(currentState.handle)
                } catch (e: Exception) {
                    Log.w(TAG, "freeModel: error during cleanup: ${e.message}")
                }
                currentState = ModelState()
            }
        }
    }

    /**
     * Get current model state (for debugging).
     */
    fun getModelState(): Map<String, Any?> {
        return stateLock.read {
            mapOf(
                "handle" to currentState.handle,
                "modelPath" to currentState.modelPath,
                "nCtx" to currentState.nCtx,
                "nThreads" to currentState.nThreads,
                "isValid" to currentState.isValid()
            )
        }
    }

    // ===== Helper Functions =====

    /**
     * Materialize a model URI (file:// or content://) to a local file.
     * Uses caching: skips copying if file already exists and is non-empty.
     * 
     * @param context Application context
     * @param uriString URI to model (file:// or content://)
     * @return File object, or null on failure
     */
    fun materializeModelToFile(context: Context, uriString: String): File? {
        return try {
            val uri = Uri.parse(uriString)
            Log.d(TAG, "materializeModelToFile: uri=$uri scheme=${uri.scheme}")

            when (uri.scheme) {
                "file" -> {
                    val path = uri.path
                    if (path != null) {
                        File(path).also { file ->
                            if (!file.exists()) {
                                Log.e(TAG, "materializeModelToFile: file not found at $path")
                                return null
                            }
                            Log.d(TAG, "materializeModelToFile: using file path ${file.absolutePath}")
                        }
                    } else {
                        Log.e(TAG, "materializeModelToFile: malformed file URI")
                        null
                    }
                }
                "content" -> {
                    val cacheDir = File(context.filesDir, "llm")
                    if (!cacheDir.exists()) {
                        cacheDir.mkdirs()
                        Log.d(TAG, "materializeModelToFile: created cache dir")
                    }

                    val modelFile = File(cacheDir, "model.gguf")

                    // Cache hit
                    if (modelFile.exists() && modelFile.length() > 0) {
                        Log.d(TAG, "materializeModelToFile: cache hit ${modelFile.absolutePath}")
                        return modelFile
                    }

                    // Copy from content URI
                    Log.d(TAG, "materializeModelToFile: copying from content URI")
                    var copiedBytes = 0L
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            modelFile.outputStream().use { output ->
                                copiedBytes = input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "materializeModelToFile: copy failed: ${e.message}")
                        return null
                    }

                    if (modelFile.length() <= 0) {
                        Log.e(TAG, "materializeModelToFile: copied file is empty")
                        return null
                    }

                    Log.d(TAG, "materializeModelToFile: copied $copiedBytes bytes to ${modelFile.absolutePath}")
                    modelFile
                }
                else -> {
                    Log.e(TAG, "materializeModelToFile: unsupported URI scheme: ${uri.scheme}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "materializeModelToFile: exception: ${e.message}", e)
            null
        }
    }
}

