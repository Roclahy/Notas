package com.omgodse.notally.room

data class SpanRepresentation(
    var bold: Boolean,
    var link: Boolean,
    var italic: Boolean,
    var monospace: Boolean,
    var strikethrough: Boolean,
    var start: Int,
    var end: Int,
    /** URL externa o referencia interna rose://note/id o rose://folder/id. */
    var url: String? = null,
    var highlighted: Boolean = false,
    var imageName: String? = null
) {

    fun isNotUseless(): Boolean {
        return bold || link || italic || monospace || strikethrough || highlighted || imageName != null
    }

    fun isEqualInSize(representation: SpanRepresentation): Boolean {
        return start == representation.start && end == representation.end
    }
}