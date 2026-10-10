package com.cat.nrof.bridge.energy;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.neoforged.neoforge.energy.IEnergyStorage;
import team.reborn.energy.api.EnergyStorage;

/**
 * Adapts a Tech Reborn {@link EnergyStorage} to a NeoForge {@link IEnergyStorage}.
 *
 * <p>The inverse of {@link NeoToTrEnergy}. For each {@code receiveEnergy} /
 * {@code extractEnergy} call, a fresh outer transaction is opened against the
 * TR storage; the operation runs inside the transaction, and the transaction
 * is committed when {@code simulate == false} or simply closed (which aborts)
 * when {@code simulate == true}.</p>
 */
public final class TrToNeoEnergy implements IEnergyStorage {

    private final EnergyStorage tr;

    public TrToNeoEnergy(EnergyStorage tr) {
        this.tr = tr;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        try (Transaction tx = Transaction.openOuter()) {
            long inserted = tr.insert(maxReceive, tx);
            if (!simulate) {
                tx.commit();
            }
            return (int) Math.min(inserted, Integer.MAX_VALUE);
        }
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        try (Transaction tx = Transaction.openOuter()) {
            long extracted = tr.extract(maxExtract, tx);
            if (!simulate) {
                tx.commit();
            }
            return (int) Math.min(extracted, Integer.MAX_VALUE);
        }
    }

    @Override
    public int getEnergyStored() {
        return (int) Math.min(tr.getAmount(), Integer.MAX_VALUE);
    }

    @Override
    public int getMaxEnergyStored() {
        return (int) Math.min(tr.getCapacity(), Integer.MAX_VALUE);
    }

    @Override
    public boolean canExtract() {
        return tr.supportsExtraction();
    }

    @Override
    public boolean canReceive() {
        return tr.supportsInsertion();
    }
}
