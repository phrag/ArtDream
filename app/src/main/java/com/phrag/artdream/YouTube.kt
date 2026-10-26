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
     *
     * The player picks its quality from its size in CSS pixels, and a TV WebView has only about
     * 960x540 of those (density 2), so it chose 480p. The player div is laid out at the screen's
     * real pixel size and scaled back down to fit. The page can start before the WebView has been
     * laid out (the window measures 0x0, and a player made then stays at 480p), so the player is
     * only created once the window has a real size, falling back to the screen's size after 3 s;
     * it is resized on every window resize, asking for the highest quality again each time.
     * Captions are off: YouTube switches them on by itself for a muted video, so the captions
     * module is unloaded each time playback starts.
     */
    fun playerHtml(id: String, sound: Boolean): String = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}
#box{position:absolute;left:0;top:0;transform-origin:0 0}#p{width:100%;height:100%}</style></head><body>
<div id="box"><div id="p"></div></div>
<script>
var box=document.getElementById('box'),player,apiReady=false,jumped=false,logged=false,waited=0;
function best(p){try{p.setPlaybackQuality('highres');}catch(x){}}
function noCaptions(p){try{p.unloadModule('captions');p.unloadModule('cc');p.setOption('captions','track',{});}catch(x){}}
// Lays the box out at the window's size in real pixels; false while the window is still 0x0.
function fit(){
  var dpr=Math.max(1,window.devicePixelRatio||1),de=document.documentElement;
  var w=innerWidth||de.clientWidth,h=innerHeight||de.clientHeight;
  if((!w||!h)&&waited>=30){w=screen.width;h=screen.height;}
  if(!w||!h)return false;
  var W=Math.round(w*dpr),H=Math.round(h*dpr);
  if(box.style.width===W+'px'&&box.style.height===H+'px')return true;
  box.style.width=W+'px';box.style.height=H+'px';box.style.transform='scale('+(1/dpr)+')';
  console.log('artdream:size:'+W+'x'+H+'@'+dpr);
  if(player&&player.setSize){player.setSize(W,H);best(player);}
  return true;
}
function start(){if(player||!apiReady||!fit())return;
  player=new YT.Player('p',{width:'100%',height:'100%',videoId:'$id',
    playerVars:{autoplay:1,mute:${if (sound) 0 else 1},controls:0,rel:0,modestbranding:1,playsinline:1,iv_load_policy:3,disablekb:1,fs:0,cc_load_policy:0,vq:'highres'},
    events:{
      onReady:function(e){${if (sound) "" else "e.target.mute();"}noCaptions(e.target);best(e.target);
        e.target.playVideo();console.log('artdream:ready');},
      onPlaybackQualityChange:function(e){console.log('artdream:quality:'+e.data);},
      onStateChange:function(e){console.log('artdream:state:'+e.data);
        if(e.data===1){noCaptions(e.target);best(e.target);
          if(!logged){logged=true;try{console.log('artdream:levels:'+e.target.getAvailableQualityLevels().join(','));}catch(x){}}}
        if(e.data===1&&!jumped){jumped=true;var d=e.target.getDuration();
          // A slide shows a minute or so: start somewhere inside a long video, not always at 0:00.
          if(d>240){var t=Math.floor(Math.random()*(d-150));e.target.seekTo(t,true);console.log('artdream:start:'+t+'/'+Math.floor(d));}}
        if(e.data===0){e.target.seekTo(0);e.target.playVideo();}},
      onError:function(e){console.log('artdream:error:'+e.data);}
    }});
}
function onYouTubeIframeAPIReady(){apiReady=true;start();}
addEventListener('resize',function(){fit();start();});
// Layout doesn't always fire a resize, so also check every 100 ms until the player exists.
(function wait(){if(player)return;fit();start();waited++;if(!player)setTimeout(wait,100);})();
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body></html>"""

    /** Where the player page pretends to come from: YouTube refuses embeds that send no referrer. */
    const val PLAYER_BASE_URL = "https://github.com/phrag/ArtDream"
}
