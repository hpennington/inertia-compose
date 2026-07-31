package org.inertiagraphics.inertia

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import okhttp3.*
import okio.ByteString
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

// ========== LOGGING ==========

/// Traces the path a schema takes from the socket to the screen. Silent unless
/// switched on, since the alternative to a log here is guessing which of the
/// three hops — schema arrival, animation lookup, clock — dropped an animation.
object InertiaLog {
    var isEnabled: Boolean = true

    fun debug(message: String) {
        if (isEnabled) Log.d("Inertia", message)
    }
}

// ========== DATA MODELS ==========

/// The retired wire format, where a container schema carried shape objects with
/// an animation nested in each. The editor sends animation schemas on their own
/// now — see [InertiaSchemaWrapper] — so nothing decodes into this any more.
@Serializable
data class InertiaSchema(
    val id: String,
    val objects: List<InertiaShape> = emptyList()
)

@Serializable
data class InertiaCanvasSize(val width: Int, val height: Int)

@Serializable
enum class MessageType { actionable, actionables, schema }

@Serializable
data class MessageWrapper(
    val type: String,
    val payload: String
)

@Serializable
data class InertiaAnimationValues(
    val scale: Float = 1.0f,
    val translate: List<Float> = listOf(0.0f, 0.0f),  // [x, y] normalized (-1..1)
    val rotate: Float = 0.0f,
    val rotateCenter: Float = 0.0f,
    val opacity: Float = 1.0f
)

@Serializable
enum class InertiaAnimationInvokeType { trigger, auto }

@Serializable
data class InertiaAnimationKeyframe(
    val id: String,
    val values: InertiaAnimationValues,
    val duration: Float // seconds
)

@Serializable
data class InertiaAnimationSchema(
    val id: String,
    val initialValues: InertiaAnimationValues = InertiaAnimationValues(),
    val invokeType: InertiaAnimationInvokeType,
    val keyframes: List<InertiaAnimationKeyframe> = emptyList()
)

@Serializable
data class AnimationContainer(
    val actionableId: String,
    val containerId: String
)

@Serializable
enum class InertiaObjectType { shape, animation }

@Serializable
data class InertiaShape(
    val id: String,
    val containerId: String,
    val width: Float,
    val height: Float,
    val position: List<Float>,  // [x, y]
    val color: List<Float>,
    val shape: String,
    val objectType: InertiaObjectType,
    val zIndex: Int,
    val animation: InertiaAnimationSchema
)

@Serializable
data class InertiaAnimationState(
    val id: String,
    val trigger: Boolean? = null,
    val isCancelled: Boolean = false
)

class InertiaDataModel(
    val containerId: String,
    var tree: Tree,
    var actionableIds: MutableSet<ActionableIdPair>
) {
    /// The animation schemas the editor has sent, keyed by `animationId`. An
    /// actionable finds its own through [actionableIdToAnimationIdMap], which is
    /// the indirection that lets two instances of the same card share a track.
    val inertiaSchemas: MutableMap<String, InertiaAnimationSchema> = mutableMapOf()
    val states: MutableMap<String, InertiaAnimationState> = mutableMapOf()
    val actionableIdToAnimationIdMap: MutableMap<String, String> = mutableMapOf()
    var isActionable: Boolean = false
}

// ========== TREE SYSTEM ==========

@Serializable
data class NodeDTO(
    val id: String,
    val parentId: String? = null,
    val children: List<NodeDTO>? = null
)

class Node(val id: String, var parentId: String? = null) {
    var parent: Node? = null
    val children: MutableList<Node> = mutableListOf()
    var tree: Tree? = null

    fun addChild(child: Node) {
        // avoid adding the same child multiple times
        if (children.any { it.id == child.id }) {
            // ensure parent reference is correct
            child.parent = this
            child.parentId = id
            return
        }
        child.parent = this
        child.parentId = id
        children += child
    }

    fun link() {
        if (parentId != null && tree != null) {
            parent = tree!!.nodeMap[parentId]
        }
        children.forEach { it.link() }
    }

    fun toDTO(): NodeDTO = NodeDTO(
        id = id,
        parentId = parentId,
        children = if (children.isEmpty()) null else children.map { it.toDTO() }
    )

    override fun toString() =
        "{id: $id, parentId: $parentId, children: [${children.joinToString { it.id }}]}"
}

@Serializable
data class TreeDTO(
    val id: String,
    val nodeMap: Map<String, NodeDTO> = emptyMap(),
    val rootNode: NodeDTO? = null
)

class Tree(val id: String) {
    var rootNode: Node? = null
    val nodeMap: MutableMap<String, Node> = mutableMapOf()

    fun addRelationship(id: String, parentId: String?, parentIsContainer: Boolean = false) {
        val current = nodeMap.getOrPut(id) {
            Node(id, parentId).also { it.tree = this }
        }
        if (parentId != null) {
            val parent = nodeMap.getOrPut(parentId) {
                Node(parentId).also { it.tree = this }
            }
            parent.addChild(current)
            if (parentIsContainer || (rootNode == null && parent.parent == null)) {
                rootNode = parent
            }
        }
    }

    fun toDTO(): TreeDTO {
        val map = nodeMap.mapValues { (_, node) -> node.toDTO() }
        return TreeDTO(id = id, nodeMap = map, rootNode = rootNode?.toDTO())
    }

