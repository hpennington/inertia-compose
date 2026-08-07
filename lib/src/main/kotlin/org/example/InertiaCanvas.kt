package org.inertiagraphics.inertia

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.TextureView
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/// Positions arrive already normalized to the container the canvas fills, with
/// a top-left origin — the same space the Metal and WebGL runtimes hand their
/// renderers — so the only work here is the flip into clip space.
private const val SHAPE_VERTEX_SHADER = """
attribute vec2 a_position;
attribute vec4 a_color;
varying vec4 v_color;

void main() {
    v_color = a_color;
    gl_Position = vec4(a_position.x * 2.0 - 1.0, 1.0 - a_position.y * 2.0, 0.0, 1.0);
}
"""

/// Colours pass through unpremultiplied; the blend function does the
/// source-over.
private const val SHAPE_FRAGMENT_SHADER = """
precision mediump float;
varying vec4 v_color;

void main() {
    gl_FragColor = v_color;
}
"""

/// Floats per vertex in the buffer handed to GL: x, y, r, g, b, a.
private const val FLOATS_PER_VERTEX = 6
private const val BYTES_PER_FLOAT = 4

/// The surface an actionable's shapes are drawn on.
///
/// A `TextureView` rather than the more usual `GLSurfaceView`, because a
/// `SurfaceView` is a separate window layer: it composites either behind the
/// whole app window — where nothing would see it — or, with `setZOrderOnTop`,
/// in front of every Compose view, which is the opposite of a backdrop. A
/// `TextureView` draws in the view hierarchy like anything else, so it sits
/// behind the content it backs and is carried by the same layer transforms the
/// animation writes.
///
/// Rendering is on demand rather than on a loop. The shapes only change when
/// the schema or the layout does, and an actionable that idles should not hold
/// a GPU thread awake — the animation moves this whole surface as a layer
/// without redrawing a triangle of it.
internal class InertiaShapeTextureView(context: Context) : TextureView(context),
    TextureView.SurfaceTextureListener {

    /// Interleaved x, y, r, g, b, a per vertex, in container-normalized space.
    var vertexData: FloatArray = FloatArray(0)
        set(value) {
            if (field.contentEquals(value)) return
            field = value
            render()
        }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context_: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var buffer = FloatArray(0).toFloatBuffer()
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    init {
        // The whole point of the canvas is what shows through it.
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        if (createContext(texture)) {
            render()
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        render()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releaseContext()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    private fun createContext(texture: SurfaceTexture): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            InertiaLog.error("no EGL display; shapes will not be drawn")
            return false
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            InertiaLog.error("eglInitialize failed; shapes will not be drawn")
            return false
        }

        // An alpha channel, because this surface has to composite over whatever
        // the container draws behind it.
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, configCount, 0)
            || configCount[0] == 0
        ) {
            InertiaLog.error("no suitable EGL config; shapes will not be drawn")
            return false
        }

        val config = configs[0] ?: return false

        context_ = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        if (context_ == EGL14.EGL_NO_CONTEXT) {
            InertiaLog.error("eglCreateContext failed; shapes will not be drawn")
            return false
        }

        surface = EGL14.eglCreateWindowSurface(
            display,
            config,
            texture,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        if (surface == EGL14.EGL_NO_SURFACE) {
            InertiaLog.error("eglCreateWindowSurface failed; shapes will not be drawn")
            return false
        }

        if (!EGL14.eglMakeCurrent(display, surface, surface, context_)) {
            InertiaLog.error("eglMakeCurrent failed; shapes will not be drawn")
            return false
        }

        program = createProgram() ?: return false
        return true
    }

    private fun releaseContext() {
        if (display == EGL14.EGL_NO_DISPLAY) return

        EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context_ != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context_)
        EGL14.eglTerminate(display)

        display = EGL14.EGL_NO_DISPLAY
        context_ = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
        program = 0
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            InertiaLog.error("shape shader failed to compile: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return null
        }

        return shader
    }

    private fun createProgram(): Int? {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, SHAPE_VERTEX_SHADER) ?: return null
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, SHAPE_FRAGMENT_SHADER) ?: return null

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            InertiaLog.error("shape program failed to link: ${GLES20.glGetProgramInfoLog(program)}")
            return null
        }

        return program
    }

    private fun render() {
        if (display == EGL14.EGL_NO_DISPLAY || surface == EGL14.EGL_NO_SURFACE || program == 0) {
            return
        }
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return

        EGL14.eglMakeCurrent(display, surface, surface, context_)

        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // An emptied shape list still clears and presents, which is what takes
        // the last frame's shapes back off the screen.
        if (vertexData.isNotEmpty()) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFuncSeparate(
                GLES20.GL_SRC_ALPHA,
                GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_SRC_ALPHA,
                GLES20.GL_ONE_MINUS_SRC_ALPHA
            )

            GLES20.glUseProgram(program)

            buffer = vertexData.toFloatBuffer()
            val stride = FLOATS_PER_VERTEX * BYTES_PER_FLOAT

            val position = GLES20.glGetAttribLocation(program, "a_position")
            buffer.position(0)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, stride, buffer)

            val color = GLES20.glGetAttribLocation(program, "a_color")
            buffer.position(2)
            GLES20.glEnableVertexAttribArray(color)
            GLES20.glVertexAttribPointer(color, 4, GLES20.GL_FLOAT, false, stride, buffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexData.size / FLOATS_PER_VERTEX)
        }

        EGL14.eglSwapBuffers(display, surface)
    }
}

