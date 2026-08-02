package org.inertiagraphics.inertia

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/// The shapes an actionable's canvas draws: how they are authored alongside an
/// animation, and how they reach the renderer. The Swift and web runtimes carry
/// the same cases — a shape that lands somewhere else on one platform is a
/// shape that was authored once and drawn twice differently.
class ShapeTest {

    /// Well inside a pixel at any sane container size, and wide enough for the
    /// error a couple of float operations can accumulate.
    private val tolerance = 1e-5f

    private fun corner(x: Float, y: Float) = Vertex(
        position = InertiaPoint(x, y),
        color = InertiaColor(red = 1f, green = 1f, blue = 1f, alpha = 1f)
    )

    /// A schema as it was written before shapes existed: the key is genuinely
    /// absent from the encoded bytes rather than present and empty, which is
    /// what `animation without shapes still decodes` is about. Encoding the real
    /// type would always write the field, so the case needs its own shape.
    @Serializable
    private data class SchemaWithoutShapes(
        val id: String,
        val initialValues: InertiaAnimationValues,
        val invokeType: InertiaAnimationInvokeType,
        val keyframes: List<InertiaAnimationKeyframe>
    )

    private fun shapedCorner(x: Float, y: Float, red: Float, green: Float, blue: Float) = Vertex(
        position = InertiaPoint(x, y),
        color = InertiaColor(red = red, green = green, blue = blue, alpha = 0.6f)
    )

    /// A card with a shape behind it, taken through the bytes rather than built
    /// in memory: what is being checked is that a shape survives the animation
    /// file, so decoding has to be part of it.
    private fun demoSchemas(): Map<String, InertiaAnimationSchema> {
        val card0 = InertiaAnimationSchema(
            id = "card0",
            invokeType = InertiaAnimationInvokeType.trigger,
            shapes = listOf(
                InertiaShape(
                    vertices = listOf(
                        shapedCorner(0f, 0f, 0.35f, 0.1f, 0.85f),
                        shapedCorner(1f, 0f, 0.1f, 0.55f, 0.95f),
                        shapedCorner(1f, 1f, 0.1f, 0.85f, 0.75f),
                        shapedCorner(0f, 1f, 0.35f, 0.1f, 0.85f)
                    )
                )
            )
        )

        val bytes = inertiaMsgPack.encodeToByteArray(listOf(card0))
        return inertiaMsgPack.decodeFromByteArray<List<InertiaAnimationSchema>>(bytes).associateBy { it.id }
    }

    @Test
    fun `shapes are decoded with their vertices`() {
        val card0 = demoSchemas().getValue("card0")

        assertEquals(1, card0.shapes.size)
        assertEquals(4, card0.shapes[0].vertices.size)
        assertEquals(InertiaPoint(0f, 0f), card0.shapes[0].vertices[0].position)
        assertEquals(0.6f, card0.shapes[0].vertices[0].color.alpha)
    }

    /// An animation authored before shapes existed — or one that simply wants
    /// none — has to keep loading, or a single old file takes the whole
    /// container's schemas down with it.
    @Test
    fun `animation without shapes still decodes`() {
        val bytes = inertiaMsgPack.encodeToByteArray(
            SchemaWithoutShapes(
                id = "card1",
                initialValues = InertiaAnimationValues(),
                invokeType = InertiaAnimationInvokeType.auto,
                keyframes = emptyList()
            )
        )

        assertEquals(emptyList(), inertiaMsgPack.decodeFromByteArray<InertiaAnimationSchema>(bytes).shapes)
    }

    /// A shape is a ring of corners; the renderer draws triangles. Four corners
    /// are the two triangles of a quad, sharing the corner the fan turns about.
    @Test
    fun `shape is triangulated as a fan`() {
        val corners = (0..3).map { corner(it.toFloat(), it.toFloat()) }

        val triangles = InertiaShape(corners).triangles()

        assertEquals(6, triangles.size)
        assertEquals(listOf(0f, 1f, 2f, 0f, 2f, 3f), triangles.map { it.position.x })
    }

    /// A shape that fits the actionable exactly gives a canvas that is the
    /// actionable: the unit box, at its origin.
    @Test
    fun `bounds of a shape filling the actionable`() {
        val shape = InertiaShape(
            listOf(corner(0f, 0f), corner(1f, 0f), corner(1f, 1f), corner(0f, 1f))
        )

        assertEquals(Rect(0f, 0f, 1f, 1f), listOf(shape).bounds())
    }

    /// The point of fitting the canvas to the shapes: one reaching past the
    /// actionable grows the canvas instead of being cut off at its edge. 1.2 is
    /// a fifth of the actionable's width past its right edge, and -0.5 half its
    /// width before its left, so the canvas spans 1.7 of it.
    @Test
    fun `bounds grow to hold shapes outside the actionable`() {
        val shape = InertiaShape(listOf(corner(-0.5f, 0f), corner(1.2f, 0f), corner(1.2f, 3f)))

        val bounds = assertNotNull(listOf(shape).bounds())

        assertEquals(-0.5f, bounds.left, tolerance)
        assertEquals(1.7f, bounds.width, tolerance)
        assertEquals(3f, bounds.height, tolerance)
    }

