package com.ephemeral.config;

import com.ephemeral.auth.CurrentUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // The vendored LiveKit SDK and self-hosted fonts never change between
        // deploys — cache them hard (saves 5-6 revalidation round trips per load).
        // app.js/style.css keep the app-wide no-cache so UI edits show on refresh.
        registry.addResourceHandler("/vendor/**", "/fonts/**")
                .addResourceLocations("classpath:/static/vendor/", "classpath:/static/fonts/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());
    }
}
