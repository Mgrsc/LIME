package org.bitfennec.lime.core

import android.content.Context
import android.graphics.PointF
import android.util.Log
import androidx.core.graphics.createBitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.core.runtime.AiModuleManager
import org.bitfennec.lime.inputmethod.ImeDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Modern lightweight PP-OCRv6 line-level CTC recognition engine for rasterized ink.
 *
 * Based on PP-OCRv6 recognition network (input [1, 3, 48, 320] NCHW, output CTC sequence [1, T, 18710]).
 * Used as IME ink recognition backend (task class != Apple online 48x48x1 single-character model).
 * Preprocessing renders white-background black-ink strokes with standard Paddle normalization.
 * Dispatching is strictly bound to ImeDispatchers.hwDispatcher. OrtSession unloads on exit or after 60s idle.
 */
object HandwritingEngine : HandwritingRecognizer {

    private const val TAG = "HandwritingEngine"
    private const val IMAGE_HEIGHT = 48
    private const val IMAGE_WIDTH = 320
    private const val CHANNELS = 3
    private const val TOP_K = 10

    private val engineScope = CoroutineScope(SupervisorJob() + ImeDispatchers.hwDispatcher)

    @Volatile
    private var ortEnv: OrtEnvironment? = null
    @Volatile
    private var ortSession: OrtSession? = null
    @Volatile
    private var characterList: List<String>? = null
    @Volatile
    private var pinyinMap: Map<String, String>? = null
    @Volatile
    private var isInitialized = false

