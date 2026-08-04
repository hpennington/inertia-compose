package org.inertiagraphics.inertia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.decodeFromByteArray

/// The track maths the editor's playhead depends on. Every runtime has to agree
/// on these or the same authored animation plays at a different rate on each
/// platform, and the playhead follows none of them.
class PlaybackTrackTest {

    private fun values(translateX: Float = 0f, opacity: Float = 1f) = InertiaAnimationValues(
        scale = 1f,
        translate = listOf(translateX, 0f),
        rotate = 0f,
        rotateCenter = 0f,
        opacity = opacity
    )

    private fun schema(
        invokeType: InertiaAnimationInvokeType = InertiaAnimationInvokeType.trigger,
        keyframes: List<InertiaAnimationKeyframe>
    ) = InertiaAnimationSchema(
        id = "card0",
        initialValues = values(translateX = 0f),
        invokeType = invokeType,
        keyframes = keyframes
    )

    @Test
    fun `track duration sums the keyframes and ignores padding`() {
        val subject = schema(
            keyframes = listOf(
                InertiaAnimationKeyframe(id = "a", values = values(0.5f), duration = 0.5f),
                InertiaAnimationKeyframe(id = "b", values = values(1f), duration = 0.75f)
            )
        )

        assertEquals(1.25f, subject.trackDuration(), 0.0001f)
    }

    /// A zero-length keyframe — two captured at the same playhead position —
    /// would divide by zero when the segment is solved.
    @Test
    fun `zero length keyframes are given a minimum duration`() {
        val subject = schema(
            keyframes = listOf(InertiaAnimationKeyframe(id = "a", values = values(1f), duration = 0f))
        )

        assertEquals(0.001f, subject.trackDuration(), 0.00001f)
    }

    /// Without this a one-second track would restart three times while a
    /// three-second one runs once.
    @Test
    fun `a short track is held at its final values until the loop is up`() {
        val subject = schema(
            keyframes = listOf(InertiaAnimationKeyframe(id = "a", values = values(1f), duration = 1f))
        )

        val filled = subject.keyframesFilling(3f)

        assertEquals(2, filled.size)
        assertEquals("a--hold", filled[1].id)
        assertEquals(2f, filled[1].duration, 0.0001f)
        assertEquals(3f, filled.fold(0f) { total, k -> total + k.duration }, 0.0001f)
    }

    @Test
    fun `a track already as long as the loop is not padded`() {
        val subject = schema(
            keyframes = listOf(InertiaAnimationKeyframe(id = "a", values = values(1f), duration = 3f))
        )

        assertEquals(1, subject.keyframesFilling(3f).size)
    }

    @Test
    fun `the playhead starts at the schema's initial values`() {
        val subject = schema(
            keyframes = listOf(InertiaAnimationKeyframe(id = "a", values = values(1f), duration = 1f))
        )

        assertEquals(0f, subject.valuesAtTime(0f, 3f).translate[0], 0.0001f)
    }

    /// Past the end of its own track, a padded track holds rather than wrapping:
    /// wrapping is the clock's job, at the end of the loop.
    @Test
    fun `the playhead holds the final values across the padding`() {
        val subject = schema(
            keyframes = listOf(InertiaAnimationKeyframe(id = "a", values = values(1f), duration = 1f))
        )

        assertEquals(1f, subject.valuesAtTime(1f, 3f).translate[0], 0.0001f)
        assertEquals(1f, subject.valuesAtTime(2.5f, 3f).translate[0], 0.0001f)
    }

    /// A run that plays once is as long as its own track, so it reaches its final
    /// values when the track ends rather than being stretched across the loop.
    @Test
    fun `a track that plays once is not padded to the loop`() {
        val subject = schema(
            keyframes = listOf(InertiaAnimationKeyframe(id = "a", values = values(1f), duration = 1f))
        )

        // Half a second in: halfway along its own track, but only a sixth of the
        // way through a three-second loop.
        val once = subject.valuesAtTime(0.5f, 3f, isRepeating = false)
        val looping = subject.valuesAtTime(0.5f, 3f, isRepeating = true)

        assertEquals(0.5f, once.translate[0], 0.0001f)
        assertEquals(0.5f, looping.translate[0], 0.0001f)

        // Past the end of the track both hold, but only the padded one was ever
        // going to still be moving.
        assertEquals(1f, subject.valuesAtTime(1f, 3f, isRepeating = false).translate[0], 0.0001f)
    }

