package io.fabianterhorst.isometric.sample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.shapes.Octahedron
import io.fabianterhorst.isometric.shapes.Prism
import io.fabianterhorst.isometric.shapes.Pyramid
import io.fabianterhorst.isometric.shapes.Stairs
import io.fabianterhorst.isometric.view.IsometricView

class ViewSampleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val isometricView: IsometricView = findViewById(R.id.isometricView)
        isometricView.setClickListener(object : IsometricView.OnItemClickListener {
            override fun onClick(item: io.fabianterhorst.isometric.RenderCommand) {}
        })
        buildScene(isometricView)
    }

    private fun buildScene(view: IsometricView) {
        val blue = IsoColor(33.0, 150.0, 243.0)

        view.clear()
        view.add(Prism(Point(1.0, -1.0, 0.0), 4.0, 5.0, 2.0), blue)
        view.add(Prism(Point(0.0, 0.0, 0.0), 1.0, 4.0, 1.0), blue)
        view.add(Prism(Point(-1.0, 1.0, 0.0), 1.0, 3.0, 1.0), blue)
        view.add(Stairs(Point(-1.0, 0.0, 0.0), 10), blue)
        view.add(
            Stairs(Point(0.0, 3.0, 1.0), 10)
                .rotateZ(Point(0.5, 3.5, 1.0), -Math.PI / 2),
            blue
        )
        view.add(Prism(Point(3.0, 0.0, 2.0), 2.0, 4.0, 1.0), blue)
        view.add(Prism(Point(2.0, 1.0, 2.0), 1.0, 3.0, 1.0), blue)
        view.add(
            Stairs(Point(2.0, 0.0, 2.0), 10)
                .rotateZ(Point(2.5, 0.5, 0.0), -Math.PI / 2),
            blue
        )
        view.add(Pyramid(Point(2.0, 3.0, 3.0)).scale(Point(2.0, 4.0, 3.0), 0.5), IsoColor(180.0, 180.0, 0.0))
        view.add(Pyramid(Point(4.0, 3.0, 3.0)).scale(Point(5.0, 4.0, 3.0), 0.5), IsoColor(180.0, 0.0, 180.0))
        view.add(Pyramid(Point(4.0, 1.0, 3.0)).scale(Point(5.0, 1.0, 3.0), 0.5), IsoColor(0.0, 180.0, 180.0))
        view.add(Pyramid(Point(2.0, 1.0, 3.0)).scale(Point(2.0, 1.0, 3.0), 0.5), IsoColor(40.0, 180.0, 40.0))
        view.add(Prism(Point(3.0, 2.0, 3.0), 1.0, 1.0, 0.2), IsoColor(50.0, 50.0, 50.0))
        view.add(Octahedron(Point(3.0, 2.0, 3.2)), IsoColor(0.0, 180.0, 180.0))
    }
}