    private fun initInternal(context: Context) {
        if (isInitialized && ortSession != null && characterList != null) return
        val appContext = context.applicationContext
        if (!AiModuleManager.isHandwritingReady(appContext)) {
            Log.i(TAG, "Handwriting module is not installed or verified in AiModuleManager")
            return
        }
        if (!AiModuleManager.prepareHandwritingRuntime(appContext)) {
            Log.e(TAG, "Failed to load shared ORT runtime via AiModuleManager")
            return
        }

        synchronized(this) {
            if (isInitialized && ortSession != null && characterList != null) return
            try {
                if (ortEnv == null) {
                    ortEnv = OrtEnvironment.getEnvironment()
                }
                if (ortSession == null) {
                    val modelFile = AiModuleManager.getHandwritingModelFile(appContext)
                    val sessionOptions = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(2)
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }
                    ortSession = ortEnv!!.createSession(modelFile.absolutePath, sessionOptions)
                }
                if (characterList == null) {
                    val keysFile = AiModuleManager.getHandwritingKeysFile(appContext)
                    characterList = if (keysFile.isFile) {
                        val lines = keysFile.readLines().filter { it.isNotEmpty() }
                        // Paddle CTCLabelDecode: character = ["blank"] + dict + [" "]
                        val list = ArrayList<String>(lines.size + 2)
                        list.add("blank")
                        list.addAll(lines)
                        list.add(" ")
                        list
                    } else {
                        emptyList()
                    }
                }

                // Verify model output dimension matches dictionary size (gate: output_dim == dict_size + special)
                val outInfo = ortSession?.outputInfo?.values?.firstOrNull()?.info as? TensorInfo
                val outShape = outInfo?.shape
                val numClasses = outShape?.getOrNull(2)
                if (numClasses != null && numClasses != -1L && characterList != null && characterList!!.isNotEmpty()) {
                    if (numClasses.toInt() != characterList!!.size) {
                        Log.e(
                            TAG,
                            "{\"event\":\"handwriting_engine_dim_mismatch\",\"model_classes\":$numClasses,\"dict_size\":${characterList!!.size}}"
                        )
                        runCatching { ortSession?.close() }
                        ortSession = null
                        isInitialized = false
                        return
                    }
                }

                if (pinyinMap == null) {
                    val pinyinFile = AiModuleManager.getHandwritingPinyinDictFile(appContext)
                    val map = HashMap<String, String>(16000)
                    if (pinyinFile.isFile) {
                        pinyinFile.useLines { lines ->
                            lines.forEach { line ->
                                val tabIndex = line.indexOf('\t')
                                if (tabIndex > 0) {
                                    val key = line.substring(0, tabIndex).trim()
                                    val py = line.substring(tabIndex + 1).trim()
                                    if (key.isNotEmpty() && py.isNotEmpty()) {
                                        val charStr = key.toIntOrNull()?.let { String(Character.toChars(it)) } ?: key
                                        map[charStr] = py
                                    }
                                }
                            }
                        }
                    }
                    pinyinMap = map
                }
                isInitialized = true
                Log.i(
                    TAG,
                    "{\"event\":\"handwriting_engine_initialization\",\"result\":\"success\",\"class_count\":${characterList?.size ?: 0}}",
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "{\"event\":\"handwriting_engine_initialization\",\"result\":\"failed\",\"exception\":\"${e.javaClass.simpleName}\"}",
                    e,
                )
            }
        }
    }

    private suspend fun safeDispatchMain(action: () -> Unit) {
        try {
            withContext(Dispatchers.Main.immediate) { action() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            action()
        }
    }

    fun init(context: Context, onReady: (() -> Unit)? = null) {
        cancelIdleUnload()
        val appContext = context.applicationContext
        engineScope.launch {
            if (isInitialized && ortSession != null && characterList != null) {
                onReady?.let { r -> safeDispatchMain(r) }
                return@launch
            }
            initInternal(appContext)
            if (isInitialized && ortSession != null) {
                onReady?.let { r -> safeDispatchMain(r) }
            }
        }
    }

    override fun recognize(strokes: List<List<PointF>>, callback: (Array<CandidateListItem>) -> Unit) {
        if (strokes.isEmpty()) return
        cancelIdleUnload()

        engineScope.launch {
            try {
                if (!isInitialized || ortSession == null || characterList == null) {
                    initInternal(Launcher.instance.context)
                }
                if (!isInitialized || ortSession == null || characterList == null) {
                    Log.e(
                        TAG,
                        "{\"event\":\"handwriting_recognition\",\"result\":\"failed\",\"reason\":\"engine_unavailable\"}",
                    )
                    withContext(Dispatchers.Main.immediate) { callback(emptyArray()) }
                    return@launch
                }

                // 1. Rasterize strokes to white-background black-ink tensor with Paddle normalization (H=48, W=320, 3 channels)
                val floatBuffer = rasterizeStrokesToOcrTensor(strokes)

                // 2. ONNX Runtime model inference
                val env = ortEnv ?: return@launch
                val session = ortSession ?: return@launch
                val inputName = session.inputNames.firstOrNull() ?: "x"

                val shape = longArrayOf(1, CHANNELS.toLong(), IMAGE_HEIGHT.toLong(), IMAGE_WIDTH.toLong())
                val tensor = OnnxTensor.createTensor(env, floatBuffer, shape)

                val output = tensor.use { t ->
                    session.run(mapOf(inputName to t))
                }

                val (logits, timeSteps, numClasses) = output.use { result ->
                    val outputTensor = result.get(0) as? OnnxTensor ?: return@use Triple(null, 0, 0)
                    val outShape = outputTensor.info.shape
                    val c = if (outShape.size >= 3) outShape[2].toInt() else 0
                    val fb = outputTensor.floatBuffer
                    val arr = FloatArray(fb.remaining())
                    fb.get(arr)
                    val t = if (c > 0) arr.size / c else if (outShape.size >= 2) outShape[1].toInt() else 0
                    Triple(arr, t, c)
                }

                if (logits == null || timeSteps <= 0 || numClasses <= 0) {
                    Log.e(
                        TAG,
                        "{\"event\":\"handwriting_recognition\",\"result\":\"failed\",\"reason\":\"missing_logits\"}",
                    )
                    withContext(Dispatchers.Main.immediate) { callback(emptyArray()) }
                    return@launch
                }

                val characters = characterList ?: return@launch

                // 3. CTC decoding and candidate extraction
                // 3.1 CTC greedy best path decoding
                val greedyChars = ArrayList<String>()
                var prevIdx = -1
                for (t in 0 until timeSteps) {
                    var maxIdx = 0
                    var maxLogit = -Float.MAX_VALUE
                    val offset = t * numClasses
                    for (c in 0 until numClasses) {
                        val logit = logits[offset + c]
                        if (logit > maxLogit) {
                            maxLogit = logit
                            maxIdx = c
                        }
                    }
                    if (maxIdx != 0 && maxIdx != prevIdx) {
                        val ch = characters.getOrNull(maxIdx)
                        if (!ch.isNullOrEmpty() && ch != " ") {
                            greedyChars.add(ch)
                        }
                    }
                    prevIdx = maxIdx
                }

                // 3.2 Extract candidate: find non-blank peak frame with highest confidence
                var peakFrame = -1
                var peakMaxScore = -Float.MAX_VALUE
                for (t in 0 until timeSteps) {
                    val offset = t * numClasses
                    var nonBlankMax = -Float.MAX_VALUE
                    for (c in 1 until numClasses) {
                        val v = logits[offset + c]
                        if (v > nonBlankMax) nonBlankMax = v
                    }
                    if (nonBlankMax > peakMaxScore) {
                        peakMaxScore = nonBlankMax
                        peakFrame = t
                    }
                }

                val candidateMap = LinkedHashMap<String, CandidateListItem>()
                for (gChar in greedyChars) {
                    val py = pinyinMap?.get(gChar) ?: ""
                    candidateMap[gChar] = CandidateListItem(py, gChar)
                }

                if (peakFrame >= 0) {
                    val offset = peakFrame * numClasses
                    val heap = PriorityQueue<Pair<Int, Float>>(TOP_K, compareBy { it.second })
                    for (c in 1 until numClasses) {
                        val score = logits[offset + c]
                        if (heap.size < TOP_K) {
                            heap.offer(Pair(c, score))
                        } else if (score > heap.peek()!!.second) {
                            heap.poll()
                            heap.offer(Pair(c, score))
                        }
                    }
                    val topIdxList = ArrayList<Int>(heap.size)
                    while (heap.isNotEmpty()) topIdxList.add(heap.poll()!!.first)
                    topIdxList.reverse()

                    for (cIdx in topIdxList) {
                        val ch = characters.getOrNull(cIdx) ?: continue
                        if (ch.isEmpty() || ch == " " || ch == "blank") continue
                        if (!candidateMap.containsKey(ch)) {
                            val py = pinyinMap?.get(ch) ?: ""
                            candidateMap[ch] = CandidateListItem(py, ch)
                        }
                        if (candidateMap.size >= TOP_K) break
                    }
                }

                val candidateItems = candidateMap.values.toTypedArray()
                if (candidateItems.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "{\"event\":\"handwriting_recognition\",\"result\":\"success\",\"candidate_count\":${candidateItems.size}}",
                    )
                    withContext(Dispatchers.Main.immediate) {
                        callback(candidateItems)
                    }
                } else {
                    Log.w(
                        TAG,
                        "{\"event\":\"handwriting_recognition\",\"result\":\"empty\"}",
                    )
                    withContext(Dispatchers.Main.immediate) { callback(emptyArray()) }
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "{\"event\":\"handwriting_recognition\",\"result\":\"failed\",\"exception\":\"${e.javaClass.simpleName}\"}",
                    e,
                )
                withContext(Dispatchers.Main.immediate) { callback(emptyArray()) }
            } finally {
                scheduleIdleUnload(60_000L)
            }
        }
    }

    private var renderBitmap: android.graphics.Bitmap? = null
    private var renderCanvas: android.graphics.Canvas? = null
    private val renderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        color = android.graphics.Color.BLACK
    }
    private val pixelArray = IntArray(IMAGE_WIDTH * IMAGE_HEIGHT)

    /**
     * Rasterizes strokes to white-background black-ink tensor with Paddle normalization (H=48, W=320, 3 channels).
     * Prefers Android Canvas + Paint with round antialiased strokes; gracefully falls back in JVM tests.
     */
    internal fun rasterizeStrokesToOcrTensor(strokes: List<List<PointF>>): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * CHANNELS * IMAGE_HEIGHT * IMAGE_WIDTH * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val validStrokes = strokes.filter { it.isNotEmpty() }
        if (validStrokes.isEmpty()) return buffer

        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        for (stroke in validStrokes) {
            for (p in stroke) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
        }

        val dx = max(maxX - minX, 1f)
        val dy = max(maxY - minY, 1f)

        // Proportional scaling to height 48 (effective 42 with padding); dynamic width with right zero-padding
        val targetHeight = (IMAGE_HEIGHT - 6).toFloat()
        var scale = targetHeight / dy
        if (dx * scale > (IMAGE_WIDTH - 6).toFloat()) {
            scale = (IMAGE_WIDTH - 6).toFloat() / dx
        }

        val scaledW = (dx * scale).roundToInt().coerceIn(1, IMAGE_WIDTH - 6)
        val scaledH = (dy * scale).roundToInt().coerceIn(1, IMAGE_HEIGHT - 6)
        val offsetY = 3f + (targetHeight - scaledH) / 2f
        val offsetX = 3f
        val activeWidth = min(IMAGE_WIDTH, (offsetX + scaledW + 3f).roundToInt().coerceAtLeast(IMAGE_HEIGHT))

        val canvasRasterized = runCatching {
            var bmp = renderBitmap
            if (bmp == null || bmp.isRecycled) {
                bmp = createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, android.graphics.Bitmap.Config.ARGB_8888)
                renderBitmap = bmp
                renderCanvas = android.graphics.Canvas(bmp)
            }
            val canvas = renderCanvas ?: android.graphics.Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            val strokeW = max(2.0f, scaledH * 0.08f)
            renderPaint.strokeWidth = strokeW

            for (stroke in validStrokes) {
                if (stroke.size == 1) {
                    val p = stroke[0]
                    val px = (offsetX + (p.x - minX) * scale).coerceIn(0f, activeWidth - 1f)
                    val py = (offsetY + (p.y - minY) * scale).coerceIn(0f, (IMAGE_HEIGHT - 1).toFloat())
                    canvas.drawCircle(px, py, strokeW / 2f, renderPaint.apply { style = android.graphics.Paint.Style.FILL })
                    renderPaint.style = android.graphics.Paint.Style.STROKE
                } else {
                    val path = android.graphics.Path()
                    val p0 = stroke[0]
                    path.moveTo(
                        (offsetX + (p0.x - minX) * scale).coerceIn(0f, activeWidth - 1f),
                        (offsetY + (p0.y - minY) * scale).coerceIn(0f, (IMAGE_HEIGHT - 1).toFloat())
                    )
                    for (i in 1 until stroke.size) {
                        val pi = stroke[i]
                        path.lineTo(
                            (offsetX + (pi.x - minX) * scale).coerceIn(0f, activeWidth - 1f),
                            (offsetY + (pi.y - minY) * scale).coerceIn(0f, (IMAGE_HEIGHT - 1).toFloat())
                        )
                    }
                    canvas.drawPath(path, renderPaint)
                }
            }
            bmp.getPixels(pixelArray, 0, IMAGE_WIDTH, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT)
            true
        }.getOrDefault(false)

        if (canvasRasterized) {
            val normGrid = Array(IMAGE_HEIGHT) { y ->
                FloatArray(IMAGE_WIDTH) { x ->
                    if (x < activeWidth) {
                        val color = pixelArray[y * IMAGE_WIDTH + x]
                        val r = (color shr 16) and 0xFF
                        (r / 255.0f - 0.5f) / 0.5f
                    } else {
                        0.0f
                    }
                }
            }
            for (ch in 0 until CHANNELS) {
                for (y in 0 until IMAGE_HEIGHT) {
                    for (x in 0 until IMAGE_WIDTH) {
                        buffer.put(normGrid[y][x])
                    }
                }
            }
            buffer.rewind()
            return buffer
        }

        // Software rasterizer fallback (white background 255f, black stroke 0f)
        val grid = Array(IMAGE_HEIGHT) { FloatArray(IMAGE_WIDTH) { 255.0f } }

        fun drawBrush(cx: Int, cy: Int) {
            val yMin = max(0, cy - 1)
            val yMax = min(IMAGE_HEIGHT - 1, cy + 1)
            val xMin = max(0, cx - 1)
            val xMax = min(activeWidth - 1, cx + 1)
            for (by in yMin..yMax) {
                for (bx in xMin..xMax) {
                    grid[by][bx] = 0.0f
                }
            }
        }

        fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int) {
            val dxL = abs(x1 - x0)
            val dyL = abs(y1 - y0)
            val sx = if (x0 < x1) 1 else -1
            val sy = if (y0 < y1) 1 else -1
            var err = dxL - dyL
            var x = x0
            var y = y0
            while (true) {
                drawBrush(x, y)
                if (x == x1 && y == y1) break
                val e2 = 2 * err
                if (e2 > -dyL) {
                    err -= dyL
                    x += sx
                }
                if (e2 < dxL) {
                    err += dxL
                    y += sy
                }
            }
        }

        for (stroke in validStrokes) {
            val normPts = ArrayList<Pair<Int, Int>>(stroke.size)
            for (p in stroke) {
                val nx = (offsetX + (p.x - minX) * scale).roundToInt().coerceIn(0, activeWidth - 1)
                val ny = (offsetY + (p.y - minY) * scale).roundToInt().coerceIn(0, IMAGE_HEIGHT - 1)
                normPts.add(Pair(nx, ny))
            }
            if (normPts.size == 1) {
                drawBrush(normPts[0].first, normPts[0].second)
            } else {
                for (i in 0 until normPts.size - 1) {
                    val p1 = normPts[i]
                    val p2 = normPts[i + 1]
                    drawLine(p1.first, p1.second, p2.first, p2.second)
                }
            }
        }

        val normGrid = Array(IMAGE_HEIGHT) { y ->
            FloatArray(IMAGE_WIDTH) { x ->
                if (x < activeWidth) {
                    (grid[y][x] / 255.0f - 0.5f) / 0.5f
                } else {
                    0.0f
                }
            }
        }

        for (ch in 0 until CHANNELS) {
            for (y in 0 until IMAGE_HEIGHT) {
                for (x in 0 until IMAGE_WIDTH) {
                    buffer.put(normGrid[y][x])
                }
            }
        }
        buffer.rewind()
        return buffer
    }

    val isReady: Boolean
        get() = isInitialized && ortSession != null

    internal var idleUnloadJob: kotlinx.coroutines.Job? = null

    /**
     * Schedules 60s idle unload to release OrtSession inference memory.
     * Preserves ortEnv and dictionary caches for fast re-initialization.
     */
    fun scheduleIdleUnload(timeoutMs: Long = 60000L) {
        synchronized(this) {
            idleUnloadJob?.cancel()
            idleUnloadJob = engineScope.launch {
                kotlinx.coroutines.delay(timeoutMs)
                synchronized(this@HandwritingEngine) {
                    if (ortSession != null) {
                        Log.i(TAG, "{\"event\":\"handwriting.engine_idle_timeout\",\"action\":\"released\"}")
                        try {
                            ortSession?.close()
                            renderBitmap?.recycle()
                            renderBitmap = null
                            renderCanvas = null
                        } catch (_: Throwable) {
                        } finally {
                            ortSession = null
                        }
                    }
                }
            }
        }
    }

    fun cancelIdleUnload() {
        synchronized(this) {
            idleUnloadJob?.cancel()
            idleUnloadJob = null
        }
    }

    /**
     * Releases ONNX Runtime native resources sequentially on hwDispatcher.
     * Closes OrtSession while retaining process-level OrtEnvironment singleton.
     */
    fun release() {
        cancelIdleUnload()
        engineScope.launch {
            synchronized(this@HandwritingEngine) {
                try {
                    ortSession?.close()
                    renderBitmap?.recycle()
                    renderBitmap = null
                    renderCanvas = null
                } catch (_: Throwable) {
                } finally {
                    ortSession = null
                    isInitialized = false
                }
            }
        }
    }
}
