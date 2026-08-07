package org.inertiagraphics.inertia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// Who starts an animation, and who does not.
///
/// `invokeType` is the whole of that answer: an `auto` animation plays as soon
/// as the runtime holds it, a `trigger` one waits for the app whatever else is
/// going on around it. The SwiftUI runtime's `StandalonePlaybackTests` covers
/// the same ground, since the two have to agree on it for one authored file to
/// play the same way on both.
class PlaybackInvokeTypeTest {

    private fun values(translateX: Float = 0f) = InertiaAnimationValues(
        scale = 1f,
        translate = listOf(translateX, 0f),
        rotate = 0f,
        rotateCenter = 0f,
        opacity = 1f
    )

    private fun schema(id: String, invokeType: InertiaAnimationInvokeType) = InertiaAnimationSchema(
        id = id,
        initialValues = values(),
        invokeType = invokeType,
        keyframes = listOf(
            InertiaAnimationKeyframe(id = "k", values = values(0.2f), duration = 0.5f)
        )
    )

    /// One `trigger` card and one `auto` card, the shape of
    /// `example/demo.inertia`.
    private fun controller(): InertiaPlaybackController {
        val controller = InertiaPlaybackController()

        controller.setSchemas(
            mapOf(
                "card0" to schema("card0", InertiaAnimationInvokeType.trigger),
                "card1" to schema("card1", InertiaAnimationInvokeType.auto)
            )
        )

        return controller
    }

    @Test
    fun `an auto animation starts as it registers and a trigger one waits`() {
        val subject = controller()

        subject.register("card0", InertiaAnimationInvokeType.trigger)
        subject.register("card1", InertiaAnimationInvokeType.auto)

        assertTrue(subject.isPlaying("card1"))
        assertFalse(subject.isPlaying("card0"))
        assertTrue(subject.isRunning)
    }

    /// Arriving on a screen plays what that screen plays by itself: the `auto`
    /// animations, from the top.
    @Test
    fun `restartAll starts the auto animations`() {
        val subject = controller()

        subject.register("card1", InertiaAnimationInvokeType.auto)
        // Somewhere other than the top, so the rewind is visible.
        subject.applySignal(AnimationSignal.Seek(1f), 1)

        subject.restartAll()

        assertTrue(subject.isPlaying("card1"))
        assertTrue(subject.isRunning)
        assertEquals(0f, subject.playheadTime)
        assertNull(subject.seekTime)
    }

    /// A navigation is not the [InertiaPlaybackController.trigger] call a
    /// `trigger` animation is waiting for. It sits at its initial values until
    /// the app makes it — including one that had already played on the screen
    /// being returned to.
    @Test
    fun `restartAll leaves trigger animations waiting`() {
        val subject = controller()

        subject.register("card0", InertiaAnimationInvokeType.trigger)
        subject.register("card1", InertiaAnimationInvokeType.auto)
        subject.trigger("card0")

        subject.restartAll()

        assertFalse(subject.isPlaying("card0"))

        subject.trigger("card0")
        assertTrue(subject.isPlaying("card0"))
    }

    /// A cancellation belongs to the screen it was made on, so the app's next
    /// trigger after a navigation is answered rather than dropped.
    @Test
    fun `restartAll clears a cancellation`() {
        val subject = controller()

        subject.register("card0", InertiaAnimationInvokeType.trigger)
        subject.cancel("card0")

        subject.restartAll()

        assertFalse(subject.isCancelled("card0"))
        assertTrue(subject.isPlaying("card1"), "the auto animation plays whatever the app cancelled")
    }

    /// A screen of nothing but `trigger` animations has no run for the playhead
    /// to follow.
    @Test
    fun `restartAll with nothing to play leaves the clock down`() {
        val subject = InertiaPlaybackController()
        subject.setSchemas(mapOf("card0" to schema("card0", InertiaAnimationInvokeType.trigger)))

        subject.register("card0", InertiaAnimationInvokeType.trigger)
        subject.restartAll()

        assertFalse(subject.isRunning)
    }
}
