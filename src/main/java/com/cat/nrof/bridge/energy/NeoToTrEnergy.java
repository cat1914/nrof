package com.cat.nrof.bridge.energy;

import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.neoforged.neoforge.energy.IEnergyStorage;
import team.reborn.energy.api.EnergyStorage;

/**
 * Adapts a NeoForge {@link IEnergyStorage} to a Tech Reborn {@link EnergyStorage}.
 *
 * <p>NeoForge's FE API is synchronous: each {@code receiveEnergy} / {@code extractEnergy}
 * call takes an explicit {@code simulate} flag. TR's API is transactional: mutations
 * are applied to in-memory buffers tied to a {@link TransactionContext} and only
 * become durable when the outer transaction commits.</p>
 *
 * <p>The mapping therefore defers the actual mutation via
 * {@code TransactionContext#addCloseCallback}: the simulated amount is reported
 * to the caller immediately (TR contracts require this), and the real mutation
 * runs only when the transaction is closing with
 * {@code CloseResult#wasCommitted()}.</p>
 *
 * <p>Overflow: NeoForge uses {@code int}, TR uses {@code long}. Values are clamped
 * at {@code Integer.MAX_VALUE} when crossing into NeoForge.</p>
 */
public final class NeoToTrEnergy implements EnergyStorage {

    private final IEnergyStorage neo;

    public NeoToTrEnergy(IEnergyStorage neo) {
        this.neo = neo;
    }

    @Override
    public long insert(long maxAmount, TransactionContext transaction) {
        int toInsert = (int) Math.min(maxAmount, Integer.MAX_VALUE);
        int simulated = neo.receiveEnergy(toInsert, /* simulate= */ true);
        if (simulated > 0) {
            transaction.addCloseCallback((tx, result) -> {
                if (result.wasCommitted()) {
                    neo.receiveEnergy(simulated, /* simulate= */ false);
                }
            });
        }
        return simulated;
    }

    @Override
    public long extract(long maxAmount, TransactionContext transaction) {
        int toExtract = (int) Math.min(maxAmount, Integer.MAX_VALUE);
        int simulated = neo.extractEnergy(toExtract, /* simulate= */ true);
        if (simulated > 0) {
            transaction.addCloseCallback((tx, result) -> {
                if (result.wasCommitted()) {
                    neo.extractEnergy(simulated, /* simulate= */ false);
                }
            });
        }
        return simulated;
    }

    @Override
    public long getAmount() {
        return neo.getEnergyStored();
    }

    @Override
    public long getCapacity() {
        return neo.getMaxEnergyStored();
    }

    @Override
    public boolean supportsInsertion() {
        return neo.canReceive();
    }

    @Override
    public boolean supportsExtraction() {
        return neo.canExtract();
    }
}