    companion object {
        fun fromDTO(dto: TreeDTO): Tree {
            val t = Tree(dto.id)
            fun build(n: NodeDTO): Node {
                val node = Node(n.id, n.parentId)
                node.tree = t
                n.children?.let { children ->
                    node.children.addAll(children.map { build(it) })
                }
                return node
            }
            dto.nodeMap.forEach { (k, v) ->
                t.nodeMap[k] = build(v)
            }
            t.rootNode = dto.rootNode?.let { build(it) }
            t.nodeMap.values.forEach { it.link() }
            return t
        }
    }
}

// ========== WEBSOCKET MESSAGES ==========

@Serializable
data class MessageActionableWrapper(val type: String, val payload: MessageActionable)

@Serializable
data class MessageActionablesWrapper(val type: String, val payload: MessageActionables)

@Serializable
data class MessageSchemaWrapper(val type: String, val payload: MessageSchema)

@Serializable
data class MessageTranslationWrapper(val type: String, val payload: MessageTranslation)

@Serializable
data class MessagePlaybackProgressWrapper(val type: String, val payload: MessagePlaybackProgress)

/// Where the run currently on screen has got to, reported while it animates so
/// the editor's playhead can follow it.
@Serializable
data class MessagePlaybackProgress(
    /// Seconds into the loop.
    val time: Float,
    /// One turn of the timeline — the longest track, or the loop, whichever is
    /// longer.
    val duration: Float,
    /// False on the last message of a run: it finished or was paused.
    val isRunning: Boolean,
    /// The `sequence` of the last signal applied when this report was produced,
    /// so the editor can tell a report caused by its own request from one still
    /// in flight from before it.
    val lastProcessedSequence: Int
)

/// The editor's transport commands.
sealed interface AnimationSignal {
    data object Pause : AnimationSignal
    data object Resume : AnimationSignal
    /// The playhead was moved by hand; hold the animation this many seconds
    /// into the loop.
    data class Seek(val time: Float) : AnimationSignal
    /// The editor's timeline was resized; play one loop over this many seconds.
    data class SetLoopDuration(val duration: Float) : AnimationSignal
}

/// Swift synthesizes `Codable` for an enum with associated values as a
/// single-key object — `{"seek": {"_0": 1.25}}` — which is what this unpacks.
/// The editor is the only sender, so this is the only shape signals arrive in.
internal fun decodeAnimationSignal(raw: JsonObject?): AnimationSignal? {
    if (raw == null) return null

    if (raw.containsKey("pause")) return AnimationSignal.Pause
    if (raw.containsKey("resume")) return AnimationSignal.Resume

    raw["seek"]?.jsonObject?.get("_0")?.jsonPrimitive?.floatOrNull?.let {
        return AnimationSignal.Seek(it)
    }
    raw["setLoopDuration"]?.jsonObject?.get("_0")?.jsonPrimitive?.floatOrNull?.let {
        return AnimationSignal.SetLoopDuration(it)
    }

    return null
}

/// How an actionable is named on the wire: the prefix the app authored it
/// against, plus the per-instance id the runtime derived from it. The editor
/// needs both — the prefix to find the animation, the id to tell two instances
/// of the same card apart — so neither travels without the other.
@Serializable
data class ActionableIdPair(
    val hierarchyIdPrefix: String,
    val hierarchyId: String
)

@Serializable
data class MessageActionables(
    val tree: TreeDTO,
    val actionableIds: Set<ActionableIdPair>
)

@Serializable
data class MessageActionable(
    val isActionable: Boolean
)

@Serializable
data class InertiaSchemaWrapper(
    val schema: InertiaAnimationSchema,
    val actionableId: String,
    val container: AnimationContainer,
    val animationId: String
)

@Serializable
data class MessageSchema(
    val schemaWrappers: List<InertiaSchemaWrapper>
)

@Serializable
data class MessageTranslation(
    val translationX: Float,
    val translationY: Float,
    val actionableIds: Set<ActionableIdPair>
)

// ========== WEBSOCKET CLIENT ==========

class WebSocketClient private constructor() : WebSocketListener() {
    companion object {
        val shared: WebSocketClient by lazy { WebSocketClient() }
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    private var socket: WebSocket? = null
    var isConnected: Boolean = false
        private set

    private val _onSelectedIds = MutableSharedFlow<Set<ActionableIdPair>>(replay = 0)
    val onSelectedIds = _onSelectedIds.asSharedFlow()

    private val _onSchema = MutableSharedFlow<List<InertiaSchemaWrapper>>(replay = 0)
    val onSchema = _onSchema.asSharedFlow()

    private val _onIsActionable = MutableSharedFlow<Boolean>(replay = 0)
    val onIsActionable = _onIsActionable.asSharedFlow()

    private val _onSignal = MutableSharedFlow<Pair<AnimationSignal, Int>>(replay = 0)
    val onSignal = _onSignal.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var onConnected: (() -> Unit)? = null

    fun connect(url: String, onConnect: () -> Unit = {}) {
        if (isConnected) return
        val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, this)
        onConnected = onConnect
    }

    fun sendMessageActionables(type: String, message: MessageActionables) {
        val wrapper = MessageActionablesWrapper(type, message)
        sendJson(wrapper)
    }

    fun sendMessageSchema(type: String, message: MessageSchema) {
        val wrapper = MessageSchemaWrapper(type, message)
        sendJson(wrapper)
    }

    fun sendMessageTranslation(message: MessageTranslation) {
        val wrapper = MessageTranslationWrapper("translationEnded", message)
        sendJson(wrapper)
    }

