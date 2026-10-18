package app.homeydash.assistant

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.homeydash.agent.Access
import app.homeydash.agent.AgentSettings
import app.homeydash.agent.BrainMode
import app.homeydash.agent.Connection
import app.homeydash.agent.FaceStyle
import app.homeydash.agent.FollowUp
import app.homeydash.agent.MicSensitivity
import app.homeydash.agent.VoiceStyle
import app.homeydash.agent.Language
import app.homeydash.agent.Provider
import app.homeydash.agent.Secret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.assistantStore by preferencesDataStore(name = "assistant")

/**
 * The assistant's settings. AI keys are stored encrypted with [KeystoreCipher].
 */
class AssistantSettingsStore(private val context: Context, private val scope: CoroutineScope) {

    val settings: StateFlow<AgentSettings> = context.assistantStore.data
        .map(::read)
        .stateIn(scope, SharingStarted.Eagerly, AgentSettings())

    fun update(change: (AgentSettings) -> AgentSettings) {
        scope.launch {
            context.assistantStore.edit { prefs -> write(prefs, change(read(prefs))) }
        }
    }

    private fun read(p: Preferences) = AgentSettings(
        enabled = p[ENABLED] != "false",
        local = connection(p, "local"),
        internet = connection(p, "internet"),
        mode = enumOr(p[MODE], BrainMode.Smart),
        language = enumOr(p[LANGUAGE], Language.Auto),
        secrets = Secret.entries.associateWith { enumOr(p[stringPreferencesKey("secret_${it.name}")], Access.Off) },
        hiddenDevices = p[HIDDEN]?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty(),
        speakerId = p[SPEAKER]?.takeIf { it.isNotBlank() },
        speakerVolume = p[VOLUME]?.toIntOrNull() ?: 35,
        speak = p[SPEAK] != "false",
        liveSpeech = p[LIVE_SPEECH] != "false",
        // Before the levels, talking over was a plain switch.
        interrupt = enumOr(p[INTERRUPT], if (p[TALK_OVER] == "false") app.homeydash.agent.Interrupt.Off else app.homeydash.agent.Interrupt.Clear),
        strangersAreGuests = p[STRANGERS] == "true",
        wake = p[WAKE] == "true",
        wakeName = p[WAKE_NAME] ?: AgentSettings().wakeName,
        onlyKnownVoices = p[KNOWN_VOICES] == "true",
        followUp = enumOr(p[FOLLOW_UP], FollowUp.Short),
        micSensitivity = p[MIC_SENSITIVITY]?.toIntOrNull() ?: MicSensitivity.DEFAULT,
        seeHiddenDevices = p[SEE_HIDDEN] != "false",
        face = enumOr(p[FACE], FaceStyle.Orb),
        view = enumOr(p[VIEW], app.homeydash.agent.AssistantView.Full),
        voice = enumOr(p[VOICE], VoiceStyle.Natural),
        wakeSound = enumOr(p[WAKE_SOUND], app.homeydash.agent.WakeSound.Hum),
        persona = enumOr(p[PERSONA], app.homeydash.agent.Persona.Nova),
        humor = p[HUMOR]?.toIntOrNull()?.coerceIn(0, 100) ?: 75,
        pace = p[PACE]?.toIntOrNull()?.coerceIn(app.homeydash.agent.PACE_RANGE) ?: app.homeydash.agent.DEFAULT_PACE,
        novaName = p[NOVA_NAME] ?: AgentSettings().novaName,
        classicRed = p[CLASSIC_RED] == "true",
        batteryReminders = p[BATTERY_REMINDERS] != "false",
        useWhisper = p[USE_WHISPER] == "true",
        whisperUrl = p[WHISPER_URL].orEmpty(),
        voiceServerUrl = p[VOICE_SERVER_URL].orEmpty(),
        voiceServerModel = p[VOICE_SERVER_MODEL]?.takeIf { it.isNotBlank() } ?: app.homeydash.agent.DEFAULT_VOICE_MODEL,
        voiceServerVoice = p[VOICE_SERVER_VOICE].orEmpty(),
        useOpenAiSpeech = p[USE_OPENAI_SPEECH] == "true",
        openAiSpeechKey = p[OPENAI_SPEECH_KEY]?.let(KeystoreCipher::decrypt).orEmpty(),
        sendLogs = p[SEND_LOGS] == "true",
        compactTools = p[COMPACT_TOOLS] != "false",
        logToken = p[LOG_TOKEN]?.let(KeystoreCipher::decrypt).orEmpty(),
        logRepo = p[LOG_REPO]?.takeIf { it.isNotBlank() } ?: AgentSettings().logRepo,
        spotifyClientId = p[SPOTIFY_ID].orEmpty(),
        spotifySecret = p[SPOTIFY_SECRET]?.let(KeystoreCipher::decrypt).orEmpty(),
    )

