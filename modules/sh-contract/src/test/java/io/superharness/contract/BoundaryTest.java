package io.superharness.contract;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;

import org.junit.jupiter.api.Test;

class BoundaryTest {
    @Test
    void doesNotImportAgentScope() {
        noClasses()
                .that()
                .resideInAPackage("io.superharness.contract..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.agentscope..")
                .check(new ClassFileImporter().importPackages("io.superharness.contract"));
    }
}
