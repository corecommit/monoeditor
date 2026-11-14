package dev.mono.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Mono's icon set: hand-drawn 24dp line icons (stroke only, round caps).
 * No icon dependency — everything the app shows is defined right here.
 */
object MonoIcons {

    private var counter = 0

    private fun vector(block: ImageVector.Builder.() -> Unit): ImageVector {
        counter++
        return ImageVector.Builder(
            name = "mono_icon_$counter",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()
    }

    private fun ImageVector.Builder.stroke(vararg nodes: PathNode) {
        addPath(
            pathData = nodes.toList(),
            fill = SolidColor(Color.Unspecified),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
    }

    private fun m(x: Float, y: Float) = PathNode.MoveTo(x, y)
    private fun l(x: Float, y: Float) = PathNode.LineTo(x, y)
    private fun h(v: Float) = PathNode.HorizontalTo(v)
    private fun v(v: Float) = PathNode.VerticalTo(v)
    private fun c(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
        PathNode.CurveTo(x1, y1, x2, y2, x, y)
    private val close = PathNode.Close

    val Play: ImageVector by lazy { vector { stroke(m(8f, 5f), l(19f, 12f), l(8f, 19f), close) } }

    val Stop: ImageVector by lazy { vector { stroke(m(7f, 7f), h(17f), v(17f), h(7f), close) } }

    val Undo: ImageVector by lazy {
        vector {
            stroke(m(8f, 6f), l(4f, 10f), l(8f, 14f))
            stroke(m(4f, 10f), l(14f, 10f), c(17f, 10f, 19f, 12f, 19f, 15f), v(18f))
        }
    }

    val Redo: ImageVector by lazy {
        vector {
            stroke(m(16f, 6f), l(20f, 10f), l(16f, 14f))
            stroke(m(20f, 10f), l(10f, 10f), c(7f, 10f, 5f, 12f, 5f, 15f), v(18f))
        }
    }

    val Search: ImageVector by lazy {
        vector {
            stroke(
                m(11f, 5f),
                c(14.31f, 5f, 17f, 7.69f, 17f, 11f),
                c(17f, 14.31f, 14.31f, 17f, 11f, 17f),
                c(7.69f, 17f, 5f, 14.31f, 5f, 11f),
                c(5f, 7.69f, 7.69f, 5f, 11f, 5f)
            )
            stroke(m(15.5f, 15.5f), l(20f, 20f))
        }
    }

    val Close: ImageVector by lazy { vector { stroke(m(6f, 6f), l(18f, 18f), m(18f, 6f), l(6f, 18f)) } }

    val Plus: ImageVector by lazy { vector { stroke(m(12f, 5f), v(19f), m(5f, 12f), h(19f)) } }

    val More: ImageVector by lazy {
        vector {
            stroke(m(12f, 6f), l(12.01f, 6f))
            stroke(m(12f, 12f), l(12.01f, 12f))
            stroke(m(12f, 18f), l(12.01f, 18f))
        }
    }

    /** Modules drawer: three indented lines. */
    val Modules: ImageVector by lazy {
        vector {
            stroke(m(4f, 6f), h(20f))
            stroke(m(4f, 12f), h(15f))
            stroke(m(4f, 18f), h(10f))
        }
    }

    /** Settings: two sliders. */
    val Settings: ImageVector by lazy {
        vector {
            stroke(m(3f, 8f), h(21f))
            stroke(m(3f, 16f), h(21f))
            stroke(m(9f, 8f), l(9.01f, 8f))
            stroke(m(15f, 16f), l(15.01f, 16f))
        }
    }

    val ChevronLeft: ImageVector by lazy { vector { stroke(m(14f, 6f), l(8f, 12f), l(14f, 18f)) } }

    val ChevronRight: ImageVector by lazy { vector { stroke(m(10f, 6f), l(16f, 12f), l(10f, 18f)) } }

    val ChevronUp: ImageVector by lazy { vector { stroke(m(6f, 14f), l(12f, 8f), l(18f, 14f)) } }

    val ChevronDown: ImageVector by lazy { vector { stroke(m(6f, 10f), l(12f, 16f), l(18f, 10f)) } }

    val File: ImageVector by lazy {
        vector {
            stroke(m(6f, 3f), l(16f, 3f), l(19f, 6f), l(19f, 21f), l(6f, 21f), close)
            stroke(m(16f, 3f), v(6f), h(3f))
        }
    }

    val Folder: ImageVector by lazy {
        vector { stroke(m(3f, 6f), h(8f), l(11f, 9f), h(10f), v(11f), l(3f, 20f), close) }
    }

    val Terminal: ImageVector by lazy {
        vector {
            stroke(m(3f, 5f), h(21f), v(19f), h(3f), close)
            stroke(m(7f, 10f), l(10f, 13f), l(7f, 16f))
            stroke(m(12f, 16f), h(5f))
        }
    }

    val Trash: ImageVector by lazy {
        vector {
            stroke(m(4f, 7f), h(20f))
            stroke(m(9f, 7f), l(9f, 4f), h(15f), l(15f, 7f))
            stroke(m(6f, 7f), l(7f, 21f), h(17f), l(18f, 7f))
        }
    }

    val Pencil: ImageVector by lazy {
        vector { stroke(m(4f, 20f), l(5f, 16f), l(16f, 5f), l(19f, 8f), l(8f, 19f), close) }
    }

    val Import: ImageVector by lazy {
        vector {
            stroke(m(12f, 4f), v(12f))
            stroke(m(8f, 10f), l(12f, 14f), l(16f, 10f))
            stroke(m(5f, 17f), l(5f, 20f), h(14f), l(19f, 20f), l(19f, 17f))
        }
    }

    /** Focus mode: a target. */
    val Focus: ImageVector by lazy {
        vector {
            stroke(
                m(12f, 5f),
                c(16.28f, 5f, 19f, 8.13f, 19f, 12f),
                c(19f, 15.87f, 16.28f, 19f, 12f, 19f),
                c(7.72f, 19f, 5f, 15.87f, 5f, 12f),
                c(5f, 8.13f, 7.72f, 5f, 12f, 5f)
            )
            stroke(
                m(12f, 9.5f),
                c(13.38f, 9.5f, 14.5f, 10.62f, 14.5f, 12f),
                c(14.5f, 13.38f, 13.38f, 14.5f, 12f, 14.5f),
                c(10.62f, 14.5f, 9.5f, 13.38f, 9.5f, 12f),
                c(9.5f, 10.62f, 10.62f, 9.5f, 12f, 9.5f)
            )
        }
    }

    val Check: ImageVector by lazy { vector { stroke(m(5f, 13f), l(10f, 18f), l(19f, 7f)) } }

    val Save: ImageVector by lazy {
        vector {
            stroke(m(4f, 4f), l(18f, 4f), l(20f, 6f), l(20f, 20f), l(4f, 20f), close)
            stroke(m(15f, 4f), v(6f), h(5f))
            stroke(m(8f, 20f), l(8f, 14f), h(14f), l(14f, 20f))
        }
    }

    val ThemeToggle: ImageVector by lazy {
        vector {
            stroke(
                m(12f, 4f),
                c(16.42f, 4f, 20f, 7.58f, 20f, 12f),
                c(20f, 16.42f, 16.42f, 20f, 12f, 20f),
                c(7.58f, 20f, 4f, 16.42f, 4f, 12f)
            )
            stroke(m(12f, 4f), v(20f))
        }
    }
}
