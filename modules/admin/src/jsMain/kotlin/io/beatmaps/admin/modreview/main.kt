package io.beatmaps.admin.modreview

import external.Axios
import external.CancelTokenSource
import external.axiosDelete
import external.generateConfig
import external.routeLink
import io.beatmaps.Config
import io.beatmaps.History
import io.beatmaps.admin.ModReviewProps
import io.beatmaps.api.ActionResponse
import io.beatmaps.api.CommentDetail
import io.beatmaps.api.DeleteReview
import io.beatmaps.api.GenericSearchResponse
import io.beatmaps.api.PutReview
import io.beatmaps.api.RepliesResponse
import io.beatmaps.api.ReplyRequest
import io.beatmaps.api.ReviewDetail
import io.beatmaps.api.ReviewReplyDetail
import io.beatmaps.api.ReviewsResponse
import io.beatmaps.common.api.ReviewSentiment
import io.beatmaps.globalContext
import io.beatmaps.setPageTitle
import io.beatmaps.shared.InfiniteScrollElementRenderer
import io.beatmaps.shared.ModalCallbacks
import io.beatmaps.shared.generateInfiniteScrollComponent
import io.beatmaps.shared.modal
import io.beatmaps.shared.modalContext
import io.beatmaps.shared.review.commentsInfiniteScroll
import io.beatmaps.util.useDidUpdateEffect
import kotlinx.dom.hasClass
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URLSearchParams
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.form
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.fc
import react.router.useLocation
import react.router.useNavigate
import react.useContext
import react.useEffect
import react.useEffectOnce
import react.useMemo
import react.useRef
import web.cssom.ClassName
import web.html.ButtonType
import web.html.InputType
import kotlin.js.Promise