    private fun write(p: MutablePreferences, s: AgentSettings) {
        p[ENABLED] = s.enabled.toString()
        putConnection(p, "local", s.local)
        putConnection(p, "internet", s.internet)
        p[MODE] = s.mode.name
        p[LANGUAGE] = s.language.name
        s.secrets.forEach { (secret, access) -> p[stringPreferencesKey("secret_${secret.name}")] = access.name }
        p[HIDDEN] = s.hiddenDevices.joinToString("\n")
        p[SPEAKER] = s.speakerId.orEmpty()
        p[VOLUME] = s.speakerVolume.toString()
        p[SPEAK] = s.speak.toString()
        p[LIVE_SPEECH] = s.liveSpeech.toString()
        p[INTERRUPT] = s.interrupt.name
        p[STRANGERS] = s.strangersAreGuests.toString()
        p[WAKE] = s.wake.toString()
        p[WAKE_NAME] = s.wakeName
        p[KNOWN_VOICES] = s.onlyKnownVoices.toString()
        p[FOLLOW_UP] = s.followUp.name
        p[MIC_SENSITIVITY] = s.micSensitivity.toString()
        p[SEE_HIDDEN] = s.seeHiddenDevices.toString()
        p[FACE] = s.face.name
        p[VIEW] = s.view.name
        p[VOICE] = s.voice.name
        p[WAKE_SOUND] = s.wakeSound.name
        p[PERSONA] = s.persona.name
        p[HUMOR] = s.humor.toString()
        p[PACE] = s.pace.toString()
        p[NOVA_NAME] = s.novaName
        p[CLASSIC_RED] = s.classicRed.toString()
        p[BATTERY_REMINDERS] = s.batteryReminders.toString()
        p[USE_WHISPER] = s.useWhisper.toString()
        p[WHISPER_URL] = s.whisperUrl.trim()
        p[VOICE_SERVER_URL] = s.voiceServerUrl.trim()
        p[VOICE_SERVER_MODEL] = s.voiceServerModel.trim()
        p[VOICE_SERVER_VOICE] = s.voiceServerVoice.trim()
        p[USE_OPENAI_SPEECH] = s.useOpenAiSpeech.toString()
        p[OPENAI_SPEECH_KEY] = KeystoreCipher.encrypt(s.openAiSpeechKey.trim())
        p[SEND_LOGS] = s.sendLogs.toString()
        p[COMPACT_TOOLS] = s.compactTools.toString()
        p[LOG_TOKEN] = KeystoreCipher.encrypt(s.logToken.trim())
        p[LOG_REPO] = s.logRepo.trim()
        p[SPOTIFY_ID] = s.spotifyClientId.trim()
        p[SPOTIFY_SECRET] = KeystoreCipher.encrypt(s.spotifySecret.trim())
    }

