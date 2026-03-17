---
title: Animation Patterns
description: Copy-paste recipes for common animations
sidebar:
  order: 2
---

Each recipe below is a self-contained `@Composable` function. All animations use `withFrameNanos` for vsync-aligned updates.

## Spinning Octahedron

Continuously rotates an octahedron around its center point.

```kotlin
@Composable
fun SpinningOctahedron() {
    var angle by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { angle += 0.02 }
        }
    }

    IsometricScene {
        Shape(
            geometry = Octahedron(Point.ORIGIN),
            color = IsoColor(156, 39, 176),
            rotation = angle,
            rotationOrigin = Point(0.5, 0.5, 0.5)
        )
    }
}
```

The `rotationOrigin` is set to the octahedron's center so it spins in place rather than orbiting the origin.

## Wave Grid

A 5x5 grid of prisms whose heights oscillate using `sin` with phase offsets per cell.

```kotlin
@Composable
fun WaveGrid() {
    var time by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { time += 0.03 }
        }
    }

    IsometricScene {
        ForEach((0 until 5).toList()) { x ->
            ForEach((0 until 5).toList()) { y ->
                val height = 0.5 + sin(time + x * 0.6 + y * 0.4) * 0.4
                Shape(
                    geometry = Prism(
                        Point(x.toDouble(), y.toDouble(), 0.0),
                        1.0, 1.0, height
                    ),
                    color = IsoColor(33, 150, 243)
                )
            }
        }
    }
}
```

Adjusting the multipliers on `x` and `y` inside `sin` changes the wave direction and wavelength.

## Color Cycling

Cycles through hues over time by computing RGB channels from `sin` functions with phase offsets.

```kotlin
@Composable
fun ColorCycling() {
    var time by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { time += 0.02 }
        }
    }

    IsometricScene {
        val r = (sin(time) * 127.5 + 127.5).coerceIn(0.0, 255.0)
        val g = (sin(time + 2.094) * 127.5 + 127.5).coerceIn(0.0, 255.0)
        val b = (sin(time + 4.189) * 127.5 + 127.5).coerceIn(0.0, 255.0)

        Shape(
            geometry = Prism(Point.ORIGIN, 2.0, 2.0, 2.0),
            color = IsoColor(r, g, b)
        )
    }
}
```

The three `sin` calls are offset by `2*PI/3` radians (approximately 2.094) so the red, green, and blue channels are evenly spaced around the cycle.

## Pulsing Scale

A prism that smoothly grows and shrinks using a sin-wave scale factor.

```kotlin
@Composable
fun PulsingScale() {
    var time by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { time += 0.03 }
        }
    }

    IsometricScene {
        val scaleFactor = 0.8 + sin(time) * 0.3

        Shape(
            geometry = Prism(Point.ORIGIN, 2.0, 2.0, 2.0),
            color = IsoColor(0, 200, 100),
            scale = scaleFactor,
            scaleOrigin = Point(1.0, 1.0, 1.0)
        )
    }
}
```

Setting `scaleOrigin` to the center of the prism makes it pulse uniformly in all directions rather than scaling from one corner.

## Orbiting Shapes

Three small cubes orbit a central shape using `cos`/`sin` to compute their positions.

```kotlin
@Composable
fun OrbitingShapes() {
    var time by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { time += 0.02 }
        }
    }

    IsometricScene {
        // Central shape
        Shape(
            geometry = Prism(Point(-0.5, -0.5, 0.0), 1.0, 1.0, 1.0),
            color = IsoColor(33, 150, 243)
        )

        // Three orbiting cubes, 120 degrees apart
        ForEach((0 until 3).toList()) { i ->
            val phase = i * 2.094 // 2*PI/3
            val orbitRadius = 2.5
            val ox = cos(time + phase) * orbitRadius
            val oy = sin(time + phase) * orbitRadius

            Shape(
                geometry = Prism(Point.ORIGIN, 0.5, 0.5, 0.5),
                color = IsoColor(255, 152, 0),
                position = Point(ox, oy, 0.5)
            )
        }
    }
}
```

Each cube is offset by `2*PI/3` radians so they are evenly spaced around the orbit.

## Staggered Entrance

Shapes appear one by one with offset timing, sliding up from below.

```kotlin
@Composable
fun StaggeredEntrance() {
    var time by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { time += 0.02 }
        }
    }

    IsometricScene {
        val items = listOf(
            Point(0.0, 0.0, 0.0) to IsoColor(33, 150, 243),
            Point(1.5, 0.0, 0.0) to IsoColor(76, 175, 80),
            Point(3.0, 0.0, 0.0) to IsoColor(255, 152, 0),
            Point(4.5, 0.0, 0.0) to IsoColor(156, 39, 176),
        )

        ForEach(items.mapIndexed { i, pair -> i to pair }.toList()) { (index, pair) ->
            val (pos, color) = pair
            val delay = index * 0.5
            val progress = ((time - delay) / 1.0).coerceIn(0.0, 1.0)

            // Ease-out cubic
            val eased = 1.0 - (1.0 - progress).let { it * it * it }

            // Slide up from below and fade in via scale
            val zOffset = (1.0 - eased) * -2.0
            val entryScale = 0.2 + eased * 0.8

            If(progress > 0.0) {
                Shape(
                    geometry = Prism(Point.ORIGIN),
                    color = color,
                    position = Point(pos.x, pos.y, pos.z + zOffset),
                    scale = entryScale,
                    scaleOrigin = Point(0.5, 0.5, 0.5)
                )
            }
        }
    }
}
```

Each shape waits `index * 0.5` time units before starting its entrance. The cubic ease-out (`1 - (1-t)^3`) produces a natural deceleration as each shape reaches its final position.

> **Tip**
>
For all animation patterns, use `remember` to cache expensive geometry objects (like `Prism` or `Cylinder`) so they are not recreated every frame. Only the changing properties (`rotation`, `position`, `scale`, `color`) should depend on the animated value.
