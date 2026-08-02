package no.beint.thim.example

import no.beint.thim.example.page.HomePage
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody

@SpringBootApplication
class App

fun main(args: Array<String>) {
    runApplication<App>(*args)
}

data class Feature(
    val name: String,
    val description: String,
)

@Controller
class HomeCtrl {
    @GetMapping("/")
    fun home() = HomePage(
        version = "0.4.0",
        greeting = "Typed models, compiled HTML, no runtime engine.",
        features = listOf(
            Feature("Safe", "Properties and messages are checked while the application compiles."),
            Feature("Small", "The runtime contains three dependency-free Java types."),
            Feature("Fast", "Generated code writes directly to the HTTP response."),
        ),
        showFooter = true,
    )

    @ResponseBody
    @GetMapping("/health")
    fun health() = "ok"
}
