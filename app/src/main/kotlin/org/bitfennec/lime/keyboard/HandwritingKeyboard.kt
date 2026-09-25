package org.bitfennec.lime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.view.MotionEvent
import android.widget.Toast
import androidx.core.graphics.createBitmap
import org.bitfennec.lime.R
import org.bitfennec.lime.data.theme.Theme
import org.bitfennec.lime.data.theme.ThemeManager
import org.bitfennec.lime.keyboard.handwriting.Bezier
import org.bitfennec.lime.keyboard.handwriting.ControlTimedPoints
import org.bitfennec.lime.keyboard.handwriting.TimedPoint
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.prefs.AppPrefs.Companion.getInstance
import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.core.HandwritingEngine
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Modern full-width handwriting input keyboard.
 * Top canvas supports cubic Bezier stroke smoothing;
 * Bottom area provides ergonomic control buttons.
 */
class HandwritingKeyboard(context: Context?) : TextKeyboard(context) {


    private val mPointsCache: MutableList<TimedPoint> = ArrayList()
    private val mControlTimedPointsCached = ControlTimedPoints()
    private var mLastUpTime: Long = 0
    private var mPoints: MutableList<TimedPoint> = ArrayList()
    private val mStrokes: MutableList<MutableList<PointF>> = ArrayList()
    private var isDrawing = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var consecutiveEmptyCount = 0
    private var recognitionGeneration = 0L

    private var mLastVelocity = 0f
    private var mLastWidth = 0f
    private var mMaxWidth: Int = convertDpToPx(12f)
    private var mMinWidth: Int = mMaxWidth / 2
    private var mVelocityFilterWeight = 0.7f
    private val mPaint = Paint()
    private var mSignatureBitmap: Bitmap? = null
    private var mSignatureBitmapCanvas: Canvas? = null
    private var usesSystemStylusWindow = false
    val isSystemStylus: Boolean
        get() = usesSystemStylusWindow
    private var hasPendingRecognition = false

    private val times: Long
        get() = getInstance().handwriting.handWritingSpeed.getValue().coerceIn(300, 1300).toLong()

    init {
        mPaint.color = ThemeManager.activeTheme.keyTextColor
        mPaint.isAntiAlias = true
        mPaint.style = Paint.Style.STROKE
        mPaint.strokeCap = Paint.Cap.ROUND
        mPaint.strokeJoin = Paint.Join.ROUND
        clear()
    }

    override fun setTheme(theme: Theme) {
        super.setTheme(theme)
        mPaint.color = mActiveTheme.keyTextColor
    }

