package no.beint.thim.benchmark.page

import no.beint.thim.TrustedUrl

data class CatalogItem(
    val id: String,
    val name: String,
    val price: Int,
    val featured: Boolean,
    val summary: String,
    val href: TrustedUrl,
)

data class CatalogPage(
    val size: Int,
    val items: List<CatalogItem>,
)
