package com.mindtable.bitbuckethelper

import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

@AnalyzeClasses(packages = ["com.mindtable.bitbuckethelper"])
class ArchitectureTest {
    @ArchTest
    val application_has_no_framework_dependencies: ArchRule = noClasses()
        .that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "io.ktor..",
            "org.quartz..",
            "liquibase..",
            "org.jooq..",
            "org.sqlite..",
            "..adapter..",
            "..bootstrap..",
        )

    @ArchTest
    val generated_bitbucket_types_are_adapter_private: ArchRule = noClasses()
        .that().resideOutsideOfPackage("..adapter.outbound.bitbucket..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..adapter.outbound.bitbucket.generated..",
        )

    @ArchTest
    val generated_jooq_types_are_adapter_private: ArchRule = noClasses()
        .that().resideOutsideOfPackage("..adapter.outbound.persistence..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..adapter.outbound.persistence.generated..",
        )

    @ArchTest
    val adapters_do_not_depend_on_bootstrap: ArchRule = noClasses()
        .that().resideInAPackage("..adapter..")
        .should().dependOnClassesThat().resideInAPackage("..bootstrap..")

    @ArchTest
    val top_level_packages_are_acyclic: ArchRule = slices()
        .matching("com.mindtable.bitbuckethelper.(*)..")
        .should().beFreeOfCycles()
}
