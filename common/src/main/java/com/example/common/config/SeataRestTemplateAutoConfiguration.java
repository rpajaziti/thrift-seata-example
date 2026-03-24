package com.example.common.config;

import com.example.common.interceptor.SeataRestTemplateInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class SeataRestTemplateAutoConfiguration {

    private final ObjectProvider<List<RestTemplate>> restTemplatesProvider;
    private final SeataRestTemplateInterceptor interceptor;

    public SeataRestTemplateAutoConfiguration(ObjectProvider<List<RestTemplate>> restTemplatesProvider) {
        this.restTemplatesProvider = restTemplatesProvider;
        this.interceptor = new SeataRestTemplateInterceptor();
    }

    @Bean
    public SeataRestTemplateInterceptor seataRestTemplateInterceptor() {
        return interceptor;
    }

    @PostConstruct
    public void init() {
        List<RestTemplate> restTemplates = restTemplatesProvider.getIfAvailable();
        if (restTemplates != null) {
            for (RestTemplate restTemplate : restTemplates) {
                List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(restTemplate.getInterceptors());
                interceptors.add(interceptor);
                restTemplate.setInterceptors(interceptors);
            }
        }
    }
}