package org.inertiagraphics.inertia

import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
                    id = "card0-rectangle",
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

    /// A shape's id is how anything points at it: the editor's hierarchy panel,
    /// the selection sent back here, and the edit that selection authors. It has
    /// to survive the file, or a shape can be drawn and never picked.
    @Test
    fun `shape id survives the wire`() {
        val card0 = demoSchemas().getValue("card0")

        assertEquals("card0-rectangle", card0.shapes[0].id)
    }

    /// Normalizing restates a shape in its canvas's own space, which is about
    /// where it is drawn and not about what it is — so every corner of the
    /// drawing comes through it, and only their positions change.
    @Test
    fun `normalizing keeps everything the shape draws`() {
        val shape = InertiaShape(
            id = "named",
            vertices = listOf(corner(0f, 0f), corner(2f, 0f), corner(2f, 2f))
        )

        val normalized = shape.normalizedTriangles(Rect(0f, 0f, 2f, 2f))

        assertEquals(shape.triangles().size, normalized.size)
        assertEquals(shape.triangles().map { it.color }, normalized.map { it.color })
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

        val triangles = InertiaShape(id = "fan", vertices = corners).triangles()

        assertEquals(6, triangles.size)
        assertEquals(listOf(0f, 1f, 2f, 0f, 2f, 3f), triangles.map { it.position.x })
    }

    /// A shape that fits the actionable exactly gives a canvas that is the
    /// actionable: the unit box, at its origin.
    @Test
    fun `bounds of a shape filling the actionable`() {
        val shape = InertiaShape(
            id = "filling",
            vertices = listOf(corner(0f, 0f), corner(1f, 0f), corner(1f, 1f), corner(0f, 1f))
        )

        assertEquals(Rect(0f, 0f, 1f, 1f), listOf(shape).bounds())
    }

    /// The point of fitting the canvas to the shapes: one reaching past the
    /// actionable grows the canvas instead of being cut off at its edge. 1.2 is
    /// a fifth of the actionable's width past its right edge, and -0.5 half its
    /// width before its left, so the canvas spans 1.7 of it.
    @Test
    fun `bounds grow to hold shapes outside the actionable`() {
        val shape = InertiaShape(
            id = "overhanging",
            vertices = listOf(corner(-0.5f, 0f), corner(1.2f, 0f), corner(1.2f, 3f))
        )

        val bounds = assertNotNull(listOf(shape).bounds())

        assertEquals(-0.5f, bounds.left, tolerance)
        assertEquals(1.7f, bounds.width, tolerance)
        assertEquals(3f, bounds.height, tolerance)
    }

    /// Several shapes share one canvas, so the box has to hold all of them.
    @Test
    fun `bounds span every shape`() {
        val left = InertiaShape(id = "left", vertices = listOf(corner(-1f, 0f), corner(0f, 0f), corner(0f, 1f)))
        val right = InertiaShape(id = "right", vertices = listOf(corner(1f, 0f), corner(2f, 0f), corner(2f, 0.5f)))

        assertEquals(Rect(-1f, 0f, 2f, 1f), listOf(left, right).bounds())
    }

    /// Shapes enclosing no area have no canvas, which is also the state in
    /// which there is nothing to draw.
    @Test
    fun `bounds of empty or degenerate shapes are null`() {
        assertNull(emptyList<InertiaShape>().bounds())
        assertNull(listOf(InertiaShape(id = "empty")).bounds())
        assertNull(listOf(InertiaShape(id = "line", vertices = listOf(corner(1f, 0f), corner(1f, 1f)))).bounds())
    }

    /// Whatever box the canvas ends up being, the renderer is handed the shape
    /// in the canvas's own 0..1 space — so the corner that defined the far edge
    /// of the bounds lands exactly on it.
    @Test
    fun `shape is normalized into the canvas bounds`() {
        val shape = InertiaShape(
            id = "normalized",
            vertices = listOf(corner(-0.5f, 0f), corner(1.5f, 0f), corner(1.5f, 2f))
        )

        val normalized = shape.normalizedTriangles(Rect(left = -0.5f, top = 0f, right = 1.5f, bottom = 2f))

        assertEquals(InertiaPoint(0f, 0f), normalized[0].position)
        assertEquals(InertiaPoint(1f, 0f), normalized[1].position)
        assertEquals(InertiaPoint(1f, 1f), normalized[2].position)
    }

    /// Fewer than three corners enclose nothing, and handing the renderer a
    /// partial triangle would have it read past the end of the list.
    @Test
    fun `shape with too few corners draws nothing`() {
        assertEquals(emptyList(), InertiaShape(id = "empty").triangles())
        assertEquals(
            emptyList(),
            InertiaShape(id = "line", vertices = listOf(corner(0f, 0f), corner(1f, 1f))).triangles()
        )
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
                    id = "card2-shape-0",
                    shape = InertiaShapeProperties(
                        id = "123",
                        type = InertiaShapeType.rectangle,
                        width = 2f,
                        height = 2f,
                        fill = InertiaColor(red = 1f, green = 0f, blue = 0f, alpha = 1f)
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
    /// are worked out from the description. A rectangle is its four corners, and
    /// the fan that covers them is the two triangles of a quad.
    @Test
    fun `described shape is drawn from its description`() {
        val shape = drawnShape()

        assertEquals(emptyList(), shape.vertices)
        assertEquals(4, shape.resolvedVertices().size)
        assertEquals(6, shape.triangles().size)
        assertNotNull(listOf(shape).bounds())
    }

    /// Normalizing is the last thing to touch a shape before the renderer, and
    /// what it hands over is the drawing rather than the outline — so everything
    /// the shape paints has to come through it, at the size the canvas is.
    @Test
    fun `normalizing hands over the whole drawing`() {
        val shape = drawnShape()
        val bounds = assertNotNull(listOf(shape).bounds())

        val normalized = shape.normalizedTriangles(bounds)

        assertEquals(shape.triangles().size, normalized.size)
        // The shape filled its own bounds, so normalizing lands it on the unit
        // box: the far corners exactly on it.
        assertEquals(0f, normalized.minOf { it.position.x }, tolerance)
        assertEquals(1f, normalized.maxOf { it.position.x }, tolerance)
    }

    // MARK: - The vectors a description resolves to

    /// A vector described rather than spelled out, in whatever box it was
    /// dragged out over.
    private fun described(
        type: InertiaShapeType,
        width: Float,
        height: Float,
        fill: InertiaColor? = InertiaColor(red = 1f, green = 0f, blue = 0f, alpha = 1f),
        stroke: InertiaColor? = null,
        strokeWidth: Float = 0f
    ) = InertiaShape(
        id = "described",
        shape = InertiaShapeProperties(
            id = "123",
            type = type,
            width = width,
            height = height,
            fill = fill,
            stroke = stroke,
            strokeWidth = strokeWidth
        )
    )

    /// The two descriptions that carry two measurements have to spend both. A
    /// rectangle sized by one of them is the bug this replaced: every vector
    /// came out square, whatever box it had been dragged out over.
    @Test
    fun `rectangle and oval fill the box they were drawn in`() {
        for (type in listOf(InertiaShapeType.rectangle, InertiaShapeType.oval)) {
            val bounds = assertNotNull(listOf(described(type, 3f, 1f)).bounds())

            assertEquals(3f, bounds.width, tolerance, "$type")
            assertEquals(1f, bounds.height, tolerance, "$type")
        }
    }

    /// The three descriptions with one measurement rather than two stay
    /// themselves whatever box they were drawn in — sized, all three, by its
    /// longer side. The triangle is the one that isn't as tall as it is wide: it
    /// is drawn equilateral, so its height is the altitude of its base.
    @Test
    fun `square circle and triangle stay regular in a lopsided box`() {
        val heights = mapOf(
            InertiaShapeType.square to 3f,
            InertiaShapeType.circle to 3f,
            InertiaShapeType.triangle to 3f * sqrt(3f) / 2f
        )

        for ((type, height) in heights) {
            val bounds = assertNotNull(listOf(described(type, 3f, 1f)).bounds())

            assertEquals(3f, bounds.width, tolerance, "$type")
            assertEquals(height, bounds.height, tolerance, "$type")
        }
    }

    /// A round vector is drawn as the many-sided polygon that reads as one, and
    /// every one of those corners sits on the ellipse — which is what stops it
    /// being the squared-off box it used to be drawn as.
    @Test
    fun `oval is a ring of corners on its ellipse`() {
        val shape = described(InertiaShapeType.oval, 4f, 2f)
        val vertices = shape.resolvedVertices()

        assertEquals(ovalSegments, vertices.size)

        for (vertex in vertices) {
            // x²/a² + y²/b² = 1, for a ring centred on the origin the
            // description is measured from.
            val position = vertex.position
            assertEquals(1f, (position.x / 2f).pow(2) + (position.y / 1f).pow(2), tolerance)
        }

        // The ring is convex, so the fan the renderer draws covers it exactly:
        // one triangle per corner but the two the fan turns about.
        assertEquals((ovalSegments - 2) * 3, shape.triangles().size)
    }

    /// Every vector the editor can author has to survive the wire, not just the
    /// one the rest of these tests happen to use. A case this runtime does not
    /// know is not a shape that comes out wrong — it is a file that fails to
    /// decode, taking the whole container's schemas down with it.
    @Test
    fun `every vector type decodes off the wire`() {
        val shapes = InertiaShapeType.entries.map { described(it, 3f, 1f) }
        val card = InertiaAnimationSchema(id = "card0", invokeType = InertiaAnimationInvokeType.auto, shapes = shapes)

        val bytes = inertiaMsgPack.encodeToByteArray(listOf(card))
        val decoded = inertiaMsgPack.decodeFromByteArray<List<InertiaAnimationSchema>>(bytes).first()

        assertEquals(InertiaShapeType.entries.size, decoded.shapes.size)
        for ((index, type) in InertiaShapeType.entries.withIndex()) {
            assertEquals(type, decoded.shapes[index].shape?.type)
            assertNotNull(listOf(decoded.shapes[index]).bounds(), "$type")
        }
    }

    /// The colour the description carries is the colour the corners come out,
    /// rather than the red placeholder every described vector used to be drawn
    /// in whatever the editor had recorded against it.
    @Test
    fun `described shape is drawn in its own color`() {
        val color = InertiaColor(red = 0.25f, green = 0.5f, blue = 0.75f, alpha = 0.5f)

        val corner = described(InertiaShapeType.rectangle, 1f, 1f, color).resolvedVertices().first()

        assertEquals(color, corner.color)
    }

    // MARK: - Filling and stroking a described vector

    private val blue = InertiaColor(red = 0f, green = 0f, blue = 1f, alpha = 1f)
    private val red = InertiaColor(red = 1f, green = 0f, blue = 0f, alpha = 1f)

    /// The two halves of painting a vector are independent: either alone is a
    /// shape, and neither drags the other along with it. Drawn fill first, so
    /// the outline lands on top of the area it encloses.
    @Test
    fun `fill and stroke are drawn independently`() {
        val filled = described(InertiaShapeType.rectangle, 2f, 2f, fill = red)
        val stroked = described(InertiaShapeType.rectangle, 2f, 2f, fill = null, stroke = blue, strokeWidth = 0.1f)
        val both = described(InertiaShapeType.rectangle, 2f, 2f, fill = red, stroke = blue, strokeWidth = 0.1f)

        assertTrue(filled.triangles().all { it.color == red })
        assertTrue(stroked.triangles().all { it.color == blue })
        assertEquals(filled.triangles().size + stroked.triangles().size, both.triangles().size)
        assertEquals(filled.triangles().map { it.color }, both.triangles().take(filled.triangles().size).map { it.color })
    }

    /// A shape with neither draws nothing, which is the one combination there is
    /// no reason to author — and a stroke colour with no width, or a width with
    /// no colour, is each half of an outline that was never asked for.
    @Test
    fun `shape with nothing to paint with draws nothing`() {
        assertEquals(emptyList(), described(InertiaShapeType.rectangle, 2f, 2f, fill = null).triangles())
        assertEquals(emptyList(), described(InertiaShapeType.rectangle, 2f, 2f, fill = null, stroke = blue).triangles())
        assertEquals(emptyList(), described(InertiaShapeType.rectangle, 2f, 2f, fill = null, strokeWidth = 0.1f).triangles())
    }

    /// The stroke is drawn inside the outline, so a shape occupies the box it
    /// was authored at whether or not it is stroked: adding an outline never
    /// moves the shape or grows the canvas fitted to it.
    @Test
    fun `stroke is drawn inside the shapes own box`() {
        val plain = assertNotNull(listOf(described(InertiaShapeType.rectangle, 2f, 2f, fill = red)).bounds())
        val thick = assertNotNull(
            listOf(described(InertiaShapeType.rectangle, 2f, 2f, fill = red, stroke = blue, strokeWidth = 0.4f)).bounds()
        )

        assertEquals(plain, thick)
    }

    /// The band is an even thickness all the way round, corners included: the
    /// inner ring of a stroked square is the square inset by the stroke on every
    /// side, which is what the mitre at each corner is for.
    @Test
    fun `stroke is an even thickness around the shape`() {
        val stroked = described(InertiaShapeType.square, 2f, 2f, fill = null, stroke = blue, strokeWidth = 0.25f)

        // The outline runs ±1 from the centre; the inside of the band ±0.75.
        val inner = stroked.triangles().filter { abs(it.position.x) < 0.9999f && abs(it.position.y) < 0.9999f }
        assertTrue(inner.isNotEmpty())

        for (vertex in inner) {
            assertEquals(0.75f, maxOf(abs(vertex.position.x), abs(vertex.position.y)), tolerance)
        }
    }

    /// A stroke thicker than the shape has room for would turn the inner ring
    /// inside out — corners crossing past each other and the band folding back
    /// through itself. Held where the ring closes, which is a solid shape.
    @Test
    fun `stroke thicker than the shape is drawn solid`() {
        val overstroked = described(InertiaShapeType.square, 2f, 2f, fill = null, stroke = blue, strokeWidth = 10f)

        for (vertex in overstroked.triangles()) {
            assertTrue(abs(vertex.position.x) <= 1.0001f)
            assertTrue(abs(vertex.position.y) <= 1.0001f)
        }
    }

    /// Every described vector can be stroked, not just the ones with corners: a
    /// round one is stroked around all 48 of the segments it is cut into, and a
    /// triangle around its three sharp corners.
    @Test
    fun `every described vector can be stroked`() {
        for (type in InertiaShapeType.entries) {
            val stroked = described(type, 3f, 1f, fill = null, stroke = blue, strokeWidth = 0.1f)

            // Two triangles per edge of the outline, and the outline closes.
            assertEquals(stroked.resolvedVertices().size * 6, stroked.triangles().size, "$type")
        }
    }

    /// One authored vector is the same drawing on every runtime, stroke and all:
    /// the Swift and WebGL runtimes cut the same ring into the same band.
    @Test
    fun `stroked square matches the other runtimes corner for corner`() {
        val stroked = described(InertiaShapeType.square, 2f, 2f, fill = null, stroke = blue, strokeWidth = 0.25f)
        val triangles = stroked.triangles()

        assertEquals(24, triangles.size)
        assertEquals(InertiaPoint(-1f, -1f), triangles[0].position)
        assertEquals(InertiaPoint(1f, -1f), triangles[1].position)
        assertEquals(InertiaPoint(0.75f, -0.75f), triangles[2].position)
    }

    // MARK: - The shape a press lands on

    /// A described vector under a name of its own, filled unless it was asked
    /// for as an outline.
    private fun drawn(
        id: String,
        type: InertiaShapeType,
        size: Float,
        stroke: Float = 0f,
        transforms: InertiaAnimationValues? = null,
        zIndex: Int = 0,
        shapes: List<InertiaShape> = emptyList()
    ) = InertiaShape(
        id = id,
        shape = InertiaShapeProperties(
            id = "$id-properties",
            type = type,
            width = size,
            height = size,
            fill = if (stroke > 0f) null else red,
            stroke = if (stroke > 0f) red else null,
            strokeWidth = stroke
        ),
        shapes = shapes,
        zIndex = zIndex,
        transforms = transforms
    )

    /// The middle of a shape is the shape. The whole of picking one by touching
    /// it rests on this.
    @Test
    fun `press on a shape finds it`() {
        assertEquals("square", listOf(drawn("square", InertiaShapeType.square, 1f)).hitTest(InertiaPoint(0f, 0f))?.id)
    }

    /// What a bounding box would have got wrong, and the reason the artwork is
    /// tested rather than the box around it: the corner of a circle's box is not
    /// the circle, so a press there has to go on through to whatever is behind.
    @Test
    fun `press in the corner of a round shapes box misses it`() {
        val circle = listOf(drawn("circle", InertiaShapeType.circle, 1f))

        // Just inside the box, which spans +/-0.5 in both directions, and well
        // outside the radius of 0.5 that the circle actually fills.
        assertNull(circle.hitTest(InertiaPoint(0.49f, 0.49f)))
        assertEquals("circle", circle.hitTest(InertiaPoint(0.4f, 0f))?.id)
    }

    /// A shape drawn as its outline alone encloses nothing in the middle, so a
    /// press through the hole falls to what is behind it rather than sticking to
    /// the ring around it.
    @Test
    fun `press through an unfilled shape misses it`() {
        val ring = listOf(drawn("ring", InertiaShapeType.square, 1f, stroke = 0.1f))

        assertNull(ring.hitTest(InertiaPoint(0f, 0f)))
        // On the band itself, which runs the 0.1 inside the edge at 0.5.
        assertEquals("ring", ring.hitTest(InertiaPoint(0.45f, 0f))?.id)
    }

    /// Nothing under the finger at all.
    @Test
    fun `press outside every shape finds nothing`() {
        assertNull(listOf(drawn("square", InertiaShapeType.square, 1f)).hitTest(InertiaPoint(4f, 4f)))
        assertNull(emptyList<InertiaShape>().hitTest(InertiaPoint(0f, 0f)))
    }

    /// Two shapes over one another hand the press to the one on top, which is
    /// the one the z-indexes draw last — not the one written first.
    @Test
    fun `press on overlapping shapes picks the one drawn on top`() {
        val drawing = listOf(
            drawn("under", InertiaShapeType.square, 1f, zIndex = 5),
            drawn("over", InertiaShapeType.square, 1f, zIndex = 9)
        )

        assertEquals("over", drawing.hitTest(InertiaPoint(0f, 0f))?.id)
        assertEquals("over", drawing.asReversed().hitTest(InertiaPoint(0f, 0f))?.id)
    }

    /// A press is tested against where a shape is *drawn*, not where its corners
    /// were authored: a placement moves the shape, and it has to move what
    /// answers for it.
    @Test
    fun `press follows a placed shape`() {
        val shifted = listOf(
            drawn("square", InertiaShapeType.square, 1f, transforms = InertiaAnimationValues(translate = listOf(2f, 0f)))
        )

        assertNull(shifted.hitTest(InertiaPoint(0f, 0f)))
        assertEquals("square", shifted.hitTest(InertiaPoint(2f, 0f))?.id)
    }

    /// Turning is undone as well as moving, and in the right order — the shape
    /// is turned where it was drawn and only then moved there, so unwinding the
    /// move first is what puts the press back on the artwork.
    @Test
    fun `press follows a turned and moved shape`() {
        val bar = InertiaShape(
            id = "bar",
            shape = InertiaShapeProperties(id = "bar-properties", type = InertiaShapeType.rectangle, width = 4f, height = 0.5f, fill = red),
            transforms = InertiaAnimationValues(translate = listOf(2f, 0f), rotate = 45f)
        )

        val corner = 2f * kotlin.math.cos(PI.toFloat() / 4f)

        assertEquals("bar", listOf(bar).hitTest(InertiaPoint(2f + corner * 0.9f, corner * 0.9f))?.id)
        // The same distance out along the axis the bar is no longer on.
        assertNull(listOf(bar).hitTest(InertiaPoint(2f + corner * 0.9f, -corner * 0.9f)))
    }

    /// A nested shape is drawn into its parent's vertex buffer and has no canvas
    /// of its own, but it is a row of its own in the editor's hierarchy — so a
    /// press on it has to name the child rather than the parent it was drawn
    /// inside of.
    @Test
    fun `press on a nested shape finds the child`() {
        val child = drawn("child", InertiaShapeType.square, 0.25f, transforms = InertiaAnimationValues(translate = listOf(0.3f, 0f)))
        val parent = listOf(drawn("parent", InertiaShapeType.square, 2f, shapes = listOf(child)))

        // The child is a quarter of the parent's box wide — 0.5 across — and
        // sits 0.6 out from the middle of a parent two wide.
        assertEquals("child", parent.hitTest(InertiaPoint(0.6f, 0f))?.id)
        // Parent where the child is not.
        assertEquals("parent", parent.hitTest(InertiaPoint(-0.6f, 0f))?.id)
    }

    /// A press misses a shape scaled away to nothing, which is also a shape with
    /// no area left to draw.
    @Test
    fun `press misses a shape scaled to nothing`() {
        val gone = InertiaAnimationValues(scale = 0f)

        assertNull(listOf(drawn("square", InertiaShapeType.square, 1f, transforms = gone)).hitTest(InertiaPoint(0f, 0f)))
    }
}