    private fun connection(p: Preferences, slot: String): Connection? {
        val provider = p[stringPreferencesKey("${slot}_provider")]?.let { name -> Provider.entries.firstOrNull { it.name == name } } ?: return null
        return Connection(
            provider = provider,
            baseUrl = p[stringPreferencesKey("${slot}_url")] ?: provider.defaultUrl,
            apiKey = p[stringPreferencesKey("${slot}_key")]?.let(KeystoreCipher::decrypt).orEmpty(),
            model = p[stringPreferencesKey("${slot}_model")] ?: provider.defaultModel,
        )
    }

    private fun putConnection(p: MutablePreferences, slot: String, c: Connection?) {
        if (c == null) {
            listOf("provider", "url", "key", "model").forEach { p.remove(stringPreferencesKey("${slot}_$it")) }
            return
        }
        p[stringPreferencesKey("${slot}_provider")] = c.provider.name
        p[stringPreferencesKey("${slot}_url")] = c.baseUrl.trim()
        p[stringPreferencesKey("${slot}_key")] = KeystoreCipher.encrypt(c.apiKey.trim())
        p[stringPreferencesKey("${slot}_model")] = c.model.trim()
    }

    private companion object {
        val ENABLED = stringPreferencesKey("enabled")
        val MODE = stringPreferencesKey("mode")
        val LANGUAGE = stringPreferencesKey("language")
        val HIDDEN = stringPreferencesKey("hidden")
        val SPEAKER = stringPreferencesKey("speaker")
        val VOLUME = stringPreferencesKey("volume")
        val SPEAK = stringPreferencesKey("speak")
        val STRANGERS = stringPreferencesKey("strangers")
        val WAKE = stringPreferencesKey("wake")
        val WAKE_NAME = stringPreferencesKey("wake_name")
        val KNOWN_VOICES = stringPreferencesKey("known_voices")
        val LIVE_SPEECH = stringPreferencesKey("live_speech")
        val TALK_OVER = stringPreferencesKey("talk_over")
        val INTERRUPT = stringPreferencesKey("interrupt")
        val FOLLOW_UP = stringPreferencesKey("follow_up")
        val MIC_SENSITIVITY = stringPreferencesKey("mic_sensitivity")
        val SEE_HIDDEN = stringPreferencesKey("see_hidden_devices")
        val FACE = stringPreferencesKey("face")
        val VIEW = stringPreferencesKey("assistant_view")
        val VOICE = stringPreferencesKey("voice")
        val WAKE_SOUND = stringPreferencesKey("wake_sound")
        val PERSONA = stringPreferencesKey("persona")
        val HUMOR = stringPreferencesKey("humor")
        val PACE = stringPreferencesKey("pace")
        val NOVA_NAME = stringPreferencesKey("nova_name")
        val CLASSIC_RED = stringPreferencesKey("classic_red")
        val BATTERY_REMINDERS = stringPreferencesKey("battery_reminders")
        val USE_WHISPER = stringPreferencesKey("use_whisper")
        val WHISPER_URL = stringPreferencesKey("whisper_url")
        val VOICE_SERVER_URL = stringPreferencesKey("voice_server_url")
        val VOICE_SERVER_MODEL = stringPreferencesKey("voice_server_model")
        val VOICE_SERVER_VOICE = stringPreferencesKey("voice_server_voice")
        val USE_OPENAI_SPEECH = stringPreferencesKey("use_openai_speech")
        val OPENAI_SPEECH_KEY = stringPreferencesKey("openai_speech_key")
        val SPOTIFY_ID = stringPreferencesKey("spotify_client_id")
        val SPOTIFY_SECRET = stringPreferencesKey("spotify_secret")
        val SEND_LOGS = stringPreferencesKey("send_logs")
        val COMPACT_TOOLS = stringPreferencesKey("compact_tools")
        val LOG_TOKEN = stringPreferencesKey("log_token")
        val LOG_REPO = stringPreferencesKey("log_repo")

        inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
    }
}
