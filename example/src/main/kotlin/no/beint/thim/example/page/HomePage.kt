package no.beint.thim.example.page

import no.beint.thim.example.Feature

data class HomePage(
    val version: String,
    val greeting: String,
    val features: List<Feature>,
    val showFooter: Boolean,
)
