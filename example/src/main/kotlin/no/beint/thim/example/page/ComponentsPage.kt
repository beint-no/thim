package no.beint.thim.example.page

import no.beint.thim.example.ui.LinkProps

data class ComponentsPage(
    val title: String,
    val card: CardProps,
    val button: ButtonProps,
    val links: List<LinkProps>,
    val form: ComponentForm,
)
data class CardProps(val title: String)
data class ButtonProps(val disabled: Boolean = false, val appearance: ButtonAppearance = ButtonAppearance.PRIMARY)
enum class ButtonAppearance(val cssClass: String) { PRIMARY("primary"), SECONDARY("secondary") }
data class ComponentForm(val name: String)

data class LargeComponentPage(val title: String, val cards: List<CardProps>)
