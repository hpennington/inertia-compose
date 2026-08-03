package org.inertiagraphics.inertia

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.ensarsarajcic.kotlinx.serialization.msgpack.MsgPack
import com.ensarsarajcic.kotlinx.serialization.msgpack.MsgPackConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
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

    /// Something the runtime could not do at all — a GPU context that would not
    /// come up, a shader that would not compile. Logged whether or not the
    /// chatter is enabled, because it is the only account of why part of the
    /// screen is missing.
    fun error(message: String) {
        Log.e("Inertia", message)
    }
}

// ========== DATA MODELS ==========

@Serializable
data class InertiaCanvasSize(val width: Int, val height: Int)

/// Every kind of message that crosses the editor channel.
///
/// The full set, matching `InertiaMessage.MessageType` in the Swift runtime and
/// `MessageType` in `inertia-base`. This used to name only the three the editor
/// sends *to* this runtime, so the four it sends back — and `signal`, which it
/// receives — were spelled as bare string literals at each of the nine places
/// they appear, where a typo would have compiled and gone out on the wire.
///
/// The name is the wire form: `MessageType.playbackProgress.name` is exactly the
/// `"playbackProgress"` the editor reads.
@Serializable
enum class MessageType {
    actionable,
    actionables,
    translationEnded,
    schema,
    selectedNodeProperties,
    signal,
    playbackProgress,
    tool,
    edit
}

/// What a drag in the runtime's viewport edits.
///
/// Picked in the editor's toolbar and sent here, because the gesture happens in
/// the app being authored rather than in the editor. One case per property of
/// [InertiaAnimationValues] — the same five the editor's timeline breaks a track
/// into. The name is the wire form, as with every other enum here.
@Serializable
enum class InertiaTool {
    translate,
    rotate,
    rotateCenter,
    opacity,
    scale
}

/// Read back from the wire as the string it is, rather than as [MessageType], so
/// a message this runtime has no case for is ignored rather than failing the
/// whole frame's decode.
///
/// [payload] is the inner message as a *separately encoded* MessagePack
/// document, which rides in a `bin` value — the same `Data` the Swift runtime
/// declares. Reading the envelope therefore never needs to know what it holds.
@Serializable
data class MessageWrapper(
    val type: String,
    val payload: ByteArray
) {
    // ByteArray gets identity equality from the data class, which would make two
    // wrappers holding the same bytes unequal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageWrapper) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
}

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
    val keyframes: List<InertiaAnimationKeyframe> = emptyList(),
    /// What the actionable's canvas draws behind it. Defaulted, so an animation
    /// recorded before shapes existed — or one that simply wants none — still
    /// decodes.
    val shapes: List<InertiaShape> = emptyList(),
    /// How long one loop of the timeline this was authored on lasts.
    ///
    /// A property of the animation rather than of the editor that recorded it:
    /// a track is padded out to the loop, so an animation played back at a
    /// length other than the one it was drawn against holds — or truncates —
    /// where its author did not mean it to. Every schema in a project carries
    /// the same value, which is what the editor's one timeline slider writes.
    ///
    /// Defaulted, so an animation recorded before the loop was part of the
    /// schema — or one happy with the default — still decodes.
    val loopDuration: Float = InertiaPlayback.defaultLoopDuration
)

/// The loop these schemas were authored against, or null if none of them say.
///
/// The longest, where a hand-edited file disagrees with itself: the loop is what
/// every track is padded out to, and the shorter answer would cut the track that
/// asked for more off at the knees.
internal fun authoredLoopDuration(schemas: Collection<InertiaAnimationSchema>): Float? =
    schemas.map { InertiaPlayback.clampLoopDuration(it.loopDuration) }.maxOrNull()

@Serializable
data class InertiaPoint(val x: Float, val y: Float)

@Serializable
data class InertiaColor(
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float
)

/// A single corner of a shape: where it sits, and what colour the shape is
/// there.
@Serializable
data class Vertex(val position: InertiaPoint, val color: InertiaColor)

/// The kinds of vector a shape can be described as, rather than spelled out
/// corner by corner. A bare string on the wire, like every other enum here.
@Serializable
enum class InertiaShapeType { rectangle, square, circle, oval, triangle }

/// A drawn vector as the editor records it: what it is, how big, and what colour
/// — the size in the same multiples of the actionable its corners would have
/// been measured in.
@Serializable
data class InertiaShapeProperties(
    val id: String,
    val type: InertiaShapeType,
    val width: Float,
    val height: Float,
    val color: InertiaColor
)

/// A shape as it is authored alongside an animation: a ring of corners, each
/// carrying its own colour, measured against the actionable it belongs to —
/// (0, 0) that view's top-left, (1, 1) its bottom-right.
///
/// Nothing holds a shape to that box, though. Coordinates outside 0..1 reach
/// past the actionable and go on being drawn, because the canvas they land on
/// is the container's rather than the view's: a shape three times the size of
/// the card it backs is authored simply by saying 3.
///
/// A shape is authored one of two ways — [vertices], corner by corner, or
/// [shape], a vector described and drawn from that description — and may carry
/// an [animation] of its own, which is what makes it a drawing rather than a
/// backdrop: the corners say what is drawn, the track says how it moves, and
/// the actionable it was authored against carries both.
@Serializable
data class InertiaShape(
    /// What this shape is, to anything that has to point at it: the editor's
    /// hierarchy panel, the selection sent back to the runtime, and the edit
    /// that selection authors.
    ///
    /// A shape used to be addressable only by where it sat — whose schema held
    /// it, and how far down the list — which is a name that changes when the
    /// shape either side of it is deleted. This does not.
    val id: String,
    val vertices: List<Vertex> = emptyList(),
    val shape: InertiaShapeProperties? = null,
    val animation: InertiaAnimationSchema? = null
)

@Serializable
data class AnimationContainer(
    val actionableId: String,
    val containerId: String
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

    /// Which property a gesture on a selected node edits, as picked in the
    /// editor's toolbar. [InertiaTool.translate] until the editor says
    /// otherwise, which is also what a runtime that reconnects mid-session falls
    /// back to until the editor resends.
    var activeTool: InertiaTool = InertiaTool.translate
}

/// What the editor's gestures have added on top of the values an actionable's
/// schema puts it at.
///
/// A delta rather than an absolute transform: the schema is what an actionable
/// *is* at, and the editor folds a gesture into it and pushes it back, at which
/// point this returns to the identity. Holding it separately is what lets the
/// two be told apart, so the same move is never counted twice.
data class InertiaToolEdit(
    /// Pixels in the container's coordinate space, which is what the gesture is
    /// measured in. Normalized against the container only on the way out.
    val translate: Offset = Offset.Zero,
    /// Degrees, about the node's top-left corner.
    val rotate: Float = 0f,
    /// Degrees, about the node's center.
    val rotateCenter: Float = 0f,
    /// Added to the schema's scale rather than multiplying it, so scale
    /// accumulates across gestures exactly like every other property here.
    val scale: Float = 0f,
    val opacity: Float = 0f
) {
    val isNone: Boolean
        get() = translate == Offset.Zero && rotate == 0f && rotateCenter == 0f &&
            scale == 0f && opacity == 0f
}

/// A node scaled to nothing has no box left to grab, and a negative scale
/// mirrors it. The smallest scale a handle will author.
internal const val minimumToolScale = 0.01f

/// These values with an in-progress edit folded into them — what the node is
/// drawn at while a handle is being dragged, and what the editor is told once it
/// is let go.
///
/// Scale and opacity are clamped rather than left to run: a scale through zero
/// flips the node inside out and a negative opacity is not a thing a keyframe
/// can hold.
internal fun InertiaAnimationValues.applying(
    edit: InertiaToolEdit,
    canvasSize: IntSize
): InertiaAnimationValues {
    if (edit.isNone) return this

    val width = if (canvasSize.width > 0) canvasSize.width.toFloat() else 1f
    val height = if (canvasSize.height > 0) canvasSize.height.toFloat() else 1f

    return InertiaAnimationValues(
        scale = maxOf(minimumToolScale, scale + edit.scale),
        translate = listOf(
            translate.getOrElse(0) { 0f } + edit.translate.x / width,
            translate.getOrElse(1) { 0f } + edit.translate.y / height
        ),
        rotate = rotate + edit.rotate,
        rotateCenter = rotateCenter + edit.rotateCenter,
        opacity = opacity.coerceIn(0f, 1f).let { (it + edit.opacity).coerceIn(0f, 1f) }
    )
}

/// Where [local] — a point in the actionable's own laid-out box, origin at its
/// top-left — is drawn in the container once these values have been applied.
///
/// The same stack the animation layers put on the node, in the same order: scale
/// about the center, rotate about the top-left, rotate about the center, then
/// the offset. Each anchor is resolved against the *layout* box, which is how
/// chained `graphicsLayer` transforms compose — an inner layer never moves an
/// outer one's origin.
///
/// The handles are drawn in the container rather than inside the node (see
/// [InertiaToolHandlesOverlay] for why), so unlike the other two runtimes every
/// piece of chrome is placed through this rather than carried by the transform.
internal fun InertiaAnimationValues.drawnPoint(
    local: Offset,
    layoutOrigin: Offset,
    layoutSize: Size,
    canvasSize: IntSize
): Offset = drawnContainerPoint(
    Offset(layoutOrigin.x + local.x, layoutOrigin.y + local.y),
    layoutOrigin,
    layoutSize,
    canvasSize
)

/// The same, for a point already given in the container's space rather than as
/// an offset into [layoutOrigin]'s box.
///
/// What composes two of these. A shape's handles sit inside the actionable's
/// transform as well as the shape's own, so a knob is this transform applied to
/// a point the inner one has already moved — and that point is a container
/// point, not an offset into a box.
internal fun InertiaAnimationValues.drawnContainerPoint(
    containerPoint: Offset,
    layoutOrigin: Offset,
    layoutSize: Size,
    canvasSize: IntSize
): Offset {
    val center = Offset(
        layoutOrigin.x + layoutSize.width / 2f,
        layoutOrigin.y + layoutSize.height / 2f
    )

    var point = containerPoint
    point = Offset(
        center.x + (point.x - center.x) * scale,
        center.y + (point.y - center.y) * scale
    )
    point = point.rotatedAround(layoutOrigin, rotate)
    point = point.rotatedAround(center, rotateCenter)

    return Offset(
        point.x + translate.getOrElse(0) { 0f } * canvasSize.width,
        point.y + translate.getOrElse(1) { 0f } * canvasSize.height
    )
}

/// A drag measured on screen, restated in the space *inside* this transform —
/// which is where an offset stacked under it is measured.
///
/// A shape is moved by an offset applied within the actionable's own rotation
/// and scale, so a drag to the right across a turned actionable is not a move to
/// the right in the space the shape's offset lands in. Undoing the turn and the
/// scale is what keeps the shape under the finger.
internal fun InertiaAnimationValues.unapplying(translation: Offset): Offset {
    val radians = -(rotate + rotateCenter) * PI.toFloat() / 180f
    val divisor = if (scale.isFinite() && abs(scale) > minimumToolScale) scale else 1f

    return Offset(
        (translation.x * cos(radians) - translation.y * sin(radians)) / divisor,
        (translation.x * sin(radians) + translation.y * cos(radians)) / divisor
    )
}

/// A transform something's handles are drawn inside of, rather than beside.
///
/// What the actionable's animation is to a shape drawn behind it: the values it
/// is displayed with, and the box those values turn and scale about. See
/// [InertiaToolHandleGeometry.outer].
data class InertiaOuterTransform(
    val values: InertiaAnimationValues,
    val layoutOrigin: Offset,
    val layoutSize: Size
)

