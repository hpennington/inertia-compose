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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
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
    val vertices = flatMap { it.normalized(bounds).triangles() }
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
/// view into pixels, which is the whole of "relative to the actionable".
///
/// Measured as zero-sized, so a surface larger than the card cannot grow the
/// card it backs; Compose does not clip a child that overflows unless asked to,
/// which is what lets those two things be true at once.
@Composable
internal fun InertiaShapeCanvas(
    shapes: List<InertiaShape>,
    actionableSize: IntSize
) {
    val bounds = remember(shapes) { shapes.bounds() } ?: return

    // Independent of the actionable's size: resizing the card moves the surface
    // without rebuilding a vertex of it.
    val vertexData = remember(shapes, bounds) { shapes.vertexData(bounds) }

    val width = (bounds.width * actionableSize.width).roundToInt()
    val height = (bounds.height * actionableSize.height).roundToInt()
    if (width <= 0 || height <= 0) return

    val left = (bounds.left * actionableSize.width).roundToInt()
    val top = (bounds.top * actionableSize.height).roundToInt()

    Layout(
        content = {
            AndroidView(
                factory = { context -> InertiaShapeTextureView(context) },
                update = { view -> view.vertexData = vertexData }
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
