package org.inertiagraphics.inertia

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

/// The move tool's two axis arrows: where they sit next to a node, and what a
/// drag on one is allowed to author.
///
/// The same arithmetic in all three runtimes — an arrow is a fixed number of
/// pixels past the drawn edge of the box on every one of them, so a node scaled
/// by its schema carries its arrows out with it rather than swallowing them.
class TranslateAxisTest {

    private val layoutOrigin = Offset(160f, 80f)
    private val layoutSize = Size(80f, 40f)
    private val canvasSize = IntSize(400, 200)

    /// From the drawn edge of the box to the middle of an arrow: the gap, plus
    /// half of the head that starts where the gap ends.
    private val reach =
        InertiaToolHandleGeometry.axisGap + InertiaToolHandleGeometry.axisLength / 2f

    private fun geometry(scale: Float = 1f) = InertiaToolHandleGeometry(
        InertiaTool.translate,
        InertiaAnimationValues(
            scale = scale,
            translate = listOf(0f, 0f),
            rotate = 0f,
            rotateCenter = 0f,
            opacity = 1f
        ),
        layoutOrigin,
        layoutSize,
        canvasSize
    )

    @Test
    fun `arrows sit off the edge they point through`() {
        val subject = geometry()
        val center = subject.drawnCenter

        assertEquals(
            Offset(center.x + layoutSize.width / 2f + reach, center.y),
            subject.axisArrowCenter(InertiaTranslateAxis.horizontal)
        )
        assertEquals(
            Offset(center.x, center.y - layoutSize.height / 2f - reach),
            subject.axisArrowCenter(InertiaTranslateAxis.vertical)
        )
    }

    @Test
    fun `arrows follow the drawn box rather than the laid-out one`() {
        val subject = geometry(scale = 2f)
        val center = subject.drawnCenter

        assertEquals(
            Offset(center.x + layoutSize.width + reach, center.y),
            subject.axisArrowCenter(InertiaTranslateAxis.horizontal)
        )
    }

    /// The stem runs from the node's drawn center out to where the head begins,
    /// so the two meet rather than overlap.
    @Test
    fun `the stem ends where the head starts`() {
        val subject = geometry()

        InertiaTranslateAxis.entries.forEach { axis ->
            val (from, to) = subject.axisStem(axis)
            val head = subject.axisArrowCenter(axis)

            assertEquals(subject.drawnCenter, from)
            assertEquals(
                InertiaToolHandleGeometry.axisLength / 2f,
                (head - to).getDistance(),
                0.0001f
            )
        }
    }

    /// Every knob the overlay grows for this tool is an arrow, in the order the
    /// axes are declared — which is how the index a press arrives with maps back
    /// to the axis it picked.
    @Test
    fun `one knob per axis, in the order the axes are declared`() {
        val subject = geometry()

        assertEquals(
            InertiaTranslateAxis.entries.map { subject.axisArrowCenter(it) },
            subject.knobs
        )
    }

    @Test
    fun `an axis authors only its own component`() {
        val drag = Offset(30f, -12f)

        assertEquals(Offset(30f, 0f), InertiaTranslateAxis.horizontal.constrain(drag))
        assertEquals(Offset(0f, -12f), InertiaTranslateAxis.vertical.constrain(drag))
    }

    /// What the constrained drag becomes once it is folded into a transform: a
    /// fraction of the canvas, and only along the one axis.
    @Test
    fun `a constrained drag moves the node along one axis only`() {
        val edit = InertiaToolEdit(
            translate = InertiaTranslateAxis.horizontal.constrain(Offset(40f, 90f))
        )
        val values = InertiaAnimationValues(
            scale = 1f,
            translate = listOf(0f, 0f),
            rotate = 0f,
            rotateCenter = 0f,
            opacity = 1f
        ).applying(edit, canvasSize)

        assertEquals(0.1f, values.translate[0], 0.0001f)
        assertEquals(0f, values.translate[1], 0.0001f)
    }
}