val modReview = fc<ModReviewProps>("modReview") { props ->
    val userData = useContext(globalContext)
    val history = History(useNavigate())
    val location = useLocation()
    val pathName = useRef(location.pathname)

    val resultsTable = useRef<HTMLElement>()
    val modalRef = useRef<ModalCallbacks>()
    val userRef = useRef<HTMLInputElement>()

    val resetRef = useRef<() -> Unit>()
    val loadReviewPageRef = useRef<(Int, CancelTokenSource) -> Promise<GenericSearchResponse<ReviewDetail>?>>()
    val loadReplyPageRef = useRef<(Int, CancelTokenSource) -> Promise<GenericSearchResponse<ReviewReplyDetail>?>>()

    val userLocal = URLSearchParams(location.search).let { u ->
        u.get("user") ?: ""
    }

    useEffectOnce {
        setPageTitle("Review Moderation")

        if (userData?.curator != true) {
            history.push("/")
        }
    }

    useEffect(location) {
        userRef.current?.value = userLocal
    }

    useDidUpdateEffect(location) {
        resetRef.current?.invoke()
    }

    fun urlExtension(): String {
        val params = listOfNotNull(
            // Fallback to allow this to be called before first render
            (userRef.current?.value ?: userLocal).let { if (it.isNotBlank()) "user=$it" else null }
        )

        return if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
    }

    val reviewRenderer = useMemo {
        val setUserCb = { userStr: String ->
            userRef.current?.value = userStr
            history.push(pathName.current + urlExtension())
        }
        val deleteCb = { it: CommentDetail?, reason: String ->
            axiosDelete<DeleteReview, String>(
                "${Config.apibase}/review/single/${it?.map?.id}/${it?.creator?.id}",
                DeleteReview(reason)
            )
        }
        val saveCb = onSave@{ it: CommentDetail?, sentiment: ReviewSentiment?, text: String ->
            Axios.put<ActionResponse>(
                "${Config.apibase}/review/single/${it?.map?.id}/${it?.creator?.id}",
                PutReview(text, sentiment ?: return@onSave null),
                generateConfig<PutReview, ActionResponse>()
            )
        }

        InfiniteScrollElementRenderer<ReviewDetail> {
            modReviewEntry {
                attrs.entry = it
                attrs.setUser = setUserCb
                attrs.onDelete = deleteCb
                attrs.onSave = saveCb
            }
        }
    }

    val replyRenderer = useMemo {
        val setUserCb = { userStr: String ->
            userRef.current?.value = userStr
            history.push(pathName.current + urlExtension())
        }
        val deleteCb = { it: CommentDetail?, reason: String ->
            axiosDelete<DeleteReview, String>(
                "${Config.apibase}/reply/single/${it?.id}",
                DeleteReview(reason)
            )
        }
        val saveCb = { it: CommentDetail?, _: ReviewSentiment?, text: String ->
            Axios.put<ActionResponse>(
                "${Config.apibase}/reply/single/${it?.id}",
                ReplyRequest(text),
                generateConfig<ReplyRequest, ActionResponse>()
            )
        }

        InfiniteScrollElementRenderer<ReviewReplyDetail> {
            modReviewEntry {
                attrs.entry = it
                attrs.setUser = setUserCb
                attrs.onDelete = deleteCb
                attrs.onSave = saveCb
            }
        }
    }

    modal {
        attrs.callbacks = modalRef
    }

    modalContext.Provider {
        attrs.value = modalRef

        form {
            table {
                attrs.className = ClassName("table table-dark table-striped-3 modreview")
                thead {
                    tr {
                        th { +"User" }
                        th { +"Map" }
                        th { +"Sentiment" }
                        th { +"Time" }
                        th { +"" }
                    }
                    tr {
                        td {
                            input {
                                attrs.type = InputType.text
                                attrs.className = ClassName("form-control")
                                attrs.placeholder = "User"
                                attrs.ariaLabel = "User"
                                ref = userRef
                            }
                        }
                        td {
                            attrs.colSpan = 3
                            button {
                                attrs.type = ButtonType.submit
                                attrs.className = ClassName("btn btn-primary")
                                attrs.onClick = {
                                    it.preventDefault()

                                    history.push(location.pathname + urlExtension())
                                }

                                +"Filter"
                            }
                        }
                        td {
                            when (props.type) {
                                ReviewDetail::class -> {
                                    routeLink("/modreply" + urlExtension(), "btn btn-primary") {
                                        +"Replies"
                                    }
                                }
                                ReviewReplyDetail::class -> {
                                    routeLink("/modreview" + urlExtension(), "btn btn-primary") {
                                        +"Reviews"
                                    }
                                }
                            }
                        }
                    }
                }
                tbody {
                    ref = resultsTable
                    key = "modreviewTable"

                    when (props.type) {
                        ReviewDetail::class -> {
                            loadReviewPageRef.current = { toLoad: Int, token: CancelTokenSource ->
                                Axios.get<ReviewsResponse>(
                                    "${Config.apibase}/review/latest/$toLoad" + urlExtension(),
                                    generateConfig<String, ReviewsResponse>(token.token)
                                ).then {
                                    return@then GenericSearchResponse.from(it.data.docs)
                                }
                            }

                            commentsInfiniteScroll {
                                attrs.resetRef = resetRef
                                attrs.rowHeight = 95.5
                                attrs.itemsPerPage = 20
                                attrs.container = resultsTable
                                attrs.loadPage = loadReviewPageRef
                                attrs.childFilter = {
                                    !it.hasClass("hiddenRow")
                                }
                                attrs.renderElement = reviewRenderer
                            }
                        }
                        ReviewReplyDetail::class -> {
                            loadReplyPageRef.current = { toLoad: Int, token: CancelTokenSource ->
                                Axios.get<RepliesResponse>(
                                    "${Config.apibase}/reply/latest/$toLoad" + urlExtension(),
                                    generateConfig<String, RepliesResponse>(token.token)
                                ).then {
                                    return@then GenericSearchResponse.from(it.data.docs)
                                }
                            }

                            repliesInfiniteScroll {
                                attrs.resetRef = resetRef
                                attrs.rowHeight = 95.5
                                attrs.itemsPerPage = 20
                                attrs.container = resultsTable
                                attrs.loadPage = loadReplyPageRef
                                attrs.childFilter = {
                                    !it.hasClass("hiddenRow")
                                }
                                attrs.renderElement = replyRenderer
                            }
                        }
                    }
                }
            }
        }
    }
}

val repliesInfiniteScroll = generateInfiniteScrollComponent(ReviewReplyDetail::class)