    fun useSystemStylusWindow() {
        usesSystemStylusWindow = true
        setTheme(ThemeManager.activeTheme)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!usesSystemStylusWindow) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec),
        )
    }

    fun clear() {
        hasPendingRecognition = false
        mPoints.clear()
        mStrokes.clear()
        mLastVelocity = 0f
        mLastWidth = mMinWidth.toFloat()
        consecutiveEmptyCount = 0
        if (mSignatureBitmap != null) {
            mSignatureBitmap = null
            ensureSignatureBitmap()
        }
        invalidate()
    }

    fun cancelPendingRecognition() {
        hasPendingRecognition = false
        handler?.removeCallbacks(runnable)
        recognitionGeneration++
        clear()
    }

    override fun onDetachedFromWindow() {
        cancelPendingRecognition()
        super.onDetachedFromWindow()
    }

    fun hasStrokes(): Boolean = mStrokes.isNotEmpty() || mPoints.isNotEmpty()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(me: MotionEvent): Boolean {
        if (handleHandwritingMotionEvent(me)) return true
        return super.onTouchEvent(me)
    }

    fun onStylusHandwritingMotionEvent(me: MotionEvent): Boolean = handleHandwritingMotionEvent(me)

    private fun handleHandwritingMotionEvent(me: MotionEvent): Boolean {
        val isHandwritingActive = InputModeSwitcher.isChineseHandWriting || usesSystemStylusWindow
        if (!isEnabled || !isHandwritingActive || isPointerLongPressActive()) {
            return false
        }

        val canvasThresholdY = height * 0.78f

        when (me.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val eventY = me.y
                // Check if touch falls in bottom control bar
                if (eventY >= canvasThresholdY) {
                    isDrawing = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    return false
                }

                val eventX = me.x
                activePointerId = me.getPointerId(0)
                isDrawing = true
                parent?.requestDisallowInterceptTouchEvent(true)
                handler?.removeCallbacks(runnable)
                HandwritingEngine.cancelIdleUnload()

                val rawWidth = getInstance().handwriting.handWritingWidth.getValue()
                val paintWidthMax = computeStrokeMaxWidthDp(rawWidth)
                val paintWidthMin = computeStrokeMinWidthDp(rawWidth)
                mMaxWidth = convertDpToPx(paintWidthMax)
                mMinWidth = convertDpToPx(paintWidthMin)

                // SegPolicy: segmentation policy (timeout / stroke limit / spatial distance)
                val now = System.currentTimeMillis()
                var forceSegment = false
                if (mLastUpTime != 0L && now - mLastUpTime > times) {
                    forceSegment = true
                } else if (mStrokes.size >= 12) {
                    forceSegment = true
                } else if (mStrokes.size >= 2) {
                    var maxX = Float.MIN_VALUE
                    var minX = Float.MAX_VALUE
                    for (s in mStrokes) {
                        for (p in s) {
                            if (p.x < minX) minX = p.x
                            if (p.x > maxX) maxX = p.x
                        }
                    }
                    val charWidth = max(maxX - minX, 1f)
                    val spatialThreshold = max(convertDpToPx(32f).toFloat(), charWidth * 0.5f)
                    if (eventX > maxX + spatialThreshold) {
                        forceSegment = true
                    }
                }

                if (forceSegment) {
                    mService?.discardHandwritingCandidates()
                    clear()
                }

                mPoints.clear()
                val newStroke = ArrayList<PointF>()
                newStroke.add(PointF(eventX, eventY))
                mStrokes.add(newStroke)

                addPoint(getNewPoint(eventX, eventY))
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDrawing || activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    return false
                }
                val pointerIndex = me.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return false

                // Extract Android historical touch samples to prevent high-refresh-rate dropped points
                val historySize = me.historySize
                for (h in 0 until historySize) {
                    val hx = me.getHistoricalX(pointerIndex, h)
                    val hy = me.getHistoricalY(pointerIndex, h)
                    if (mStrokes.isNotEmpty()) {
                        mStrokes.last().add(PointF(hx, hy))
                    }
                    addPoint(getNewPoint(hx, hy))
                }

                val curX = me.getX(pointerIndex)
                val curY = me.getY(pointerIndex)
                if (mStrokes.isNotEmpty()) {
                    mStrokes.last().add(PointF(curX, curY))
                }
                addPoint(getNewPoint(curX, curY))
                updatePathDelayed()
            }

            MotionEvent.ACTION_UP -> {
                if (!isDrawing) {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    return false
                }
                val pointerIndex = me.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) {
                    val curX = me.getX(pointerIndex)
                    val curY = me.getY(pointerIndex)
                    if (mStrokes.isNotEmpty()) {
                        mStrokes.last().add(PointF(curX, curY))
                    }
                    addPoint(getNewPoint(curX, curY))
                }
                mLastUpTime = System.currentTimeMillis()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isDrawing = false

                // Trigger ONNX handwriting recognition
                recognitionData()
                if (!hasPendingRecognition) {
                    updatePathDelayed()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                isDrawing = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                return false
            }

            else -> return false
        }
        invalidate()
        return true
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Render handwriting strokes
        if (mSignatureBitmap != null) {
            canvas.drawBitmap(mSignatureBitmap!!, 0f, 0f, mPaint)
        }

        // Draw subtle divider between canvas and bottom control bar
        val dividerY = height * 0.78f
        dividerPaint.apply {
            color = mActiveTheme.keyTextColor
            alpha = 26 // ~10% opacity
            strokeWidth = convertDpToPx(0.5f).toFloat()
            isAntiAlias = true
        }
        canvas.drawLine(convertDpToPx(16f).toFloat(), dividerY, width - convertDpToPx(16f).toFloat(), dividerY, dividerPaint)
    }

    private fun getNewPoint(x: Float, y: Float): TimedPoint? {
        val mCacheSize = mPointsCache.size
        val timedPoint = if (mCacheSize == 0) TimedPoint() else mPointsCache.removeAt(mCacheSize - 1)
        return timedPoint.set(x, y)
    }

    private fun recyclePoint(point: TimedPoint?) {
        if (point != null) {
            mPointsCache.add(point)
        }
    }

    private fun addPoint(newPoint: TimedPoint?) {
        if (newPoint == null) return
        mPoints.add(newPoint)
        val pointsCount = mPoints.size
        if (pointsCount > 3) {
            var tmp = calculateCurveControlPoints(mPoints[0], mPoints[1], mPoints[2])
            val c2 = tmp.c2!!
            recyclePoint(tmp.c1)
            tmp = calculateCurveControlPoints(mPoints[1], mPoints[2], mPoints[3])
            val c3 = tmp.c1!!
            recyclePoint(tmp.c2)
            val curve = Bezier(mPoints[1], c2, c3, mPoints[2])
            val startPoint = curve.startPoint
            val endPoint = curve.endPoint
            var velocity = endPoint.velocityFrom(startPoint)
            velocity = if (velocity.isNaN()) 0.0f else velocity
            velocity = (mVelocityFilterWeight * velocity + (1 - mVelocityFilterWeight) * mLastVelocity)
            val newWidth = strokeWidth(velocity)
            addBezier(curve, mLastWidth, newWidth)
            mLastVelocity = velocity
            mLastWidth = newWidth
            recyclePoint(mPoints.removeAt(0))
            recyclePoint(c2)
            recyclePoint(c3)
        } else if (pointsCount == 1) {
            val firstPoint = mPoints[0]
            mPoints.add(getNewPoint(firstPoint.x, firstPoint.y)!!)
        }
    }

    private fun addBezier(curve: Bezier, startWidth: Float, endWidth: Float) {
        ensureSignatureBitmap()
        val originalWidth = mPaint.strokeWidth
        val widthDelta = endWidth - startWidth
        val drawSteps = ceil(curve.length().toDouble()).toFloat()
        var i = 0
        while (i < drawSteps) {
            val t = (i.toFloat()) / drawSteps
            val tt = t * t
            val ttt = tt * t
            val u = 1 - t
            val uu = u * u
            val uuu = uu * u
            var x = uuu * curve.startPoint.x
            x += 3 * uu * t * curve.control1.x
            x += 3 * u * tt * curve.control2.x
            x += ttt * curve.endPoint.x
            var y = uuu * curve.startPoint.y
            y += 3 * uu * t * curve.control1.y
            y += 3 * u * tt * curve.control2.y
            y += ttt * curve.endPoint.y
            mPaint.strokeWidth = startWidth + ttt * widthDelta
            mSignatureBitmapCanvas?.drawPoint(x, y, mPaint)
            i++
        }
        mPaint.strokeWidth = originalWidth
    }

    private fun calculateCurveControlPoints(s1: TimedPoint, s2: TimedPoint, s3: TimedPoint): ControlTimedPoints {
        val dx1 = s1.x - s2.x
        val dy1 = s1.y - s2.y
        val dx2 = s2.x - s3.x
        val dy2 = s2.y - s3.y
        val m1X = (s1.x + s2.x) / 2.0f
        val m1Y = (s1.y + s2.y) / 2.0f
        val m2X = (s2.x + s3.x) / 2.0f
        val m2Y = (s2.y + s3.y) / 2.0f
        val l1 = sqrt((dx1 * dx1 + dy1 * dy1).toDouble()).toFloat()
        val l2 = sqrt((dx2 * dx2 + dy2 * dy2).toDouble()).toFloat()
        val dxm = (m1X - m2X)
        val dym = (m1Y - m2Y)
        var k = l2 / (l1 + l2)
        if (k.isNaN()) k = 0.0f
        val cmX = m2X + dxm * k
        val cmY = m2Y + dym * k
        val tx = s2.x - cmX
        val ty = s2.y - cmY
        return mControlTimedPointsCached.set(
            getNewPoint(m1X + tx, m1Y + ty),
            getNewPoint(m2X + tx, m2Y + ty)
        )
    }

    private fun strokeWidth(velocity: Float): Float {
        return max((mMaxWidth / (velocity + 1)).toDouble(), mMinWidth.toDouble()).toFloat()
    }

    private fun ensureSignatureBitmap() {
        if (mSignatureBitmap == null && width > 0 && height > 0) {
            mSignatureBitmap = createBitmap(width, height)
            mSignatureBitmapCanvas = Canvas(mSignatureBitmap!!)
        }
    }

    private fun convertDpToPx(dp: Float): Int {
        return (context.resources.displayMetrics.density * dp).roundToInt()
    }

    private fun recognitionData() {
        if (mStrokes.isEmpty()) return
        val boundModalSessionId = mService?.currentHandwritingModalSessionId()
        if (boundModalSessionId == null) {
            hasPendingRecognition = true
            handler?.removeCallbacks(runnable)
            return
        }
        hasPendingRecognition = false
        val currentGen = ++recognitionGeneration
        val boundEditorSessionId = org.bitfennec.lime.inputmethod.EnginePipeline.currentSessionId
        val strokesCopy = mStrokes.map { ArrayList(it) }
        HandwritingEngine.recognize(strokesCopy) { items ->
            mService?.post {
                if (currentGen == recognitionGeneration &&
                    mService?.currentHandwritingModalSessionId() == boundModalSessionId &&
                    org.bitfennec.lime.inputmethod.EnginePipeline.currentSessionId == boundEditorSessionId
                ) {
                    mService?.responseHandwritingResultEvent(boundModalSessionId, items)
                    handleRecognitionResult(items)
                }
            }
        }
    }

    private fun handleRecognitionResult(items: Array<CandidateListItem>) {
        if (items.isEmpty()) {
            if (!org.bitfennec.lime.core.runtime.AiModuleManager.isHandwritingReady(context)) {
                Toast.makeText(context, context.getString(R.string.handwriting_module_download_required), Toast.LENGTH_SHORT).show()
            } else {
                consecutiveEmptyCount++
                if (consecutiveEmptyCount >= 3) {
                    consecutiveEmptyCount = 0
                    Toast.makeText(context, context.getString(R.string.handwriting_empty_hint), Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            consecutiveEmptyCount = 0
        }
    }

    fun onModalSessionReady() {
        if (hasPendingRecognition && mStrokes.isNotEmpty()) {
            if (mService?.currentHandwritingModalSessionId() != null) {
                recognitionData()
                updatePathDelayed()
            }
        }
    }

    fun updatePathDelayed() {
        runnable.let { handler?.removeCallbacks(it) }
        handler?.postDelayed(runnable, times)
    }

    var runnable = Runnable {
        clear()
    }

    companion object {
        const val MIN_VISIBLE_STROKE_DP = 1.5f

        fun computeStrokeMaxWidthDp(thicknessPercent: Int): Float {
            val clamped = thicknessPercent.coerceIn(0, 100)
            return max(MIN_VISIBLE_STROKE_DP, clamped * 0.4f)
        }

        fun computeStrokeMinWidthDp(thicknessPercent: Int): Float {
            val maxDp = computeStrokeMaxWidthDp(thicknessPercent)
            return max(MIN_VISIBLE_STROKE_DP, maxDp / 2f)
        }
    }
}