private fun FloatArray.toFloatBuffer(): FloatBuffer =
    ByteBuffer.allocateDirect(size * BYTES_PER_FLOAT)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(this)
        .apply { position(0) }

/// Every shape restated in the canvas's own space and flattened into the one
/// triangle list the GPU draws.
internal fun List<InertiaShape>.vertexData(bounds: Rect): FloatArray {
    val vertices = flatMap { it.normalizedTriangles(bounds) }
    val data = FloatArray(vertices.size * FLOATS_PER_VERTEX)

    vertices.forEachIndexed { index, vertex ->
        val offset = index * FLOATS_PER_VERTEX
        data[offset] = vertex.position.x
        data[offset + 1] = vertex.position.y
        data[offset + 2] = vertex.color.red
        data[offset + 3] = vertex.color.green
        data[offset + 4] = vertex.color.blue
        data[offset + 5] = vertex.color.alpha
    }

    return data
}

/// The actionable's canvas: the shapes authored alongside its animation, drawn
/// in GLES behind its content.
///
/// Sized to the shapes rather than to the container — see [bounds] — so a shape
/// reaching past the actionable makes the surface bigger instead of being cut
/// off at its edge. [actionableSize] turns those bounds from multiples of the
/// view into pixels, which is the whole of "relative to the actionable": its
/// shorter side is the length they are multiples of, across and down alike, so a
/// circle stays round on a view of any shape.
///
/// Measured as zero-sized, so a surface larger than the card cannot grow the
/// card it backs; Compose does not clip a child that overflows unless asked to,
/// which is what lets those two things be true at once.
///
/// A canvas given an [animation] holds one shape's own drawing rather than the
/// actionable's backdrop: the surface carries that track's layer transforms,
/// stacked on top of the actionable's own because it sits inside them.
/// [hierarchyIdPrefix] is the actionable the shape belongs to, which is what
/// says whether that actionable has been triggered, and [containerSize] is what
/// a translation of 1 crosses — the same measure the actionable's own animation
/// is offset by.
@Composable
internal fun InertiaShapeCanvas(
    shapes: List<InertiaShape>,
    actionableSize: IntSize,
    animation: InertiaAnimationSchema? = null,
    hierarchyIdPrefix: String? = null,
    containerSize: IntSize = IntSize.Zero,
    /// Present only in the editor, and only while the viewport is in actionable
    /// mode. A canvas holding one selected shape grows a border and publishes
    /// the handles the container's overlay draws — see [InertiaShapeEditing].
    editing: InertiaShapeEditing? = null
) {
    val bounds = remember(shapes) { shapes.bounds() } ?: return

    // Independent of the actionable's size: resizing the card moves the surface
    // without rebuilding a vertex of it.
    val vertexData = remember(shapes, bounds) { shapes.vertexData(bounds) }

    // The length a shape's coordinates are multiples of, across and down alike:
    // the shorter side of the actionable's box.
    //
    // One length rather than two is what keeps a described vector the shape it
    // was described as. Scaling x by the view's width and y by its height puts a
    // shape in a square space that is then stretched to fit the view, so a
    // circle of size 1 came out an oval on every view that was not itself
    // square, and the taller or wider the view the further from round it got.
    // Measured against one side, a circle is round, a square is square, and a
    // shape keeps its proportions at every size that view takes.
    //
    // The shorter side rather than the longer one, so a shape authored at 1
    // still fits inside the view it backs in both directions.
    val unit = minOf(actionableSize.width, actionableSize.height)

    val width = (bounds.width * unit).roundToInt()
    val height = (bounds.height * unit).roundToInt()
    if (width <= 0 || height <= 0) return

    // Where the box sits inside the actionable, measured from the *middle* of it
    // rather than its top-left corner: the origin a shape's coordinates are drawn
    // about is the centre of the view it backs, so a shape half the size of its
    // actionable sits in the middle of it rather than hanging off a corner. This
    // Layout places into a Box that aligns top-start, so that half-view step is
    // taken here — the same one the Swift runtime gets from centring its ZStack.
    val left = (actionableSize.width / 2f + bounds.left * unit).roundToInt()
    val top = (actionableSize.height / 2f + bounds.top * unit).roundToInt()

    /// The one shape this canvas holds, when the editor has picked it. Selection
    /// is what gives a shape a canvas to itself, so there is never more than one.
    val selected = shapes.singleOrNull()?.takeIf { editing?.isSelected(it) == true }

    /// What this shape is drawn at right now: its own track at the playhead with
    /// the gesture in progress folded in, and nothing of the actionable's — that
    /// transform is on the element this canvas sits inside, and is handed to the
    /// chrome separately as its outer one.
    val sample = shapeSample(animation, hierarchyIdPrefix, containerSize) {
        selected?.let { editing?.edit(it) } ?: InertiaToolEdit()
    }

    InertiaShapeHandles(
        selected = selected,
        editing = editing,
        sample = sample,
        containerSize = containerSize,
        shapeLayoutOffset = Offset(left.toFloat(), top.toFloat()),
        shapeLayoutSize = Size(width.toFloat(), height.toFloat())
    )

    Layout(
        content = {
            AndroidView(
                factory = { context -> InertiaShapeTextureView(context) },
                update = { view -> view.vertexData = vertexData },
                modifier = shapeAnimationModifier(
                    isTransformed = animation != null || selected != null,
                    containerSize = containerSize,
                    sample = sample
                )
                    // Inside every layer above, so a press is carried back out
                    // through them before it is tested: Compose hit-tests
                    // through a `graphicsLayer`, so what arrives here is already
                    // in the space the artwork was drawn in.
                    .then(pickModifier(shapes, bounds, unit, editing))
                    // Inside every layer above, so the border stays glued to the
                    // shape as it turns and scales — exactly where an
                    // actionable's own sits relative to it.
                    .then(if (selected != null) Modifier.border(2.dp, Color.Green) else Modifier)
            )
        }
    ) { measurables, _ ->
        val constraints = Constraints.fixed(width, height)
        val placeables = measurables.map { it.measure(constraints) }

        layout(0, 0) {
            placeables.forEach { it.place(left, top) }
        }
    }
}

