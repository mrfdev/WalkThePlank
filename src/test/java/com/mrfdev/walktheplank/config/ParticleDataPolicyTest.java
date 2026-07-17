package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

class ParticleDataPolicyTest {
    @Test
    void bundledParticleUsesTheSupportedNoDataOverload() {
        assertEquals(
                Particle.TOTEM_OF_UNDYING,
                ConfigurationManager.requireParticleWithoutData(
                        Particle.TOTEM_OF_UNDYING,
                        "TOTEM_OF_UNDYING"));
    }

    @Test
    void typedParticlesCannotUseTheNoDataOverload() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigurationManager.requireParticleWithoutData(Particle.DUST, "DUST"));
    }
}
