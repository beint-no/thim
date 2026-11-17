package no.beint.thim.benchmark.page

import no.beint.thim.TrustedUrl

data class ComponentItem(val name: String, val href: TrustedUrl, val selected: Boolean)
data class ComponentPage(val items: List<ComponentItem>)
data class InlinePage(val items: List<ComponentItem>)