    /// The playhead moves every frame, and a stall anywhere downstream would let
    /// sends pile up in the socket layer and then burst. Reports are dropped
    /// while the socket still has bytes to drain; the next frame produces
    /// another one.
    ///
    /// The last report of a run is exempt: `isRunning: false` is what returns the
    /// editor's transport controls to their paused state, and there is no
    /// following report to carry it if this one is dropped.
    fun sendMessagePlaybackProgress(message: MessagePlaybackProgress) {
        if (!isConnected) return
        if (message.isRunning && (socket?.queueSize() ?: 0L) > 0L) return

        sendJson(MessagePlaybackProgressWrapper("playbackProgress", message))
    }

    private fun sendJson(wrapper: Any) {
        if (!isConnected || socket == null) return
        try {
            // Serialize inner payload to JSON string
            val payloadJson = when (wrapper) {
                is MessageActionableWrapper -> json.encodeToString(wrapper.payload)
                is MessageActionablesWrapper -> json.encodeToString(wrapper.payload)
                is MessageSchemaWrapper -> json.encodeToString(wrapper.payload)
                is MessageTranslationWrapper -> json.encodeToString(wrapper.payload)
                is MessagePlaybackProgressWrapper -> json.encodeToString(wrapper.payload)
                else -> return
            }

            // Encode JSON string to Base64
            val payloadBase64 = Base64.getEncoder().encodeToString(payloadJson.toByteArray(StandardCharsets.UTF_8))

            // Wrap with type + Base64 payload
            val type = when (wrapper) {
                is MessageActionableWrapper -> "actionable"
                is MessageActionablesWrapper -> "actionables"
                is MessageSchemaWrapper -> "schema"
                is MessageTranslationWrapper -> "translationEnded"
                is MessagePlaybackProgressWrapper -> "playbackProgress"
                else -> return
            }

            val wrapperObj = MessageWrapper(type, payloadBase64)
            val wrapperJson = json.encodeToString(wrapperObj)

            // Send as text WebSocket frame
            socket?.send(wrapperJson)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        isConnected = true
        onConnected?.invoke()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        runCatching {
            // Parse the outer wrapper
            val wrapper = json.decodeFromString<MessageWrapper>(text)

            // Decode Base64 payload
            val payloadBytes = Base64.getDecoder().decode(wrapper.payload)
            val payloadJson = String(payloadBytes, StandardCharsets.UTF_8)

            // Deserialize based on type
            when (wrapper.type) {
                "actionable" -> {
                    val decoded = json.decodeFromString<MessageActionable>(payloadJson)
                    scope.launch { _onIsActionable.emit(decoded.isActionable) }
                }
                "actionables" -> {
                    val decoded = json.decodeFromString<MessageActionables>(payloadJson)
                    scope.launch { _onSelectedIds.emit(decoded.actionableIds) }
                }
                "schema" -> {
                    val decoded = json.decodeFromString<MessageSchema>(payloadJson)
                    scope.launch { _onSchema.emit(decoded.schemaWrappers) }
                }
                "signal" -> {
                    // Decoded by hand: the signal is a Swift enum with associated
                    // values, whose synthesized encoding has no fixed key set.
                    val obj = json.parseToJsonElement(payloadJson).jsonObject
                    val signal = decodeAnimationSignal(obj["signal"]?.jsonObject) ?: return@runCatching
                    val sequence = obj["sequence"]?.jsonPrimitive?.intOrNull ?: 0
                    scope.launch { _onSignal.emit(signal to sequence) }
                }
            }

        }.onFailure { it.printStackTrace() }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        onMessage(webSocket, bytes.string(StandardCharsets.UTF_8))
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        isConnected = false
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        isConnected = false
        t.printStackTrace()
    }
}

// ========== TRACKS ==========

object InertiaPlaybackDefaults {
    /// How long one loop lasts until the editor says otherwise.
    ///
    /// A loop lasts as long as the timeline the animation was authored on, not
    /// as long as its last keyframe: a track that stops moving after half a
    /// second holds there until the loop comes round again. Every track is
    /// padded to the loop, so actionables of different lengths restart together
    /// and the editor's playhead — which draws exactly this span — stays with
    /// them.
    const val defaultLoopDuration: Float = 3.0f

    /// The range the timeline can be resized to. Shorter than this cannot hold a
    /// keyframe apart from its neighbours; longer is past the point of seeing
    /// the whole thing at once.
    const val minLoopDuration: Float = 0.1f
    const val maxLoopDuration: Float = 60.0f

