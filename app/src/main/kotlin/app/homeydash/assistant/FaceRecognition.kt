package app.homeydash.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.homeydash.agent.FaceMatcher
import app.homeydash.agent.KnownPerson
import app.homeydash.agent.Role
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One face found in a camera frame: its fingerprint and the aligned 112×112 crop it came from. */
class FaceSample(val embedding: FloatArray, val crop: Bitmap)

/**
 * Face recognition that runs entirely on the tablet: Google ML Kit finds the face and its
 * eyes, and MobileFaceNet (a 5 MB TensorFlow Lite model in the app) turns the aligned face
 * into 192 numbers. No picture is stored or sent anywhere.
 */
class FaceEngine(private val context: Context) {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.12f)
            .build(),
    )
    private val interpreter: Interpreter by lazy { Interpreter(model(), Interpreter.Options().setNumThreads(2)) }

    private fun model(): ByteBuffer = context.assets.openFd(MODEL).use { fd ->
        FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    /** The biggest face in [frame], or null. Call off the main thread. */
    fun sample(frame: Bitmap): FaceSample? {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)))
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return null
        val crop = align(frame, face)
        return FaceSample(embed(crop), crop)
    }

    /** Rotates and scales the face so the eyes are level and in the same place every time. */
    private fun align(frame: Bitmap, face: Face): Bitmap {
        val a: PointF? = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val b: PointF? = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val m = Matrix()
        if (a != null && b != null) {
            val (l, r) = if (a.x <= b.x) a to b else b to a
            val dx = r.x - l.x
            val dy = r.y - l.y
            val theta = atan2(dy, dx)
            val size = hypot(dx, dy) * 2.5f
            val offset = size * 0.15f
            val cx = (l.x + r.x) / 2 - offset * sin(theta)
            val cy = (l.y + r.y) / 2 + offset * cos(theta)
            m.postTranslate(-cx, -cy)
            m.postRotate(-Math.toDegrees(theta.toDouble()).toFloat())
            m.postScale(SIDE / size, SIDE / size)
        } else {
            val box = face.boundingBox
            val size = maxOf(box.width(), box.height()).toFloat()
            m.postTranslate(-box.exactCenterX(), -box.exactCenterY())
            m.postScale(SIDE / size, SIDE / size)
        }
        m.postTranslate(SIDE / 2f, SIDE / 2f)
        val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(frame, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun embed(crop: Bitmap): FloatArray {
        val shape = interpreter.getInputTensor(0).shape()
        val batch = shape.getOrElse(0) { 1 }.coerceAtLeast(1)
        val input = ByteBuffer.allocateDirect(batch * SIDE * SIDE * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(SIDE * SIDE)
        crop.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
        // MobileFaceNet takes RGB scaled to -1..1; other batch slots stay empty.
        for (p in pixels) {
            input.putFloat(((p shr 16 and 0xFF) - 127.5f) / 127.5f)
            input.putFloat(((p shr 8 and 0xFF) - 127.5f) / 127.5f)
            input.putFloat(((p and 0xFF) - 127.5f) / 127.5f)
        }
        input.rewind()
        val dim = interpreter.getOutputTensor(0).shape().last()
        val output = Array(batch) { FloatArray(dim) }
        interpreter.run(input, output)
        return FaceMatcher.normalize(output[0])
    }

    fun close() {
        detector.close()
        runCatching { interpreter.close() }
    }

    companion object {
        const val MODEL = "mobilefacenet.tflite"
        const val SIDE = 112
    }
}

/** Who the camera saw last: a known person, or a stranger (person = null). */
data class Seen(val person: KnownPerson?, val at: Long)

/**
 * Keeps the enrolled people (encrypted on the tablet) and, while the dashboard is on screen,
 * watches the front camera a couple of times a second to know who is standing there.
 */
class FaceService(private val context: Context) {
    private val file = File(context.filesDir, "faces.enc")
    private val engine by lazy { FaceEngine(context) }
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var lastFrame = 0L
    /** Set while adding someone: frames go here instead of to recognition. */
    @Volatile private var enrolling: ((FaceSample?) -> Unit)? = null

    private val _people = MutableStateFlow(load())
    val people: StateFlow<List<KnownPerson>> = _people.asStateFlow()

    private val _seen = MutableStateFlow<Seen?>(null)
    val seen: StateFlow<Seen?> = _seen.asStateFlow()

    /** Called on the main thread whenever a face is in front of the tablet. */
    var onFace: (() -> Unit)? = null

    val running: Boolean get() = analysis != null

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Who is at the tablet now: someone seen in the last [withinMs]. */
    fun current(withinMs: Long = 20_000): Seen? = _seen.value?.takeIf { SystemClock.elapsedRealtime() - it.at < withinMs }

    fun start(owner: LifecycleOwner) {
        if (analysis != null || !hasPermission()) return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            val a = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            a.setAnalyzer(executor, ::analyze)
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, a)
                provider = p
                analysis = a
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        runCatching { provider?.unbindAll() }
        analysis = null
    }

    private fun analyze(image: ImageProxy) {
        image.use { proxy ->
            val now = SystemClock.elapsedRealtime()
            // Twice a second while nobody is there, so a face is noticed at once; once someone
            // has been recognised, every 1.5 s is enough to know they're still there.
            val known = _seen.value?.let { it.person != null && now - it.at < RECOGNISED_FOR_MS } == true
            val interval = when {
                enrolling != null -> 250L
                known -> 1_500L
                else -> 500L
            }
            if (now - lastFrame < interval) return
            lastFrame = now
            val raw = runCatching { proxy.toBitmap() }.getOrNull() ?: return
            val frame = runCatching { rotate(raw, proxy.imageInfo.rotationDegrees) }.getOrNull()
            if (frame !== raw) raw.recycle()
            frame ?: return
            val sample = try {
                runCatching { engine.sample(frame) }.getOrNull()
            } finally {
                // Each frame is over a megabyte; freeing it at once spares the tablet garbage-collection pauses.
                frame.recycle()
            }
            enrolling?.let { it(sample); return }
            if (sample == null) return
            val person = FaceMatcher.match(sample.embedding, _people.value.filter { it.samples.isNotEmpty() })
            _seen.value = Seen(person, now)
            ContextCompat.getMainExecutor(context).execute { onFace?.invoke() }
        }
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap =
        if (degrees == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    /** Sends every analysed frame to [onSample] (on a background thread) until [endEnrolment]. */
    fun beginEnrolment(onSample: (FaceSample?) -> Unit) {
        enrolling = onSample
    }

    fun endEnrolment() {
        enrolling = null
    }

    /** Adds someone and returns their id. [samples] may be empty for a voice-only person. */
    fun add(name: String, role: Role, samples: List<FloatArray>): String {
        val id = UUID.randomUUID().toString()
        _people.update { it + KnownPerson(id, name.trim(), role, samples) }
        save()
        return id
    }

    /** Replaces what the tablet knows of someone's voice. */
    fun setVoice(id: String, voice: List<FloatArray>) {
        _people.update { list -> list.map { if (it.id == id) it.copy(voice = voice) else it } }
        save()
    }

    /** Adds voice fingerprints to someone's, from teaching or from a wake that clearly was them. */
    fun addVoice(id: String, more: List<FloatArray>) {
        _people.update { list -> list.map { if (it.id == id) it.copy(voice = FaceMatcher.withVoice(it.voice, more)) else it } }
        save()
    }

    /** Adds voiceprints (see [app.homeydash.agent.VoiceId]) said with [wakeName] to someone's. */
    fun addPrints(id: String, wakeName: String, more: List<FloatArray>) {
        if (more.isEmpty()) return
        _people.update { list -> list.map { if (it.id == id) it.withPrints(wakeName, more) else it } }
        save()
    }

    /** Adds ways of saying [wakeName] (see [app.homeydash.agent.SaidName]) to someone's, from teaching their voice. */
    fun addSaid(id: String, wakeName: String, more: List<app.homeydash.agent.SaidName.Shape>) {
        if (more.isEmpty()) return
        _people.update { list -> list.map { if (it.id == id) it.withSaid(wakeName, more) else it } }
        save()
    }

    /** How sure the wake judge must be before waking for someone when they say [wakeName], 0–100. */
    fun setStrictness(id: String, wakeName: String, value: Int) {
        val key = app.homeydash.agent.WakeName.key(wakeName)
        _people.update { list -> list.map { if (it.id == id) it.copy(strictness = it.strictness + (key to value.coerceIn(0, 100))) else it } }
        save()
    }

    /** The character and voice someone talks with; null for the house's. */
    fun setCharacters(id: String, characters: List<app.homeydash.agent.Persona>, voices: Map<app.homeydash.agent.Persona, app.homeydash.agent.VoiceStyle>) {
        _people.update { list -> list.map { if (it.id == id) it.copy(characters = characters, voices = voices) else it } }
        save()
    }

    /** The Warm voice recording someone's [character] speaks in with them; blank for the house's. */
    fun setWarmVoice(id: String, character: app.homeydash.agent.Persona, name: String) {
        _people.update { list ->
            list.map {
                if (it.id != id) it
                else it.copy(warmVoices = if (name.isBlank()) it.warmVoices - character else it.warmVoices + (character to name.trim()))
            }
        }
        save()
    }

    /** The email of someone's own account on the tablet, whose calendar is theirs; blank for none. */
    fun setCalendar(id: String, email: String) {
        _people.update { list -> list.map { if (it.id == id) it.copy(calendar = email.trim()) else it } }
        save()
    }

    fun setRole(id: String, role: Role) {
        _people.update { list -> list.map { if (it.id == id) it.copy(role = role) else it } }
        save()
    }

    fun remove(id: String) {
        _people.update { list -> list.filterNot { it.id == id } }
        save()
    }

    private fun save() {
        val json = buildJsonArray {
            _people.value.forEach { p ->
                add(buildJsonObject {
                    put("id", p.id)
                    put("name", p.name)
                    put("role", p.role.name)
                    put("samples", vectors(p.samples))
                    put("voice", vectors(p.voice))
                    put("prints", buildJsonObject { p.prints.forEach { (wake, list) -> put(wake, vectors(list)) } })
                    put("printsBy", PRINTS_BY)
                    put("characters", buildJsonArray { p.characters.forEach { add(JsonPrimitive(it.name)) } })
                    put("voices", buildJsonObject { p.voices.forEach { (c, v) -> put(c.name, v.name) } })
                    put("warmVoices", buildJsonObject { p.warmVoices.forEach { (c, v) -> put(c.name, v) } })
                    put("saidNames", buildJsonObject { p.saidNames.forEach { (wake, list) -> put(wake, buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }) } })
                    put("strictness", buildJsonObject { p.strictness.forEach { (wake, v) -> put(wake, v) } })
                    put("calendar", p.calendar)
                })
            }
        }
        file.writeText(KeystoreCipher.encrypt(json.toString()))
    }

    private fun vectors(list: List<FloatArray>) = buildJsonArray { list.forEach { v -> add(JsonArray(v.map { JsonPrimitive(it) })) } }

    private fun vectors(element: kotlinx.serialization.json.JsonElement?): List<FloatArray> =
        (element as? JsonArray)?.map { s -> s.jsonArray.map { it.jsonPrimitive.float }.toFloatArray() }.orEmpty()

    private fun persona(name: String?) = app.homeydash.agent.Persona.entries.firstOrNull { it.name == name }

    // One "character" and "voiceStyle" was saved before several characters could be picked.
    private fun characters(o: JsonObject): List<app.homeydash.agent.Persona> =
        (o["characters"] as? JsonArray)?.mapNotNull { persona(it.jsonPrimitive.contentOrNull) }
            ?: listOfNotNull(persona(o["character"]?.jsonPrimitive?.contentOrNull))

    private fun voices(o: JsonObject): Map<app.homeydash.agent.Persona, app.homeydash.agent.VoiceStyle> {
        fun style(name: String?) = app.homeydash.agent.VoiceStyle.entries.firstOrNull { it.name == name }
        (o["voices"] as? JsonObject)?.let { m ->
            return m.mapNotNull { (c, v) -> persona(c)?.let { p -> style(v.jsonPrimitive.contentOrNull)?.let { p to it } } }.toMap()
        }
        val old = persona(o["character"]?.jsonPrimitive?.contentOrNull) ?: return emptyMap()
        return style(o["voiceStyle"]?.jsonPrimitive?.contentOrNull)?.let { mapOf(old to it) }.orEmpty()
    }

    private fun load(): List<KnownPerson> {
        if (!file.exists()) return emptyList()
        val text = KeystoreCipher.decrypt(file.readText()) ?: return emptyList()
        val array = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            KnownPerson(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                role = Role.entries.firstOrNull { it.name == o["role"]?.jsonPrimitive?.contentOrNull } ?: Role.Family,
                samples = vectors(o["samples"]),
                voice = vectors(o["voice"]),
                // Voiceprints made before they came from sherpa-onnx don't compare with today's; they are
                // dropped, and the person is shown as not taught for that name until taught again.
                characters = characters(o),
                voices = voices(o),
                warmVoices = (o["warmVoices"] as? JsonObject)?.mapNotNull { (c, v) -> persona(c)?.let { p -> v.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let { p to it } } }?.toMap().orEmpty(),
                saidNames = (o["saidNames"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty() }.orEmpty(),
                strictness = (o["strictness"] as? JsonObject)?.mapNotNull { (k, v) -> v.jsonPrimitive.intOrNull?.let { k to it } }?.toMap().orEmpty(),
                calendar = o["calendar"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                prints = if (o["printsBy"]?.jsonPrimitive?.contentOrNull == PRINTS_BY) (o["prints"] as? JsonObject)?.mapValues { vectors(it.value) }.orEmpty() else emptyMap(),
            )
        }
    }
}

/** Which model made the stored voiceprints; prints from another can't be compared. */
private const val PRINTS_BY = "sherpa-wespeaker-resnet34"

/** How long after recognising someone face recognition looks less often. */
private const val RECOGNISED_FOR_MS = 10_000L
