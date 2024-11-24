package io.beatmaps.api.playlist

import de.nielsfalk.ktor.swagger.ok
import de.nielsfalk.ktor.swagger.responds
import io.beatmaps.api.LatestPlaylistSort
import io.beatmaps.api.OauthScope
import io.beatmaps.api.PlaylistApi
import io.beatmaps.api.PlaylistBasic
import io.beatmaps.api.PlaylistFull
import io.beatmaps.api.PlaylistSearchResponse
import io.beatmaps.api.from
import io.beatmaps.api.limit
import io.beatmaps.api.notNull
import io.beatmaps.api.search.BsSolr
import io.beatmaps.api.search.SolrSearchParams
import io.beatmaps.api.solr.SolrFilter
import io.beatmaps.api.solr.apply
import io.beatmaps.api.solr.eq
import io.beatmaps.api.solr.getIds
import io.beatmaps.api.solr.paged
import io.beatmaps.api.util.getWithOptions
import io.beatmaps.common.api.EPlaylistType
import io.beatmaps.common.dbo.Playlist
import io.beatmaps.common.dbo.User
import io.beatmaps.common.dbo.curatorAlias
import io.beatmaps.common.dbo.handleCurator
import io.beatmaps.common.dbo.handleOwner
import io.beatmaps.common.dbo.joinOwner
import io.beatmaps.common.dbo.joinPlaylistCurator
import io.beatmaps.util.cdnPrefix
import io.beatmaps.util.optionalAuthorization
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.datetime.toJavaInstant
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.playlistSearch() {
    getWithOptions<PlaylistApi.ByUploadDate>("Get playlists ordered by created/updated".responds(ok<PlaylistSearchResponse>())) {
        optionalAuthorization(OauthScope.PLAYLISTS) { _, sess ->
            val sortField = when (it.sort) {
                null, LatestPlaylistSort.CREATED -> Playlist.createdAt
                LatestPlaylistSort.SONGS_UPDATED -> Playlist.songsChangedAt
                LatestPlaylistSort.UPDATED -> Playlist.updatedAt
                LatestPlaylistSort.CURATED -> Playlist.curatedAt
            }

            val pageSize = (it.pageSize ?: 20).coerceIn(1, 100)
            val playlists = transaction {
                Playlist
                    .joinMaps()
                    .joinPlaylistCurator()
                    .joinOwner()
                    .select(Playlist.columns + User.columns + curatorAlias.columns + Playlist.Stats.all)
                    .where {
                        Playlist.id.inSubQuery(
                            Playlist
                                .select(Playlist.id)
                                .where {
                                    (Playlist.deletedAt.isNull() and (sess?.let { s -> Playlist.owner eq s.userId or (Playlist.type eq EPlaylistType.Public) } ?: (Playlist.type eq EPlaylistType.Public)))
                                        .notNull(it.before) { o -> sortField less o.toJavaInstant() }
                                        .notNull(it.after) { o -> sortField greater o.toJavaInstant() }
                                        .let { q ->
                                            if (it.sort == LatestPlaylistSort.CURATED) q.and(Playlist.curatedAt.isNotNull()) else q
                                        }
                                }
                                .orderBy(sortField to (if (it.after != null) SortOrder.ASC else SortOrder.DESC))
                                .limit(pageSize)
                        )
                    }
                    .groupBy(Playlist.id, User.id, curatorAlias[User.id])
                    .handleOwner()
                    .handleCurator()
                    .sortedByDescending { row -> row[sortField] }
                    .map { playlist ->
                        PlaylistFull.from(playlist, cdnPrefix())
                    }
            }

            call.respond(PlaylistSearchResponse(playlists))
        }
    }

    getWithOptions<PlaylistApi.Text>("Search for playlists".responds(ok<PlaylistSearchResponse>())) { req ->
        val searchInfo = SolrSearchParams.parseSearchQuery(req.q)
        val actualSortOrder = searchInfo.validateSearchOrder(req.sortOrder)

        newSuspendedTransaction {
            val results = PlaylistSolr.newQuery()
                .let { q ->
                    searchInfo.applyQuery(q)
                }
                .also { q ->
                    EPlaylistType.entries
                        .filter { v -> v.anonymousAllowed }
                        .map { PlaylistSolr.type eq it.name }
                        .reduce<SolrFilter, SolrFilter> { acc, f -> acc or f }
                        .let { q.apply(it) }
                }
                .also { q ->
                    if (req.includeEmpty != true) {
                        q.apply((PlaylistSolr.totalMaps greater 0) or (PlaylistSolr.type eq EPlaylistType.Search.name))
                    }
                }
                .also { q ->
                    val mapperIds = searchInfo.userSubQuery?.map { it[User.id].value } ?: listOf()

                    mapperIds.map { id ->
                        BsSolr.mapperIds eq id
                    }.reduceOrNull<SolrFilter, SolrFilter> { a, b -> a or b }?.let {
                        q.apply(it)
                    }
                }
                .notNull(req.minNps) { o -> PlaylistSolr.maxNps greaterEq o }
                .notNull(req.maxNps) { o -> PlaylistSolr.minNps lessEq o }
                .notNull(req.from) { o -> PlaylistSolr.created greaterEq o }
                .notNull(req.to) { o -> PlaylistSolr.created lessEq o }
                .notNull(req.curated) { o -> PlaylistSolr.curated.any().let { if (o) it else it.not() } }
                .notNull(req.verified) { o -> PlaylistSolr.verified eq o }
                .let { q ->
                    PlaylistSolr.addSortArgs(q, req.seed.hashCode(), actualSortOrder)
                }
                .setFields("id")
                .paged(req.page.toInt())
                .getIds(PlaylistSolr)

            val playlists = Playlist
                .joinMaps()
                .joinOwner()
                .joinPlaylistCurator()
                .select(
                    Playlist.columns + User.columns + curatorAlias.columns + Playlist.Stats.all
                )
                .where {
                    Playlist.id inList results.mapIds
                }
                .groupBy(Playlist.id, User.id, curatorAlias[User.id])
                .handleCurator()
                .handleOwner()
                .map { playlist ->
                    PlaylistFull.from(playlist, cdnPrefix())
                }
                .sortedBy { results.order[it.playlistId] }

            call.respond(PlaylistSearchResponse(playlists))
        }
    }

    getWithOptions<PlaylistApi.ByUser>("Get playlists by user".responds(ok<PlaylistSearchResponse>())) { req ->
        optionalAuthorization(OauthScope.PLAYLISTS) { _, sess ->
            fun <T> doQuery(table: Query = Playlist.selectAll(), groupBy: Array<Column<*>> = arrayOf(Playlist.id), block: (ResultRow) -> T) =
                transaction {
                    table
                        .where {
                            Playlist.id.inSubQuery(
                                Playlist
                                    .select(Playlist.id)
                                    .where {
                                        ((Playlist.owner eq req.userId) and Playlist.deletedAt.isNull()).let {
                                            if (req.userId == sess?.userId) {
                                                it
                                            } else {
                                                it and (Playlist.type eq EPlaylistType.Public)
                                            }
                                        }
                                    }
                                    .orderBy(
                                        (Playlist.type neq EPlaylistType.System) to SortOrder.ASC,
                                        Playlist.createdAt to SortOrder.DESC
                                    )
                                    .limit(req.page, 20)
                            )
                        }
                        .orderBy(
                            (Playlist.type neq EPlaylistType.System) to SortOrder.ASC,
                            Playlist.createdAt to SortOrder.DESC
                        )
                        .groupBy(*groupBy)
                        .handleOwner()
                        .handleCurator()
                        .map(block)
                }

            if (req.basic) {
                val page = doQuery {
                    PlaylistBasic.from(it, cdnPrefix())
                }

                call.respond(page)
            } else {
                val page = doQuery(
                    Playlist
                        .joinMaps()
                        .joinOwner()
                        .joinPlaylistCurator()
                        .select(Playlist.columns + User.columns + curatorAlias.columns + Playlist.Stats.all),
                    arrayOf(Playlist.id, User.id, curatorAlias[User.id])
                ) {
                    PlaylistFull.from(it, cdnPrefix())
                }

                call.respond(PlaylistSearchResponse(page))
            }
        }
    }
}
