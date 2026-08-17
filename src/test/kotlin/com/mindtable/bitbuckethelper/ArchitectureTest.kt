package com.mindtable.bitbuckethelper

import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

@AnalyzeClasses(packages = ["com.mindtable.bitbuckethelper"])
class ArchitectureTest {
    @ArchTest
    val domain_has_no_outward_dependencies: ArchRule = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..application..",
            "..adapter..",
            "..bootstrap..",
            "io.ktor..",
            "org.quartz..",
            "liquibase..",
            "org.jooq..",
            "org.sqlite..",
            "kotlinx.serialization..",
        )

    @ArchTest
    val application_has_no_framework_dependencies: ArchRule = noClasses()
        .that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "io.ktor..",
            "org.quartz..",
            "liquibase..",
            "org.jooq..",
            "org.sqlite..",
            "kotlinx.serialization..",
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
    val domain_and_application_do_not_depend_on_generated_product_dtos: ArchRule = noClasses()
        .that().resideInAnyPackage("..domain..", "..application..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..generated.api.v1..",
            "..api.v1.generated..",
        )

    @ArchTest
    val adapters_do_not_depend_on_bootstrap: ArchRule = noClasses()
        .that().resideInAPackage("..adapter..")
        .should().dependOnClassesThat().resideInAPackage("..bootstrap..")

    @ArchTest
    val product_cli_reaches_business_logic_only_through_generated_local_api_contracts: ArchRule = noClasses()
        .that().resideInAPackage("..cli..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.mindtable.bitbuckethelper.domain..",
            "com.mindtable.bitbuckethelper.application..",
            "com.mindtable.bitbuckethelper.adapter.inbound..",
            "com.mindtable.bitbuckethelper.adapter.outbound.persistence..",
            "com.mindtable.bitbuckethelper.adapter.outbound.bitbucket..",
            "com.mindtable.bitbuckethelper.adapter.outbound.notification..",
            "com.mindtable.bitbuckethelper.bootstrap..",
            "org.quartz..",
            "liquibase..",
            "org.jooq..",
            "org.sqlite..",
        )

    @ArchTest
    val top_level_packages_are_acyclic: ArchRule = slices()
        .matching("com.mindtable.bitbuckethelper.(*)..")
        .should().beFreeOfCycles()
}
