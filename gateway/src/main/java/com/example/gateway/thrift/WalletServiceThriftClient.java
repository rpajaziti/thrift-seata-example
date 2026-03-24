package com.example.gateway.thrift;

import com.example.common.thrift.SeataThriftHttpClient;
import com.example.thrift.wallet.TWalletService;
import org.apache.thrift.protocol.TJSONProtocol;
import org.apache.thrift.transport.TTransportException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WalletServiceThriftClient {

    @Value("${wallet-service.connectTimeout}")
    private int connectionTimeout;

    @Value("${wallet-service.readTimeout}")
    private int readTimeout;

    @Value("${wallet-service.endpoint}")
    private String endpoint;

    @Bean("walletServiceClient")
    public TWalletService.Client walletServiceClient() throws TTransportException {
        SeataThriftHttpClient httpClient = new SeataThriftHttpClient(endpoint);
        httpClient.setConnectTimeout(connectionTimeout);
        httpClient.setReadTimeout(readTimeout);
        return new TWalletService.Client(new TJSONProtocol(httpClient));
    }
}