private fun Offset.rotatedAround(anchor: Offset, degrees: Float): Offset {
    if (degrees == 0f) return this

    val radians = degrees * PI.toFloat() / 180f
    val dx = x - anchor.x
    val dy = y - anchor.y

    return Offset(
        anchor.x + dx * cos(radians) - dy * sin(radians),
        anchor.y + dx * sin(radians) + dy * cos(radians)
    )
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
data class MessageActionableWrapper(val type: MessageType, val payload: MessageActionable)

@Serializable
data class MessageActionablesWrapper(val type: MessageType, val payload: MessageActionables)

@Serializable
data class MessageSchemaWrapper(val type: MessageType, val payload: MessageSchema)

@Serializable
data class MessageTranslationWrapper(val type: MessageType, val payload: MessageTranslation)

@Serializable
data class MessageEditWrapper(val type: MessageType, val payload: MessageEdit)

@Serializable
data class MessagePlaybackProgressWrapper(val type: MessageType, val payload: MessagePlaybackProgress)

@Serializable
data class MessageSelectedNodePropertiesWrapper(val type: MessageType, val payload: MessageSelectedNodeProperties)

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

/// One case of a Swift enum as it arrives: the associated value under `_0`, or
/// nothing at all for a case that carries none.
@Serializable
data class AnimationSignalCaseDTO(
    @SerialName("_0") val value: Float = 0f
)

/// Swift synthesizes `Codable` for an enum with associated values as a
/// single-key map — `{"seek": {"_0": 1.25}}`, and `{"pause": {}}` for a case
/// with no value. Exactly one of these is ever present; the rest decode to null
/// from their defaults, which is how the case is identified.
@Serializable
data class AnimationSignalDTO(
    val pause: AnimationSignalCaseDTO? = null,
    val resume: AnimationSignalCaseDTO? = null,
    val seek: AnimationSignalCaseDTO? = null,
    val setLoopDuration: AnimationSignalCaseDTO? = null
)

@Serializable
data class MessageSignalDTO(
    val signal: AnimationSignalDTO,
    val sequence: Int = 0
)

/// The editor is the only sender of signals, so the shape above is the only one
/// they arrive in.
internal fun decodeAnimationSignal(raw: AnimationSignalDTO?): AnimationSignal? {
    if (raw == null) return null

    if (raw.pause != null) return AnimationSignal.Pause
    if (raw.resume != null) return AnimationSignal.Resume
    raw.seek?.let { return AnimationSignal.Seek(it.value) }
    raw.setLoopDuration?.let { return AnimationSignal.SetLoopDuration(it.value) }

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

/// Editor → runtime: which tool a gesture on a selected node applies.
@Serializable
data class MessageTool(
    val tool: InertiaTool
)

/// Runtime → editor: where a gesture left the selection.
///
/// The whole transform rather than the one property the tool changed, because
/// that is what the editor records — a keyframe holds all five values, and the
/// four the tool did not touch still have to be the ones the node is sitting at.
///
/// Generalizes [MessageTranslation], which this runtime no longer sends.
@Serializable
data class MessageEdit(
    val tool: InertiaTool,
    val values: InertiaAnimationValues,
    val actionableIds: Set<ActionableIdPair>
)

/// Where the node being dragged is, for the editor's inspector readout, sent
/// continuously while the drag is in progress.
///
/// Distinct from [MessageTranslation], which is sent once when the drag ends and
/// is what the editor authors into the schema. This one is only ever displayed,
/// so it is safe to send at pointer rate and safe to miss.
///
/// [positionX] and [positionY] are the accumulated drag — how far the node has
/// been moved from where layout put it, in pixels — matching the SwiftUI
/// runtime's `totalOffset`. (The React runtime sends the node's absolute
/// top-left in the container's space here instead; the two disagree, and the
/// editor only prints whichever it is given.)
@Serializable
data class MessageSelectedNodeProperties(
    val positionX: Float,
    val positionY: Float,
    val sizeX: Float,
    val sizeY: Float,
    /// What the selection would be authored at if the gesture ended now. Left
    /// out by a runtime that only knows how to move a node, which is why the
    /// editor decodes it as optional.
    val values: InertiaAnimationValues? = null
)

/// How every schema is decoded, whether it arrived over the socket or came out
/// of the shipped animation file. Shared so the two paths cannot drift: a field
/// the editor adds must be ignorable by both.
///
/// MessagePack rather than JSON — see `InertiaCoding` in the Swift runtime,
/// which decides the format for all three. Two settings matter for talking to
/// it: enums go out as their names rather than their ordinals (the default, and
/// what Swift writes), and `rawCompatibility` stays off so a `ByteArray` is a
/// `bin` value rather than a string.
internal val inertiaMsgPack = MsgPack(MsgPackConfiguration(ignoreUnknownKeys = true))

/// The extension a shipped animation file carries, matching the Swift runtime's
/// `InertiaCoding.fileExtension`.
internal const val INERTIA_FILE_EXTENSION = "msgpack"

// ========== WEBSOCKET CLIENT ==========

class WebSocketClient private constructor() : WebSocketListener() {
    companion object {
        val shared: WebSocketClient by lazy { WebSocketClient() }
        private val msgPack = inertiaMsgPack

        /// How long to wait before dialing the editor again, backing off from the
        /// first to the second. The editor is usually not up yet when the app
        /// launches, and it can be restarted under a running app, so a dial that
        /// is never retried means a dev session that silently never connects.
        /// Backing off keeps a runtime left running against no editor from
        /// dialing at full speed forever, while staying quick enough that
        /// starting the editor attaches within a few seconds.
        private const val reconnectBaseDelayMs = 500L
        private const val reconnectMaxDelayMs = 4_000L

        private const val normalClosureStatus = 1000
    }

    /// One client for the process. Every `newWebSocket` off it is a separate
    /// connection, and rebuilding the client per dial would throw away the
    /// connection pool and dispatcher threads on every retry.
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /// Guards the connection state below, which is touched both from whatever
    /// thread calls `connect`/`disconnect` and from OkHttp's callback thread.
    private val connectionLock = Any()

    /// Written only under the lock, but read without it by the send path, which
    /// runs on the frame clock and has no business blocking on a dial.
    @Volatile
    private var socket: WebSocket? = null
    private var url: String? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    /// True from the moment a dial is handed to OkHttp until it opens or fails,
    /// so repeated `connect` calls cannot stack sockets up on a slow dial.
    private var isDialing = false

    @Volatile
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

    /// The tool the editor's toolbar is on. Replayed to a runtime that attaches
    /// mid-session by the editor itself, so this needs no replay of its own.
    private val _onTool = MutableSharedFlow<InertiaTool>(replay = 0)
    val onTool = _onTool.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var onConnected: (() -> Unit)? = null

    /// Asks to be connected to the editor, and to stay connected: a drop is
    /// redialed until `disconnect`. Safe to call repeatedly — a call made while a
    /// connection is up, in flight, or waiting out a backoff only re-arms
    /// `onConnect`, which every open runs so the editor is resynced after a
    /// reconnect as well as after the first dial.
    fun connect(url: String, onConnect: () -> Unit = {}) {
        val shouldDial = synchronized(connectionLock) {
            onConnected = onConnect

            if (url != this.url) {
                // The endpoint moved, so whatever is open or scheduled belongs to
                // the old one. Dropping the socket here means its own callback
                // finds itself stale and leaves the redial below alone.
                this.url = url
                reconnectJob?.cancel()
                reconnectJob = null
                reconnectAttempt = 0
                socket?.close(normalClosureStatus, null)
                socket = null
                isConnected = false
                isDialing = false
            }

            !isConnected && !isDialing && reconnectJob == null
        }

        if (shouldDial) dial()
    }

    private fun dial() {
        synchronized(connectionLock) {
            val target = url ?: return
            if (isConnected || isDialing) return

            InertiaLog.debug("dialing editor at $target")
            isDialing = true
            val request = Request.Builder().url(target).build()
            // Assigned under the lock so a callback that lands immediately still
            // finds the socket it is reporting on, rather than reading itself as
            // stale against the previous one.
            socket = client.newWebSocket(request, this)
        }
    }

    private fun scheduleReconnect() {
        synchronized(connectionLock) {
            if (url == null || reconnectJob != null) return

            val delayMs = (reconnectBaseDelayMs * 2.0.pow(reconnectAttempt))
                .toLong()
                .coerceIn(reconnectBaseDelayMs, reconnectMaxDelayMs)
            reconnectAttempt += 1

            reconnectJob = scope.launch {
                delay(delayMs)
                synchronized(connectionLock) { reconnectJob = null }
                dial()
            }
        }
    }

    /// Stops dialing and drops the connection. Nothing in the runtime calls this
    /// — a container that leaves composition leaves the socket up for the next
    /// one — but a host that tears the runtime down needs a way out of the retry
    /// loop.
    fun disconnect() {
        synchronized(connectionLock) {
            url = null
            onConnected = null
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempt = 0
            socket?.close(normalClosureStatus, null)
            socket = null
            isConnected = false
            isDialing = false
        }
    }

    fun sendMessageActionables(type: MessageType, message: MessageActionables) {
        val wrapper = MessageActionablesWrapper(type, message)
        sendPacked(wrapper)
    }

    fun sendMessageSchema(type: MessageType, message: MessageSchema) {
        val wrapper = MessageSchemaWrapper(type, message)
        sendPacked(wrapper)
    }

    fun sendMessageTranslation(message: MessageTranslation) {
        val wrapper = MessageTranslationWrapper(MessageType.translationEnded, message)
        sendPacked(wrapper)
    }

    /// One message whatever the tool, carrying the whole transform: a keyframe
    /// holds all five values, so the four a gesture did not touch have to travel
    /// with the one it did.
    fun sendMessageEdit(message: MessageEdit) {
        sendPacked(MessageEditWrapper(MessageType.edit, message))
    }

    /// The inspector readout, sent on every pointer event of a drag.
    ///
    /// Dropped rather than queued while the socket still has bytes to drain, for
    /// the same reason [sendMessagePlaybackProgress] is: a stall anywhere
    /// downstream would let a fast drag pile sends up and then burst. Nothing
    /// depends on any single one arriving — the next event produces another, and
    /// what the editor authors into the schema is [sendMessageTranslation] at
    /// the end of the gesture.
    fun sendMessageSelectedNodeProperties(message: MessageSelectedNodeProperties) {
        if (!isConnected) return
        if ((socket?.queueSize() ?: 0L) > 0L) return

        sendPacked(MessageSelectedNodePropertiesWrapper(MessageType.selectedNodeProperties, message))
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

        sendPacked(MessagePlaybackProgressWrapper(MessageType.playbackProgress, message))
    }

    private fun sendPacked(wrapper: Any) {
        if (!isConnected || socket == null) return
        try {
            // Serialize the inner payload on its own, and take the type off the
            // wrapper rather than deriving it from the wrapper's class a second
            // time: the two used to be separate `when`s over the same five
            // classes, which is one place for them to disagree.
            val (type, payloadBytes) = when (wrapper) {
                is MessageActionableWrapper -> wrapper.type to msgPack.encodeToByteArray(wrapper.payload)
                is MessageActionablesWrapper -> wrapper.type to msgPack.encodeToByteArray(wrapper.payload)
                is MessageSchemaWrapper -> wrapper.type to msgPack.encodeToByteArray(wrapper.payload)
                is MessageTranslationWrapper -> wrapper.type to msgPack.encodeToByteArray(wrapper.payload)
                is MessageEditWrapper -> wrapper.type to msgPack.encodeToByteArray(wrapper.payload)
                is MessagePlaybackProgressWrapper -> wrapper.type to msgPack.encodeToByteArray(wrapper.payload)
                is MessageSelectedNodePropertiesWrapper -> wrapper.type to msgPack.encodeToByteArray(wrapper.payload)
                else -> return
            }

            // The envelope carries those bytes as-is, in a `bin` value — no
            // base64, which is what the JSON envelope needed to hold them.
            val wrapperBytes = msgPack.encodeToByteArray(MessageWrapper(type.name, payloadBytes))

            // Binary frame: MessagePack has no text form.
            socket?.send(wrapperBytes.toByteString())

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        val onConnect = synchronized(connectionLock) {
            if (webSocket !== socket) return
            isConnected = true
            isDialing = false
            reconnectAttempt = 0
            onConnected
        }

        InertiaLog.debug("editor connected")
        onConnect?.invoke()
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        runCatching {
            // Parse the outer wrapper, whose payload is the inner message's own
            // bytes — no base64 to undo.
            val wrapper = msgPack.decodeFromByteArray<MessageWrapper>(bytes.toByteArray())

            // Deserialize based on type. A type this runtime has no case for —
            // one of its own reports echoed back, or something a newer editor
            // sends — resolves to null and falls through the `when`.
            when (MessageType.entries.firstOrNull { it.name == wrapper.type }) {
                MessageType.actionable -> {
                    val decoded = msgPack.decodeFromByteArray<MessageActionable>(wrapper.payload)
                    scope.launch { _onIsActionable.emit(decoded.isActionable) }
                }
                MessageType.actionables -> {
                    val decoded = msgPack.decodeFromByteArray<MessageActionables>(wrapper.payload)
                    scope.launch { _onSelectedIds.emit(decoded.actionableIds) }
                }
                MessageType.schema -> {
                    val decoded = msgPack.decodeFromByteArray<MessageSchema>(wrapper.payload)
                    scope.launch { _onSchema.emit(decoded.schemaWrappers) }
                }
                MessageType.signal -> {
                    // Decoded through a DTO of its own: the signal is a Swift
                    // enum with associated values, so which key is present is
                    // what names the case.
                    val decoded = msgPack.decodeFromByteArray<MessageSignalDTO>(wrapper.payload)
                    val signal = decodeAnimationSignal(decoded.signal) ?: return@runCatching
                    scope.launch { _onSignal.emit(signal to decoded.sequence) }
                }
                MessageType.tool -> {
                    val decoded = msgPack.decodeFromByteArray<MessageTool>(wrapper.payload)
                    scope.launch { _onTool.emit(decoded.tool) }
                }
                // The ones this runtime only ever sends — plus null, an
                // unrecognized type — are not errors to receive.
                MessageType.translationEnded,
                MessageType.selectedNodeProperties,
                MessageType.playbackProgress,
                MessageType.edit,
                null -> Unit
            }

        }.onFailure { it.printStackTrace() }
    }

    /// Nothing on this channel has a text form any more. The editor sends
    /// binary frames; a text one is not something this runtime can read.
    override fun onMessage(webSocket: WebSocket, text: String) {
        InertiaLog.error("ignoring an unexpected text frame (${text.length} chars) — frames are MessagePack")
    }

    /// The editor going away sends a close that has to be answered before the
    /// connection actually finishes; without this it sits half-closed and the
    /// redial waits on a socket that is never coming back.
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(normalClosureStatus, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        handleDisconnect(webSocket, "closed ($code)")
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        handleDisconnect(webSocket, t.message ?: t.toString())
    }

    /// Both ways a connection can end, plus a dial that never opened at all —
    /// the editor not being up yet, which is the normal case at launch. Ignores
    /// any socket the client has already moved on from, so a close that arrives
    /// after a redial was decided on cannot schedule a second one.
    private fun handleDisconnect(webSocket: WebSocket, reason: String) {
        val wasConnected = synchronized(connectionLock) {
            if (webSocket !== socket) return

            val wasConnected = isConnected
            isConnected = false
            isDialing = false
            socket = null
            wasConnected
        }

        // A dial against an editor that is not running yet fails on every retry,
        // so only a connection that was actually up is worth a line.
        if (wasConnected) InertiaLog.debug("editor disconnected: $reason — will retry")

        scheduleReconnect()
    }
}

// ========== TRACKS ==========

object InertiaPlayback {
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
    val loopDurationRange: ClosedFloatingPointRange<Float> = 0.1f..60.0f

    /// Brings a loop length the user typed, or a peer sent, into range.
    fun clampLoopDuration(seconds: Float): Float {
        if (!seconds.isFinite()) return defaultLoopDuration
        return seconds.coerceIn(loopDurationRange)
    }
}

/// How many corners a round vector's ring is cut into. An oval has no corners of
/// its own, so it is drawn as the many-sided polygon that reads as one at the
/// sizes a shape is authored at — and the same count as the Swift and WebGL
/// runtimes use, so an oval authored once is the same drawing on all three.
internal const val ovalSegments = 48

/// The ring of corners a described vector is drawn from, in the actionable's own
/// units and centred on its top-left corner — the origin the description is
/// measured from.
///
/// Matches the Swift and WebGL runtimes corner for corner, so one authored
/// vector is the same drawing on all three. A rectangle comes out as the two
/// triangles of a quad rather than four corners; the fan in [triangles]
/// re-covers the same area from them.
///
/// A square, a circle and a triangle are the descriptions with one measurement
/// rather than two, so each is sized by the longer side of the box it was drawn
/// in — the shape stays square, stays round, stays a triangle whatever box it
/// was dragged out over.
private fun InertiaShapeProperties.describedVertices(): List<Vertex> {
    val size = maxOf(width, height)
    fun corner(x: Float, y: Float) = Vertex(InertiaPoint(x, y), color)

    /// The ring inscribed in a box: one corner per segment, stepping around the
    /// ellipse. The ring is convex, so the fan the renderer draws it with covers
    /// it exactly.
    fun ring(width: Float, height: Float): List<Vertex> {
        val radiusX = width / 2f
        val radiusY = height / 2f

        return (0 until ovalSegments).map { segment ->
            val angle = 2.0 * PI * segment / ovalSegments
            corner(radiusX * cos(angle).toFloat(), radiusY * sin(angle).toFloat())
        }
    }

    /// The two triangles of a quad, which is how a rectangle reaches the
    /// renderer.
    fun quad(width: Float, height: Float): List<Vertex> {
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val topLeft = corner(-halfWidth, -halfHeight)
        val topRight = corner(halfWidth, -halfHeight)
        val bottomLeft = corner(-halfWidth, halfHeight)
        val bottomRight = corner(halfWidth, halfHeight)
        return listOf(topLeft, topRight, bottomRight, topLeft, bottomLeft, bottomRight)
    }

    return when (type) {
        InertiaShapeType.rectangle -> quad(width, height)
        InertiaShapeType.square -> quad(size, size)
        InertiaShapeType.circle -> ring(size, size)
        InertiaShapeType.oval -> ring(width, height)
        InertiaShapeType.triangle -> {
            val triangleHeight = size * sqrt(3f) / 2f
            val halfBase = size / 2f
            listOf(
                corner(0f, triangleHeight / 2f),
                corner(-halfBase, -triangleHeight / 2f),
                corner(halfBase, -triangleHeight / 2f)
            )
        }
    }
}

/// The corners this shape is drawn from, however it was authored: the ones
/// recorded against it, or the ones its description resolves to.
internal fun InertiaShape.resolvedVertices(): List<Vertex> =
    if (vertices.isNotEmpty()) vertices else shape?.describedVertices() ?: emptyList()

/// The shape as the triangle list the GPU draws: a fan around the first corner,
/// so three corners are a triangle and four a quad. Fewer than three enclose no
/// area and contribute nothing.
internal fun InertiaShape.triangles(): List<Vertex> {
    val vertices = resolvedVertices()
    if (vertices.size < 3) return emptyList()

    return (1 until vertices.size - 1).flatMap {
        listOf(vertices[0], vertices[it], vertices[it + 1])
    }
}

/// The smallest box holding every corner of these shapes, in the units they are
/// authored in — multiples of the actionable's own frame, so `(0, 0, 1, 1)` is
/// exactly the actionable and `(0, 0, 3, 3)` three times it.
///
/// This is what the canvas is sized and placed by. Sizing it to the shapes
/// rather than to the container is what keeps a shape whole: a canvas is a
/// rectangle that rotates with the view it backs, so anything reaching past its
/// edge is cut — and a canvas fitted to the container was already cutting a
/// shape bigger than the container, then sweeping that straight edge through
/// the artwork as the view turned. Fitted to the shapes, there is nothing
/// outside it to lose.
///
/// Null when the shapes enclose no area, which is also when there is nothing to
/// draw.
internal fun List<InertiaShape>.bounds(): Rect? {
    val positions = flatMap { shape -> shape.resolvedVertices().map { it.position } }
    val first = positions.firstOrNull() ?: return null

    var minX = first.x
    var maxX = first.x
    var minY = first.y
    var maxY = first.y

    positions.forEach { position ->
        minX = minOf(minX, position.x)
        maxX = maxOf(maxX, position.x)
        minY = minOf(minY, position.y)
        maxY = maxOf(maxY, position.y)
    }

    val bounds = Rect(left = minX, top = minY, right = maxX, bottom = maxY)
    return if (bounds.width > 0f && bounds.height > 0f) bounds else null
}

/// The same shape restated against [bounds] — the canvas's own box — so (0, 0)
/// is the canvas's top-left corner and (1, 1) its bottom-right, which is the
/// space the renderer draws in.
///
/// The corners are resolved on the way through: whatever the shape was authored
/// as, what comes out is the ring that lands in [bounds]. Its animation rides
/// along, since normalizing is about where the shape is drawn and not about what
/// it then does.
internal fun InertiaShape.normalized(bounds: Rect): InertiaShape {
    if (bounds.width <= 0f || bounds.height <= 0f) return this

    return InertiaShape(
        id = id,
        vertices = resolvedVertices().map { vertex ->
            Vertex(
                position = InertiaPoint(
                    x = (vertex.position.x - bounds.left) / bounds.width,
                    y = (vertex.position.y - bounds.top) / bounds.height
                ),
                color = vertex.color
            )
        },
        animation = animation
    )
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
    loopDuration: Float,
    isRepeating: Boolean = true
): InertiaAnimationValues {
    // A run that plays once is as long as its own track — padding it to the loop
    // would only hold it at the end, which is what the loop is for.
    val track = if (isRepeating) keyframesFilling(loopDuration) else playableKeyframes()
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
/// Reached through [LocalInertia], and keyed by the `id` the app hands to
/// [Inertia] rather than the per-instance hierarchy id the runtime derives from
/// it — that id is the one an animation is authored against, so starting one
/// starts every actionable sharing it.
@Stable
class InertiaPlaybackController internal constructor() {

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

    /// How long one loop lasts.
    ///
    /// Seeded from the schemas — the loop is part of what was authored, so a
    /// shipped build loops over the span its animation was drawn against without
    /// anything having to tell it — and moved from there by the editor's
    /// timeline. Read each frame, so resizing the timeline mid-run stretches the
    /// loop rather than waiting for it to be restarted.
    var loopDuration: Float by mutableFloatStateOf(InertiaPlayback.defaultLoopDuration)

    /// How far into the run currently on screen we are, in seconds.
    var playheadTime: Float by mutableFloatStateOf(0f)
        private set

    /// Whether a run is on screen: playing, or holding the frame it finished on.
    /// Not the same as the clock ticking — a run that has played once and stopped
    /// still holds its final values.
    internal var isRunning: Boolean by mutableStateOf(false)
        private set

    /// Whether the clock is advancing. What the container's frame loop follows.
    internal var isTicking: Boolean by mutableStateOf(false)
        private set

    /// Whether tracks repeat once they reach the end of the loop.
    ///
    /// Set by the app. A repeating run wraps at [playbackDuration] and every
    /// track is padded out to it, so actionables of different lengths restart
    /// together; a run that plays once stops at the end and holds there.
    var isRepeating: Boolean by mutableStateOf(true)

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
    internal val playbackDuration: Float
        get() = maxOf(loopDuration, schemas.values.maxOfOrNull { it.trackDuration() } ?: 0f)

    // MARK: - App-facing controls

    /// Starts an animation that was waiting on its `trigger` invoke type.
    ///
    /// A trigger arriving while the clock is already running joins the run in
    /// progress rather than cutting it short — [restart] is the one that starts
    /// over. Cancelled animations are left where they are: stopping one is the
    /// app's call, and picking it back up is [restart]'s.
    fun trigger(id: String) {
        val state = states[id]
        if (state?.isCancelled == true || state?.trigger == true) return

        markTriggered(id)
        seekTime = null
        startClock()
    }

    /// Stops an animation and returns it to its initial values, where it stays
    /// until [restart].
    ///
    /// The clock stops with the last animation running off it, since a playhead
    /// with nothing left to follow is one the editor should see parked.
    fun cancel(id: String) {
        states[id] = InertiaAnimationState(
            id = id,
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
    fun restart(id: String) {
        markTriggered(id)

        stopClock()
        playheadTime = 0f
        seekTime = null
        startClock()
    }

    fun isCancelled(id: String): Boolean =
        states[id]?.isCancelled == true

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

        // The loop travels with the schemas, so a project authored at a length
        // other than the default plays at it from the first send — and in a
        // shipped build, where no editor is ever going to say otherwise. An
        // empty set leaves the current loop alone rather than snapping back to
        // the default.
        authoredLoopDuration(schemas.values)?.let { loopDuration = it }

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
                loopDuration = InertiaPlayback.clampLoopDuration(signal.duration)
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
        // The ticking flag, not `isRunning`: a non-repeating run that has played
        // out is still on screen, and has to be startable again.
        if (isTicking) return

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
        isTicking = true
        report(isRunning = true)
    }

    private fun stopClock() {
        isRunning = false
        isTicking = false
        runStartNanos = null
    }

    /// Advances the playhead. Driven by the container's frame loop, which runs
    /// only while [isRunning].
    internal fun tick(frameNanos: Long) {
        if (!isTicking) return

        val start = runStartNanos
        if (start == null) {
            runStartNanos = frameNanos
            return
        }

        // Read each frame: the timeline can be resized mid-run.
        val duration = playbackDuration
        val elapsed = runOffset + (frameNanos - start) / 1_000_000_000f

        // A run that plays once ends here, holding its final frame: the clock
        // stops but the run stays on screen, which is what `isRunning` says.
        // Starting it again is the app's call.
        if (!isRepeating && elapsed >= duration) {
            playheadTime = duration
            isTicking = false
            runStartNanos = null
            report(isRunning = false)
            return
        }

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

// ========== ALIGNMENT GRID ==========

/// Off for now, and only in this runtime — the SwiftUI and web runtimes draw
/// their grids.
///
/// The overlay spans the container and moves on every pointer event, so a drag
/// repaints the whole surface at the frame rate. On the emulator this runtime is
/// usually watched in, that costs enough frames that the node visibly stops
/// tracking the pointer, which is worse than having no guides. The drawing below
/// is correct and the drag path no longer recomposes; what is left is the cost of
/// the repaint itself, which needs a cheaper way to put a full-container overlay
/// on screen rather than another pass over this code. Flip this to put it back.
private const val showAlignmentGrid = true

/// Where the node being dragged sits in the container's coordinate space. An
/// absolute position rather than a translation: the guides are drawn from it, and
/// a node need not be laid out at the container's center.
data class InertiaGuides(val center: Offset, val size: Size)

/// The alignment overlay's state, held per [InertiaContainer] and written by the
/// actionable being dragged.
///
/// Kept out of [InertiaDataModel] on purpose: that model is replaced wholesale on
/// every write, which would rebuild the schema map and re-run the container's
/// effects on every frame of a drag.
///
/// One state object rather than a field each, because a drag writes this on every
/// pointer event: one write is one invalidation, and it is only ever read from
/// [InertiaAlignmentGrid]'s draw scope, so a drag repaints the overlay's layer
/// without recomposing or re-laying out anything.
@Stable
class InertiaGuideState {
    /// Null when no drag is in progress, which is also when the overlay draws
    /// nothing. The overlay itself stays composed either way — taking it in and
    /// out of the tree would recompose the container, and with it the whole app's
    /// content, at both ends of every drag.
    var guides by mutableStateOf<InertiaGuides?>(null)
        private set

    /// The container's own coordinates, so an actionable can express its position
    /// in the same space the overlay draws in. Read from gesture callbacks rather
    /// than composition, so a plain field is enough — and has to be one, or every
    /// layout pass would invalidate whoever read it.
    var containerCoordinates: LayoutCoordinates? = null

    fun show(center: Offset, size: Size) {
        guides = InertiaGuides(center, size)
    }

    fun hide() {
        guides = null
    }
}

private val guideColor = Color.Cyan
private val crosshairColor = Color.Red

/// Dashed guides tracking the dragged node's edges and center within the
/// container, over a crosshair through the container's own center.
@Composable
private fun InertiaAlignmentGrid(guides: InertiaGuideState, modifier: Modifier = Modifier) {
    // Deliberately no `graphicsLayer` here. It would confine the overlay's
    // redraws, but a container-sized layer is a container-sized offscreen buffer
    // to allocate and composite every frame, which an emulator's renderer — where
    // this runtime is usually being watched — pays for far more dearly than it
    // saves.
    Canvas(modifier) {
        val current = guides.guides ?: return@Canvas
        val node = current.size
        val center = current.center
        // A node measured before its first layout, or mid-teardown, has nothing
        // to draw guides against.
        if (!node.isSpecified || node.width <= 0f || node.height <= 0f) return@Canvas
        if (!center.isSpecified || center.x.isNaN() || center.y.isNaN()) return@Canvas

        val width = 1.dp.toPx()
        val dash = 4.dp.toPx()
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))

        drawLine(
            color = crosshairColor,
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height),
            strokeWidth = width
        )
        drawLine(
            color = crosshairColor,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = width
        )

        drawGuide(center.x - node.width / 2f, isVertical = true, isCenter = false, width, dashEffect)
        drawGuide(center.x, isVertical = true, isCenter = true, width, dashEffect)
        drawGuide(center.x + node.width / 2f, isVertical = true, isCenter = false, width, dashEffect)

        drawGuide(center.y - node.height / 2f, isVertical = false, isCenter = false, width, dashEffect)
        drawGuide(center.y, isVertical = false, isCenter = true, width, dashEffect)
        drawGuide(center.y + node.height / 2f, isVertical = false, isCenter = false, width, dashEffect)

        drawRect(
            color = guideColor,
            topLeft = Offset(center.x - node.width / 2f, center.y - node.height / 2f),
            size = node,
            style = Stroke(width = width, pathEffect = dashEffect)
        )
    }
}

// ========== TOOL HANDLES ==========

private val handleColor = Color(0xFF2EB67D)

/// Which way one of the move tool's axis arrows lets a drag move the node.
///
/// The node's own body stays free in both directions; an arrow pins one component
/// of the drag to zero, for the moves that have to keep a row or a column. Screen
/// axes, not the node's own — the arrows are placed along them however the node
/// has been turned, which is what
/// [InertiaToolHandleGeometry.axisArrowCenter] leaves the rotation out for.
enum class InertiaTranslateAxis {
    horizontal,
    vertical;

    /// The drag with the component this axis does not author dropped.
    fun constrain(delta: Offset): Offset = when (this) {
        horizontal -> Offset(delta.x, 0f)
        vertical -> Offset(0f, delta.y)
    }
}

/// What one actionable's handles are drawn from, published by that actionable
/// whenever it is the one being edited.
///
/// Carries its own callbacks because the chrome lives in the container while the
/// state it edits lives in the node: the overlay decides *that* a knob was
/// grabbed and where the finger has gone, and the node turns that into an edit.
@Stable
class InertiaToolHandleTarget(
    /// The actionable these handles belong to. Every actionable publishes, and
    /// this is what stops one that is not selected from taking down the handles
    /// of the one that is.
    val owner: String,
    val tool: InertiaTool,
    /// The node's transform as drawn right now, gesture included.
    ///
    /// A lambda rather than a snapshot: the overlay calls it from its draw scope,
    /// so the edit the node is accumulating is read there and a gesture repaints
    /// the chrome without anything having to republish this target.
    val values: () -> InertiaAnimationValues,
    /// The node's laid-out box in the container's coordinate space, before any
    /// of the transform is applied. Unaffected by any tool that grows handles —
    /// only the move tool changes where a node sits, and it has none.
    val layoutOrigin: Offset,
    val layoutSize: Size,
    val canvasSize: IntSize,
    /// The transform these handles are drawn inside of, for a shape. Null for an
    /// actionable — see [InertiaToolHandleGeometry.outer].
    ///
    /// A lambda for the same reason [values] is: the actionable a shape sits
    /// inside of is moving too, and the chrome has to follow it without anything
    /// republishing this target.
    val outer: (() -> InertiaOuterTransform)? = null,
    /// See [InertiaToolHandleGeometry.movesByKnob].
    val movesByKnob: Boolean = false,
    /// Pointer positions, in the container's coordinate space. The index is the
    /// knob that was grabbed, its place in [InertiaToolHandleGeometry.knobs] —
    /// which is what tells the move tool's two arrows apart.
    val onBegin: (Int, Offset) -> Unit,
    val onDrag: (Offset) -> Unit,
    val onEnd: () -> Unit
) {
    fun geometry(): InertiaToolHandleGeometry =
        InertiaToolHandleGeometry(
            tool, values(), layoutOrigin, layoutSize, canvasSize, outer?.invoke(), movesByKnob
        )
}

/// Where every piece of one actionable's chrome sits, for one set of values.
class InertiaToolHandleGeometry(
    val tool: InertiaTool,
    val values: InertiaAnimationValues,
    private val layoutOrigin: Offset,
    private val layoutSize: Size,
    private val canvasSize: IntSize,
    /// The transform this chrome sits *inside*, and the box it turns about.
    ///
    /// Null for an actionable, whose handles sit directly in the container. A
    /// shape's do not: they are inside the actionable's own animation as well as
    /// the shape's, so every knob has to be carried out through that second
    /// transform before it is in the space a pointer reports its position in.
    private val outer: InertiaOuterTransform? = null,
    /// Whether the move tool grows a knob at the node's center for dragging it
    /// freely, on top of its two axis arrows.
    ///
    /// A shape's, and only a shape's. An actionable is moved freely by pressing
    /// its own body — see its pointer input — but a shape's surface is placed
    /// outside the bounds of the zero-sized node holding it, and Compose does
    /// not deliver pointer events to a child outside its parent's bounds. The
    /// same constraint that put every other piece of chrome in this overlay.
    /// The other two runtimes drag the shape itself and have no knob here.
    val movesByKnob: Boolean = false
) {
    val isDrawable: Boolean
        get() = layoutSize.width > 0f && layoutSize.height > 0f

    /// Every scale this chrome is drawn inside of, so a knob keeps its place
    /// however the node *and* whatever carries it have been scaled.
    private val drawnScale: Float
        get() = values.scale * (outer?.values?.scale ?: 1f)

    /// Where a point of the node's own box ends up on screen.
    fun drawn(local: Offset): Offset {
        val point = values.drawnPoint(local, layoutOrigin, layoutSize, canvasSize)
        return outer?.let {
            it.values.drawnContainerPoint(point, it.layoutOrigin, it.layoutSize, canvasSize)
        } ?: point
    }

    val drawnCenter: Offset
        get() = drawn(Offset(layoutSize.width / 2f, layoutSize.height / 2f))

    /// The point the gesture turns or scales about. Rotation about the top-left
    /// corner turns about that corner; everything else pivots on the center.
    val anchor: Offset
        get() = if (tool == InertiaTool.rotate) drawn(Offset.Zero) else drawnCenter

    /// Every knob for this tool — what is drawn, and what a press is tested
    /// against.
    val knobs: List<Offset>
        get() = when (tool) {
            // In the order [InertiaTranslateAxis] declares, which is how a knob
            // index maps back to the axis it pins a move to. The free move comes
            // after them, so an index past the axes is the one that is free in
            // both — see [movesByKnob] and [openToolGesture].
            InertiaTool.translate ->
                InertiaTranslateAxis.entries.map { axisArrowCenter(it) } +
                    (if (movesByKnob) listOf(drawnCenter) else emptyList())
            InertiaTool.rotate -> listOf(drawn(rotateKnobLocal))
            InertiaTool.rotateCenter -> listOf(drawn(Offset(layoutSize.width / 2f, -knobGapLocal)))
            InertiaTool.scale -> listOf(
                drawn(Offset.Zero),
                drawn(Offset(layoutSize.width, 0f)),
                drawn(Offset(0f, layoutSize.height)),
                drawn(Offset(layoutSize.width, layoutSize.height))
            )
            InertiaTool.opacity -> listOf(opacityKnob)
        }

    /// The middle of one of the move tool's axis arrows — what it is drawn about,
    /// and what a press on it is tested against.
    ///
    /// Placed from the node's drawn center along the screen's own axes, with the
    /// node's rotation deliberately left out: what an arrow pins a move to is
    /// horizontal and vertical on screen, so it has to point that way whatever
    /// the node has been turned by. The SwiftUI and React runtimes draw their
    /// chrome inside the node and counter-rotate it to arrive at the same place.
    fun axisArrowCenter(axis: InertiaTranslateAxis): Offset {
        val reach = axisGap + axisLength / 2f
        val half = Size(layoutSize.width * drawnScale / 2f, layoutSize.height * drawnScale / 2f)

        return when (axis) {
            InertiaTranslateAxis.horizontal ->
                Offset(drawnCenter.x + half.width + reach, drawnCenter.y)
            InertiaTranslateAxis.vertical ->
                Offset(drawnCenter.x, drawnCenter.y - half.height - reach)
        }
    }

    /// The stem into one arrow: the node's drawn center out to the arrow's tail,
    /// which is where the head begins.
    fun axisStem(axis: InertiaTranslateAxis): Pair<Offset, Offset> {
        val center = axisArrowCenter(axis)
        val back = axisLength / 2f

        return drawnCenter to when (axis) {
            InertiaTranslateAxis.horizontal -> Offset(center.x - back, center.y)
            InertiaTranslateAxis.vertical -> Offset(center.x, center.y + back)
        }
    }

    /// The gap is expressed in the node's own space so that [drawn] carries it
    /// out to where the node has been scaled and turned to — which is what keeps
    /// a knob on the corner it belongs to however the node has been transformed.
    private val knobGapLocal: Float
        get() = if (drawnScale > minimumToolScale) knobGap / drawnScale else knobGap

    private val rotateKnobLocal: Offset
        get() {
            val diagonal = maxOf(hypot(layoutSize.width, layoutSize.height), 1f)
            return Offset(
                -(layoutSize.width / diagonal) * knobGapLocal,
                -(layoutSize.height / diagonal) * knobGapLocal
            )
        }

    /// The opacity track runs along the bottom of the node, held out to a
    /// minimum on screen so a small node still gets something aimable.
    val opacityTrack: Pair<Offset, Offset>
        get() {
            val localWidth = if (drawnScale > minimumToolScale) trackWidth / drawnScale else trackWidth
            val y = layoutSize.height + knobGapLocal
            val left = (layoutSize.width - localWidth) / 2f
            return drawn(Offset(left, y)) to drawn(Offset(left + localWidth, y))
        }

    val trackWidth: Float
        get() = maxOf(layoutSize.width * drawnScale, 60f)

    private val opacityKnob: Offset
        get() {
            val (start, end) = opacityTrack
            val fraction = values.opacity.coerceIn(0f, 1f)
            return Offset(
                start.x + (end.x - start.x) * fraction,
                start.y + (end.y - start.y) * fraction
            )
        }

    val readout: String?
        get() = when (tool) {
            InertiaTool.translate -> null
            InertiaTool.rotate -> "${values.rotate.roundToInt()}°"
            InertiaTool.rotateCenter -> "${values.rotateCenter.roundToInt()}°"
            InertiaTool.scale -> String.format(Locale.US, "%.2f×", values.scale)
            InertiaTool.opacity -> "${(values.opacity * 100).roundToInt()}%"
        }

    companion object {
        /// How far outside the node's box a knob sits, on screen.
        const val knobGap = 22f
        /// From the drawn edge of the node's box out to an axis arrow's tail, and
        /// the head's own length and half-width.
        const val axisGap = 22f
        const val axisLength = 14f
        const val axisHalfWidth = 7f
    }
}

/// Everything a shape drawn behind an actionable needs in order to be picked and
/// dragged, or null when nothing here is selectable.
///
/// Nil in a shipped build and whenever the editor has the viewport out of
/// actionable mode — a shape is then a backdrop and nothing more.
///
/// The shapes sit inside their actionable's own transform, so they are handed it
/// as [outer]: a lambda, because that actionable may be animating and the chrome
/// has to follow it.
internal class InertiaShapeEditing(
    /// Whether this shape is one the editor has picked. Selected by the shape's
    /// own id, which is what a selection carries — see [InertiaShape.id].
    val isSelected: (InertiaShape) -> Boolean,
    /// Which property a gesture on a selected shape authors, as picked in the
    /// editor's toolbar. The same tool the actionables are edited with: there is
    /// one palette, and a shape is edited through it exactly as a view is.
    val tool: InertiaTool,
    /// The actionable's transform as it is drawn right now, gesture included.
    /// Read per frame, from the overlay's draw scope, so the chrome follows an
    /// actionable that is animating.
    val outerValues: () -> InertiaAnimationValues,
    /// The actionable's laid-out box in the container's space. Measured rather
    /// than sampled — `positionInRoot` walks the tree to the root — so it is
    /// taken when the handles are published and when a gesture opens, never per
    /// frame. The same bargain the actionable's own handles strike.
    val outerLayoutBox: () -> Pair<Offset, Size>,
    /// Where the chrome is published. One target at a time, so an actionable and
    /// a shape both being selected leaves the handles on whichever published
    /// last — the hierarchy panel picks one thing at a time.
    val handles: InertiaToolHandleState?,
    /// What this shape's handles are owned by, which is what stops one node from
    /// taking down another's chrome. Instance-scoped, since two instances of a
    /// card carry two copies of the same shape.
    val owner: (InertiaShape) -> String,
    /// What the gesture on this shape has produced so far, held by the actionable
    /// so it survives the canvas being rebuilt mid-drag.
    val edit: (InertiaShape) -> InertiaToolEdit,
    val onChange: (InertiaShape, InertiaToolEdit) -> Unit,
    val onEnded: (InertiaShape) -> Unit
)

/// The handle overlay's state, held per [InertiaContainer] and written by the
/// actionable being edited. The same shape as [InertiaGuideState], and for the
/// same reason: one write per pointer event, read only from the overlay's draw
/// scope and its gesture handler, so a gesture repaints the overlay without
/// recomposing anything.
@Stable
class InertiaToolHandleState {
    var target by mutableStateOf<InertiaToolHandleTarget?>(null)
        private set

    fun show(target: InertiaToolHandleTarget) {
        this.target = target
    }

    fun hide() {
        target = null
    }
}

/// The chrome for the active tool, drawn over the container.
///
/// In the container rather than inside the node it belongs to, which is the one
/// place this runtime deliberately differs from the SwiftUI and React ones.
/// Every tool but the move tool puts at least one knob outside the node's own
/// box — the rotation knob past a corner, the opacity track below the bottom
/// edge — and Compose does not deliver pointer events to a child outside its
/// parent's bounds, so a knob drawn inside the node would be visible and not
/// grabbable. Drawn out here the chrome is placed through
/// [InertiaToolHandleTarget.drawn] instead of being carried by the node's
/// transform, which is why it needs no counter-scaling: it is already in the
/// container's space, where a pixel is a pixel.
@Composable
private fun InertiaToolHandlesOverlay(
    state: InertiaToolHandleState,
    modifier: Modifier = Modifier
) {
    val coordinates = remember { InertiaHandleCoordinates() }
    val touchSize = with(LocalDensity.current) { (knobTouchRadius * 2f).toDp() }

    Box(modifier.onGloballyPositioned { coordinates.overlay = it }) {
        // The chrome draws in a node that takes no pointer input at all.
        //
        // A container-sized `pointerInput` here is what the first version of
        // this had, and it made the app under test untouchable: a pointer input
        // node that is hit stops its siblings *underneath* from being hit at
        // all, whatever it does or doesn't consume — so an overlay spanning the
        // container swallowed every tap and drag meant for the actionables
        // below it. Only the knobs take pointer input now, and only where they
        // actually are.
        InertiaToolHandleChrome(state, Modifier.matchParentSize())

        // Read in composition, so this changes when the selection or the tool
        // does — not when a gesture moves anything. Derived from the tool rather
        // than from the geometry for exactly that reason: the geometry reads the
        // node's live edit, and reading it here would recompose per event.
        val target = state.target
        val knobCount = when (target?.tool) {
            null -> 0
            // One arrow per axis, and for a shape one more for the free move:
            // an actionable's own body takes that drag, and a shape's cannot.
            // See [InertiaToolHandleGeometry.movesByKnob].
            InertiaTool.translate ->
                InertiaTranslateAxis.entries.size + (if (target.movesByKnob) 1 else 0)
            InertiaTool.scale -> 4
            else -> 1
        }

        repeat(knobCount) { index ->
            Box(
                Modifier
                    // Placed in the placement phase, so a knob follows the node
                    // it belongs to without recomposing anything.
                    .offset {
                        val knob = state.target?.geometry()?.knobs?.getOrNull(index)
                            ?: return@offset offscreen
                        IntOffset(
                            (knob.x - knobTouchRadius).roundToInt(),
                            (knob.y - knobTouchRadius).roundToInt()
                        )
                    }
                    .size(touchSize)
                    .onGloballyPositioned { coordinates.knobs[index] = it }
                    .pointerInput(target, index) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val grabbed = state.target ?: return@awaitEachGesture

                            // Consumed, so the actionable underneath treats this
                            // as no press of its own — without it a grab on a
                            // knob would also toggle the selection off.
                            down.consume()
                            grabbed.onBegin(index, coordinates.inOverlay(index, down.position))

                            do {
                                val event = awaitPointerEvent()
                                event.changes.firstOrNull()?.let { change ->
                                    grabbed.onDrag(coordinates.inOverlay(index, change.position))
                                    change.consume()
                                }
                            } while (event.changes.any { it.pressed })

                            grabbed.onEnd()
                        }
                    }
            )
        }
    }
}

/// Where the overlay and each of its knobs sit, so a press on a knob can be
/// expressed in the container's coordinate space — which is the space the
/// gesture math, and the geometry it is measured against, are in.
///
/// Plain fields: written on every layout pass and read only from gesture
/// callbacks, never from composition.
private class InertiaHandleCoordinates {
    var overlay: LayoutCoordinates? = null
    val knobs = arrayOfNulls<LayoutCoordinates>(4)

    /// Converted through the live coordinates of both nodes, so it stays exact
    /// even though the knob is moving under the finger: a press is reported
    /// relative to wherever the knob is *now*, and this puts it back into the
    /// one frame that is standing still.
    fun inOverlay(index: Int, local: Offset): Offset {
        val overlay = overlay ?: return local
        val knob = knobs.getOrNull(index) ?: return local
        if (!overlay.isAttached || !knob.isAttached) return local

        return overlay.localPositionOf(knob, local)
    }
}

/// Somewhere a knob with nothing to point at cannot be pressed.
private val offscreen = IntOffset(-10_000, -10_000)

@Composable
private fun InertiaToolHandleChrome(
    state: InertiaToolHandleState,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier) {
        val target = state.target ?: return@Canvas
        // Called in the draw scope, so the edit the node is accumulating is read
        // here: a gesture repaints this overlay's layer without recomposing or
        // re-laying out anything.
        val geometry = target.geometry()
        if (!geometry.isDrawable) return@Canvas

        // The knob's own outline stays fine; the rings and the track are
        // heavier, so they read against whatever the app happens to be drawing
        // underneath them.
        val stroke = 1.5.dp.toPx()
        val chromeStroke = 3.dp.toPx()
        val dashEffect = PathEffect.dashPathEffect(
            floatArrayOf(9.dp.toPx(), 7.dp.toPx())
        )

        when (geometry.tool) {
            InertiaTool.translate -> {
                InertiaTranslateAxis.entries.forEach { axis ->
                    val (from, to) = geometry.axisStem(axis)
                    drawLine(
                        color = handleColor,
                        start = from,
                        end = to,
                        strokeWidth = chromeStroke,
                        alpha = 0.6f
                    )

                    val head = axisArrowPath(axis, geometry.axisArrowCenter(axis))
                    drawPath(head, color = handleColor)
                    drawPath(head, color = Color.White, style = Stroke(width = stroke))
                }
            }

            InertiaTool.rotate, InertiaTool.rotateCenter -> {
                val anchor = geometry.anchor
                val knob = geometry.knobs.firstOrNull() ?: return@Canvas

                drawCircle(
                    color = handleColor,
                    radius = (knob - anchor).getDistance(),
                    center = anchor,
                    alpha = 0.6f,
                    style = Stroke(width = chromeStroke, pathEffect = dashEffect)
                )
                drawLine(
                    color = handleColor,
                    start = anchor,
                    end = knob,
                    strokeWidth = chromeStroke,
                    alpha = 0.6f
                )
            }

            InertiaTool.scale -> Unit

            InertiaTool.opacity -> {
                val (start, end) = geometry.opacityTrack
                val fraction = geometry.values.opacity.coerceIn(0f, 1f)
                val filled = Offset(
                    start.x + (end.x - start.x) * fraction,
                    start.y + (end.y - start.y) * fraction
                )
                val thickness = 7.dp.toPx()

                drawLine(handleColor, start, end, strokeWidth = thickness, alpha = 0.25f)
                drawLine(handleColor, start, filled, strokeWidth = thickness)
            }
        }

        // The move tool's knobs are the arrowheads drawn above; every other
        // tool's is a circle sitting where it can be grabbed. The free move is
        // the exception: it is a knob of the move tool and is drawn as one,
        // since nothing else marks where a shape can be taken hold of.
        val circles = if (geometry.tool == InertiaTool.translate) {
            geometry.knobs.drop(InertiaTranslateAxis.entries.size)
        } else {
            geometry.knobs
        }

        circles.forEach { knob ->
            drawCircle(color = handleColor, radius = knobRadius, center = knob)
            drawCircle(
                color = Color.White,
                radius = knobRadius,
                center = knob,
                style = Stroke(width = stroke)
            )
        }

        geometry.readout?.let { text ->
            val laid = textMeasurer.measure(
                text = text,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            )
            // Above the node's top-left, clear of every knob, and never turned
            // with it — a number is only readable one way up.
            val topLeft = geometry.drawn(Offset.Zero) -
                Offset(0f, InertiaToolHandleGeometry.knobGap + 64f)
            val padding = 9.dp.toPx()

            drawRoundRect(
                color = handleColor,
                topLeft = topLeft - Offset(padding, padding / 2f),
                size = Size(laid.size.width + padding * 2f, laid.size.height + padding),
                cornerRadius = CornerRadius(laid.size.height.toFloat())
            )
            drawText(laid, topLeft = topLeft)
        }
    }
}

/// A filled arrowhead about [center], pointing along one screen axis: right for
/// the horizontal one, up for the vertical one.
///
/// One head rather than two: both directions of an axis are draggable, and the
/// arrow only has to read as the axis it stands for.
private fun axisArrowPath(axis: InertiaTranslateAxis, center: Offset): Path {
    val long = InertiaToolHandleGeometry.axisLength / 2f
    val across = InertiaToolHandleGeometry.axisHalfWidth

    return Path().apply {
        when (axis) {
            InertiaTranslateAxis.horizontal -> {
                moveTo(center.x - long, center.y - across)
                lineTo(center.x + long, center.y)
                lineTo(center.x - long, center.y + across)
            }
            InertiaTranslateAxis.vertical -> {
                moveTo(center.x - across, center.y + long)
                lineTo(center.x, center.y - long)
                lineTo(center.x + across, center.y + long)
            }
        }
        close()
    }
}

/// How close a press has to land to a knob to grab it. Generous next to the
/// knob's own radius, because a fingertip is.
private const val knobRadius = 7f
private const val knobTouchRadius = 28f

/// The handle gesture one node has in progress, if any.
internal class InertiaToolGesture {
    var start: InertiaToolGestureStart? = null
}

/// Where a handle gesture opened. Everything here is taken once, at the press,
/// so the math stays measured against the transform the node had before the
/// gesture rather than the one the gesture is giving it.
internal class InertiaToolGestureStart(
    /// The point the gesture turns or scales about, in the container's space.
    val anchor: Offset,
    /// The axis the arrow this opened on pins a move to, for the move tool. Taken
    /// at the press, because the arrow travels with the node the drag is moving.
    val axis: InertiaTranslateAxis?,
    /// The finger's opening vector from [anchor], which an angle or a distance
    /// ratio is taken relative to.
    val reference: Offset,
    /// The finger's opening position, in the container's space.
    val origin: Offset,
    /// The node's transform when the gesture began, and the edit already folded
    /// into it.
    val values: InertiaAnimationValues,
    val edit: InertiaToolEdit,
    /// The node's laid-out box in the container's space. The origin is only the
    /// guides' business, and only while a move is being dragged.
    val layoutOrigin: Offset,
    val layoutSize: Size,
    /// The transform the node was sitting inside when the gesture began, for a
    /// shape. Null for an actionable, which sits inside nothing.
    val outer: InertiaOuterTransform? = null,
    /// Whether this opened on the move tool's free knob rather than on one of
    /// its axis arrows — a move that keeps both components of the drag. See
    /// [InertiaToolHandleGeometry.movesByKnob].
    val isFreeMove: Boolean = false
) {
    /// Every scale the node was drawn with when the gesture opened — its own,
    /// and whatever carries it.
    val drawnScale: Float
        get() = values.scale * (outer?.values?.scale ?: 1f)
}

private fun Offset.angleDegrees(): Float = atan2(y, x) * 180f / PI.toFloat()

/// Opens a handle gesture on a node, whatever kind of node it is.
///
/// [knobIndex] is the knob that was grabbed, its place in
/// [InertiaToolHandleGeometry.knobs] — which is what tells the move tool's two
/// arrows apart.
internal fun openToolGesture(
    tool: InertiaTool,
    knobIndex: Int,
    position: Offset,
    values: InertiaAnimationValues,
    edit: InertiaToolEdit,
    layoutOrigin: Offset,
    layoutSize: Size,
    canvasSize: IntSize,
    outer: InertiaOuterTransform? = null,
    movesByKnob: Boolean = false
): InertiaToolGestureStart {
    val anchor = InertiaToolHandleGeometry(
        tool, values, layoutOrigin, layoutSize, canvasSize, outer, movesByKnob
    ).anchor

    return InertiaToolGestureStart(
        anchor = anchor,
        // Which knob was grabbed is which axis it stands for — see
        // [InertiaToolHandleGeometry.knobs].
        axis = if (tool == InertiaTool.translate) {
            InertiaTranslateAxis.entries.getOrNull(knobIndex)
        } else {
            null
        },
        reference = position - anchor,
        origin = position,
        values = values,
        edit = edit,
        layoutOrigin = layoutOrigin,
        layoutSize = layoutSize,
        outer = outer,
        isFreeMove = tool == InertiaTool.translate
            && movesByKnob
            && knobIndex >= InertiaTranslateAxis.entries.size
    )
}

/// The edit this gesture has reached, given where the finger is now.
///
/// The one place the five tools are worked out, for the actionables and for the
/// shapes drawn behind them alike: a shape is edited through the same palette a
/// view is, so it is edited by the same math. Mirrors the same five cases in the
/// SwiftUI and React runtimes.
internal fun InertiaToolGestureStart.editAt(tool: InertiaTool, position: Offset): InertiaToolEdit {
    val current = position - anchor

    return when (tool) {
        InertiaTool.translate -> {
            // The same move the body of the node takes, with the axis the arrow
            // stands for the only one it is allowed to author. An arrow with no
            // axis behind it moves nothing rather than moving freely — a press
            // that cannot be attributed is not a move anyone asked for.
            //
            // Constrained on screen, where the arrows are, and carried into the
            // space the node's own offset lands in afterwards.
            val delta = position - origin
            val constrained = if (isFreeMove) delta else axis?.constrain(delta) ?: Offset.Zero
            edit.copy(translate = edit.translate + (outer?.values?.unapplying(constrained) ?: constrained))
        }

        InertiaTool.rotate, InertiaTool.rotateCenter -> {
            val swept = current.angleDegrees() - reference.angleDegrees()
            if (tool == InertiaTool.rotate) {
                edit.copy(rotate = edit.rotate + swept)
            } else {
                edit.copy(rotateCenter = edit.rotateCenter + swept)
            }
        }

        InertiaTool.scale -> {
            val referenceLength = reference.getDistance()
            if (referenceLength > 1f) {
                val factor = current.getDistance() / referenceLength
                val scaled = maxOf(minimumToolScale, values.scale * factor)
                edit.copy(scale = edit.scale + (scaled - values.scale))
            } else {
                edit
            }
        }

        InertiaTool.opacity -> {
            // Measured along the track from where the gesture opened, so the
            // knob tracks the finger instead of jumping to it. The track as it
            // is drawn, which is the node's box through every scale above it.
            val width = maxOf(layoutSize.width * drawnScale, 60f)
            val travelled = (position.x - origin.x) / width
            val settled = (values.opacity + travelled).coerceIn(0f, 1f)
            edit.copy(opacity = edit.opacity + (settled - values.opacity))
        }
    }
}

/// What the alignment guides for one actionable are drawn from.
///
/// Plain fields, not snapshot state: [coordinates] is reassigned on every layout
/// pass and the rest on every gesture, and none of it is read from composition.
/// Backing any of it with `mutableStateOf` would put a write into the layout
/// phase that invalidates whoever reads it, which is the shape of a drag that
/// re-lays out the tree on every pointer event.
private class InertiaNodeMeasurement {
    var coordinates: LayoutCoordinates? = null
    var baseCenter: Offset = Offset.Zero
        private set
    var size: Size = Size.Zero
        private set

    /// Takes the node's laid-out center in [container]'s space and the size the
    /// guides box in. Called once when a drag starts rather than on every layout:
    /// the values it reads only change when layout does, and `positionInRoot`
    /// walks the tree to the root every time it is asked.
    ///
    /// The modifier this measures from sits outside both the drag offset and the
    /// animation layer, so what lands here is where layout put the node — which
    /// is what the drag offset is added to — rather than where it is drawn.
    fun measure(container: LayoutCoordinates?) {
        val coordinates = coordinates ?: return
        if (container == null || !coordinates.isAttached || !container.isAttached) return

        val origin = coordinates.positionInRoot() - container.positionInRoot()
        size = coordinates.size.toSize()
        baseCenter = Offset(origin.x + size.width / 2f, origin.y + size.height / 2f)
    }
}

/// One guide line spanning the container, solid through the node's center and
/// dashed along its edges.
private fun DrawScope.drawGuide(
    at: Float,
    isVertical: Boolean,
    isCenter: Boolean,
    width: Float,
    dash: PathEffect
) {
    drawLine(
        color = guideColor,
        start = if (isVertical) Offset(at, 0f) else Offset(0f, at),
        end = if (isVertical) Offset(at, size.height) else Offset(size.width, at),
        strokeWidth = width,
        alpha = if (isCenter) 1f else 0.5f,
        pathEffect = if (isCenter) null else dash
    )
}

// ========== COMPOSITION LOCALS ==========

/// Playback for the enclosing [InertiaContainer].
val LocalInertia = staticCompositionLocalOf<InertiaPlaybackController> {
    error("LocalInertia was read outside of an InertiaContainer.")
}

/// The alignment overlay of the enclosing [InertiaContainer].
private val LocalInertiaGuides = compositionLocalOf<InertiaGuideState?> { null }

/// The tool-handle overlay of the enclosing [InertiaContainer].
private val LocalInertiaToolHandles = compositionLocalOf<InertiaToolHandleState?> { null }

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

/// The frame every animation in it is measured against.
///
/// A `translate` of 1 crosses the whole container, so what the container *is* has
/// to mean the same thing on every runtime or one authored animation moves a
/// different distance on each. It is the space the host offers this composable,
/// filled — the same rectangle SwiftUI's `GeometryReader` reports and the React
/// runtime's container div occupies. A container that sized itself to its
/// content instead would be as big as whatever happened to be inside it, which
/// is not something the editor can know while the animation is being authored.
@Composable
fun InertiaContainer(
    dev: Boolean,
    id: String,
    hierarchyId: String,
    baseURL: String,
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

    val playback = remember { InertiaPlaybackController() }
    val guides = remember { InertiaGuideState() }
    val toolHandles = remember { InertiaToolHandleState() }

    val context = LocalContext.current

    /// Outside the editor the schemas come from the shipped animation file
    /// rather than the socket, the way the SwiftUI runtime reads
    /// `<id>.msgpack` from its bundle and the React runtime fetches it from
    /// `baseURL`. A missing or unreadable file leaves the actionables at their
    /// layout positions rather than bringing the app down — a broken animation
    /// is not worth a crash.
    LaunchedEffect(dev, id) {
        if (dev) return@LaunchedEffect

        val fileName = "$id.$INERTIA_FILE_EXTENSION"
        val schemas = try {
            // Read as bytes rather than text: the file is MessagePack, and most
            // of it is not valid UTF-8.
            val bytes = context.assets.open(fileName).use { it.readBytes() }
            inertiaMsgPack.decodeFromByteArray<List<InertiaAnimationSchema>>(bytes)
        } catch (error: Exception) {
            InertiaLog.error("failed to load $fileName: $error")
            return@LaunchedEffect
        }

        InertiaLog.debug("loaded ${schemas.size} schema(s) from $fileName")

        // Keyed by the id they were authored against, which is the `id` an
        // actionable hands to [Inertia]: there are no per-instance ids on disk.
        model = model.copyMutable {
            schemas.forEach { schema ->
                inertiaSchemas[schema.id] = schema
                actionableIdToAnimationIdMap[schema.id] = schema.id
            }
        }
    }

    LaunchedEffect(dev, model.tree, baseURL) {
        // Nothing but the editor is on the other end of this socket, so a
        // shipped build does not open one. The SwiftUI runtime gates its
        // channel on `dev` and the React runtime returns before connecting.
        if (!dev) return@LaunchedEffect

        val ws = WebSocketClient.shared

        // The URL the app passed, as passed. This used to rewrite `127.0.0.1` to
        // a hardcoded address for one particular emulator network, which is not
        // something the runtime can know — an app that needs a different host
        // (`10.0.2.2` from a stock emulator, a LAN address from a device) says so
        // in `baseURL`.
        InertiaLog.debug("connecting to $baseURL")

        ws.connect(url = baseURL) {
            val msg = MessageActionables(
                tree = model.tree.toDTO(),
                actionableIds = model.actionableIds.toSet()
            )
            ws.sendMessageActionables(MessageType.actionables, msg)
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
        launch {
            ws.onTool.collect { tool ->
                model = model.copyMutable { activeTool = tool }
                // A gesture in progress was opened against the old tool's
                // handle, and the property it was editing is not the one the new
                // tool would author. The node drops its own half in the same
                // breath — see the effect keyed on the tool.
                toolHandles.hide()
                guides.hide()
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

    // The clock. Keyed on `isTicking` so a paused container — or one holding the
    // last frame of a run that plays once — is not holding the frame loop open.
    LaunchedEffect(playback.isTicking) {
        if (!playback.isTicking) return@LaunchedEffect

        while (isActive) {
            withFrameNanos { playback.tick(it) }
        }
    }

    Box(
        modifier = Modifier
            // The space the host offered, filled — see this composable's doc.
            // This used to be `wrapContentSize()`, which sized the container to
            // whatever was inside it: the same `translate: [0.5, 0]` then moved
            // a card half the width of the *content* on Android and half the
            // width of the *screen* on iOS and the web.
            .fillMaxSize()
            .onSizeChanged { size = it }
            // The frame the guides are measured against, so a position taken in
            // an actionable and a point drawn in the overlay share an origin.
            .onGloballyPositioned { guides.containerCoordinates = it },
        // What `wrapContentSize` gave for free and filling the space does not:
        // content smaller than the container sits in the middle of it rather
        // than in its top-left corner. The same placement as the SwiftUI
        // runtime's `ZStack(alignment: .center)`.
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(
            LocalInertia provides playback,
            LocalCanvasSize provides size,
            LocalInertiaDataModel provides model,
            LocalUpdateModel provides updateModel,
            LocalInertiaGuides provides guides,
            LocalInertiaToolHandles provides toolHandles,
            LocalInertiaParentId provides hierarchyId,
            LocalInertiaContainerId provides hierarchyId,
            LocalInertiaIsContainer provides true
        ) { content() }

        if (showAlignmentGrid) {
            // Composed whether or not a drag is in progress — it draws nothing
            // when there is none — so the container's composition never depends
            // on the overlay's state. `matchParentSize` rather than
            // `fillMaxSize`: the overlay spans the container without taking part
            // in measuring it, which is what keeps it out of the way of whatever
            // constraints the host handed down.
            InertiaAlignmentGrid(guides, Modifier.matchParentSize())
        }

        // After the grid, so a knob is never drawn under a guide line, and
        // topmost in the container, so a press on one reaches it before the
        // actionable underneath. Composed whether or not anything is selected —
        // it draws nothing and grabs nothing when the state is empty — for the
        // same reason the grid is.
        InertiaToolHandlesOverlay(toolHandles, Modifier.matchParentSize())
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
    copy.activeTool = activeTool
    block(copy)
    return copy
}

@Composable
fun Inertia(
    id: String,
    content: @Composable () -> Unit
) {
    val model = LocalInertiaDataModel.current
    val updateModel = LocalUpdateModel.current
    val playback = LocalInertia.current
    val guides = LocalInertiaGuides.current
    val handles = LocalInertiaToolHandles.current
    val parentId = LocalInertiaParentId.current
    val isContainer = LocalInertiaIsContainer.current
    val canvasSize = LocalCanvasSize.current

    /// The id the app authored against, which every instance of this actionable
    /// shares. What playback is keyed by, and what a schema loaded from a project
    /// file is named after.
    val hierarchyIdPrefix = id

    val indexMap = SharedIndexManager.indexMap
    /// This instance's own id: the prefix plus its index among its siblings.
    var instanceId by remember { mutableStateOf<String?>(null) }
    var isSelected by remember { mutableStateOf(false) }
    /// Everything the editor's gestures have added on top of this node's schema,
    /// still waiting for the editor to fold them in. A gesture reports movement
    /// relative to its own start, so without carrying this every gesture after
    /// the first would snap the node back to where its schema puts it.
    var edit by remember { mutableStateOf(InertiaToolEdit()) }
    /// The same, for each shape drawn behind this node, by the shape's own id.
    ///
    /// Held here rather than in the canvas that draws the shape, so a gesture
    /// survives that canvas being rebuilt under it — which is what happens on
    /// every frame the shape is dragged.
    val shapeEdits = remember { mutableStateMapOf<String, InertiaToolEdit>() }
    /// This node's laid-out size. The shapes behind it are measured in multiples
    /// of it, and nothing else about where it sits matters to them — the canvas
    /// is a child of this node, so it travels with it.
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }

    val measurement = remember { InertiaNodeMeasurement() }

    LaunchedEffect(hierarchyIdPrefix) {
        val next = (indexMap[hierarchyIdPrefix] ?: 0)
        indexMap[hierarchyIdPrefix] = next + 1
        instanceId = "$hierarchyIdPrefix--$next"
    }

    LaunchedEffect(instanceId) {
        val instance = instanceId ?: return@LaunchedEffect
        model?.tree?.addRelationship(instance, parentId, isContainer)
    }

    LaunchedEffect(instanceId, model?.actionableIds) {
        instanceId?.let { instance ->
            isSelected = model?.actionableIds?.any { it.hierarchyId == instance } == true
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
    val animation = remember(model, instanceId) {
        val instance = instanceId ?: return@remember null
        val map = model?.actionableIdToAnimationIdMap ?: return@remember null
        val animId = map[instance] ?: map[hierarchyIdPrefix]

        animId?.let { model.inertiaSchemas[it] } ?: model.inertiaSchemas[hierarchyIdPrefix]
    }

    /// The shapes authored against this actionable, if it has any. Read off the
    /// schema rather than the running animation, so the backdrop is there
    /// whether or not the animation is playing.
    val shapes = animation?.shapes ?: emptyList()

    // The animation layer below already puts this node where the schema says it
    // starts, so the drag stacked on top of it goes back to zero whenever those
    // values change: by then the gesture has been authored into the schema, and
    // leaving it in place would count the same move twice. It is also what
    // returns a node to the origin when the editor resets an animation's initial
    // values — until this, a reset changed the authored animation and left the
    // node sitting wherever it had last been dragged to.
    //
    // Keyed on the values themselves rather than on the model instance, which is
    // replaced wholesale on every update: any other change — a selection, say —
    // would otherwise drop a drag the editor has not been told about yet,
    // snapping the node out from under the finger.
    LaunchedEffect(animation?.initialValues) {
        edit = InertiaToolEdit()
    }

    // And the shapes' drags, for the same reason: a shape's gesture comes back
    // as a track on that shape, and the edit that authored it has to go once it
    // has. Keyed on the tracks themselves rather than on the model instance, so
    // a selection does not drop a drag the editor has not been told about yet.
    // One shape's track landing clears them all, which costs nothing: only one
    // shape is ever being dragged, and its own edit is the one going stale.
    LaunchedEffect(shapes.map { it.animation?.initialValues }) {
        shapeEdits.clear()
    }

    // An animation starts as soon as the runtime holds its schema, or waits for
    // the app, depending on its `invokeType` — which is why this waits on the
    // schema rather than on the actionable registering.
    LaunchedEffect(animation?.id, animation?.invokeType) {
        val invokeType = animation?.invokeType
        if (invokeType == null) {
            InertiaLog.debug(
                "no animation for instanceId=$instanceId hierarchyId=$hierarchyIdPrefix — " +
                    "map=${model?.actionableIdToAnimationIdMap} " +
                    "schemas=${model?.inertiaSchemas?.keys}"
            )
            return@LaunchedEffect
        }

        playback.register(hierarchyIdPrefix, invokeType)
    }

    /// Whether this node is the one the editor is editing. Selection alone is
    /// not enough: turning the editor's switch off leaves the selection as it
    /// was, so a node picked beforehand would go on taking gestures against an
    /// editor that has stopped accepting them.
    val isEditable = isSelected && model?.isActionable == true
    val tool = model?.activeTool ?: InertiaTool.translate

    /// What this node is drawn at right now: whatever its schema shows — the
    /// values it starts from, or where the run has got to — with the gesture in
    /// progress folded in.
    ///
    /// A lambda, not a value: the playhead and the edit are read when it is
    /// *called*, which is from a `graphicsLayer` block or a gesture callback.
    /// Both see the same values a read in composition would, but a read out here
    /// would recompose and re-lay out every actionable on every frame of every
    /// run, and on every pointer event of every gesture.
    val sample = {
        // Playback is keyed by prefix, so every actionable authored against the
        // same id runs off the one the app started.
        val isPlayable = animation != null && playback.isPlaying(hierarchyIdPrefix)
        // Scrubbing shows the animation without running it, which is why a
        // parked playhead draws the same way a running one does.
        val isShowingTrack = isPlayable && (playback.isRunning || playback.seekTime != null)

        val base = when {
            animation == null -> InertiaAnimationValues()
            isShowingTrack -> animation.valuesAtTime(
                playback.playheadTime,
                playback.playbackDuration,
                playback.isRepeating
            )
            else -> animation.initialValues.sanitized()
        }

        // One matrix for the schema and the gesture together, rather than the
        // gesture applied as an offset somewhere outside the animation's own
        // layers: what the editor is sent is a single set of values, and this is
        // what makes the node's appearance agree with them.
        if (isEditable) base.applying(edit, canvasSize) else base
    }

    val modifierWithAnim = run {
        // A node with no schema still needs the layers while the editor is
        // dragging it: an actionable nobody has animated yet has no schema until
        // the first gesture is written into one, and it has to move under the
        // finger before then.
        if ((animation == null && !isEditable) || canvasSize == IntSize.Zero) {
            Modifier
        } else {

            // `rotate` pivots on the top left corner and `rotateCenter` on the
            // center, and a layer carries a single transformOrigin — so the two
            // rotations want a layer each. Chained modifiers wrap outermost-first,
            // the order the SwiftUI runtime stacks its own modifiers in, so the
            // same schema composes the same matrix on both: offset, rotateCenter
            // and opacity outside, then rotate, then scale against the content.
            Modifier
                .graphicsLayer {
                    val v = sample()
                    translationX = v.translate.getOrElse(0) { 0f } * canvasSize.width
                    translationY = v.translate.getOrElse(1) { 0f } * canvasSize.height
                    rotationZ = v.rotateCenter
                    alpha = v.opacity
                    transformOrigin = TransformOrigin.Center
                    // An alpha below 1 is composited by drawing the layer into
                    // an offscreen buffer and then fading the buffer — and that
                    // buffer is the size of the node, so everything the
                    // animation draws outside its own box is thrown away. A
                    // scaled, rotated card came out cut off at the edges of the
                    // box it started in, which looked like clipping and was.
                    //
                    // Modulating alpha applies it to each drawing instruction
                    // instead, with no buffer and so no bounds to fall outside
                    // of. What that gives up is self-overlap: where the content
                    // covers itself, the overlap now shows through at partial
                    // opacity rather than fading as one flat image.
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
    }

    /// The values this node's schema starts it at, which an edit is measured
    /// from and which the editor is told the total of.
    val initialValues = animation?.initialValues?.sanitized() ?: InertiaAnimationValues()

    // Anything a gesture reads has to reach it through a state holder rather
    // than be captured by value.
    //
    // Both gestures outlive the composition that set them up: `pointerInput`
    // launches its block once per key change and keeps that one coroutine
    // running, and the handle callbacks are held by whatever target was last
    // published. A plain capture is frozen at whatever it was when either of
    // those started. `model` is the one that bites: the tap toggle computes the
    // new selection from `model.actionableIds`, and against a frozen copy the
    // first tap adds this node's pair and every tap after it re-adds the same
    // pair to the same stale set — a node that selects and can then never be
    // tapped off again.
    //
    // `isSelected`, `edit` and `instanceId` need none of this: a local `var`
    // held by `mutableStateOf` captures the state object, so reads inside a
    // long-running block are already live.
    val currentModel by rememberUpdatedState(model)
    val currentTool by rememberUpdatedState(tool)
    val currentUpdateModel by rememberUpdatedState(updateModel)
    val currentInitialValues by rememberUpdatedState(initialValues)
    val currentCanvasSize by rememberUpdatedState(canvasSize)

    /// Shows what a gesture has produced so far and reports it to the editor's
    /// inspector. Nothing is authored by this — see the commit below.
    val applyEdit = { next: InertiaToolEdit ->
        edit = next

        val authored = currentInitialValues.applying(next, currentCanvasSize)
        WebSocketClient.shared.sendMessageSelectedNodeProperties(
            MessageSelectedNodeProperties(
                positionX = authored.translate.getOrElse(0) { 0f } * currentCanvasSize.width,
                positionY = authored.translate.getOrElse(1) { 0f } * currentCanvasSize.height,
                sizeX = measurement.size.width,
                sizeY = measurement.size.height,
                values = authored
            )
        )
    }

    /// Ends a gesture and hands the result to the editor to be written into the
    /// schema. One message whatever the tool, carrying the whole transform: a
    /// keyframe holds all five values, so the four this gesture did not touch
    /// have to travel with the one it did.
    val commitEdit = {
        val m = currentModel
        if (m != null && currentCanvasSize != IntSize.Zero) {
            WebSocketClient.shared.sendMessageEdit(
                MessageEdit(
                    tool = currentTool,
                    values = currentInitialValues.applying(edit, currentCanvasSize),
                    // The whole selection as it stands now, not as it stood when
                    // this gesture's handles were published: an edit is authored
                    // against every node the editor has picked.
                    actionableIds = m.actionableIds.toSet()
                )
            )
        }
    }

    /// Ends a gesture on a shape drawn behind this node and hands the result to
    /// the editor.
    ///
    /// The same [MessageEdit] this node sends for itself, naming the shape's own
    /// id under the schema that carries it — which is exactly how it was
    /// selected. Measured from the shape's own authored starting values rather
    /// than from wherever its track has it, for the reason [commitEdit] gives.
    val commitShapeEdit = { shape: InertiaShape ->
        if (currentCanvasSize != IntSize.Zero) {
            val settled = shapeEdits[shape.id] ?: InertiaToolEdit()
            val base = shape.animation?.initialValues?.sanitized() ?: InertiaAnimationValues()

            WebSocketClient.shared.sendMessageEdit(
                MessageEdit(
                    tool = currentTool,
                    values = base.applying(settled, currentCanvasSize),
                    actionableIds = setOf(
                        ActionableIdPair(
                            hierarchyIdPrefix = hierarchyIdPrefix,
                            hierarchyId = shape.id
                        )
                    )
                )
            )
        }
    }

    /// This node's laid-out box in the container's space, measured on demand.
    /// `positionInRoot` walks the tree to the root, so this is called when a
    /// gesture opens and when the handles are republished, not per layout pass.
    val measureLayoutBox = {
        measurement.measure(guides?.containerCoordinates)
        val size = measurement.size
        measurement.baseCenter - Offset(size.width / 2f, size.height / 2f) to size
    }

    /// Where the handle gesture opened, taken once so its math stays measured
    /// against the transform the node had before it rather than the one it is
    /// being given. A plain field for the same reason [InertiaNodeMeasurement]
    /// uses them: written per pointer event and never read from composition.
    val handleGesture = remember { InertiaToolGesture() }

    val beginHandleGesture = { index: Int, position: Offset ->
        val (origin, size) = measureLayoutBox()

        handleGesture.start = openToolGesture(
            tool = currentTool,
            knobIndex = index,
            position = position,
            values = sample(),
            edit = edit,
            layoutOrigin = origin,
            layoutSize = size,
            canvasSize = canvasSize
        )
    }

    val dragHandleGesture = { position: Offset ->
        handleGesture.start?.let { opening ->
            applyEdit(opening.editAt(tool, position))

            // The guides box a node in as it is moved, whichever handle is doing
            // the moving — the same ones the body drag puts up. They mean nothing
            // for a rotation or an opacity, where the node stays where layout put
            // it.
            if (tool == InertiaTool.translate && showAlignmentGrid) {
                val drawn = sample()
                guides?.show(
                    center = drawn.drawnPoint(
                        Offset(opening.layoutSize.width / 2f, opening.layoutSize.height / 2f),
                        opening.layoutOrigin,
                        opening.layoutSize,
                        currentCanvasSize
                    ),
                    size = Size(
                        opening.layoutSize.width * drawn.scale,
                        opening.layoutSize.height * drawn.scale
                    )
                )
            }
        }
        Unit
    }

    val endHandleGesture = {
        if (handleGesture.start != null) {
            handleGesture.start = null
            if (showAlignmentGrid) guides?.hide()
            commitEdit()
        }
    }

    /// Hands the container's overlay everything it needs to draw this node's
    /// handles and to run a gesture on them. Republished when the selection or
    /// the tool changes; a gesture needs no republish, since the overlay reads
    /// the values back through [sample].
    val publishHandles = {
        val store = handles
        val instance = instanceId
        if (store != null && instance != null) {
            if (isEditable && canvasSize != IntSize.Zero) {
                val (origin, size) = measureLayoutBox()
                store.show(
                    InertiaToolHandleTarget(
                        owner = instance,
                        tool = tool,
                        values = sample,
                        layoutOrigin = origin,
                        layoutSize = size,
                        canvasSize = canvasSize,
                        onBegin = beginHandleGesture,
                        onDrag = dragHandleGesture,
                        onEnd = endHandleGesture
                    )
                )
            } else if (store.target?.owner == instance) {
                // Only ever takes down its own: every actionable runs this, and
                // the ones that are not selected have no business clearing the
                // handles of the one that is.
                store.hide()
            }
        }
    }

    // Republished rather than left as it was: the geometry it carries is
    // measured, and a node that has been re-laid out, reselected or handed a new
    // schema is not where the last target says it is.
    LaunchedEffect(isEditable, tool, instanceId, canvasSize, animation?.initialValues, layoutSize) {
        publishHandles()
    }

    // Leaving composition takes the handles with it. Without this, an actionable
    // that goes away mid-selection leaves chrome behind with nothing under it.
    DisposableEffect(handles, instanceId) {
        onDispose {
            val instance = instanceId
            if (instance != null && handles?.target?.owner == instance) {
                handles.hide()
            }
        }
    }

    // When in actionable mode, handle both tap (for selection) and drag (for the
    // move tool). Every other tool is driven from the container's handle overlay
    // — see [InertiaToolHandlesOverlay] for why the knobs cannot live in here.
    val interactionModifier = if (model?.isActionable == true) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val downPosition = down.position
                var totalDrag = Offset.Zero
                var hasDragged = false

                // Where this node sits before the gesture moves it, taken once
                // here so the guides can be positioned — and the inspector
                // readout sized — without measuring anything per pointer event.
                // Unconditional: the size it takes is what
                // `MessageSelectedNodeProperties` reports, which happens whether
                // or not the guides are being drawn.
                val (layoutOrigin, layoutBox) = measureLayoutBox()

                // Where the node sat before this gesture began. `totalDrag` is
                // measured from this gesture's own down, so without carrying what
                // came before it every drag after the first snaps back to the
                // node's layout position.
                val startEdit = edit
                // The whole node is the handle for the move tool, and only for
                // it. A drag across the body of a node under any other tool does
                // nothing — the way a modal tool behaves in any other editor.
                val canDragBody = isSelected && currentTool == InertiaTool.translate

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

                            if (canDragBody) {
                                // Everything since the finger went down, not just
                                // the events since the threshold was crossed:
                                // adding up only the latter leaves the threshold
                                // subtracted from the drag, and the node trailing
                                // the pointer by it for the rest of the gesture.
                                applyEdit(startEdit.copy(translate = startEdit.translate + totalDrag))
                                if (showAlignmentGrid) {
                                    // The box the node is *drawn* in, rather than
                                    // the one it was laid out in: the schema's
                                    // own transform moves and scales it too, and
                                    // guides that ignored it boxed the node's
                                    // layout position instead of the node.
                                    val drawn = sample()
                                    guides?.show(
                                        center = drawn.drawnPoint(
                                            Offset(layoutBox.width / 2f, layoutBox.height / 2f),
                                            layoutOrigin,
                                            layoutBox,
                                            canvasSize
                                        ),
                                        size = Size(
                                            layoutBox.width * drawn.scale,
                                            layoutBox.height * drawn.scale
                                        )
                                    )
                                }
                                dragEvent.consume()
                            }
                        }
                    }
                } while (event.changes.any { it.pressed })

                // On release
                if (showAlignmentGrid) guides?.hide()

                if (hasDragged && canDragBody) {
                    commitEdit()
                } else if (!hasDragged) {
                    // It was a tap, toggle selection
                    val instance = instanceId
                    val m = currentModel
                    if (instance != null && m != null) {
                        val newActionableIds = m.actionableIds.toMutableSet()
                        val pair = ActionableIdPair(
                            hierarchyIdPrefix = hierarchyIdPrefix,
                            hierarchyId = instance
                        )
                        if (!newActionableIds.remove(pair)) {
                            newActionableIds.add(pair)
                        }

                        // Update UI immediately (like React does)
                        currentUpdateModel { prev ->
                            prev.copyMutable { actionableIds = newActionableIds }
                        }

                        // Send updated selection to WebSocket
                        WebSocketClient.shared.sendMessageActionables(MessageType.actionables,
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

    /// Everything a shape drawn behind this node needs in order to be picked and
    /// dragged, or null when nothing here is selectable.
    ///
    /// Not gated on this node's own selection: a shape is picked in the editor's
    /// hierarchy panel, and picking one does not pick the view it was authored
    /// behind.
    val shapeEditing = if (model?.isActionable == true && instanceId != null) {
        InertiaShapeEditing(
            isSelected = { shape ->
                currentModel?.actionableIds?.any { it.hierarchyId == shape.id } == true
            },
            tool = tool,
            outerValues = sample,
            outerLayoutBox = measureLayoutBox,
            handles = handles,
            owner = { shape -> "$instanceId--${shape.id}" },
            edit = { shape -> shapeEdits[shape.id] ?: InertiaToolEdit() },
            onChange = { shape, next -> shapeEdits[shape.id] = next },
            onEnded = commitShapeEdit
        )
    } else {
        null
    }

    Box(
        modifier = Modifier
            // Ahead of the animation layer and the drag offset, so these are the
            // coordinates of where layout put this node — which is what
            // `dragOffset` is added to. Taken after either of them, they report
            // the *drawn* position and the guides would chase themselves.
            .onGloballyPositioned { measurement.coordinates = it }
            // The size the shapes behind this node are measured against. Taken
            // here, ahead of the animation layer, for the same reason as the
            // measurement above: read through a rotation, a node's box is the
            // bounding box of the rotated view, which swells and shrinks as the
            // angle turns and would have the shapes pulse in step with the spin.
            .onSizeChanged { layoutSize = it }
            .then(modifierWithAnim)
            .then(modifierSelectedBorder(isEditable))
            .then(interactionModifier)
    ) {
        // First in the box, so it draws behind the content it backs. Inside the
        // animation layer and the drag offset above, so both carry the shapes
        // along with the node rather than leaving them behind.
        //
        // A shape with no animation of its own is backdrop: it belongs to the
        // actionable, moves only as the actionable moves, and shares one canvas
        // with every other shape like it. A shape that was given a track is a
        // drawing in its own right and gets a canvas of its own, so that track
        // can move it without disturbing the actionable or the other shapes.
        // The drawn ones come after, in the order they were authored — shapes
        // have no z-index of their own, and the file's order is the only
        // ordering anyone has expressed.
        //
        // Being selected puts a shape on a canvas of its own too: the border and
        // the handles are fitted to one shape's box, and a shape sharing a
        // canvas has no box of its own to fit them to. The same split the Swift
        // runtime makes in `isDrawnAlone`.
        if (shapes.isNotEmpty() && layoutSize != IntSize.Zero) {
            val isDrawnAlone = { shape: InertiaShape ->
                shape.animation != null || shapeEditing?.isSelected(shape) == true
            }

            val backdrop = shapes.filterNot(isDrawnAlone)
            if (backdrop.isNotEmpty()) {
                InertiaShapeCanvas(shapes = backdrop, actionableSize = layoutSize)
            }

            shapes.filter(isDrawnAlone).forEach { shape ->
                InertiaShapeCanvas(
                    shapes = listOf(shape),
                    actionableSize = layoutSize,
                    animation = shape.animation,
                    hierarchyIdPrefix = hierarchyIdPrefix,
                    containerSize = canvasSize,
                    editing = shapeEditing
                )
            }
        }

        CompositionLocalProvider(
            LocalInertiaParentId provides instanceId
        ) {
            content()
        }
    }
}

/// A stroke rather than a fill, and `border` rather than `background`, so it
/// lands over the content this node wraps: a background draws behind that
/// content and any opaque child hides it entirely. Matches the Swift runtime's
/// `.overlay { Rectangle().stroke(.green) }`.
@Composable
private fun modifierSelectedBorder(show: Boolean): Modifier =
    if (!show) Modifier
    else Modifier.border(2.dp, Color.Green)

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
