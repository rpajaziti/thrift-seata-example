package com.example.gateway.thrift;

import com.example.common.thrift.SeataThriftHttpClient;
import com.example.thrift.inventory.TInventoryService;
import org.apache.thrift.protocol.TJSONProtocol;
import org.apache.thrift.transport.TTransportException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InventoryServiceThriftClient {

    @Value("${inventory-service.connectTimeout}")
    private int connectionTimeout;

    @Value("${inventory-service.readTimeout}")
    private int readTimeout;

    @Value("${inventory-service.endpoint}")
    private String endpoint;

    @Bean("inventoryServiceClient")
    public TInventoryService.Client inventoryServiceClient() throws TTransportException {
        SeataThriftHttpClient httpClient = new SeataThriftHttpClient(endpoint);
        httpClient.setConnectTimeout(connectionTimeout);
        httpClient.setReadTimeout(readTimeout);
        return new TInventoryService.Client(new TJSONProtocol(httpClient));
    }
}
