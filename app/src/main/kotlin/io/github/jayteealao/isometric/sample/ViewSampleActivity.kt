package io.github.jayteealao.isometric.sample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Pyramid
import io.github.jayteealao.isometric.shapes.Stairs
import io.github.jayteealao.isometric.view.IsometricView

class ViewSampleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val isometricView: IsometricView = findViewById(R.id.isometricView)
        isometricView.setClickListener(object : IsometricView.OnItemClickListener {
            override fun onClick(item: io.github.jayteealao.isometric.RenderCommand) {}
        })
        buildScene(isometricView)
    }

    private fun buildScene(view: IsometricView) {
        val blue = IsoColor(33.0, 150.0, 243.0)

        view.clear()
        view.add(Prism(position = Point(1.0, -1.0, 0.0), width = 4.0, depth = 5.0, height = 2.0), blue)
        view.add(Prism(position = Point(0.0, 0.0, 0.0), width = 1.0, depth = 4.0, height = 1.0), blue)
        view.add(Prism(position = Point(-1.0, 1.0, 0.0), width = 1.0, depth = 3.0, height = 1.0), blue)
        view.add(Stairs(position = Point(-1.0, 0.0, 0.0), stepCount = 10), blue)
        view.add(
            Stairs(position = Point(0.0, 3.0, 1.0), stepCount = 10)
                .rotateZ(Point(0.5, 3.5, 1.0), -Math.PI / 2),
            blue
        )
        view.add(Prism(position = Point(3.0, 0.0, 2.0), width = 2.0, depth = 4.0, height = 1.0), blue)
        view.add(Prism(position = Point(2.0, 1.0, 2.0), width = 1.0, depth = 3.0, height = 1.0), blue)
        view.add(
            Stairs(position = Point(2.0, 0.0, 2.0), stepCount = 10)
                .rotateZ(Point(2.5, 0.5, 0.0), -Math.PI / 2),
            blue
        )
        view.add(Pyramid(position = Point(2.0, 3.0, 3.0)).scale(Point(2.0, 4.0, 3.0), 0.5), IsoColor(180.0, 180.0, 0.0))
        view.add(Pyramid(position = Point(4.0, 3.0, 3.0)).scale(Point(5.0, 4.0, 3.0), 0.5), IsoColor(180.0, 0.0, 180.0))
        view.add(Pyramid(position = Point(4.0, 1.0, 3.0)).scale(Point(5.0, 1.0, 3.0), 0.5), IsoColor(0.0, 180.0, 180.0))
        view.add(Pyramid(position = Point(2.0, 1.0, 3.0)).scale(Point(2.0, 1.0, 3.0), 0.5), IsoColor(40.0, 180.0, 40.0))
        view.add(Prism(position = Point(3.0, 2.0, 3.0), width = 1.0, depth = 1.0, height = 0.2), IsoColor(50.0, 50.0, 50.0))
        view.add(Octahedron(position = Point(3.0, 2.0, 3.2)), IsoColor(0.0, 180.0, 180.0))
    }
}