    fun clampLoopDuration(seconds: Float): Float {
        if (!seconds.isFinite()) return defaultLoopDuration
        return seconds.coerceIn(minLoopDuration, maxLoopDuration)
    }
}

private val identityValues = InertiaAnimationValues()

private fun InertiaAnimationValues.isFinite(): Boolean =
    scale.isFinite() &&
        (translate.getOrNull(0)?.isFinite() == true) &&
        (translate.getOrNull(1)?.isFinite() == true) &&
        rotate.isFinite() &&
        rotateCenter.isFinite() &&
        opacity.isFinite()

/// Falls back to the identity transform, so a NaN that slipped into a schema
/// cannot reach the layer and blank the view out.
internal fun InertiaAnimationValues?.sanitized(): InertiaAnimationValues =
    if (this != null && isFinite()) this else identityValues

/// The keyframes that can actually be interpolated. A zero-length keyframe —
/// which the editor records for two keyframes captured at the same playhead
/// position — would divide by zero when solving the segment.
internal fun InertiaAnimationSchema.playableKeyframes(): List<InertiaAnimationKeyframe> =
    keyframes.mapNotNull { keyframe ->
        when {
            !keyframe.values.isFinite() -> null
            !keyframe.duration.isFinite() || keyframe.duration <= 0f -> keyframe.copy(duration = 0.001f)
            else -> keyframe
        }
    }

/// How long this schema's own track runs, before any padding.
internal fun InertiaAnimationSchema.trackDuration(): Float =
    playableKeyframes().fold(0f) { total, keyframe -> total + keyframe.duration }

/// The playable track held at its final values until `duration` is up.
///
/// Without this a track that ends after one second would restart three times
/// while a three-second one runs once, and the playhead — which follows the
/// loop rather than any one actionable — would agree with neither.
internal fun InertiaAnimationSchema.keyframesFilling(duration: Float): List<InertiaAnimationKeyframe> {
    val track = playableKeyframes()
    val last = track.lastOrNull() ?: return track

    val remainder = duration - track.fold(0f) { total, keyframe -> total + keyframe.duration }
    if (remainder <= 0.001f) return track

    return track + InertiaAnimationKeyframe(
        id = "${last.id}--hold",
        values = last.values,
        duration = remainder
    )
}

/// Approximates the runtime's cubic keyframes: eased in and out of every
/// segment rather than a linear ramp between them.
private fun easeInOut(fraction: Float): Float =
    if (fraction < 0.5f) 4f * fraction * fraction * fraction
    else 1f - (-2f * fraction + 2f).pow(3) / 2f

private fun interpolate(
    from: InertiaAnimationValues,
    to: InertiaAnimationValues,
    fraction: Float
): InertiaAnimationValues {
    val t = easeInOut(fraction.coerceIn(0f, 1f))
    fun lerp(a: Float, b: Float) = a + (b - a) * t

    return InertiaAnimationValues(
        scale = lerp(from.scale, to.scale),
        translate = listOf(
            lerp(from.translate.getOrElse(0) { 0f }, to.translate.getOrElse(0) { 0f }),
            lerp(from.translate.getOrElse(1) { 0f }, to.translate.getOrElse(1) { 0f })
        ),
        rotate = lerp(from.rotate, to.rotate),
        rotateCenter = lerp(from.rotateCenter, to.rotateCenter),
        opacity = lerp(from.opacity, to.opacity)
    )
}

/// The values this schema's track reaches `time` seconds into a loop of
/// `loopDuration`.
///
/// Playing, pausing and scrubbing are all the same thing to a runtime that
/// draws from the editor's clock: read the track at the playhead. It is also
/// the only way play can pick up mid-loop.
internal fun InertiaAnimationSchema.valuesAtTime(
    time: Float,
    loopDuration: Float
): InertiaAnimationValues {
    val track = keyframesFilling(loopDuration)
    var previous = initialValues.sanitized()

    if (track.isEmpty()) return previous

    var elapsed = 0f
    for (keyframe in track) {
        val values = keyframe.values.sanitized()
        if (time <= elapsed + keyframe.duration) {
            return interpolate(previous, values, (time - elapsed) / keyframe.duration)
        }
        elapsed += keyframe.duration
        previous = values
    }

    return previous
}

// ========== PLAYBACK ==========

/// Owns the clock every actionable in a container is drawn from, and the app's
/// handle on playback: start an animation the schema left waiting, stop one, or
/// start it over.
///
/// The editor's timeline and the animation on screen have to be the same thing,
/// so nothing here hands a track to Compose's animation system and lets it keep
/// its own time — a run the editor cannot seek into is a run its playhead can
/// only guess at. Instead one clock advances per frame and every actionable
/// samples the values its track reaches at the playhead. Playing, pausing and
/// scrubbing are then all the same operation.
///
/// Reached through [LocalInertia], and keyed by the `hierarchyIdPrefix` the app
/// hands to [Inertiaable] rather than the per-instance hierarchy id the runtime
/// derives from it — the prefix is the id an animation is authored against, so
/// starting one starts every actionable sharing it.
@Stable
class InertiaPlayback internal constructor() {

    /// What the app has asked of each prefix. An absent entry is an animation
    /// nothing has started yet, which is what lets `auto` tell a run it has
    /// already had from one it has not.
    private val states = mutableStateMapOf<String, InertiaAnimationState>()

    /// Every prefix an actionable has registered, and how it starts.
    ///
    /// Held apart from [states] because the editor's play button has to start
    /// animations nothing has touched yet — a `trigger` animation waiting on the
    /// app has no state of its own, and iterating [states] alone would find
    /// nothing to play.
    private val registered = mutableStateMapOf<String, InertiaAnimationInvokeType>()

    /// The schemas the container holds, keyed by `animationId`. Only their
    /// lengths matter here — an actionable samples its own track.
    private var schemas by mutableStateOf<Map<String, InertiaAnimationSchema>>(emptyMap())

    /// How long one loop lasts, as set on the editor's timeline. Read each frame,
    /// so resizing the timeline mid-run stretches the loop rather than waiting
    /// for it to be restarted.
    var loopDuration: Float by mutableFloatStateOf(InertiaPlaybackDefaults.defaultLoopDuration)
        private set

    /// How far into the run currently on screen we are, in seconds.
    var playheadTime: Float by mutableFloatStateOf(0f)
        private set

    var isRunning: Boolean by mutableStateOf(false)
        private set

    /// Where the editor has parked the playhead, while it is parked there.
    /// Non-nil means the run is being scrubbed or paused rather than played.
    var seekTime: Float? by mutableStateOf(null)
        private set

    /// The `sequence` of the last signal applied, echoed back on every progress
    /// report so the editor can tell its own request's effect from a report still
    /// in flight from before it.
    internal var lastProcessedSignalSequence: Int = 0
        private set

    /// Called on every tick of the clock while running, and once more when a run
    /// stops. The container forwards these to the editor.
    internal var onProgress: ((MessagePlaybackProgress) -> Unit)? = null

