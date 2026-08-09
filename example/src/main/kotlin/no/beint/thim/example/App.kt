package no.beint.thim.example

import no.beint.thim.FieldError
import no.beint.thim.FormErrors
import no.beint.thim.example.generated.ExampleRoutes
import no.beint.thim.example.page.HomePage
import no.beint.thim.spring.ThimResult
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile

@SpringBootApplication
class App

fun main(args: Array<String>) {
    runApplication<App>(*args)
}

data class Feature(
    val name: String,
    val description: String,
)

enum class FeatureKind {
    SAFE, SMALL, FAST;

    override fun toString(): String = name.lowercase()
}

data class FeedbackForm(
    val author: String,
    val message: String,
    val rating: Int,
    // Only the checkbox may be absent from a post; a default on every parameter would
    // make Kotlin emit a no-arg constructor, which Spring would pick over value binding.
    val subscribe: Boolean = false,
    val priority: String,
    val category: String,
) {
    companion object {
        fun empty() = FeedbackForm(author = "", message = "", rating = 5, priority = "normal", category = "general")
    }
}

@Controller
class HomeCtrl {
    @GetMapping("/")
    fun home() = HomePage(
        version = "0.7.0",
        greeting = "Typed models, compiled HTML, no runtime engine.",
        features = listOf(
            Feature("Safe", "Properties and messages are checked while the application compiles."),
            Feature("Small", "The runtime is dependency-free Java."),
            Feature("Fast", "Generated code writes directly to the HTTP response."),
        ),
        showFooter = true,
        unreadCount = 3,
        feedbackForm = FeedbackForm.empty(),
    )

    @ResponseBody
    @GetMapping("/health")
    fun health() = "ok"

    @ResponseBody
    @GetMapping("/feature")
    fun features() = "Features"

    @ResponseBody
    @GetMapping("/feature/{name}")
    fun feature(
        @PathVariable name: String,
        @RequestParam(required = false) highlight: String?,
        @RequestParam(name = "filter.status", required = false) filterStatus: String?,
        @RequestParam(required = false) tags: List<String>?,
    ) = "Feature: $name${highlight?.let { " ($it)" }.orEmpty()} ${filterStatus.orEmpty()} ${tags.orEmpty()}"

    @ResponseBody
    @GetMapping("/feature-kind/{kind}")
    fun featureKind(@PathVariable kind: FeatureKind) = "Feature kind: $kind"

    @GetMapping("/feature-result/{name}")
    fun featureResult(@PathVariable name: String, @RequestParam(defaultValue = "false") redirect: Boolean): ThimResult =
        if (redirect) {
            ThimResult.Redirect(
                ExampleRoutes.featureByName(
                    name,
                    highlight = "redirected",
                    filter_status = "active",
                    tags = listOf("typed routes", "safe"),
                    additionalQueryParameters = mapOf("source" to "thim example"),
                ),
            )
        } else {
            ThimResult.Page(home())
        }

    @GetMapping("/feature-kind-redirect")
    fun featureKindRedirect(): ThimResult.Redirect = ThimResult.Redirect(ExampleRoutes.featureKind(FeatureKind.SAFE))

    @ResponseBody
    @PostMapping("/upload")
    fun upload(@RequestParam file: MultipartFile): String = file.originalFilename.orEmpty()

    @PostMapping("/feedback")
    fun feedback(form: FeedbackForm): HomePage {
        val errors = if (form.author.isBlank()) {
            FormErrors(listOf(FieldError("author", "Author is required", form.author)))
        } else {
            FormErrors.NONE
        }
        return home().copy(
            feedbackForm = if (errors.isEmpty()) FeedbackForm.empty() else form,
            errors = errors,
        )
    }
}
