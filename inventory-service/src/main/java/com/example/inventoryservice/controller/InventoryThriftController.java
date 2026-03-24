package com.example.inventoryservice.controller;

import com.example.common.exception.InsufficientStockException;
import com.example.common.exception.ResourceNotFoundException;
import com.example.inventoryservice.service.InventoryService;
import com.example.thrift.inventory.TInventoryException;
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
    public void decrementStock(long productId, int quantity) throws TInventoryException, TException {
        try {
            inventoryService.decrementStock(productId, quantity);
        } catch (ResourceNotFoundException e) {
            throw new TInventoryException(404, e.getMessage());
        } catch (InsufficientStockException e) {
            throw new TInventoryException(409, e.getMessage());
        }
    }

    @Override
    public List<TProduct> listProducts() throws TException {
        return inventoryService.listProducts();
    }
}
