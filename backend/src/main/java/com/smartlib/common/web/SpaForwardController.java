package com.smartlib.common.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * When the production frontend build is packaged into {@code classpath:/static}, forward every client-side route
 * (paths without a file extension, outside /api) to index.html so deep links and refreshes work.
 */
@Controller
@ConditionalOnResource(resources = "classpath:/static/index.html")
public class SpaForwardController {

    @GetMapping({"/", "/{path:^(?!api$|actuator$|assets$|brand$)[^\\.]*}", "/{path:^(?!api$|actuator$|assets$|brand$)[^\\.]*}/**"})
    public String forward() {
        return "forward:/index.html";
    }
}
