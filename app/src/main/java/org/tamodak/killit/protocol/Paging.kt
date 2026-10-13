package org.tamodak.killit.protocol

/*
 * The two paged message kinds, APP_LIST and MOVE_STATE (SPEC.md §11.10, §11.14): packing a long
 * list into pages that each fit one envelope, and putting the pages back together with the rules
 * that span more than one page.
 *
 * A decoder sees one page at a time, so it can only check what a single page says. Whether the
 * pages agree with each other, whether one is missing, and whether a package turns up on two of
 * them is decided here, once every page has arrived.
 */

/** Where putting a paged list back together stands. */
sealed interface Assembly<out T> {
    /** Every page arrived and the whole passed every rule. */
    class Complete<T>(val value: T) : Assembly<T>

    /** [missing] pages haven't arrived yet. */
    class Incomplete(val missing: Int) : Assembly<Nothing>

    /** The pages break a rule; the whole list is dropped. */
    class Invalid(val reason: String) : Assembly<Nothing>
}

/** Fills pages in order, each with as many items as fit in one body (SPEC.md §11.10). */
private fun <T> pack(items: List<T>, emptyPageBytes: Int, itemBytes: (T) -> Int): List<List<T>> {
    val pages = mutableListOf<List<T>>()
    var page = mutableListOf<T>()
    var size = emptyPageBytes
    for (item in items) {
        val bytes = itemBytes(item)
        require(emptyPageBytes + bytes <= MAX_BODY_BYTES) { "an item is too large for a page" }
        if (size + bytes > MAX_BODY_BYTES) {
            pages += page
            page = mutableListOf()
            size = emptyPageBytes
        }
        page += item
        size += bytes
    }
    pages += page
    return pages
}

private val NO_ID = ByteArray(LIST_ID_BYTES)

/**
 * Splits an app list into APP_LIST pages: sorted by package name, cut at [MAX_LISTED_APPS].
 *
 * @throws IllegalArgumentException when the list would need more than 255 pages, which 1000 apps
 *   never do.
 */
fun pageAppList(listId: ByteArray, apps: List<ListedApp>): List<MessageBody.AppList> {
    val sorted = apps.sortedBy { it.packageName }.take(MAX_LISTED_APPS)
    val empty = encodeBody(MessageBody.AppList(NO_ID, 0, 1, emptyList())).size
    val groups = pack(sorted, empty) { encodeBody(MessageBody.AppList(NO_ID, 0, 1, listOf(it))).size - empty }
    require(groups.size <= 255) { "too many pages" }
    return groups.mapIndexed { page, group -> MessageBody.AppList(listId, page, groups.size, group) }
}

/** Puts an app list back together: the same list id and page count everywhere, each package once. */
fun assembleAppList(pages: List<MessageBody.AppList>): Assembly<List<ListedApp>> {
    val first = pages.firstOrNull() ?: return Assembly.Incomplete(1)
    val byPage = mutableMapOf<Int, MessageBody.AppList>()
    for (page in pages) {
        if (!page.listId.contentEquals(first.listId) || page.pageCount != first.pageCount) return Assembly.Invalid("pages of different lists")
        if (byPage.put(page.page, page) != null) return Assembly.Invalid("a page arrived twice")
    }
    if (byPage.size < first.pageCount) return Assembly.Incomplete(first.pageCount - byPage.size)
    val apps = (0 until first.pageCount).flatMap { byPage.getValue(it).apps }
    if (apps.map { it.packageName }.toSet().size != apps.size) return Assembly.Invalid("a package appears twice")
    if (apps.size > MAX_LISTED_APPS) return Assembly.Invalid("too many apps")
    return Assembly.Complete(apps)
}

/** What a move hands over, once its pages are put together (SPEC.md §11.14). */
class MovedState(
    val moveId: ByteArray,
    val appManagement: Boolean,
    val protection: Int,
    val pairings: List<MovedPairing>,
    val apps: List<MovedApp>,
)

/** Splits a move's state into MOVE_STATE pages: pairings first, then apps, each page as full as it fits. */
fun pageMoveState(state: MovedState): List<MessageBody.MoveState> {
    fun page(pairings: List<MovedPairing>, apps: List<MovedApp>, index: Int = 0, count: Int = 1) =
        MessageBody.MoveState(state.moveId, index, count, state.appManagement, state.protection, pairings, apps)

    val empty = encodeBody(page(emptyList(), emptyList())).size
    val items: List<Any> = state.pairings + state.apps
    val groups = pack(items, empty) { item ->
        when (item) {
            is MovedPairing -> encodeBody(page(listOf(item), emptyList())).size - empty
            else -> encodeBody(page(emptyList(), listOf(item as MovedApp))).size - empty
        }
    }
    require(groups.size <= 255) { "too many pages" }
    return groups.mapIndexed { index, group ->
        page(group.filterIsInstance<MovedPairing>(), group.filterIsInstance<MovedApp>(), index, groups.size)
    }
}

/**
 * Puts a move's state back together, with every check SPEC.md §11.14 step 3 lists.
 *
 * @param newFp the new phone: the one receiving the state.
 * @param oldFp the old phone, which sent it.
 */
fun assembleMoveState(pages: List<MessageBody.MoveState>, newFp: ByteArray, oldFp: ByteArray): Assembly<MovedState> {
    val first = pages.firstOrNull() ?: return Assembly.Incomplete(1)
    val byPage = mutableMapOf<Int, MessageBody.MoveState>()
    for (page in pages) {
        val same = page.moveId.contentEquals(first.moveId) &&
            page.pageCount == first.pageCount &&
            page.appManagement == first.appManagement &&
            page.protection == first.protection
        if (!same) return Assembly.Invalid("pages of different moves")
        if (byPage.put(page.page, page) != null) return Assembly.Invalid("a page arrived twice")
    }
    if (byPage.size < first.pageCount) return Assembly.Incomplete(first.pageCount - byPage.size)

    val ordered = (0 until first.pageCount).map { byPage.getValue(it) }
    val pairings = ordered.flatMap { it.pairings }
    val apps = ordered.flatMap { it.apps }
    if (pairings.map { it.pairId.toHex() }.toSet().size != pairings.size) return Assembly.Invalid("a pairing appears twice")
    for (pairing in pairings) {
        if (!verifyBundle(pairing.peer)) return Assembly.Invalid("a peer's bundle doesn't verify")
        val peerFp = fingerprint(pairing.peer.signPk)
        if (peerFp.contentEquals(newFp) || peerFp.contentEquals(oldFp)) return Assembly.Invalid("a peer is one of the two phones")
    }
    if (apps.map { it.app.packageName }.toSet().size != apps.size) return Assembly.Invalid("a package appears twice")
    if (pairings.size > MAX_MOVED_PAIRINGS || apps.size > MAX_MOVED_APPS) return Assembly.Invalid("too much state")
    // The setting, the protection options and the decisions about apps belong to a phone that has
    // guardians, and to no other.
    val guarded = pairings.any { it.role == Role.PROTECTED }
    if (!guarded && (first.appManagement || first.protection != 0 || apps.isNotEmpty())) {
        return Assembly.Invalid("protection state without guardians")
    }
    return Assembly.Complete(MovedState(first.moveId, first.appManagement, first.protection, pairings, apps))
}
