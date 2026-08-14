package no.beint.thim.benchmark.page

data class InboxMessage(
    val from: String,
    val subject: String,
    val unread: Boolean,
)

data class InboxPage(
    val greeting: String,
    val unreadCount: Int,
    val messages: List<InboxMessage>,
)