/// What listens for a press on one canvas, so a shape can be picked by touching
/// it rather than only by finding its row in the editor's hierarchy.
///
/// [Modifier] and nothing else outside the editor: a shape is backdrop in a
/// shipped build, and a backdrop that took touches would swallow the taps meant
/// for the composables it sits behind.
///
/// A press that lands on the artwork belongs to the shape, and is consumed so
/// that it does. The actionable this canvas is drawn inside of runs its own
/// gesture on the box around it — [awaitFirstDown] there wants an unconsumed
/// press — so without taking the press here, touching a vector would pick the
/// vector *and* toggle the view it was authored behind.
///
/// A press that misses is left alone rather than consumed, which is what lets it
/// go on through: a canvas is fitted to the box its shapes occupy together, and
/// that box is mostly not shape. The corner beside a circle, the margin beside a
/// triangle's slope, the hole through an unfilled ring — all of it reaches the
/// actionable underneath exactly as it did before any of this existed. See
/// [InertiaShape.hitTest].
///
/// One handler for the whole canvas rather than one per shape, because the
/// shapes sharing it share a vertex buffer and have no boxes of their own to
/// hang a gesture off. Which of them was pressed is answered by testing the
/// point, which is also the only way to answer it for a *nested* shape — drawn
/// into its parent's buffer, and a row of its own in the hierarchy all the same.
private fun pickModifier(
    shapes: List<InertiaShape>,
    /// The box these shapes occupy together, which is what the canvas was sized
    /// and placed by — so it is also what turns a press on the canvas back into
    /// a point in the units the shapes are authored in.
    bounds: Rect,
    /// The length a shape's coordinate of 1 is drawn at, in pixels.
    unit: Int,
    editing: InertiaShapeEditing?
): Modifier {
    if (editing == null || unit <= 0) return Modifier

    return Modifier.pointerInput(shapes, bounds, unit, editing.onTap) {
        awaitEachGesture {
            val down = awaitFirstDown()

            val shape = shapes.hitTest(
                InertiaPoint(
                    bounds.left + down.position.x / unit,
                    bounds.top + down.position.y / unit
                )
            ) ?: return@awaitEachGesture

            down.consume()

            // A press that wanders off the shape and lifts elsewhere is not a
            // tap, and picks nothing — the same reading every tap detector makes.
            waitForUpOrCancellation()?.let { up ->
                up.consume()
                editing.onTap(shape)
            }
        }
    }
}

