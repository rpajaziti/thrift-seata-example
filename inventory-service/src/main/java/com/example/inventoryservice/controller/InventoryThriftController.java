package com.example.inventoryservice.controller;

import com.example.inventoryservice.service.InventoryService;
import com.example.thrift.inventory.TInventoryService;
import com.example.thrift.inventory.TProduct;
import org.apache.thrift.TException;

import java.util.List;

public class InventoryThriftController implements TInventoryService.Iface {

    private final InventoryService inventoryService;

    public InventoryThriftController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Override
    public void decrementStock(long productId, int quantity) throws TException {
        inventoryService.decrementStock(productId, quantity);
    }

    @Override
    public List<TProduct> listProducts() throws TException {
        return inventoryService.listProducts();
    }
}
