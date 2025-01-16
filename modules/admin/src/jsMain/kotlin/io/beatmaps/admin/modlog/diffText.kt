package io.beatmaps.admin.modlog

import io.beatmaps.util.fcmemo
import react.Props
import react.dom.i
import react.dom.p
import react.dom.span

external interface DiffTextProps : Props {
    var description: String
    var old: String
    var new: String
}

val diffText = fcmemo<DiffTextProps>("diffText") { props ->
    if (props.new != props.old) {
        p("card-text") {
            if (props.new.isNotEmpty()) {
                +"Updated ${props.description}"
                span("text-danger d-block") {
                    i("fas fa-minus") {}
                    +" ${props.old}"
                }
                span("text-success d-block") {
                    i("fas fa-plus") {}
                    +" ${props.new}"
                }
            } else {
                // Shows as empty if curator is changing tags
                +"Deleted ${props.description}"
            }
        }
    }
}
