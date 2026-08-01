package org.inertiagraphics.inertia

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/// The shapes an actionable's canvas draws: how they are authored alongside an
/// animation, and how they reach the renderer. The Swift and web runtimes carry
/// the same cases — a shape that lands somewhere else on one platform is a
/// shape that was authored once and drawn twice differently.
class ShapeTest {

    private val json = Json { ignoreUnknownKeys = true }

    /// Well inside a pixel at any sane container size, and wide enough for the
    /// error a couple of float operations can accumulate.
    private val tolerance = 1e-5f

    private fun corner(x: Float, y: Float) = Vertex(
        position = InertiaPoint(x, y),
        color = InertiaColor(red = 1f, green = 1f, blue = 1f, alpha = 1f)
    )

    /// The two kinds of entry `example/demo.inertia/animations/animation.json`
    /// holds: a card with a shape behind it, and one without the key at all.
    private val demoJson = """
    [
      {
        "id": "card0",
        "initialValues": {"opacity": 1, "rotate": 0, "rotateCenter": 0, "scale": 1, "translate": [0, 0]},
        "invokeType": "trigger",
        "keyframes": [],
        "shapes": [
          {
            "vertices": [
              {"position": {"x": 0, "y": 0}, "color": {"red": 0.35, "green": 0.1, "blue": 0.85, "alpha": 0.6}},
              {"position": {"x": 1, "y": 0}, "color": {"red": 0.1, "green": 0.55, "blue": 0.95, "alpha": 0.6}},
              {"position": {"x": 1, "y": 1}, "color": {"red": 0.1, "green": 0.85, "blue": 0.75, "alpha": 0.6}},
              {"position": {"x": 0, "y": 1}, "color": {"red": 0.35, "green": 0.1, "blue": 0.85, "alpha": 0.6}}
            ]
          }
        ]
      },
      {
        "id": "card1",
        "initialValues": {"opacity": 1, "rotate": 0, "rotateCenter": 0, "scale": 1, "translate": [0, 0]},
        "invokeType": "auto",
        "keyframes": []
      }
    ]
    """.trimIndent()

    private fun demoSchemas(): Map<String, InertiaAnimationSchema> =
        json.decodeFromString<List<InertiaAnimationSchema>>(demoJson).associateBy { it.id }

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
        assertEquals(emptyList(), demoSchemas().getValue("card1").shapes)
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
}
