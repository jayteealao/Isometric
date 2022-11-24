package io.fabianterhorst.isometric

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.tooling.preview.Preview
import io.fabianterhorst.isometric.shapes.Octahedron
import io.fabianterhorst.isometric.shapes.Prism
import io.fabianterhorst.isometric.shapes.Pyramid
import io.fabianterhorst.isometric.shapes.Stairs

@Composable
fun IsometricCompose(
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.fillMaxSize()
    ) {

        val isometric = Isometric()
        isometric.add(Prism(Point(1.0, -1.0, 0.0), 4.0, 5.0, 2.0), Color(33.0, 150.0, 243.0))
        isometric.add(Prism(Point(0.0, 0.0, 0.0), 1.0, 4.0, 1.0), Color(33.0, 150.0, 243.0))
        isometric.add(Prism(Point(-1.0, 1.0, 0.0), 1.0, 3.0, 1.0), Color(33.0, 150.0, 243.0))
        isometric.add(Stairs(Point(-1.0, 0.0, 0.0), 10.0), Color(33.0, 150.0, 243.0))
        isometric.add(
            Stairs(Point(0.0, 3.0, 1.0), 10.0).rotateZ(
                Point(0.5, 3.5, 1.0),
                -Math.PI / 2
            ), Color(33.0, 150.0, 243.0)
        )
        isometric.add(Prism(Point(3.0, 0.0, 2.0), 2.0, 4.0, 1.0), Color(33.0, 150.0, 243.0))
        isometric.add(Prism(Point(2.0, 1.0, 2.0), 1.0, 3.0, 1.0), Color(33.0, 150.0, 243.0))
        isometric.add(
            Stairs(Point(2.0, 0.0, 2.0), 10.0).rotateZ(
                Point(2.5, 0.5, 0.0),
                -Math.PI / 2
            ), Color(33.0, 150.0, 243.0)
        )
        isometric.add(
            Pyramid(Point(2.0, 3.0, 3.0)).scale(Point(2.0, 4.0, 3.0), 0.5),
            Color(180.0, 180.0, 0.0)
        )
        isometric.add(
            Pyramid(Point(4.0, 3.0, 3.0)).scale(Point(5.0, 4.0, 3.0), 0.5),
            Color(180.0, 0.0, 180.0)
        )
        isometric.add(
            Pyramid(Point(4.0, 1.0, 3.0)).scale(Point(5.0, 1.0, 3.0), 0.5),
            Color(0.0, 180.0, 180.0)
        )
        isometric.add(
            Pyramid(Point(2.0, 1.0, 3.0)).scale(Point(2.0, 1.0, 3.0), 0.5),
            Color(40.0, 180.0, 40.0)
        )
        isometric.add(Prism(Point(3.0, 2.0, 3.0), 1.0, 1.0, 0.2), Color(50.0, 50.0, 50.0))
        isometric.add(
            Octahedron(Point(3.0, 2.0, 3.2)).rotateZ(Point(3.5, 2.5, 0.0), 0.0),
            Color(0.0, 180.0, 180.0)
        )
        isometric.measure(size.width.toInt(), size.height.toInt(), false, false, false)
        drawIntoCanvas {
            isometric.draw(it.nativeCanvas)
        }
    }
}

@Preview
@Composable
fun PreviewIsometric() {
    IsometricCompose()
}