    /// Whether the editor has asked for playback and not since paused it. Held
    /// across schema arrivals because the two race: a `resume` lands before the
    /// actionable it applies to has registered, and there is nothing yet for it
    /// to start.
    private var isEditorPlaying: Boolean = false

    private var runStartNanos: Long? = null
    private var runOffset: Float = 0f

    /// One turn of the timeline: the loop the editor drew, or the longest track,
    /// whichever is longer. Anything recorded past the end of the loop stretches
    /// it, which keeps every track the same length as every other.
    val playbackDuration: Float
        get() = maxOf(loopDuration, schemas.values.maxOfOrNull { it.trackDuration() } ?: 0f)

    // MARK: - App-facing controls

    /// Starts an animation that was waiting on its `trigger` invoke type.
    ///
    /// A trigger arriving while the clock is already running joins the run in
    /// progress rather than cutting it short — [restart] is the one that starts
    /// over. Cancelled animations are left where they are: stopping one is the
    /// app's call, and picking it back up is [restart]'s.
    fun trigger(hierarchyIdPrefix: String) {
        val state = states[hierarchyIdPrefix]
        if (state?.isCancelled == true || state?.trigger == true) return

        markTriggered(hierarchyIdPrefix)
        seekTime = null
        startClock()
    }

    /// Stops an animation and returns it to its initial values, where it stays
    /// until [restart].
    ///
    /// The clock stops with the last animation running off it, since a playhead
    /// with nothing left to follow is one the editor should see parked.
    fun cancel(hierarchyIdPrefix: String) {
        states[hierarchyIdPrefix] = InertiaAnimationState(
            id = hierarchyIdPrefix,
            trigger = false,
            isCancelled = true
        )

        if (hasTriggeredActionable) return

        stopClock()
        report(isRunning = false)
    }

    /// Clears a cancellation and plays from the top of the timeline.
    ///
    /// Every actionable in a container is drawn from the one clock, so this
    /// rewinds the playhead for all of them rather than for this animation alone
    /// — the same shared clock that makes a trigger mid-run join the run in
    /// progress instead of restarting it.
    fun restart(hierarchyIdPrefix: String) {
        markTriggered(hierarchyIdPrefix)

        stopClock()
        playheadTime = 0f
        seekTime = null
        startClock()
    }

    fun isCancelled(hierarchyIdPrefix: String): Boolean =
        states[hierarchyIdPrefix]?.isCancelled == true

    // MARK: - Registration

    /// Replaces the schemas the loop is measured against.
    ///
    /// Starting the clock here settles a race: an actionable can register — and
    /// mark itself triggered — before the container has handed over the schema
    /// its length comes from, and [startClock] declines to start with nothing to
    /// follow. Not while the playhead is parked, where starting a run would take
    /// it away from whoever is scrubbing.
    internal fun setSchemas(schemas: Map<String, InertiaAnimationSchema>) {
        this.schemas = schemas

        if (seekTime == null && hasTriggeredActionable) startClock()
    }

    /// Starts an animation the app does not have to start itself.
    ///
    /// `invokeType` says who owns the start: a `trigger` animation waits for the
    /// app to call [trigger], an `auto` one runs as soon as the runtime holds its
    /// schema — as does everything else while the editor is playing, since
    /// authoring a `trigger` animation is exactly when nothing is going to call
    /// [trigger] for it. Only ever the first run: an animation the app has since
    /// cancelled, or is already playing, is one the app owns from here.
    internal fun register(hierarchyIdPrefix: String, invokeType: InertiaAnimationInvokeType) {
        registered[hierarchyIdPrefix] = invokeType

        if (invokeType != InertiaAnimationInvokeType.auto && !isEditorPlaying) return
        if (states.containsKey(hierarchyIdPrefix)) return

        markTriggered(hierarchyIdPrefix)
        InertiaLog.debug("register: started $hierarchyIdPrefix ($invokeType)")

        // A parked playhead means the editor is scrubbing, and starting the clock
        // would pull the run out from under whoever is dragging it.
        if (seekTime != null) return

        startClock()
    }

    internal fun isPlaying(hierarchyIdPrefix: String): Boolean {
        val state = states[hierarchyIdPrefix] ?: return false
        return state.trigger == true && !state.isCancelled
    }

    // MARK: - Editor signals

    internal fun applySignal(signal: AnimationSignal, sequence: Int) {
        InertiaLog.debug("signal: $signal (sequence $sequence)")
        lastProcessedSignalSequence = sequence

        when (signal) {
            AnimationSignal.Pause -> pausePlayback()
            AnimationSignal.Resume -> resumePlayback()
            is AnimationSignal.Seek -> seek(signal.time)
            is AnimationSignal.SetLoopDuration -> {
                loopDuration = InertiaPlaybackDefaults.clampLoopDuration(signal.duration)
            }
        }
    }

    /// Stops the run and reports where it stopped, so a paused playhead sits
    /// exactly where the animation froze.
    private fun pausePlayback() {
        isEditorPlaying = false
        stopClock()
        seekTime = playheadTime
        report(isRunning = false)
    }

