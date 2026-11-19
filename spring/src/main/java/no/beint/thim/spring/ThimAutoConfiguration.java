package no.beint.thim.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.support.RequestDataValueProcessor;

@AutoConfiguration
public class ThimAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ThimRenderer thimRenderer(
            ObjectProvider<RequestDataValueProcessor> requestDataValueProcessor,
            ObjectProvider<ThimRenderObserver> renderObservers
    ) {
        return new ThimRenderer(
                requestDataValueProcessor.getIfAvailable(),
                renderObservers.orderedStream().toList()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(WebMvcConfigurer.class)
    ThimWebMvcConfigurer thimWebMvcConfigurer(ThimRenderer renderer) {
        return new ThimWebMvcConfigurer(renderer);
    }
}
