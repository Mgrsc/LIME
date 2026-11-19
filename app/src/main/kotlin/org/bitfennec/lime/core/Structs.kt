package org.bitfennec.lime.core

import androidx.annotation.Keep

@Keep
data class SchemaItem(
    val id: String,
    val name: String = "",
)

@Keep
data class CandidateListItem(
    var comment: String,
    var text: String,
    val identity: CandidateIdentity? = null,
)

enum class CandidateKind { RIME, PREDICTION }

data class CandidateIdentity(
    val sessionId: Long,
    val revision: Long,
    val index: Int,
    val kind: CandidateKind = CandidateKind.RIME,
)

@Keep
data class RimeComposition(
    val length: Int = 0,
    val cursorPos: Int = 0,
    val selStart: Int = 0,
    val selEnd: Int = 0,
    val preedit: String? = "",
    val literalText: String = "",
    val displayBoundaries: IntArray = intArrayOf(),
    val rawBoundaries: IntArray = intArrayOf(),
    val displayCursorPos: Int = -1,
)

@Keep
data class RimePageCandidate(
    val pageIndex: Int,
    val text: String,
    val comment: String,
)

@Keep
data class RimeCandidatePage(
    val pageNo: Int = 0,
    val pageSize: Int = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val highlightedIndex: Int = 0,
    val candidates: Array<RimePageCandidate> = emptyArray(),
)

@Keep
data class RimeT9Metadata(
    val preedit: String = "",
    val prefixOptions: Array<String> = emptyArray(),
    val lockedCount: Int = 0,
)

@Keep
data class RimeSnapshot(
    val handled: Boolean = false,
    val schemaId: String = "",
    val rawInput: String = "",
    val composition: RimeComposition = RimeComposition(),
    val commitText: String? = null,
    val page: RimeCandidatePage = RimeCandidatePage(),
    val t9Metadata: RimeT9Metadata? = null,
    val candidateRevision: Long = 0L,
)
