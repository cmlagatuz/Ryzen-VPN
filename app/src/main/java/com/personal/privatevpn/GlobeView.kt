package com.personal.privatevpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import kotlin.math.*

class GlobeView(context: Context) : View(context) {
    private data class Land(val country: Int, val rings: List<List<Pair<Double, Double>>>)
    private val lands: List<Land>
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hitRegions = mutableListOf<Pair<Int, Region>>()
    private var longitude = 30.0
    private var latitude = 20.0
    private var spin: ValueAnimator? = null
    private var downX = 0f
    private var downY = 0f
    private var previousX = 0f
    private var previousY = 0f
    private var moved = false
    var selected = 0
    var liveCountry = -1
    var onCountry: (Int) -> Unit = {}
    private val centers = listOf(39.0 to 35.0, -14.0 to -51.0, 49.0 to 32.0)

    init {
        contentDescription = "Interactive globe. Drag to rotate. Tap Türkiye, Brazil or Ukraine, or use the country selector below."
        val data = JSONArray(context.assets.open("world.json").bufferedReader().use { it.readText() })
        lands = (0 until data.length()).map { i ->
            val item = data.getJSONObject(i)
            val rings = item.getJSONArray("rings")
            Land(listOf("TUR", "BRA", "UKR").indexOf(item.getString("code")),
                (0 until rings.length()).map { r ->
                    val ring = rings.getJSONArray(r)
                    (0 until ring.length()).map { p ->
                        val point = ring.getJSONArray(p)
                        point.getDouble(1) to point.getDouble(0)
                    }
                })
        }
    }

    fun focusCountry(country: Int) {
        selected = country
        spin?.cancel()
        val startLon = longitude
        val startLat = latitude
        val end = centers[country]
        val delta = ((end.second - startLon + 540) % 360) - 180
        spin = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 850
            addUpdateListener {
                val t = it.animatedValue as Float
                longitude = startLon + delta * t
                latitude = startLat + (end.first - startLat) * t
                invalidate()
            }
            start()
        }
    }

    private fun project(lat: Double, lon: Double): DoubleArray {
        val phi = Math.toRadians(lat)
        val theta = Math.toRadians(lon - longitude)
        val tilt = Math.toRadians(latitude)
        return doubleArrayOf(cos(phi) * sin(theta),
            cos(tilt) * sin(phi) - sin(tilt) * cos(phi) * cos(theta),
            sin(tilt) * sin(phi) + cos(tilt) * cos(phi) * cos(theta))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * .43f
        paint.shader = RadialGradient(cx, cy, radius * 1.18f,
            intArrayOf(Color.rgb(15, 43, 64), Color.rgb(8, 22, 36), Color.TRANSPARENT),
            floatArrayOf(0f, .85f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * 1.18f, paint)
        paint.shader = null
        val clip = Region((cx-radius).toInt(), (cy-radius).toInt(), (cx+radius).toInt(), (cy+radius).toInt())
        hitRegions.clear()
        // Clip each country ring against the front hemisphere before projecting.
        for (land in lands) {
            for (ring in land.rings) {
                val points = ring.map { project(it.first, it.second) }
                val front = mutableListOf<DoubleArray>()
                var a = points.last()
                for (b in points) {
                    if ((a[2] >= 0) != (b[2] >= 0)) {
                        val t = a[2] / (a[2] - b[2])
                        front.add(doubleArrayOf(a[0] + t*(b[0]-a[0]), a[1] + t*(b[1]-a[1]), 0.0))
                    }
                    if (b[2] >= 0) front.add(b)
                    a = b
                }
                if (front.size < 3) continue
                val path = Path()
                front.forEachIndexed { i, p ->
                    val x = cx + radius * p[0].toFloat()
                    val y = cy - radius * p[1].toFloat()
                    if (i == 0) path.moveTo(x,y) else path.lineTo(x,y)
                }
                path.close()
                paint.style = Paint.Style.FILL
                paint.color = when (land.country) {
                    -1 -> Color.rgb(47, 59, 72)
                    selected -> Color.rgb(39, 198, 193)
                    else -> Color.rgb(49, 118, 134)
                }
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = resources.displayMetrics.density * .5f
                paint.color = Color.rgb(11, 24, 36)
                canvas.drawPath(path, paint)
                if (land.country >= 0) hitRegions.add(land.country to Region().apply { setPath(path, clip) })
            }
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        paint.color = Color.rgb(38, 85, 105)
        canvas.drawCircle(cx, cy, radius, paint)
        for ((index, center) in centers.withIndex()) {
            val p = project(center.first, center.second)
            if (p[2] < 0) continue
            val x = cx + radius*p[0].toFloat()
            val y = cy - radius*p[1].toFloat()
            paint.color = if (index == liveCountry) Color.rgb(70,235,158) else Color.rgb(126,224,225)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x,y,4 * resources.displayMetrics.density,paint)
            if (index == liveCountry) {
                val phase = (android.os.SystemClock.uptimeMillis() % 1800) / 1800f
                paint.style = Paint.Style.STROKE
                paint.alpha = ((1-phase)*200).toInt()
                canvas.drawCircle(x,y,(8 + phase*20)*resources.displayMetrics.density,paint)
                paint.alpha = 255
            }
        }
        paint.style = Paint.Style.FILL
        if (liveCountry >= 0 && isShown) postInvalidateDelayed(33)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                spin?.cancel()
                downX = event.x; downY = event.y
                previousX = event.x; previousY = event.y; moved = false
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x-downX,event.y-downY) > 10*resources.displayMetrics.density) moved = true
                longitude = (longitude - (event.x-previousX)*.5 + 360) % 360
                latitude = (latitude + (event.y-previousY)*.4).coerceIn(-75.0,75.0)
                previousX = event.x; previousY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(false)
                if (!moved) {
                    performClick()
                    hitRegions.lastOrNull { it.second.contains(event.x.toInt(),event.y.toInt()) }
                        ?.let { onCountry(it.first) }
                }
            }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { spin?.cancel(); super.onDetachedFromWindow() }
}
