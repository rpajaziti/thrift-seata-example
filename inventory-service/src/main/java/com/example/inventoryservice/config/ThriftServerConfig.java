package com.example.inventoryservice.config;

import com.example.inventoryservice.controller.InventoryThriftController;
import com.example.inventoryservice.service.InventoryService;
import com.example.thrift.inventory.TInventoryService;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TJSONProtocol;
import org.apache.thrift.server.TServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// TODO: Temporary — manual servlet registration until thrift-spring-boot-starter supports DI
@Configuration
public class ThriftServerConfig {

    @Bean
    public ServletRegistrationBean<TServlet> inventoryThriftServlet(InventoryService inventoryService) {
        TProcessor processor = new TInventoryService.Processor<>(new InventoryThriftController(inventoryService));
        TServlet servlet = new TServlet(processor, new TJSONProtocol.Factory());
        return new ServletRegistrationBean<>(servlet, "/api");
    }
}
