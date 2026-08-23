package androidx.compose.material.icons.outlined

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Compatibility icons for the Compose Material icon set used by Wisp Trader. */
public val ChatBubbleOutline: ImageVector
    get() = WispCompatIcons.ChatBubbleOutline

public val Launch: ImageVector
    get() = WispCompatIcons.Launch

public val ShowChart: ImageVector
    get() = WispCompatIcons.ShowChart

private object WispCompatIcons {
    val ChatBubbleOutline: ImageVector by lazy {
        ImageVector.Builder(
            name = "WispChatBubbleOutline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path {
            moveTo(4f, 4f)
            horizontalLineTo(20f)
            verticalLineTo(16f)
            horizontalLineTo(8f)
            lineTo(4f, 20f)
            close()
            moveTo(6f, 6f)
            verticalLineTo(14f)
            horizontalLineTo(18f)
            verticalLineTo(6f)
            close()
        }.build()
    }

    val Launch: ImageVector by lazy {
        ImageVector.Builder(
            name = "WispLaunch",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path {
            moveTo(5f, 5f)
            horizontalLineTo(10f)
            verticalLineTo(7f)
            horizontalLineTo(7f)
            verticalLineTo(17f)
            horizontalLineTo(17f)
            verticalLineTo(14f)
            horizontalLineTo(19f)
            verticalLineTo(19f)
            horizontalLineTo(5f)
            close()
            moveTo(13f, 5f)
            horizontalLineTo(19f)
            verticalLineTo(11f)
            horizontalLineTo(17f)
            verticalLineTo(8.41f)
            lineTo(10.71f, 14.71f)
            lineTo(9.29f, 13.29f)
            lineTo(15.59f, 7f)
            horizontalLineTo(13f)
            close()
        }.build()
    }

    val ShowChart: ImageVector by lazy {
        ImageVector.Builder(
            name = "WispShowChart",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path {
            moveTo(5f, 19f)
            horizontalLineTo(3f)
            verticalLineTo(5f)
            horizontalLineTo(5f)
            close()
            moveTo(21f, 19f)
            horizontalLineTo(19f)
            verticalLineTo(9f)
            horizontalLineTo(21f)
            close()
            moveTo(17f, 19f)
            horizontalLineTo(15f)
            verticalLineTo(12f)
            horizontalLineTo(17f)
            close()
            moveTo(13f, 19f)
            horizontalLineTo(11f)
            verticalLineTo(7f)
            horizontalLineTo(13f)
            close()
            moveTo(7f, 19f)
            horizontalLineTo(9f)
            verticalLineTo(10f)
            horizontalLineTo(7f)
            close()
        }.build()
    }
}
