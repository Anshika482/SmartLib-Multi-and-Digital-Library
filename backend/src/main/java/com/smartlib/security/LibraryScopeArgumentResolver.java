package com.smartlib.security;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class LibraryScopeArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return LibraryScope.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer, NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        Object scope = webRequest.getAttribute(LibraryScopeInterceptor.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (scope == null) {
            throw new IllegalStateException("LibraryScope requested outside /api/manage/libraries/{libraryId}");
        }
        return scope;
    }
}