    /// The editor's play button: runs every animation, whatever its `invokeType`,
    /// picking a paused or scrubbed run back up where it was left.
    ///
    /// A `trigger` animation is waiting on the app to call [trigger], which is not
    /// something the app does while its animation is being authored, so the editor
    /// stands in for the app here. Signals only ever come from the editor, so the
    /// same animation running without the editor attached still waits for its
    /// trigger.
    private fun resumePlayback() {
        isEditorPlaying = true
        // Unparked before the bail-out below: a play following a pause has to
        // release the playhead even when the actionables it applies to have not
        // registered yet, or `register` finds it still parked and declines to
        // start the run.
        seekTime = null

        // Every registered prefix, not just the ones with state: a `trigger`
        // animation waiting on the app has none, and it is exactly what the
        // editor's play button is here to start.
        registered.keys.toList().forEach { prefix ->
            if (states[prefix]?.isCancelled != true) markTriggered(prefix)
        }

        // Nothing to play yet: whatever registers after this will start itself,
        // which is where the race above is settled.
        if (!hasTriggeredActionable) return

        startClock()
    }

    /// Freezes the animation at `time`. The editor is the one moving the playhead
    /// here, so this does not report back: the position it would send is the one
    /// it just asked for.
    private fun seek(time: Float) {
        stopClock()

        val clamped = time.coerceIn(0f, playbackDuration)
        seekTime = clamped
        playheadTime = clamped
    }

    // MARK: - The clock

    private val hasTriggeredActionable: Boolean
        get() = states.values.any { it.trigger == true && !it.isCancelled }

    private fun markTriggered(hierarchyIdPrefix: String) {
        states[hierarchyIdPrefix] = InertiaAnimationState(
            id = hierarchyIdPrefix,
            trigger = true,
            isCancelled = false
        )
    }

    /// Starts the run that just began.
    ///
    /// Playing picks up from wherever the playhead was left — scrubbed to, or
    /// paused at — rather than from the top; only a playhead parked at the very
    /// end of the loop starts over, since there is nothing left to play.
    private fun startClock() {
        if (isRunning) return

        // Nothing loaded yet: there is no animation for the playhead to follow.
        if (schemas.isEmpty()) {
            InertiaLog.debug("startClock: declined, no schemas held yet")
            return
        }

        InertiaLog.debug("startClock: playing ${schemas.size} schema(s), duration=$playbackDuration")

        val duration = playbackDuration
        runOffset = if (playheadTime < duration) playheadTime else 0f
        playheadTime = runOffset
        runStartNanos = null
        isRunning = true
        report(isRunning = true)
    }

    private fun stopClock() {
        isRunning = false
        runStartNanos = null
    }

    /// Advances the playhead. Driven by the container's frame loop, which runs
    /// only while [isRunning].
    internal fun tick(frameNanos: Long) {
        if (!isRunning) return

        val start = runStartNanos
        if (start == null) {
            runStartNanos = frameNanos
            return
        }

        // Read each frame: the timeline can be resized mid-run.
        val duration = playbackDuration
        val elapsed = runOffset + (frameNanos - start) / 1_000_000_000f

        playheadTime = if (duration > 0f) elapsed % duration else 0f
        report(isRunning = true)
    }

