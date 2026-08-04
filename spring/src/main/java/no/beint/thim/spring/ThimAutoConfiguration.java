package no.beint.thim.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.support.RequestDataValueProcessor;

@AutoConfiguration
public class ThimAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ThimRenderer thimRenderer(ObjectProvider<RequestDataValueProcessor> requestDataValueProcessor) {
        return new ThimRenderer(requestDataValueProcessor.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    ThimWebMvcConfigurer thimWebMvcConfigurer(ThimRenderer renderer) {
        return new ThimWebMvcConfigurer(renderer);
    }
}
