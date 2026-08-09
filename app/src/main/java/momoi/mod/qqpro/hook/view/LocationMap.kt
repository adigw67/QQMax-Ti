package momoi.mod.qqpro.hook.view

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.widget.ImageView
import momoi.mod.qqpro.child
import momoi.mod.qqpro.safeCacheDir
import momoi.mod.qqpro.util.Utils
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Renders a static map thumbnail centered on (lat,lng) by stitching raw OSM
 * tiles — no API key required. The keyless static-map services (e.g. the old
 * staticmap.openstreetmap.de) are dead, but the tile server
 * tile.openstreetmap.org/{z}/{x}/{y}.png is alive and only requires an
 * identifiable User-Agent (OSM tile usage policy), which `download` does NOT
 * set — hence this dedicated loader.
 */
private val mapExecutor = Executors.newFixedThreadPool(2) { r ->
    Thread(r, "qqpro-osm-map").apply { isDaemon = true }
}

private const val TILE = 256
private const val USER_AGENT = "QQProWatch/1.0 (qqpro mod; +https://github.com/momoi)"

fun ImageView.loadOsmStaticMap(
    lat: Double,
    lng: Double,
    zoom: Int = 15,
    outW: Int = 400,
    outH: Int = 200,
    cacheKey: String,
) = apply {
    val dir = context.safeCacheDir ?: return@apply
    val cacheFile = dir.child("$cacheKey.png")
    if (cacheFile.exists()) {
        Utils.log("OSM map from cache ${cacheFile.path}")
        BitmapFactory.decodeFile(cacheFile.path)?.let { setImageBitmap(it) }
        return@apply
    }
    mapExecutor.execute {
        try {
            val bmp = renderMap(lat, lng, zoom, outW, outH)
            if (bmp == null) {
                Utils.log("OSM map render failed for $lat,$lng")
                return@execute
            }
            cacheFile.parentFile?.mkdirs()
            cacheFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            post { setImageBitmap(bmp) }
        } catch (e: Exception) {
            Utils.log("OSM map exception: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }
}

private fun renderMap(lat: Double, lng: Double, zoom: Int, outW: Int, outH: Int): Bitmap? {
    val n = (1 shl zoom).toDouble()
    val latRad = Math.toRadians(lat)
    // Web-Mercator global pixel coords of the center point.
    val cx = (lng + 180.0) / 360.0 * n * TILE
    val cy = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n * TILE
    val left = cx - outW / 2.0
    val top = cy - outH / 2.0
    val tileX0 = floor(left / TILE).toInt()
    val tileY0 = floor(top / TILE).toInt()
    val tileX1 = floor((cx + outW / 2.0) / TILE).toInt()
    val tileY1 = floor((cy + outH / 2.0) / TILE).toInt()
    val maxIdx = (1 shl zoom) - 1

    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(Color.parseColor("#E8E8E8"))

    var got = 0
    for (tx in tileX0..tileX1) {
        for (ty in tileY0..tileY1) {
            if (tx < 0 || ty < 0 || tx > maxIdx || ty > maxIdx) continue
            val tile = downloadTile(zoom, tx, ty) ?: continue
            canvas.drawBitmap(tile, (tx * TILE - left).toFloat(), (ty * TILE - top).toFloat(), null)
            tile.recycle()
            got++
        }
    }
    if (got == 0) return null

    // Marker pin at the center.
    val mx = outW / 2f
    val my = outH / 2f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = Color.parseColor("#E53935")
    canvas.drawCircle(mx, my - 10f, 8f, p)
    val path = Path().apply {
        moveTo(mx - 6f, my - 6f)
        lineTo(mx + 6f, my - 6f)
        lineTo(mx, my + 4f)
        close()
    }
    canvas.drawPath(path, p)
    p.color = Color.WHITE
    canvas.drawCircle(mx, my - 10f, 3f, p)
    return out
}

private fun downloadTile(z: Int, x: Int, y: Int): Bitmap? {
    val url = URL("https://tile.openstreetmap.org/$z/$x/$y.png")
    var conn: HttpURLConnection? = null
    return try {
        conn = (url.openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 30_000
            readTimeout = 15_000
            requestMethod = "GET"
        }
        conn.connect()
        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } else {
            Utils.log("OSM tile $z/$x/$y http=${conn.responseCode}")
            null
        }
    } catch (e: Exception) {
        Utils.log("OSM tile $z/$x/$y err: ${e.javaClass.simpleName}: ${e.message}")
        null
    } finally {
        conn?.disconnect()
    }
}
