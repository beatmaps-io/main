package io.beatmaps.api.search

import io.beatmaps.common.SearchOrder
import org.apache.solr.client.solrj.SolrQuery

class SolrSearchParams(
    escapedQuery: String,
    private val query: String,
    private val quotedSections: List<String>,
    bothWithoutQuotes: String,
    mappers: List<String>
) : SearchParams(escapedQuery, bothWithoutQuotes, mappers) {
    fun applyQuery(q: SolrQuery): SolrQuery =
        if (query.isBlank() && quotedSections.isEmpty()) {
            q.setQuery("*:*")
        } else {
            // Recombine string without key: or mapper:
            q.setQuery("${query.replace(':', ' ')} ${quotedSections.joinToString("\" \"", "\"", "\"")}")
        }

    override fun validateSearchOrder(originalOrder: SearchOrder) =
        if (originalOrder == SearchOrder.Relevance && bothWithoutQuotes.isNotBlank()) {
            SearchOrder.Relevance
        } else {
            super.validateSearchOrder(originalOrder)
        }

    fun addSortArgs(q: SolrQuery, seed: Int?, searchOrder: SearchOrder) =
        when (searchOrder) {
            SearchOrder.Relevance -> listOf(
                (SolrBaseScore product BsSolr.voteScore).desc()
            )
            SearchOrder.Rating -> listOf(
                BsSolr.voteScore.desc(),
                BsSolr.uploaded.desc()
            )
            SearchOrder.Latest -> listOf(
                BsSolr.uploaded.desc()
            )
            SearchOrder.Curated -> listOf(
                BsSolr.curated.desc(),
                BsSolr.uploaded.desc()
            )
            SearchOrder.Random -> listOf(
                SolrQuery.SortClause("random_$seed", SolrQuery.ORDER.desc)
            )
        }.fold(q) { query, it ->
            query.addSort(it)
        }

    companion object {
        fun parseSearchQuery(q: String) =
            parseSearchQuery(q) { originalQuery, query, quotedSections, bothWithoutQuotes, mappers ->
                SolrSearchParams(originalQuery, query, quotedSections, bothWithoutQuotes, mappers)
            }
    }
}