    private fun report(isRunning: Boolean) {
        onProgress?.invoke(
            MessagePlaybackProgress(
                time = playheadTime,
                duration = playbackDuration,
                isRunning = isRunning,
                lastProcessedSequence = lastProcessedSignalSequence
            )
        )
    }
}

// ========== COMPOSITION LOCALS ==========

/// Playback for the enclosing [InertiaContainer].
val LocalInertia = staticCompositionLocalOf<InertiaPlayback> {
    error("LocalInertia was read outside of an InertiaContainer.")
}

private val LocalInertiaDataModel = compositionLocalOf<InertiaDataModel?> { null }
private val LocalUpdateModel = compositionLocalOf<((InertiaDataModel) -> InertiaDataModel) -> Unit> { {} }
private val LocalInertiaParentId = compositionLocalOf<String?> { null }
private val LocalInertiaContainerId = compositionLocalOf<String?> { null }
private val LocalInertiaIsContainer = compositionLocalOf<Boolean> { false }
private val LocalCanvasSize = compositionLocalOf<IntSize> { IntSize.Zero }

// ========== SHARED INDEX MANAGER ==========

object SharedIndexManager {
    val indexMap: MutableMap<String, Int> = mutableMapOf()
    val objectIndexMap: MutableMap<String, Int> = mutableMapOf()
    val objectIdSet: MutableSet<String> = mutableSetOf()
}

// ========== COMPOSABLES ==========

@Composable
fun InertiaContainer(
    id: String,
    baseURL: String,
    dev: Boolean = false,
    content: @Composable () -> Unit
) {
    var model by remember {
        mutableStateOf(
            InertiaDataModel(
                containerId = id,
                tree = Tree(id),
                actionableIds = mutableSetOf()
            )
        )
    }

    // Create a stable reference to update model that can be called from children
    val updateModel = remember {
        { updater: (InertiaDataModel) -> InertiaDataModel ->
            model = updater(model)
        }
    }

    var size by remember { mutableStateOf(IntSize.Zero) }

    val playback = remember { InertiaPlayback() }

    LaunchedEffect(model.tree, baseURL) {
        val ws = WebSocketClient.shared

        val host = "192.168.64.1"
        val finalUrl = if (host != null) baseURL.replace("127.0.0.1", host) else baseURL

        ws.connect(url = finalUrl) {
            val msg = MessageActionables(
                tree = model.tree.toDTO(),
                actionableIds = model.actionableIds.toSet()
            )
            ws.sendMessageActionables("actionables", msg)
        }

        launch {
            ws.onSelectedIds.collect { set ->
                model = model.copyMutable { actionableIds = set.toMutableSet() }
            }
        }
        launch {
            ws.onSchema.collect { wrappers ->
                InertiaLog.debug("schema: ${wrappers.size} wrapper(s) for container ${model.containerId}")
                wrappers.forEach { w ->
                    InertiaLog.debug(
                        "schema: container=${w.container.containerId} actionableId=${w.actionableId} " +
                            "animationId=${w.animationId} invokeType=${w.schema.invokeType} " +
                            "keyframes=${w.schema.keyframes.size}"
                    )
                    if (w.container.containerId == model.containerId) {
                        model = model.copyMutable {
                            inertiaSchemas[w.animationId] = w.schema
                            actionableIdToAnimationIdMap[w.actionableId] = w.animationId
                        }
                    }
                }
            }
        }
        launch {
            ws.onIsActionable.collect { value ->
                model = model.copyMutable { isActionable = value }
            }
        }
        launch {
            ws.onSignal.collect { (signal, sequence) ->
                playback.applySignal(signal, sequence)
            }
        }
    }

    // The editor's playhead has no other way to know where the animation is.
    DisposableEffect(playback) {
        playback.onProgress = { WebSocketClient.shared.sendMessagePlaybackProgress(it) }
        onDispose { playback.onProgress = null }
    }

    // Only the lengths are needed here — an actionable samples its own track —
    // but the loop is as long as the longest of them, so they all have to be in.
    LaunchedEffect(model) {
        playback.setSchemas(model.inertiaSchemas.toMap())
    }

    // The clock. Keyed on `isRunning` so a paused or unstarted container is not
    // holding the frame loop open.
    LaunchedEffect(playback.isRunning) {
        if (!playback.isRunning) return@LaunchedEffect

        while (isActive) {
            withFrameNanos { playback.tick(it) }
        }
    }

    Box(
        modifier = Modifier
            .wrapContentSize()
            .onSizeChanged { size = it }
    ) {
        CompositionLocalProvider(
            LocalInertia provides playback,
            LocalCanvasSize provides size,
            LocalInertiaDataModel provides model,
            LocalUpdateModel provides updateModel,
            LocalInertiaParentId provides id,
            LocalInertiaContainerId provides id,
            LocalInertiaIsContainer provides true
        ) { content() }
    }
}

private inline fun InertiaDataModel.copyMutable(block: InertiaDataModel.() -> Unit): InertiaDataModel {
    val copy = InertiaDataModel(
        containerId = containerId,
        tree = tree,
        actionableIds = actionableIds.toMutableSet()
    )
    copy.inertiaSchemas.putAll(inertiaSchemas)
    copy.states.putAll(states)
    copy.actionableIdToAnimationIdMap.putAll(actionableIdToAnimationIdMap)
    copy.isActionable = isActionable
    block(copy)
    return copy
}

@Composable
fun Inertiaable(
    hierarchyIdPrefix: String,
    content: @Composable () -> Unit
) {
    val model = LocalInertiaDataModel.current
    val updateModel = LocalUpdateModel.current
    val playback = LocalInertia.current
    val parentId = LocalInertiaParentId.current
    val isContainer = LocalInertiaIsContainer.current
    val canvasSize = LocalCanvasSize.current

    val indexMap = SharedIndexManager.indexMap
    var hierarchyId by remember { mutableStateOf<String?>(null) }
    var isSelected by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(hierarchyIdPrefix) {
        val next = (indexMap[hierarchyIdPrefix] ?: 0)
        indexMap[hierarchyIdPrefix] = next + 1
        hierarchyId = "$hierarchyIdPrefix--$next"
    }

    LaunchedEffect(hierarchyId) {
        val id = hierarchyId ?: return@LaunchedEffect
        model?.tree?.addRelationship(id, parentId, isContainer)
    }

    LaunchedEffect(hierarchyId, model?.actionableIds) {
        hierarchyId?.let { id ->
            isSelected = model?.actionableIds?.any { it.hierarchyId == id } == true
        }
    }

    // Keyed on the model instance: the socket handlers replace it wholesale on
    // every update, so this re-resolves when a schema lands.
    //
    // The editor names an animation by the actionable it was authored against,
    // which is normally this instance's hierarchy id. Schemas loaded from a
    // project file are keyed by the prefix instead — there are no instances on
    // disk — so a miss falls back to the prefix rather than leaving the
    // actionable unanimated.
    val animation = remember(model, hierarchyId) {
        val id = hierarchyId ?: return@remember null
        val map = model?.actionableIdToAnimationIdMap ?: return@remember null
        val animId = map[id] ?: map[hierarchyIdPrefix]

        animId?.let { model.inertiaSchemas[it] } ?: model.inertiaSchemas[hierarchyIdPrefix]
    }

    // An animation starts as soon as the runtime holds its schema, or waits for
    // the app, depending on its `invokeType` — which is why this waits on the
    // schema rather than on the actionable registering.
    LaunchedEffect(animation?.id, animation?.invokeType) {
        val invokeType = animation?.invokeType
        if (invokeType == null) {
            InertiaLog.debug(
                "no animation for hierarchyId=$hierarchyId prefix=$hierarchyIdPrefix — " +
                    "map=${model?.actionableIdToAnimationIdMap} " +
                    "schemas=${model?.inertiaSchemas?.keys}"
            )
            return@LaunchedEffect
        }

        playback.register(hierarchyIdPrefix, invokeType)
    }

    val modifierWithAnim = run {
        if (animation == null || canvasSize == IntSize.Zero) {
            Modifier
        } else {
            // The playhead is read inside the layer block rather than in
            // composition. Both see the same values, but a read out here would
            // recompose and re-lay out every actionable on every frame of every
            // run; deferred to the layer, a frame only re-runs this block.
            Modifier.graphicsLayer {
                // Playback is keyed by prefix, so every actionable authored
                // against the same id runs off the one the app started.
                val isPlayable = playback.isPlaying(hierarchyIdPrefix)
                // Scrubbing shows the animation without running it, which is why
                // a parked playhead draws the same way a running one does.
                val isShowingTrack =
                    isPlayable && (playback.isRunning || playback.seekTime != null)

                val v = if (isShowingTrack) {
                    animation.valuesAtTime(playback.playheadTime, playback.playbackDuration)
                } else {
                    animation.initialValues.sanitized()
                }

                translationX = v.translate.getOrElse(0) { 0f } * canvasSize.width
                translationY = v.translate.getOrElse(1) { 0f } * canvasSize.height
                rotationZ = v.rotateCenter
                scaleX = v.scale
                scaleY = v.scale
                alpha = v.opacity
                transformOrigin = TransformOrigin.Center
            }
        }
    }

    // When in actionable mode, handle both tap (for selection) and drag (for translation)
    val interactionModifier = if (model?.isActionable == true) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val downPosition = down.position
                var totalDrag = Offset.Zero
                var hasDragged = false

                // Wait for either drag or up
                do {
                    val event = awaitPointerEvent()
                    val dragEvent = event.changes.firstOrNull()

                    if (dragEvent != null) {
                        val dragChange = dragEvent.positionChange()
                        totalDrag += dragChange

                        // Consider it a drag if moved more than 10 pixels
                        if (abs(totalDrag.x) > 10f || abs(totalDrag.y) > 10f) {
                            hasDragged = true

                            // Only drag if selected
                            if (isSelected) {
                                dragOffset += dragChange
                                dragEvent.consume()
                            }
                        }
                    }
                } while (event.changes.any { it.pressed })

                // On release
                if (hasDragged && isSelected && canvasSize != IntSize.Zero) {
                    // Send drag translation
                    val m = model
                    if (m != null) {
                        WebSocketClient.shared.sendMessageTranslation(
                            MessageTranslation(
                                translationX = dragOffset.x / canvasSize.width,
                                translationY = dragOffset.y / canvasSize.height,
                                actionableIds = m.actionableIds.toSet()
                            )
                        )
                    }
                } else if (!hasDragged) {
                    // It was a tap, toggle selection
                    val id = hierarchyId
                    val m = model
                    if (id != null && m != null) {
                        val newActionableIds = m.actionableIds.toMutableSet()
                        val pair = ActionableIdPair(
                            hierarchyIdPrefix = hierarchyIdPrefix,
                            hierarchyId = id
                        )
                        if (!newActionableIds.remove(pair)) {
                            newActionableIds.add(pair)
                        }

                        // Update UI immediately (like React does)
                        updateModel { prev ->
                            prev.copyMutable { actionableIds = newActionableIds }
                        }

                        // Send updated selection to WebSocket
                        WebSocketClient.shared.sendMessageActionables("actionables",
                            MessageActionables(
                                tree = m.tree.toDTO(),
                                actionableIds = newActionableIds.toSet()
                            )
                        )
                    }
                }
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifierWithAnim
            .then(
                if (isSelected && model?.isActionable == true && dragOffset != Offset.Zero) {
                    Modifier.offset {
                        androidx.compose.ui.unit.IntOffset(
                            dragOffset.x.toInt(),
                            dragOffset.y.toInt()
                        )
                    }
                } else {
                    Modifier
                }
            )
            .then(modifierSelectedBorder(isSelected && (model?.isActionable == true)))
            .then(interactionModifier)
    ) {
        CompositionLocalProvider(
            LocalInertiaParentId provides hierarchyId
        ) {
            content()
        }
    }
}

