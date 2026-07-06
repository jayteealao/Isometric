package io.github.jayteealao.isometric.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.github.jayteealao.isometric.*

/**
 * Android View for rendering isometric 3D scenes.
 * Refactored to use IsometricEngine from :isometric-core
 *
 * ## Mutation and redraw contract
 *
 * Scene-affecting mutations ([add], [clear], [setSort], [setCull], [setBoundsCheck],
 * [setStrokeStyle]) mark the scene dirty and call [invalidate]. On the next [onDraw]
 * call the scene is re-projected exactly once — batching any number of preceding
 * mutations into a single projection per frame.
 *
 * Hit-test-only setters ([setHitOrder], [setTouchRadius]) adjust hit-test parameters
 * only. They do **not** mark the scene dirty or call [invalidate] because rendered
 * output is unaffected.
 */
class IsometricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    // sdlc-debt: internal test seam — injected projector for projection-count assertions;
    // upgrade path: a proper DI mechanism or a @VisibleForTesting companion factory if
    // the module ever adopts Hilt/Koin.
    internal val engine: SceneProjector = IsometricEngine()
) : View(context, attrs, defStyleAttr) {

    interface OnItemClickListener {
        fun onClick(item: RenderCommand)
    }

    private var listener: OnItemClickListener? = null

    private var renderOptions = RenderOptions.Default
    private var hitOrder = HitOrder.BACK_TO_FRONT
    private var touchRadius = 0.0
    private var strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke()

    // sdlc-debt: test-visible via internal accessor; upgrade path: remove once
    // a Robolectric hardware-canvas mode or instrumented smoke makes pixel-level
    // assertions feasible in the fast-test layer.
    internal var cachedScene: PreparedScene? = null
        private set

    /**
     * True when the scene graph or render options have changed since the last
     * [projectScene] call. [onDraw] re-projects exactly once when this is true,
     * then clears the flag — batching all preceding mutations into one projection
     * per frame regardless of how many [add]/setter calls occurred.
     */
    private var sceneDirty = true

    /**
     * Enable/disable depth sorting.
     *
     * Marks the scene dirty and calls [invalidate]; the re-projection occurs on
     * the next [onDraw].
     */
    fun setSort(sort: Boolean) {
        renderOptions = renderOptions.copy(enableDepthSorting = sort)
        sceneDirty = true
        invalidate()
    }

    /**
     * Enable/disable back-face culling (improves performance).
     * Paths must be defined in counter-clockwise rotation order.
     *
     * Marks the scene dirty and calls [invalidate]; the re-projection occurs on
     * the next [onDraw].
     */
    fun setCull(cull: Boolean) {
        renderOptions = renderOptions.copy(enableBackfaceCulling = cull)
        sceneDirty = true
        invalidate()
    }

    /**
     * Enable/disable bounds checking (improves performance).
     *
     * Marks the scene dirty and calls [invalidate]; the re-projection occurs on
     * the next [onDraw].
     */
    fun setBoundsCheck(boundsCheck: Boolean) {
        renderOptions = renderOptions.copy(enableBoundsChecking = boundsCheck)
        sceneDirty = true
        invalidate()
    }

    /**
     * Configure hit-test ordering.
     *
     * This setter affects only hit-testing behaviour; rendered output is unchanged.
     * It does **not** mark the scene dirty or call [invalidate].
     */
    fun setHitOrder(hitOrder: HitOrder) {
        this.hitOrder = hitOrder
    }

    /**
     * Radius of circular touch region in screen pixels.
     *
     * This setter affects only hit-testing behaviour; rendered output is unchanged.
     * It does **not** mark the scene dirty or call [invalidate].
     */
    fun setTouchRadius(touchRadius: Double) {
        this.touchRadius = touchRadius
    }

    /**
     * Set click listener.
     */
    fun setClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    /**
     * Set the stroke style used when rendering faces.
     *
     * Mirrors the semantics of the Compose [StrokeStyle] type without introducing
     * a cross-module dependency. See [StrokeStyle] for the available variants.
     *
     * Marks the scene dirty and calls [invalidate]; the re-projection occurs on
     * the next [onDraw].
     */
    fun setStrokeStyle(style: StrokeStyle) {
        strokeStyle = style
        sceneDirty = true
        invalidate()
    }

    /**
     * Clear all items from the scene.
     *
     * Marks the scene dirty and calls [invalidate]; the next draw will render an
     * empty scene.
     */
    fun clear() {
        engine.clear()
        sceneDirty = true
        invalidate()
    }

    /**
     * Add a path to the scene.
     *
     * Marks the scene dirty and calls [invalidate]; the path renders on the next
     * draw without requiring a layout pass.
     */
    fun add(path: Path, color: IsoColor) {
        engine.add(path, color, null)
        sceneDirty = true
        invalidate()
    }

    /**
     * Add a shape to the scene.
     *
     * Marks the scene dirty and calls [invalidate]; the shape renders on the next
     * draw without requiring a layout pass.
     */
    fun add(shape: Shape, color: IsoColor) {
        engine.add(shape, color)
        sceneDirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sceneDirty = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Projection no longer happens here — moved to onDraw so post-layout
        // mutations are reflected on the next frame without a resize pass.
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (sceneDirty || cachedScene == null) {
            val w = width
            val h = height
            if (w > 0 && h > 0) {
                cachedScene = engine.projectScene(w, h, renderOptions)
                sceneDirty = false
            }
        }

        cachedScene?.let { scene ->
            AndroidCanvasRenderer.renderIsometric(canvas, scene, strokeStyle)
        }
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (listener != null) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_UP -> {
                    cachedScene?.let { scene ->
                        val item = engine.findItemAt(
                            preparedScene = scene,
                            x = event.x.toDouble(),
                            y = event.y.toDouble(),
                            order = hitOrder,
                            touchRadius = touchRadius
                        )

                        item?.let {
                            listener?.onClick(it)
                        }
                    }
                    performClick()
                    // Return true: a click listener is registered so this gesture
                    // is always consumed (L7 fix — previously fell through to
                    // super.onTouchEvent which returned false for non-clickable Views).
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