/// Publishes a selected shape's handles to the container's overlay, and runs the
/// gesture they open.
///
/// The same chrome an actionable shows and the same tools, because a shape is
/// edited exactly as a view is: one palette, one gesture, one `MessageEdit`.
/// What differs is where it is measured from — the shape's box sits inside the
/// actionable's transform, which the overlay is handed as
/// [InertiaToolHandleTarget.outer] — and what the resulting edit names, which is
/// the shape's own id.
///
/// Draws nothing itself. The knobs live in the container's overlay, for the
/// reason [InertiaToolHandlesOverlay] gives, and this only hands that overlay
/// what it needs.
@Composable
private fun InertiaShapeHandles(
    selected: InertiaShape?,
    editing: InertiaShapeEditing?,
    sample: () -> InertiaAnimationValues,
    containerSize: IntSize,
    /// Where this shape's box sits inside the actionable's, as laid out — before
    /// either transform. Added to the actionable's own layout origin, it is the
    /// box the handles are turned about.
    shapeLayoutOffset: Offset,
    shapeLayoutSize: Size
) {
    /// Where the gesture opened, taken once so its math stays measured against
    /// the transform the shape had before it. A plain field: written per pointer
    /// event and never read from composition.
    val gesture = remember { InertiaToolGesture() }

    val currentSample by rememberUpdatedState(sample)
    val currentEditing by rememberUpdatedState(editing)
    val currentSelected by rememberUpdatedState(selected)
    val currentOffset by rememberUpdatedState(shapeLayoutOffset)
    val currentSize by rememberUpdatedState(shapeLayoutSize)
    val currentContainerSize by rememberUpdatedState(containerSize)

    /// The actionable's box and this shape's own inside it, both in the
    /// container's space and both outside every transform — which is the frame
    /// the handles are turned about. Measured, so it is called when the handles
    /// are published and when a gesture opens rather than per frame.
    val measureBoxes = {
        val outer = currentEditing?.outerLayoutBox()?.let { (origin, size) ->
            InertiaOuterTransform(InertiaAnimationValues(), origin, size)
        }
        outer to ((outer?.layoutOrigin ?: Offset.Zero) + currentOffset to currentSize)
    }

    if (selected != null && editing?.handles != null && containerSize != IntSize.Zero) {
        // Republished rather than left as it was: the geometry it carries is
        // measured, and a shape that has been re-laid out or handed a new track
        // is not where the last target says it is. A gesture needs no republish,
        // since the overlay reads the values back through the lambdas.
        LaunchedEffect(
            editing.handles,
            editing.tool,
            editing.owner(selected),
            containerSize,
            shapeLayoutOffset,
            shapeLayoutSize
        ) {
            val (outerBox, shapeBox) = measureBoxes()
            val (origin, size) = shapeBox

            editing.handles.show(
                InertiaToolHandleTarget(
                    owner = editing.owner(selected),
                    tool = editing.tool,
                    values = { currentSample() },
                    layoutOrigin = origin,
                    layoutSize = size,
                    canvasSize = containerSize,
                    // The box is the one measured just now; only the values are
                    // read per frame, which is what lets the chrome follow an
                    // actionable that is animating without walking the layout
                    // tree on every one.
                    outer = {
                        InertiaOuterTransform(
                            currentEditing?.outerValues?.invoke() ?: InertiaAnimationValues(),
                            outerBox?.layoutOrigin ?: Offset.Zero,
                            outerBox?.layoutSize ?: Size.Zero
                        )
                    },
                    movesByKnob = true,
                    onBegin = { index, position ->
                        val shape = currentSelected ?: return@InertiaToolHandleTarget
                        val edits = currentEditing ?: return@InertiaToolHandleTarget
                        val (gestureOuter, gestureBox) = measureBoxes()
                        val (gestureOrigin, gestureSize) = gestureBox

                        gesture.start = openToolGesture(
                            tool = edits.tool,
                            knobIndex = index,
                            position = position,
                            values = currentSample(),
                            edit = edits.edit(shape),
                            layoutOrigin = gestureOrigin,
                            layoutSize = gestureSize,
                            canvasSize = currentContainerSize,
                            outer = InertiaOuterTransform(
                                edits.outerValues(),
                                gestureOuter?.layoutOrigin ?: Offset.Zero,
                                gestureOuter?.layoutSize ?: Size.Zero
                            ),
                            movesByKnob = true
                        )
                    },
                    onDrag = { position ->
                        val shape = currentSelected
                        val edits = currentEditing
                        val opening = gesture.start
                        if (shape != null && edits != null && opening != null) {
                            edits.onChange(shape, opening.editAt(edits.tool, position))
                        }
                    },
                    onEnd = {
                        val shape = currentSelected
                        if (gesture.start != null && shape != null) {
                            gesture.start = null
                            currentEditing?.onEnded(shape)
                        }
                    }
                )
            )
        }
    }

    // Leaving composition, or being deselected, takes the handles with it.
    // Without this a shape that goes away mid-selection leaves chrome behind
    // with nothing under it. Only ever takes down its own: every selected shape
    // runs this, and one has no business clearing another's.
    val owner = selected?.let { editing?.owner(it) }
    DisposableEffect(editing?.handles, owner) {
        onDispose {
            val store = editing?.handles
            if (owner != null && store?.target?.owner == owner) {
                store.hide()
            }
        }
    }
}

