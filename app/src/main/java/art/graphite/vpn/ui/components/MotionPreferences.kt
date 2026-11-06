package art.graphite.vpn.ui.components

/** Stored by enum name so adding modes does not change an existing preference. */
enum class PageTurnStyle { CURL, ROLL }

object NotebookMotionDefaults {
    const val PAGE_MILLIS = 3500
    const val MIN_PAGE_MILLIS = 1000
    const val MAX_PAGE_MILLIS = 5000
    const val REVEAL_MILLIS = 1200
    const val MIN_REVEAL_MILLIS = 300
    const val MAX_REVEAL_MILLIS = 3000
    fun style(stored:String?):PageTurnStyle = PageTurnStyle.entries.firstOrNull {it.name==stored} ?: PageTurnStyle.CURL
}
