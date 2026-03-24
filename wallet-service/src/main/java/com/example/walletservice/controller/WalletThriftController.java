package com.example.walletservice.controller;

import com.example.common.exception.InsufficientBalanceException;
import com.example.common.exception.ResourceNotFoundException;
import com.example.walletservice.service.WalletService;
import com.example.thrift.wallet.TWalletException;
import com.example.thrift.wallet.TWalletService;
import org.apache.thrift.TException;

public class WalletThriftController implements TWalletService.Iface {

    private final WalletService walletService;

    public WalletThriftController(WalletService walletService) {
        this.walletService = walletService;
    }

    @Override
    public void deductBalance(String userId, int amount) throws TWalletException, TException {
        try {
            walletService.deductBalance(userId, amount);
        } catch (ResourceNotFoundException e) {
            throw new TWalletException(404, e.getMessage());
        } catch (InsufficientBalanceException e) {
            throw new TWalletException(409, e.getMessage());
        }
    }

    @Override
    public void topUp(String userId, int amount) throws TWalletException, TException {
        try {
            walletService.topUp(userId, amount);
        } catch (RuntimeException e) {
            throw new TWalletException(500, e.getMessage());
        }
    }
}
