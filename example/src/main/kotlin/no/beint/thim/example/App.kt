package no.beint.thim.example

import no.beint.thim.Thim
import no.beint.thim.example.generated.ExampleTemplates
import no.beint.thim.spring.ThimWebMvcConfigurer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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

@Thim("home")
data class HomePage(
    val version: String,
    val greeting: String,
    val features: List<Feature>,
    val showFooter: Boolean,
)

@Controller
class HomeCtrl {
    @GetMapping("/")
    fun home() = HomePage(
        version = "0.1",
        greeting = "Typed Kotlin, compiled HTML, no runtime engine.",
        features = listOf(
            Feature("Safe", "Properties and messages are checked while Kotlin compiles."),
            Feature("Small", "The runtime contains four dependency-free Java types."),
            Feature("Fast", "Generated code writes directly to the HTTP response."),
        ),
        showFooter = true,
    )

    @ResponseBody
    @GetMapping("/health")
    fun health() = "ok"
}

@Configuration(proxyBeanMethods = false)
class WebCfg {
    @Bean
    fun thimWebMvcConfigurer() = ThimWebMvcConfigurer(ExampleTemplates)
}
