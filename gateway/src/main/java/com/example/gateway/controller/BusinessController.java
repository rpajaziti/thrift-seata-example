package com.example.gateway.controller;

import com.example.gateway.dto.OrderRequest;
import com.example.gateway.dto.TopUpRequest;
import com.example.gateway.service.OrderOrchestrationService;
import com.example.thrift.inventory.TInventoryService;
import com.example.thrift.inventory.TProduct;
import com.example.thrift.wallet.TWalletService;
import org.apache.thrift.TException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import jakarta.validation.Valid;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BusinessController {

    private final OrderOrchestrationService orderOrchestrationService;
    private final RestTemplate restTemplate;
    private final TWalletService.Client walletServiceClient;
    private final TInventoryService.Client inventoryServiceClient;

    @Value("${order-service.endpoint}")
    private String orderServiceEndpoint;

    public BusinessController(OrderOrchestrationService orderOrchestrationService,
                              RestTemplate restTemplate,
                              @Qualifier("walletServiceClient") TWalletService.Client walletServiceClient,
                              @Qualifier("inventoryServiceClient") TInventoryService.Client inventoryServiceClient) {
        this.orderOrchestrationService = orderOrchestrationService;
        this.restTemplate = restTemplate;
        this.walletServiceClient = walletServiceClient;
        this.inventoryServiceClient = inventoryServiceClient;
    }

    @PostMapping("/orders")
    public ResponseEntity<Map<String, String>> placeOrder(@Valid @RequestBody OrderRequest request,
                                                          @RequestParam(defaultValue = "false") boolean simulateFail) throws TException {
        orderOrchestrationService.placeOrder(request, simulateFail);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Collections.singletonMap("message", "Order placed successfully"));
    }

    @GetMapping("/orders")
    public ResponseEntity<List<?>> getOrders() {
        List<?> orders = restTemplate.getForObject(orderServiceEndpoint, List.class);
        return ResponseEntity.ok(orders);
    }

    @PostMapping("/wallets/top-up")
    public ResponseEntity<Map<String, String>> topUpWallet(@Valid @RequestBody TopUpRequest request) throws TException {
        walletServiceClient.topUp(request.getUserId(), request.getAmount());
        return ResponseEntity.ok(Collections.singletonMap("message", "Wallet topped up successfully"));
    }

    @GetMapping("/products")
    public ResponseEntity<List<TProduct>> getProducts() throws TException {
        return ResponseEntity.ok(inventoryServiceClient.listProducts());
    }
}
