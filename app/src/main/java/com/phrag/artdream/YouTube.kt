package com.phrag.artdream

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** YouTube links: parsing what a person pastes, and the few URLs the app builds from an id. */
object YouTube {

    private val ID = Regex("[A-Za-z0-9_-]{11}")
    private val HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtube-nocookie.com", "www.youtube-nocookie.com",
    )

    /**
     * The video id in a watch, youtu.be, shorts, embed, live or /v/ link (with or without
     * "https://", with any extra parameters), or a bare 11-character id. Null for anything else,
     * including other hosts, playlists without a video, and channel pages.
     */
    fun parseVideoId(text: String): String? {
        val t = text.trim()
        if (ID.matches(t)) return t
        val uri = try {
            URI(if ("://" in t) t else "https://$t")
        } catch (_: Exception) {
            return null
        }
        if (uri.scheme != "https" && uri.scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null
        val segs = (uri.rawPath ?: "").trim('/').split('/').filter { it.isNotEmpty() }
        val candidate = when {
            host == "youtu.be" || host == "www.youtu.be" -> segs.firstOrNull()
            host in HOSTS -> when (segs.firstOrNull()) {
                "watch" -> queryParam(uri.rawQuery, "v")
                "shorts", "embed", "live", "v" -> segs.getOrNull(1)
                else -> null
            }
            else -> null
        }
        return candidate?.takeIf { ID.matches(it) }
    }

    private fun queryParam(query: String?, name: String): String? =
        query?.split('&')?.firstNotNullOfOrNull { kv ->
            kv.takeIf { it.substringBefore('=') == name }?.substringAfter('=', "")
        }

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    /**
     * Title and channel from YouTube's oEmbed (no API key). Blocking; call off the main thread.
     * Throws [HttpStatusException] with 401/403/404 when the video is gone, private or has
     * embedding switched off; other failures (no network, 5xx) are plain IOExceptions.
     */
    fun oEmbed(id: String): Link {
        val url = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(watchUrl(id), "UTF-8")
        val o = JSONObject(Http.getString(url))
        return Link(id, o.optString("title"), o.optString("author_name"))
    }

    /** What a ".yt" file in My media holds. */
    class Link(val id: String, val title: String, val channel: String) {
        fun toJson(): String = JSONObject().put("id", id).put("title", title).put("channel", channel).toString()
    }

    /** Parses a ".yt" file's text; null unless it holds a valid id. */
    fun readLink(text: String): Link? = try {
        val o = JSONObject(text)
        val id = o.optString("id")
        if (ID.matches(id)) Link(id, o.optString("title"), o.optString("channel")) else null
    } catch (_: Exception) {
        null
    }

    /** A file name for a link: "Title [id].yt" with the title cleaned like any uploaded name. */
    fun fileName(link: Link): String {
        val stem = OwnMedia.sanitizeName(link.title.ifBlank { "YouTube video" } + ".yt")
            ?.removeSuffix(".yt")?.take(60)?.trimEnd(' ', '_') ?: "YouTube video"
        return "$stem [${link.id}].yt"
    }

    /**
     * The page the slideshow loads in a WebView. It talks back through console messages
     * ("artdream:ready", "artdream:state:N", "artdream:error:N") so no script bridge is exposed
     * to the frames YouTube serves. Muted unless [sound]; autoplay needs muting anyway on most WebViews.
     */
    fun playerHtml(id: String, sound: Boolean): String = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body,#p{margin:0;width:100%;height:100%;background:#000;overflow:hidden}</style></head><body>
<div id="p"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player,jumped=false;
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{width:'100%',height:'100%',videoId:'$id',
    playerVars:{autoplay:1,mute:${if (sound) 0 else 1},controls:0,rel:0,modestbranding:1,playsinline:1,iv_load_policy:3,disablekb:1,fs:0},
    events:{
      onReady:function(e){${if (sound) "" else "e.target.mute();"}e.target.playVideo();console.log('artdream:ready');},
      onStateChange:function(e){console.log('artdream:state:'+e.data);
        if(e.data===1&&!jumped){jumped=true;var d=e.target.getDuration();
          // A slide shows a minute or so: start somewhere inside a long video, not always at 0:00.
          if(d>240){var t=Math.floor(Math.random()*(d-150));e.target.seekTo(t,true);console.log('artdream:start:'+t+'/'+Math.floor(d));}}
        if(e.data===0){e.target.seekTo(0);e.target.playVideo();}},
      onError:function(e){console.log('artdream:error:'+e.data);}
    }});
}
</script></body></html>"""

    /** Where the player page pretends to come from: YouTube refuses embeds that send no referrer. */
    const val PLAYER_BASE_URL = "https://github.com/phrag/ArtDream"
}
