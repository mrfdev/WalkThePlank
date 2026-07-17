package com.mrfdev.walktheplank.database;

/** Non-sensitive durable identity for a reward command. */
public record RewardStepSpec(String commandRoot, String commandHash) {
    public RewardStepSpec {
        commandRoot = PersistenceValidation.commandRoot(commandRoot);
        commandHash = PersistenceValidation.commandHash(commandHash);
    }
}