    /// Several shapes share one canvas, so the box has to hold all of them.
    @Test
    fun `bounds span every shape`() {
        val left = InertiaShape(listOf(corner(-1f, 0f), corner(0f, 0f), corner(0f, 1f)))
        val right = InertiaShape(listOf(corner(1f, 0f), corner(2f, 0f), corner(2f, 0.5f)))

        assertEquals(Rect(-1f, 0f, 2f, 1f), listOf(left, right).bounds())
    }

    /// Shapes enclosing no area have no canvas, which is also the state in
    /// which there is nothing to draw.
    @Test
    fun `bounds of empty or degenerate shapes are null`() {
        assertNull(emptyList<InertiaShape>().bounds())
        assertNull(listOf(InertiaShape(emptyList())).bounds())
        assertNull(listOf(InertiaShape(listOf(corner(1f, 0f), corner(1f, 1f)))).bounds())
    }

    /// Whatever box the canvas ends up being, the renderer is handed the shape
    /// in the canvas's own 0..1 space — so the corner that defined the far edge
    /// of the bounds lands exactly on it.
    @Test
    fun `shape is normalized into the canvas bounds`() {
        val shape = InertiaShape(listOf(corner(-0.5f, 0f), corner(1.5f, 0f), corner(1.5f, 2f)))

        val normalized = shape.normalized(Rect(left = -0.5f, top = 0f, right = 1.5f, bottom = 2f))

        assertEquals(InertiaPoint(0f, 0f), normalized.vertices[0].position)
        assertEquals(InertiaPoint(1f, 0f), normalized.vertices[1].position)
        assertEquals(InertiaPoint(1f, 1f), normalized.vertices[2].position)
    }

    /// Fewer than three corners enclose nothing, and handing the renderer a
    /// partial triangle would have it read past the end of the list.
    @Test
    fun `shape with too few corners draws nothing`() {
        assertEquals(emptyList(), InertiaShape(emptyList()).triangles())
        assertEquals(emptyList(), InertiaShape(listOf(corner(0f, 0f), corner(1f, 1f))).triangles())
    }

    // -- Shapes that carry an animation --

    /// The other way a shape is authored: a drawn vector, described rather than
    /// spelled out corner by corner, with a track of its own attached — which is
    /// what makes it move independently of the actionable it is drawn behind.
    private fun drawnShape(): InertiaShape {
        fun values(x: Float, y: Float) = InertiaAnimationValues(translate = listOf(x, y))

        val card2 = InertiaAnimationSchema(
            id = "card2",
            invokeType = InertiaAnimationInvokeType.auto,
            shapes = listOf(
                InertiaShape(
                    shape = InertiaShapeProperties(
                        id = "123",
                        type = InertiaShapeType.rectangle,
                        width = 2f,
                        height = 2f
                    ),
                    animation = InertiaAnimationSchema(
                        id = "shape0",
                        invokeType = InertiaAnimationInvokeType.auto,
                        keyframes = listOf(
                            InertiaAnimationKeyframe("a", values(0.8f, 0.9f), duration = 0.001f),
                            InertiaAnimationKeyframe("b", values(-0.02f, -0.05f), duration = 1.3f)
                        )
                    )
                )
            )
        )

        val bytes = inertiaMsgPack.encodeToByteArray(listOf(card2))
        return inertiaMsgPack.decodeFromByteArray<List<InertiaAnimationSchema>>(bytes).first().shapes.first()
    }

    /// A shape given a track keeps it: without this the vector is decoded and
    /// drawn, and then sits still because the only animation that reached the
    /// runtime was the actionable's.
    @Test
    fun `shape carries its own animation`() {
        val animation = assertNotNull(drawnShape().animation)

        assertEquals("shape0", animation.id)
        assertEquals(InertiaAnimationInvokeType.auto, animation.invokeType)
        assertEquals(2, animation.keyframes.size)
        assertEquals(-0.02f, animation.keyframes.last().values.translate[0], tolerance)
    }

    /// A described shape has no corners on the wire; the ones it is drawn from
    /// are worked out from the description. A rectangle is the two triangles of
    /// a quad, so it reaches the renderer as six corners.
    @Test
    fun `described shape is drawn from its description`() {
        val shape = drawnShape()

        assertEquals(emptyList(), shape.vertices)
        assertEquals(6, shape.resolvedVertices().size)
        assertNotNull(listOf(shape).bounds())
    }

    /// Normalizing is about where a shape lands on the canvas, not about what it
    /// then does — so the track has to come through it. It is the last thing to
    /// touch a shape before the renderer, and a shape that lost its animation
    /// here would be drawn in the right place and never move.
    @Test
    fun `normalizing keeps the shapes animation`() {
        val shape = drawnShape()
        val bounds = assertNotNull(listOf(shape).bounds())

        val normalized = shape.normalized(bounds)

        assertEquals("shape0", normalized.animation?.id)
        assertEquals(shape.resolvedVertices().size, normalized.vertices.size)
    }
}