/// What one shape is drawn at, given where the playhead is and what the editor's
/// gesture has added on top.
///
/// Read at the same playhead as everything else, so a shape moves in time with
/// the actionable it was authored behind rather than on a clock of its own — and
/// is padded to the same loop, so the two come round together. What it does not
/// share is the actionable's `invokeType`: a shape animation marked `auto` runs
/// as soon as the clock does, even while the actionable it backs is still
/// waiting on the app to trigger it.
///
/// A lambda, not a value: the playhead and the edit are read when it is
/// *called*, which is from a `graphicsLayer` block or a gesture callback. A read
/// in composition would recompose the canvas on every frame of every run.
///
/// A shape authored as backdrop has no track and sits at the identity, which is
/// where the first edit on it is measured from — the editor writes that edit
/// into a track the shape did not have until then.
@Composable
private fun shapeSample(
    animation: InertiaAnimationSchema?,
    hierarchyIdPrefix: String?,
    containerSize: IntSize,
    edit: () -> InertiaToolEdit
): () -> InertiaAnimationValues {
    val playback = LocalInertia.current

    return {
        val base = if (animation == null) {
            InertiaAnimationValues()
        } else {
            // The actionable's own read, held frame and all — a shape is drawn on
            // the frame the actionable it backs is drawn on. A shape carrying an
            // `auto` track of its own runs as soon as the clock does, even while
            // that actionable is still waiting to be triggered, and is scrubbed
            // with the playhead like anything else.
            val isPlaying = playback.isRunning || playback.seekTime != null
            val playheadTime = playback.seekTime ?: playback.playheadTime
            val trackTime = hierarchyIdPrefix?.let { playback.trackTime(it) }
                ?: playheadTime.takeIf {
                    animation.invokeType == InertiaAnimationInvokeType.auto && isPlaying
                }

            if (trackTime != null) {
                animation.valuesAtTime(
                    trackTime,
                    playback.playbackDuration,
                    playback.isRepeating
                )
            } else {
                animation.initialValues.sanitized()
            }
        }

        // One matrix for the track and the gesture together, rather than the
        // gesture applied as an offset outside the animation's own layers: what
        // the editor is sent is a single set of values, and this is what makes
        // the shape's appearance agree with them.
        if (containerSize == IntSize.Zero) base else base.applying(edit(), containerSize)
    }
}

/// The layers that move a shape drawn on its own track — or dragged by the
/// editor — and nothing at all for a shape that is only backdrop.
///
/// The layers are stacked exactly as [InertiaActionable] stacks its own, for the
/// reasons set out there: the playhead is read inside the layer blocks rather
/// than in composition, and the two rotations want a layer each because they
/// pivot on different points.
@Composable
private fun shapeAnimationModifier(
    /// Whether there is anything for these layers to carry: a track, or the
    /// editor's gesture. A shape with neither is drawn where it was authored and
    /// wants no layer at all.
    isTransformed: Boolean,
    containerSize: IntSize,
    sample: () -> InertiaAnimationValues
): Modifier {
    if (!isTransformed || containerSize == IntSize.Zero) return Modifier

    return Modifier
        .graphicsLayer {
            val v = sample()
            translationX = v.translate.getOrElse(0) { 0f } * containerSize.width
            translationY = v.translate.getOrElse(1) { 0f } * containerSize.height
            rotationZ = v.rotateCenter
            alpha = v.opacity
            transformOrigin = TransformOrigin.Center
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
        .graphicsLayer {
            rotationZ = sample().rotate
            transformOrigin = TransformOrigin(0f, 0f)
        }
        .graphicsLayer {
            val scale = sample().scale
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin.Center
        }
}
