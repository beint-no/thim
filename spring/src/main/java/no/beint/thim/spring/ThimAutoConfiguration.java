package no.beint.thim.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class ThimAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ThimWebMvcConfigurer thimWebMvcConfigurer() {
        return new ThimWebMvcConfigurer();
    }
}
