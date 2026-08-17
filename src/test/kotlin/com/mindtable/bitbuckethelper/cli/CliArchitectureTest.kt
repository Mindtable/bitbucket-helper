package com.mindtable.bitbuckethelper.cli

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(
    packages = ["com.mindtable.bitbuckethelper.cli"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class CliArchitectureTest {
    @ArchTest
    val cli_has_no_domain_application_adapter_persistence_or_bootstrap_dependencies: ArchRule = noClasses()
        .that().resideInAPackage("..cli..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..domain..",
            "..application..",
            "..adapter..",
            "..persistence..",
            "..bootstrap..",
        )

    @ArchTest
    val cli_has_no_http_server_or_tcp_client_dependencies: ArchRule = noClasses()
        .that().resideInAPackage("..cli..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "io.ktor.server..",
            "java.net.http..",
            "okhttp3..",
            "org.apache.http..",
        )

    @ArchTest
    val cli_does_not_open_inet_sockets: ArchRule = noClasses()
        .that().resideInAPackage("..cli..")
        .should().dependOnClassesThat().haveNameMatching(
            "java\\.net\\.(Socket|ServerSocket|InetSocketAddress|HttpURLConnection)",
        )

    @ArchTest
    val cli_does_not_read_environment_credentials_by_name: ArchRule = noClasses()
        .that().resideInAPackage("..cli..")
        .should().callMethod(System::class.java, "getenv", String::class.java)

    @ArchTest
    val cli_does_not_read_the_environment_map: ArchRule = noClasses()
        .that().resideInAPackage("..cli..")
        .should().callMethod(System::class.java, "getenv")
}
