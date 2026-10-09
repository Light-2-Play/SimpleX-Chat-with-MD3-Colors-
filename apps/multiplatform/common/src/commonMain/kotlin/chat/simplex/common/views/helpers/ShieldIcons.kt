package chat.simplex.common.views.helpers

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val ShieldShieldBoltIcon: ImageVector by lazy {
  ImageVector.Builder(
    name = "ShieldShieldBolt",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
  ).apply {
    // 1. Внешний щит (контур)
    path(
      stroke = SolidColor(Color.White),
      strokeLineWidth = 1.5f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round
    ) {
      moveTo(12f, 2f)
      lineTo(4f, 5.5f)
      verticalLineTo(11f)
      curveTo(4f, 16.3f, 7.4f, 21.2f, 12f, 22.4f)
      curveTo(16.6f, 21.2f, 20f, 16.3f, 20f, 11f)
      verticalLineTo(5.5f)
      lineTo(12f, 2f)
      close()
    }

    // 2. Внутренний щит (контур)
    path(
      stroke = SolidColor(Color.White),
      strokeLineWidth = 1.2f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round
    ) {
      moveTo(12f, 5.5f)
      lineTo(6.5f, 8f)
      verticalLineTo(11.5f)
      curveTo(6.5f, 15.2f, 8.8f, 18.7f, 12f, 19.7f)
      curveTo(15.2f, 18.7f, 17.5f, 15.2f, 17.5f, 11.5f)
      verticalLineTo(8f)
      lineTo(12f, 5.5f)
      close()
    }

    // 3. Молния по центру
    path(
      fill = SolidColor(Color.White)
    ) {
      moveTo(12.3f, 8.5f)
      lineTo(9.5f, 12.5f)
      lineTo(11.7f, 12.5f)
      lineTo(10.9f, 17.0f)
      lineTo(15.0f, 11.5f)
      lineTo(12.5f, 11.5f)
      lineTo(13.7f, 8.5f)
      close()
    }
  }.build()
}
