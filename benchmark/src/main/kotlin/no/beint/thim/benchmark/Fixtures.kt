package no.beint.thim.benchmark

import no.beint.thim.TrustedUrl
import no.beint.thim.benchmark.page.CatalogItem
import no.beint.thim.benchmark.page.CatalogPage
import no.beint.thim.benchmark.page.InboxMessage
import no.beint.thim.benchmark.page.InboxPage
import no.beint.thim.benchmark.page.PlainPage

object Fixtures {
    const val INBOX_SIZE = 20
    const val CATALOG_SIZE = 50

    @JvmStatic
    @JvmOverloads
    fun inbox(messageCount: Int = INBOX_SIZE, unreadCount: Int = 3): InboxPage {
        val messages = List(messageCount) { index ->
            InboxMessage(
                from = "sender-$index@example.com",
                subject = "Subject $index: invoices & <updates>",
                unread = index < unreadCount,
            )
        }
        return InboxPage(
            greeting = "Hello, $messageCount threads",
            unreadCount = unreadCount,
            messages = messages,
        )
    }

    @JvmStatic
    @JvmOverloads
    fun catalog(itemCount: Int = CATALOG_SIZE): CatalogPage {
        val items = List(itemCount) { index ->
            val id = (index + 1).toString()
            CatalogItem(
                id = id,
                name = "Item $id",
                price = 199 + index,
                featured = index % 7 == 0,
                summary = "A compact description of item $id with an ampersand & a <tag>.",
                href = TrustedUrl("/item/$id"),
            )
        }
        return CatalogPage(size = itemCount, items = items)
    }

    @JvmStatic
    fun plain(): PlainPage = PlainPage("Thim render benchmark")
}
