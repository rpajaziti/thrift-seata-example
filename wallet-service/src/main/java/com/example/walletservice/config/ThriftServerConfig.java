package com.example.walletservice.config;

import com.example.walletservice.controller.WalletThriftController;
import com.example.walletservice.service.WalletService;
import com.example.thrift.wallet.TWalletService;
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
    public ServletRegistrationBean<TServlet> walletThriftServlet(WalletService walletService) {
        TProcessor processor = new TWalletService.Processor<>(new WalletThriftController(walletService));
        TServlet servlet = new TServlet(processor, new TJSONProtocol.Factory());
        return new ServletRegistrationBean<>(servlet, "/api");
    }
}
