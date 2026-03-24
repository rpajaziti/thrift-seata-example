package com.example.walletservice.controller;

import com.example.walletservice.service.WalletService;
import com.example.thrift.wallet.TWalletService;
import org.apache.thrift.TException;

public class WalletThriftController implements TWalletService.Iface {

    private final WalletService walletService;

    public WalletThriftController(WalletService walletService) {
        this.walletService = walletService;
    }

    @Override
    public void deductBalance(String userId, int amount) throws TException {
        walletService.deductBalance(userId, amount);
    }

    @Override
    public void topUp(String userId, int amount) throws TException {
        walletService.topUp(userId, amount);
    }
}
