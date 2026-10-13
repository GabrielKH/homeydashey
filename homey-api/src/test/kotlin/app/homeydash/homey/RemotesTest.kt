package app.homeydash.homey

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotesTest {
    private val cards = FlowCards.parseActions(
        HomeyJson.parseToJsonElement(
            """
            {
              "homey:device:sam:send_key": {"id": "homey:device:sam:send_key", "ownerUri": "homey:device:sam",
                 "args": [{"name": "device", "type": "device"}, {"name": "key", "type": "dropdown", "values": [
                   {"id": "KEY_UP", "label": "Up"}, {"id": "KEY_DOWN"}, {"id": "KEY_LEFT"}, {"id": "KEY_RIGHT"},
                   {"id": "KEY_ENTER"}, {"id": "KEY_RETURN"}, {"id": "KEY_HOME"}, {"id": "KEY_VOLUP"},
                   {"id": "KEY_CHDOWN"}, {"id": "KEY_PLAY"}, {"id": "KEY_PAUSE"}, {"id": "KEY_PAGE_UP"}]}]},
              "homey:device:sam:launch_app": {"id": "homey:device:sam:launch_app", "ownerUri": "homey:device:sam",
                 "args": [{"name": "app", "type": "autocomplete"}]},
              "homey:device:son:volume_up": {"id": "homey:device:son:volume_up", "ownerUri": "homey:device:son"},
              "homey:device:son:toggle_play_pause": {"id": "homey:device:son:toggle_play_pause", "ownerUri": "homey:device:son"}
            }
            """,
        ),
    )
    private val byDevice = FlowCards.byDevice(cards)
    private val samsung = Device("sam", "TV", deviceClass = "tv", capabilities = listOf("onoff", "volume_down", "volume_mute", "channel_up"))
    private val sonos = Device("son", "Kitchen", deviceClass = "speaker", capabilities = listOf("onoff", "volume_set", "speaker_playing"))

    @Test fun `key names from tv apps`() {
        assertEquals(RemoteKey.Up, Remotes.keyName("KEYCODE_DPAD_UP"))
        assertEquals(RemoteKey.VolumeUp, Remotes.keyName("KEY_VOLUP"))
        assertEquals(RemoteKey.VolumeDown, Remotes.keyName("Volume down"))
        assertEquals(RemoteKey.ChannelUp, Remotes.keyName("KEY_CHUP"))
        assertEquals(RemoteKey.Select, Remotes.keyName("OK/Enter"))
        assertNull(Remotes.keyName("KEY_PAGE_UP"))
        assertEquals(RemoteKey.ChannelUp, FlowCards.remoteKey("channel_up"))
    }

    @Test fun `samsung send key card makes a full remote`() {
        val tvCards = byDevice.getValue("sam")
        val keyCard = Remotes.keyCard(tvCards)!!
        assertEquals("send_key", keyCard.card.cardId)
        val items = Remotes.keyItems(FlowCards.dropdownItems(keyCard.arg))
        val actions = Remotes.actions(samsung, tvCards, keyCard, items)
        val up = actions[RemoteKey.Up] as RemoteAction.Card
        assertEquals(JsonObject(mapOf("key" to JsonPrimitive("KEY_UP"))), up.args)
        assertEquals(JsonObject(mapOf("key" to JsonPrimitive("KEY_PLAY"))), (actions[RemoteKey.PlayPause] as RemoteAction.Card).args)
        assertEquals(RemoteAction.Capability("volume_down", toggle = false), actions[RemoteKey.VolumeDown])
        assertEquals(RemoteAction.Capability("volume_mute", toggle = true), actions[RemoteKey.Mute])
        assertTrue(actions[RemoteKey.VolumeUp] is RemoteAction.Card)
        assertTrue(Remotes.hasRemote(samsung, tvCards))
    }

    @Test fun `tv with only capabilities still gets a remote, speakers do not`() {
        assertTrue(Remotes.hasRemote(samsung, emptyList()))
        assertFalse(Remotes.hasRemote(sonos, byDevice.getValue("son")))
    }
}

class AppleTvRemoteTest {
    private val cards = FlowCards.parseActions(
        HomeyJson.parseToJsonElement(
            """
            {
              "homey:device:atv:appletv_remote": {"id": "homey:device:atv:appletv_remote", "ownerUri": "homey:device:atv",
                 "args": [{"name": "device", "type": "device"}, {"name": "command", "type": "dropdown", "values": [
                   {"id": "up"}, {"id": "down"}, {"id": "left"}, {"id": "right"}, {"id": "select"}, {"id": "menu"},
                   {"id": "home"}, {"id": "playPause"}, {"id": "volumeUp"}, {"id": "volumeDown"}, {"id": "topMenu"}, {"id": "mute"}]}]},
              "homey:device:sam:volume_up": {"id": "homey:device:sam:volume_up", "ownerUri": "homey:device:sam"}
            }
            """,
        ),
    )
    private val byDevice = FlowCards.byDevice(cards)
    private val atv = Device("atv", "Apple TV", zone = "living", deviceClass = "settopbox", capabilities = listOf("onoff", "volume_up", "volume_down"))
    private val tv = Device("sam", "Samsung", zone = "living", deviceClass = "tv", capabilities = listOf("onoff", "volume_down", "volume_mute"))

    @Test fun `apple tv remote card gives every key, menu is back`() {
        val atvCards = byDevice.getValue("atv")
        val keyCard = Remotes.keyCard(atvCards)!!
        val actions = Remotes.actions(atv, atvCards, keyCard, Remotes.keyItems(FlowCards.dropdownItems(keyCard.arg)))
        assertEquals(JsonObject(mapOf("command" to JsonPrimitive("menu"))), (actions[RemoteKey.Back] as RemoteAction.Card).args)
        assertEquals(JsonObject(mapOf("command" to JsonPrimitive("volumeUp"))), (actions[RemoteKey.VolumeUp] as RemoteAction.Card).args)
        assertEquals(JsonObject(mapOf("command" to JsonPrimitive("playPause"))), (actions[RemoteKey.PlayPause] as RemoteAction.Card).args)
    }

    @Test fun `volume goes to the tv in the same room`() {
        val (target, keys) = Remotes.volumeTv(atv, listOf(tv to byDevice.getValue("sam")))!!
        assertEquals("sam", target.id)
        assertTrue(keys[RemoteKey.VolumeUp] is RemoteAction.Card)
        assertEquals(RemoteAction.Capability("volume_down", toggle = false), keys[RemoteKey.VolumeDown])
        assertNull(Remotes.volumeTv(atv, listOf(tv.copy(zone = "kitchen") to emptyList())))
    }

    @Test fun `streaming apps go first`() {
        val apps = listOf("com.apple.TVSettings" to "Settings", "se.svt.svtplay" to "SVT Play", "x" to "Arcade", "com.netflix.Netflix" to "Netflix", "y" to "Disney+")
        val ordered = TvApps.arrange(apps, { it.first }, { it.second }, null).map { it.second }
        assertEquals(listOf("Netflix", "Disney+", "SVT Play", "Arcade", "Settings"), ordered)
        assertEquals(listOf("SVT Play"), TvApps.arrange(apps, { it.first }, { it.second }, listOf("se.svt.svtplay", "gone")).map { it.second })
    }
}
