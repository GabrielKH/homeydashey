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
        strangersAreGuests = p[STRANGERS] == "true",
        wake = p[WAKE] == "true",
        wakeName = p[WAKE_NAME] ?: AgentSettings().wakeName,
        onlyKnownVoices = p[KNOWN_VOICES] == "true",
        followUp = enumOr(p[FOLLOW_UP], FollowUp.Short),
        micSensitivity = p[MIC_SENSITIVITY]?.toIntOrNull() ?: MicSensitivity.DEFAULT,
        seeHiddenDevices = p[SEE_HIDDEN] != "false",
        face = enumOr(p[FACE], FaceStyle.Orb),
        voice = enumOr(p[VOICE], VoiceStyle.Natural),
        classicRed = p[CLASSIC_RED] == "true",
        useWhisper = p[USE_WHISPER] == "true",
        whisperUrl = p[WHISPER_URL].orEmpty(),
        useOpenAiSpeech = p[USE_OPENAI_SPEECH] == "true",
        openAiSpeechKey = p[OPENAI_SPEECH_KEY]?.let(KeystoreCipher::decrypt).orEmpty(),
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
        p[STRANGERS] = s.strangersAreGuests.toString()
        p[WAKE] = s.wake.toString()
        p[WAKE_NAME] = s.wakeName
        p[KNOWN_VOICES] = s.onlyKnownVoices.toString()
        p[FOLLOW_UP] = s.followUp.name
        p[MIC_SENSITIVITY] = s.micSensitivity.toString()
        p[SEE_HIDDEN] = s.seeHiddenDevices.toString()
        p[FACE] = s.face.name
        p[VOICE] = s.voice.name
        p[CLASSIC_RED] = s.classicRed.toString()
        p[USE_WHISPER] = s.useWhisper.toString()
        p[WHISPER_URL] = s.whisperUrl.trim()
        p[USE_OPENAI_SPEECH] = s.useOpenAiSpeech.toString()
        p[OPENAI_SPEECH_KEY] = KeystoreCipher.encrypt(s.openAiSpeechKey.trim())
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
        val FOLLOW_UP = stringPreferencesKey("follow_up")
        val MIC_SENSITIVITY = stringPreferencesKey("mic_sensitivity")
        val SEE_HIDDEN = stringPreferencesKey("see_hidden_devices")
        val FACE = stringPreferencesKey("face")
        val VOICE = stringPreferencesKey("voice")
        val CLASSIC_RED = stringPreferencesKey("classic_red")
        val USE_WHISPER = stringPreferencesKey("use_whisper")
        val WHISPER_URL = stringPreferencesKey("whisper_url")
        val USE_OPENAI_SPEECH = stringPreferencesKey("use_openai_speech")
        val OPENAI_SPEECH_KEY = stringPreferencesKey("openai_speech_key")

        inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
    }
}
