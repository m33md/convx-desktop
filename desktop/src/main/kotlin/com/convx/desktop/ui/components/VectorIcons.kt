/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.w3c.dom.Element
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The phone app's own vector drawables (copied into resources/icons), parsed into
 * ImageVectors so the desktop shows the exact same glyphs.
 */
object VectorIcons {
    private val cache = ConcurrentHashMap<String, ImageVector>()
    private const val Ns = "http://schemas.android.com/apk/res/android"

    fun get(name: String): ImageVector = cache.getOrPut(name) { parse(name) }

    private fun parse(name: String): ImageVector {
        val stream = VectorIcons::class.java.classLoader.getResourceAsStream("icons/$name.xml")
            ?: error("Missing icon $name")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val root = stream.use { factory.newDocumentBuilder().parse(it).documentElement }
        fun attr(e: Element, key: String): String? = e.getAttributeNS(Ns, key).takeIf { it.isNotEmpty() }
        fun dp(value: String?) = value?.removeSuffix("dp")?.toFloatOrNull() ?: 24f
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = dp(attr(root, "width")).dp,
            defaultHeight = dp(attr(root, "height")).dp,
            viewportWidth = attr(root, "viewportWidth")?.toFloatOrNull() ?: 24f,
            viewportHeight = attr(root, "viewportHeight")?.toFloatOrNull() ?: 24f,
        )
        val paths = root.getElementsByTagName("path")
        for (i in 0 until paths.length) {
            val p = paths.item(i) as Element
            val data = attr(p, "pathData") ?: continue
            val fill = parseColor(attr(p, "fillColor"))
            val stroke = parseColor(attr(p, "strokeColor"))
            builder.addPath(
                pathData = addPathNodes(data),
                pathFillType = if (attr(p, "fillType") == "evenOdd") PathFillType.EvenOdd else PathFillType.NonZero,
                fill = fill?.takeIf { it.alpha > 0f }?.let(::SolidColor),
                fillAlpha = attr(p, "fillAlpha")?.toFloatOrNull() ?: 1f,
                stroke = stroke?.takeIf { it.alpha > 0f }?.let(::SolidColor),
                strokeAlpha = attr(p, "strokeAlpha")?.toFloatOrNull() ?: 1f,
                strokeLineWidth = attr(p, "strokeWidth")?.toFloatOrNull() ?: 0f,
                strokeLineCap = when (attr(p, "strokeLineCap")) {
                    "round" -> StrokeCap.Round
                    "square" -> StrokeCap.Square
                    else -> StrokeCap.Butt
                },
                strokeLineJoin = when (attr(p, "strokeLineJoin")) {
                    "round" -> StrokeJoin.Round
                    "bevel" -> StrokeJoin.Bevel
                    else -> StrokeJoin.Miter
                },
            )
        }
        return builder.build()
    }

    private fun parseColor(value: String?): Color? {
        // Resource colours only appear on glyphs the tint recolours anyway.
        if (value != null && (value.startsWith("@") || value.startsWith("?"))) {
            return if (value.endsWith("transparent")) null else Color.White
        }
        val hex = value?.removePrefix("#") ?: return null
        val argb = when (hex.length) {
            8 -> hex.toLongOrNull(16)
            6 -> hex.toLongOrNull(16)?.or(0xFF000000)
            4 -> hex.map { "$it$it" }.joinToString("").toLongOrNull(16)
            3 -> ("FF" + hex.map { "$it$it" }.joinToString("")).toLongOrNull(16)
            else -> null
        } ?: return null
        return Color(argb.toInt())
    }
}

/** An icon from the phone's drawable set, tinted like any Material icon. */
@Composable
fun ConvxIcon(
    name: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    contentDescription: String? = null,
) {
    Icon(VectorIcons.get(name), contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}

/**
 * The Convx wordmark, Aryan's own lettering (2026-11-06), traced into a vector so it stays sharp
 * at any size. Drawn in its own grey unless [tint] says otherwise.
 */
@Composable
fun ConvxWordmark(height: Dp, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    val vector = VectorIcons.get("convx_wordmark")
    Icon(
        vector,
        contentDescription = "Convx",
        tint = tint,
        modifier = modifier.height(height).width(height * (vector.viewportWidth / vector.viewportHeight)),
    )
}
