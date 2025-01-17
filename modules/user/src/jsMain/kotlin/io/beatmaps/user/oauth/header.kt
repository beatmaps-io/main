package io.beatmaps.user.oauth

import external.axiosGet
import io.beatmaps.Config
import io.beatmaps.api.UserDetail
import io.beatmaps.common.json
import io.beatmaps.setPageTitle
import kotlinx.browser.window
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.b
import react.dom.html.ReactHTML.br
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.fc
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

external interface OauthHeaderProps : Props {
    var clientName: String
    var clientIcon: String?
    var callback: (Boolean) -> Unit
    var logoutLink: String?
}

val oauthHeader = fc<OauthHeaderProps>("oauthHeader") { props ->
    val (username, setUsername) = useState<String?>(null)
    val (avatar, setAvatar) = useState<String?>(null)

    useEffectOnce {
        setPageTitle("Login with BeatSaver")

        axiosGet<String>(
            "${Config.apibase}/users/me"
        ).then {
            // Decode is here so that 401 actually passes to error handler
            val data = json.decodeFromString<UserDetail>(it.data)

            setUsername(data.name)
            setAvatar(data.avatar)
            props.callback(true)
        }.catch {
            setUsername(null)
            setAvatar(null)
            props.callback(false)
        }
    }

    div {
        attrs.className = ClassName("card-header")
        props.clientIcon?.let {
            img {
                attrs.alt = "Icon"
                attrs.src = it
                attrs.className = ClassName("oauthicon")
                attrs.title = props.clientName
            }
        }
        b {
            +props.clientName
        }
        br {}
        +" wants to access your BeatSaver account"
        br {}
        username?.let {
            +"Hi, "
            b {
                +it
            }
            +" "
            img {
                attrs.src = avatar
                attrs.className = ClassName("authorize-avatar")
            }
            br {}

            a {
                attrs.href = props.logoutLink ?: ("/oauth2/authorize/not-me" + window.location.search)
                +"Not you?"
            }
        }
    }
}