@Composable
private fun modifierSelectedBorder(show: Boolean): Modifier =
    if (!show) Modifier
    else Modifier.background(Color.Green)

// ========== UTILITIES ==========

fun getHostForWebSocket(defaultHost: String = "192.168.64.1"): String {
    return try {
        // In Waydroid, the default gateway usually points to the host
        val proc = Runtime.getRuntime().exec("ip route show default")
        val reader = proc.inputStream.bufferedReader()
        val output = reader.readText()
        proc.waitFor()

        // Parse: "default via 192.168.240.1 dev eth0"
        val pattern = """default via (\d+\.\d+\.\d+\.\d+)""".toRegex()
        pattern.find(output)?.groupValues?.get(1) ?: defaultHost
    } catch (e: Exception) {
        e.printStackTrace()
        defaultHost
    }
}

fun isValidIPv4(ip: String): Boolean {
    val pattern = Pattern.compile(
        "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}" +
                "(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$"
    )
    return pattern.matcher(ip).matches()
}

fun getFirstDnsIP(): String? {
    val dnsProps = listOf("net.dns1", "net.dns2", "net.dns3", "net.dns4")
    val dnsIPs = mutableListOf<String>()

    try {
        val systemProperties = Class.forName("android.os.SystemProperties")
        val getProp = systemProperties.getMethod("get", String::class.java)
        for (prop in dnsProps) {
            val value = getProp.invoke(null, prop) as String
            if (isValidIPv4(value)) {
                dnsIPs.add(value)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return dnsIPs.firstOrNull()
}

@JvmName("toColorFromInts")
fun List<Int>.toColor(): Color = when (size) {
    3 -> Color(this[0], this[1], this[2])
    4 -> Color(this[0], this[1], this[2], this[3])
    else -> Color.Unspecified
}

@JvmName("toColorFromFloats")
fun List<Float>.toColor(): Color = when (size) {
    3 -> Color(this[0], this[1], this[2])
    4 -> Color(this[0], this[1], this[2], this[3])
    else -> Color.Unspecified
}
