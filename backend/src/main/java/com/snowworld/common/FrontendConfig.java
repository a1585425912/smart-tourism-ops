package com.snowworld.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 托管前端构建产物(frontend/dist), 使后台、大屏、移动巡检与 API 同源同端口。
 * 非 /api 的未知路径回退到 index.html, 交给前端路由处理。未构建前端时不生效。
 */
@Configuration
public class FrontendConfig implements WebMvcConfigurer {

    private final Path dist;

    public FrontendConfig(@Value("${snow.frontend-dir:../frontend/dist}") String dir) {
        this.dist = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (Files.isRegularFile(dist.resolve("index.html"))) {
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!Files.isRegularFile(dist.resolve("index.html"))) {
            return;
        }
        registry.addResourceHandler("/**")
                .addResourceLocations(dist.toUri().toString())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("v3/") || resourcePath.startsWith("actuator/")) {
                            return null;
                        }
                        Resource found = super.getResource(resourcePath, location);
                        if (found != null) {
                            return found;
                        }
                        // 带扩展名的静态文件不存在时返回 404, 其余交给前端路由
                        return resourcePath.contains(".") ? null : new FileSystemResource(dist.resolve("index.html"));
                    }
                });
    }
}
