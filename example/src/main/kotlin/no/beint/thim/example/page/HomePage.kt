package no.beint.thim.example.page

import no.beint.thim.FormErrors
import no.beint.thim.example.Feature
import no.beint.thim.example.FeedbackForm

data class HomePage(
    val version: String,
    val greeting: String,
    val features: List<Feature>,
    val showFooter: Boolean,
    val feedbackForm: FeedbackForm,
    val errors: FormErrors = FormErrors.NONE,
)
