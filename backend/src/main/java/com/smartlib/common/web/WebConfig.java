package com.smartlib.common.web;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.smartlib.security.LibraryScopeArgumentResolver;
import com.smartlib.security.LibraryScopeInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final LibraryScopeInterceptor libraryScopeInterceptor;

    public WebConfig(LibraryScopeInterceptor libraryScopeInterceptor) {
        this.libraryScopeInterceptor = libraryScopeInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Every staff route is guarded here, so a missing annotation can never open cross-library access.
        registry.addInterceptor(libraryScopeInterceptor).addPathPatterns("/api/manage/libraries/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new LibraryScopeArgumentResolver());
    }
}