    /// Eased in and out of every segment, approximating the cubic keyframes the
    /// iOS runtime plays — so the midpoint is halfway and the quarter point is
    /// not.
    @Test
    fun `segments are eased rather than linear`() {
        val subject = schema(
            keyframes = listOf(InertiaAnimationKeyframe(id = "a", values = values(1f), duration = 1f))
        )

        assertEquals(0.5f, subject.valuesAtTime(0.5f, 1f).translate[0], 0.0001f)
        assertEquals(0.0625f, subject.valuesAtTime(0.25f, 1f).translate[0], 0.0001f)
    }

    /// A NaN in a schema must not reach the layer and blank the view out.
    @Test
    fun `values that are not finite fall back to the identity transform`() {
        val broken = InertiaAnimationValues(scale = Float.NaN)

        assertEquals(1f, broken.sanitized().scale, 0.0001f)
    }

    /// The runtime works this out for the app it is animating and a canvas view
    /// works it out for the schemas it draws on its own. Both have to reach the
    /// same answer, or a track padded in one is a different length from the same
    /// track padded in the other and the two playheads drift apart over the loop.
    @Test
    fun `one turn of the timeline is the loop, or the longest track past it`() {
        val short = schema(
            keyframes = listOf(InertiaAnimationKeyframe(id = "a", values = values(1f), duration = 1f))
        )
        val overrunning = schema(
            keyframes = listOf(InertiaAnimationKeyframe(id = "b", values = values(1f), duration = 4.5f))
        )

        assertEquals(3f, InertiaPlayback.duration(3f, listOf(short)), 0.0001f)
        assertEquals(4.5f, InertiaPlayback.duration(3f, listOf(short, overrunning)), 0.0001f)
        assertEquals(3f, InertiaPlayback.duration(3f, emptyList()), 0.0001f)
    }

    /// What a canvas view reads is what the runtime reads: one function behind
    /// both, so a shape drawn with none of the app around it is at the frame the
    /// app is showing.
    @Test
    fun `sampling at a playhead is the same read whichever asks for it`() {
        val subject = schema(
            keyframes = listOf(InertiaAnimationKeyframe(id = "a", values = values(1f), duration = 1f))
        )

        // Padded: past the end of its own track, held across the loop.
        assertEquals(
            subject.valuesAtTime(2.5f, 3f).translate[0],
            subject.valuesAt(2.5f, filling = 3f).translate[0],
            0.0001f
        )

        // Unpadded, which is what a run that plays once is.
        assertEquals(
            subject.valuesAtTime(0.5f, 3f, isRepeating = false).translate[0],
            subject.valuesAt(0.5f, filling = null).translate[0],
            0.0001f
        )
    }

    @Test
    fun `loop durations are clamped into range`() {
        assertEquals(0.1f, InertiaPlayback.clampLoopDuration(0.01f), 0.0001f)
        assertEquals(60f, InertiaPlayback.clampLoopDuration(120f), 0.0001f)
        assertEquals(3f, InertiaPlayback.clampLoopDuration(Float.NaN), 0.0001f)
    }

    /// Swift synthesizes `Codable` for an enum with associated values as a
    /// single-key map, which is the only shape signals arrive in.
    ///
    /// These are the literal bytes `MessagePackEncoder` writes for an
    /// `AnimationSignal`, rather than bytes this runtime produced for itself:
    /// what matters is that the editor's encoding decodes here, and a fixture
    /// written by the same library on both sides would never show it failing to.
    /// `81` opens a one-entry map, `a4`/`a5` a short string, `ca` a float32, and
    /// `80` is the empty map a case with no associated value encodes to.
    @Test
    fun `editor signals decode from Swift's synthesized encoding`() {
        fun decode(hex: String) = decodeAnimationSignal(
            inertiaMsgPack.decodeFromByteArray<AnimationSignalDTO>(hex.hexToBytes())
        )

        // {"pause": {}}
        assertEquals(AnimationSignal.Pause, decode("81a5706175736580"))
        // {"resume": {}}
        assertEquals(AnimationSignal.Resume, decode("81a6726573756d6580"))
        // {"seek": {"_0": 1.25}}
        assertEquals(AnimationSignal.Seek(1.25f), decode("81a47365656b81a25f30ca3fa00000"))
        // {"setLoopDuration": {"_0": 4.5}}
        assertEquals(
            AnimationSignal.SetLoopDuration(4.5f),
            decode("81af7365744c6f6f704475726174696f6e81a25f30ca40900000")
        )
        // {"somethingElse": {}}
        assertNull(decode("81ad736f6d657468696e67456c736580"))
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
